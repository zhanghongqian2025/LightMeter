package com.litemask.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行帮助：文本层 + 行为层。
 *
 * <p><b>这里防的是什么</b>
 * 块 37 开工前的实测：真二进制 {@code --help} 的用法块里**完全没有**
 * {@code autostart} 整块、{@code model download}、{@code model trust}，
 * 而 README 都在教。根因是 {@code Main.usage()} 以前是**手抄**子命令的用法
 * （抄漏了），手抄的清单必然会漂，而且漂了没有任何东西会喊。
 *
 * <p><b>两条断言分属两层，缺一不可</b>
 * <ol>
 *   <li><b>结构层</b>：顶层用法块必须**逐行**含有子命令自己声明的命令行。
 *       顶层改成手抄的那一刻就会红 —— 抄的那份迟早与真源不一致。
 *       （真源在子命令那边，顶层只是把它嵌进来。）</li>
 *   <li><b>行为层</b>：帮助文本里写出来的**每一条**命令，CLI 必须真的认。
 *       文本层再对，行为层也可能对不上（写了条不存在的命令）。
 *       探针用「一个不存在的选项」把「认不认识这条命令」和「这个选项对不对」
 *       分开：命令不认识 → 报「未知子命令 / 未知动作」；命令认识只是选项不对
 *       → 报别的错。</li>
 * </ol>
 *
 * <p><b>为什么行为层要起子进程</b>：{@code Main.main} 里是 {@code System.exit}，
 * 进程内调不到返回值；而「这条命令认不认识」这件事只有真跑一次才知道。
 * 子进程的 {@code user.home} 指向临时目录，探针万一动了什么也碰不到开发机。
 */
class CliHelpTest {

    /** 探针用的不存在选项。它必须以 {@code --} 开头（被当成选项而不是位置参数）。 */
    private static final String PROBE_OPTION = "--zzz-not-a-real-option";

    /** 命令形式的行首标识符串。 */
    private static final java.util.regex.Pattern IDENT_RUN =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_-]*(?:[ \\t]+[A-Za-z][A-Za-z0-9_-]*)*");

    // ---------------------------------------------------------------- 抓文本

    private static String capture(java.util.function.Consumer<PrintStream> action) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            action.accept(ps);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** 用法块：从「用法:」那一行之后，到第一个空行为止。 */
    private static List<String> usageBlock(String text) {
        List<String> lines = new ArrayList<>();
        boolean inBlock = false;
        for (String line : text.split("\n", -1)) {
            if (!inBlock) {
                if (line.contains("用法:")) {
                    inBlock = true;
                }
                continue;
            }
            if (line.isBlank()) {
                inBlock = false;
                continue;
            }
            lines.add(line);
        }
        return lines;
    }

    /** 用法块里的命令形式（行首标识符串）。

     *  <p><b>必须锚定在行首</b>：像 {@code [--ner-model 模型目录]} 这种续行，
     *  行首是「[」，不锚定就会把里面的 {@code ner-model} 当成一条命令。
     */
    private static List<String> commandForms(String text) {
        List<String> forms = new ArrayList<>();
        for (String line : usageBlock(text)) {
            String stripped = line.strip();
            java.util.regex.Matcher m = IDENT_RUN.matcher(stripped);
            if (m.lookingAt()) {
                forms.add(m.group().replaceAll("[ \\t]+", " "));
            }
        }
        return forms;
    }

    // ------------------------------------------------------------ 结构层断言

    @Test
    @DisplayName("顶层 --help 必须逐行嵌入子命令自己声明的命令行（不许手抄）")
    void topLevelEmbedsSubcommandUsage() {
        List<String> block = usageBlock(capture(Main::usage));
        assertFalse(block.isEmpty(), "顶层 help 里没解析出用法块 —— 这条断言等于没跑");

        for (String sub : new String[] {"model", "autostart"}) {
            String declared = capture(ps -> {
                if ("model".equals(sub)) {
                    ModelCommand.usageLines(ps);
                } else {
                    AutostartCommand.usageLines(ps);
                }
            });
            for (String line : declared.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                assertTrue(block.contains(line),
                        "顶层 --help 的用法块里少了 " + sub + " 自己声明的这一行：" + line
                                + "（顶层必须直接嵌入子命令的清单，不许手抄）");
            }
        }
    }

    @Test
    @DisplayName("顶层用法块里出现的每条命令，CLI 都真的认（不许只是文本）")
    void everyCommandFormIsAccepted(@TempDir Path tmp) throws Exception {
        List<String> forms = commandForms(capture(Main::usage));
        assertFalse(forms.isEmpty(), "用法块里没解析出命令形式 —— 这条断言等于没跑");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        for (String form : forms) {
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            cmd.add("-Duser.home=" + tmp);
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(Main.class.getName());
            for (String part : form.split(" ")) {
                cmd.add(part);
            }
            cmd.add(PROBE_OPTION);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "跑 help 里写的命令超时了：" + form);
            assertFalse(output.contains("未知子命令"),
                    "CLI 不认 help 里写的命令：" + form + "（输出：" + output.trim() + "）");
            assertFalse(output.contains("未知动作"),
                    "CLI 不认 help 里写的命令：" + form + "（输出：" + output.trim() + "）");
        }
    }

    @Test
    @DisplayName("help 的用法块里必须说清四个以前漏掉的开关")
    void helpMentionsPreviouslyMissingSwitches() {
        // 【为什么只看**用法块**，不看 help 全文 —— 块 37 收尾时撞到的】
        //   这四个开关在 help 全文里**各出现两次**：一次在用法块的选项表里，
        //   一次在下面的说明段（「--max-upload-bytes 限制单份上传体积，--task-timeout …」）。
        //   → 断言写成「help 全文里有这个字符串」时，
        //     把**用法块里那一处**删掉，说明段那一处还在 → 断言照样成立 → **探针空转**
        //     （对照实验里那条「help 里漏掉 --task-timeout」就是这么报 GREEN 的：
        //      变异确实写进去了，症状却没被制造出来）。
        //   → 用户「在 help 里查这个开关」查的是**用法块的选项表**，
        //     说明段里的解释不算数 → 断言必须落在**用法块**上。
        List<String> block = usageBlock(capture(Main::usage));
        assertFalse(block.isEmpty(), "顶层 help 里没解析出用法块 —— 这条断言等于没跑");
        String usage = String.join("\n", block);

        for (String flag : new String[] {"--allow-unsigned", "--max-upload-bytes", "--sig-url", "--task-timeout"}) {
            assertTrue(usage.contains(flag),
                    "顶层 --help 的用法块里没有 " + flag
                            + "（README 教了它，实现里也有；说明段里提到它不算数，用户查的是用法块）");
        }
        for (String cmd : new String[] {"autostart install", "model download", "model trust add"}) {
            assertTrue(usage.contains(cmd),
                    "顶层 --help 的用法块里没有 " + cmd + " 这一条命令（README 教了它）");
        }
    }
}
