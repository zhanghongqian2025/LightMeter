package com.litemask.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行帮助：<b>处处成立</b>，而且「看帮助」绝不等于「动手干活」。
 *
 * <p><b>这里防的是什么（块 38 开工前的实测）</b>
 * <pre>
 *   rules --help        退出码 0、stderr 全空，stdout 是一张规则表
 *                       —— 它**静默执行了 rules**。用户以为看到了用法，
 *                          其实跑了一条命令，而且**看不出任何异常**。
 *   redact 合同.txt --help
 *                       **真的把合同脱敏了**（产出 .redacted.txt 与映射件），
 *                       最后才因为「找不到文件 --help」报 rc=1。
 *   redact --ouput 合同.txt
 *                       报「错误: --ouput」—— 那其实是「文件 --ouput 不存在」
 *                       的异常消息：解析层把未知开关当成了**文件名**。
 *   restore/serve/uninstall/autostart --help
 *                       报「错误」并退出码 2（用户看到的是错误，不是用法）。
 * </pre>
 *
 * <p><b>根因不是「忘了加 --help」，是「用法只有一份、写在别处」</b>：
 * 顶层 help 里那几行文字，与各命令自己该有的用法，天然是两份；
 * 而「不认识这个开关」这件事在解析层<b>没有位置</b>，于是被当成文件名。
 * → 修法：每个命令类提供自己的用法（唯一真源），顶层与各自的 {@code --help}
 * 都从那里取；未知开关当场报成未知开关。
 *
 * <p><b>三条断言分属三层，缺一不可</b>
 * <ol>
 *   <li><b>行为层</b>：每个子命令的 {@code --help} 必须 rc=0、stdout 给出法、
 *       stderr 全空，而且<b>什么都不做</b>（工作目录里不新增任何文件）。
 *       —— 「--help 会不会顺手把活干了」只能靠真跑一次知道。</li>
 *   <li><b>结构层</b>：子命令自己的用法行必须逐行出现在顶层 help 的用法块里。
 *       —— 证明它们真的是同一份（顶层嵌子命令），不是各写一遍。</li>
 *   <li><b>未知开关层</b>：拼错的开关必须被报成「未知开关」并给出建议，
 *       而且**不能**被当成文件名（不能产出脱敏文件）。</li>
 * </ol>
 *
 * <p><b>为什么行为层要起子进程</b>：{@code Main.main} 里是 {@code System.exit}，
 * 进程内调不到返回值；而「这条命令认不认识 help」与「help 会不会顺手干活」
 * 只能在真进程里看。子进程的 {@code user.home} 与工作目录都指向临时目录，
 * 探针万一动了什么也碰不到开发机。
 */
class CliHelpEverywhereTest {

    /** 所有子命令。顶层 {@code --help} 之外的每一个都要有自己的 help。 */
    private static final List<String> SUBCOMMANDS =
            List.of("redact", "restore", "rules", "serve", "uninstall", "autostart", "model");

    /** 起子进程时给的时限。{@code serve --help} 若没短路，会真的起一个服务并永久等待。 */
    private static final long TIMEOUT_SECONDS = 60;

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

    /** 各子命令自己的用法文本（唯一真源）。 */
    private static String usageTextOf(String sub) {
        return switch (sub) {
            case "redact" -> Main.redactUsageText();
            case "restore" -> Main.restoreUsageText();
            case "rules" -> Main.rulesUsageText();
            case "serve" -> ServeCommand.usageText();
            case "uninstall" -> UninstallCommand.usageText();
            case "autostart" -> AutostartCommand.usageText();
            case "model" -> ModelCommand.usageText();
            default -> throw new IllegalArgumentException("没登记这个子命令：" + sub);
        };
    }

    // ------------------------------------------------------------ 起子进程

    /** 一次子进程运行的结果。 */
    private record Run(int rc, String out, String err) {
    }

    private static Run runCli(Path workDir, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(
                java,
                "-Duser.home=" + workDir));
        // 【2026-10-25】用**产品自己的** jvm.args 起子进程，
        //   而不是另写一份「测试专用的编码参数」。
        //   为什么：输出编码是**产品行为**的一部分。
        //     Windows 上 stdout/stderr 默认取系统本地代码页
        //     （中文系统 = GBK 936，其它 locale = cp1252/437），
        //     于是同一句「用法:」在 macOS 上是 UTF-8、在 Windows 上是 GBK，
        //     甚至在非中文 locale 上根本写不出来（变成 '?'）。
        //   → 读 tools\package-identity.properties 的 jvm.args（跨端唯一真源），
        //     装出来的包里也是这同一串参数 → 测试验的就是交付配置。
        cmd.addAll(productJvmArgs());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Main.class.getName());
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        Process proc = pb.start();
        // 【2026-10-25 修掉的真缺陷：Windows 上这里会死锁】
        //   原来的写法是「先 readAllBytes(stdout) 再 readAllBytes(stderr) 再 waitFor」，
        //   而 readAllBytes 要读到 EOF（= 子进程退出）才返回：
        //     子进程写 usage 到 stderr → Windows 匿名管道只有 4KB（macOS 是 64KB）
        //     → 写满就卡住 → 子进程不退出 → stdout 永远没 EOF → 父进程永远等下去。
        //   实测：这条测试在 Windows 上挂死（子进程栈停在 FileOutputStream.writeBytes）。
        //   macOS 上没暴露只是因为 usage 恰好装得进 64KB。
        // → 两条流同时排空（见 CliProc）。
        CliProc.Result r;
        try {
            r = CliProc.collect(proc, TIMEOUT_SECONDS);
        } catch (IllegalStateException e) {
            // 没短路就是「--help 顺手把活干了」（比如 serve 起了个服务在那儿等）。
            // 必须杀掉，否则测试会挂到 surefire 的超时。
            proc.destroyForcibly();
            proc.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("CLI 没有在 " + TIMEOUT_SECONDS + " 秒内返回："
                    + String.join(" ", args) + "（--help 必须短路，不能真的干活）");
        }
        return new Run(r.rc(), r.out(), r.err());
    }

    private static List<String> listFiles(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * 产品自己的 JVM 参数（来自跨端唯一真源 tools\package-identity.properties）。
     *
     * <p>读不到就退回一个**明确的**保守集合，而不是悄悄用系统默认 ——
     * 用系统默认的话，Windows 上这条测试会以「输出乱码」的形式红，
     * 而红的原因（编码）与被测逻辑无关，排查成本极高。
     */
    private static List<String> productJvmArgs() {
        List<String> out = new ArrayList<>();
        try {
            Path id = Path.of(System.getProperty("user.dir"), "tools", "package-identity.properties");
            for (String line : Files.readAllLines(id, java.nio.charset.StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("jvm.args=")) {
                    for (String a : t.substring("jvm.args=".length()).trim().split("\\s+")) {
                        if (!a.isEmpty()) {
                            out.add(a);
                        }
                    }
                    return out;
                }
            }
        } catch (Exception ignored) {
            // 落到下面的兜底
        }
        out.add("-Dfile.encoding=UTF-8");
        out.add("-Dstdout.encoding=UTF-8");
        out.add("-Dstderr.encoding=UTF-8");
        return out;
    }

    // ------------------------------------------------------------ 行为层断言

    @Test
    @DisplayName("每个子命令的 --help 都得：给用法、退出码 0、stderr 全空、什么都不做")
    void everySubcommandHelpIsHarmless(@TempDir Path tmp) throws Exception {
        for (String sub : SUBCOMMANDS) {
            Path work = Files.createDirectory(tmp.resolve(sub));
            Run r = runCli(work, sub, "--help");

            assertEquals(0, r.rc(),
                    sub + " --help 的退出码不是 0（stderr：" + r.err().trim() + "）"
                            + " —— 用户想看用法，看到的却是错误");
            assertTrue(r.out().contains("用法:"),
                    sub + " --help 的 stdout 里没有「用法:」（stdout：" + r.out().trim() + "）");
            assertEquals("", r.err(),
                    sub + " --help 的 stderr 不该有内容（stderr：" + r.err().trim() + "）");

            List<String> produced = listFiles(work);
            assertTrue(produced.isEmpty(),
                    sub + " --help 竟然动了工作目录：产出了 " + produced
                            + " —— 「看帮助」绝不能变成「干活」");
        }
    }

    @Test
    @DisplayName("rules --help 绝不能静默执行 rules（它以前退出码 0 且 stderr 全空）")
    void rulesHelpDoesNotRunRules(@TempDir Path tmp) throws Exception {
        Path work = Files.createDirectory(tmp.resolve("rules"));
        Run r = runCli(work, "rules", "--help");

        assertEquals(0, r.rc(), "rules --help 的退出码不是 0");
        // 规则表的表头里有这一列名：只要 stdout 里有它，就说明 rules 被真的执行了。
        assertFalse(r.out().contains("占位前缀"),
                "rules --help 打出了规则表 —— 它静默执行了 rules，而用户以为自己在看用法"
                        + "（stdout：" + r.out().trim() + "）");
        assertTrue(r.out().contains("用法:"), "rules --help 的 stdout 里没有「用法:」");
    }

    @Test
    @DisplayName("redact 带着 --help 时不许真的脱敏（它以前会先脱敏再报错）")
    void redactWithHelpDoesNotRedact(@TempDir Path tmp) throws Exception {
        Path work = Files.createDirectory(tmp.resolve("redact"));
        Path doc = work.resolve("合同.txt");
        Files.writeString(doc, "姓名 张三 身份证 110101199003074567\n", StandardCharsets.UTF_8);

        Run r = runCli(work, "redact", "合同.txt", "--help");

        assertEquals(0, r.rc(), "redact 合同.txt --help 的退出码不是 0（stderr：" + r.err().trim() + "）");
        List<String> produced = listFiles(work);
        assertEquals(List.of("合同.txt"), produced,
                "redact 合同.txt --help 竟然产出了文件：" + produced
                        + " —— 它**先脱敏了一遍**才报错，用户「看一眼用法」的代价是一次脱敏");
    }

    // ------------------------------------------------------------ 结构层断言

    @Test
    @DisplayName("子命令自己的用法行必须逐行出现在顶层 help 的用法块里（同一份，不许各写一遍）")
    void topLevelEmbedsEverySubcommandUsage() {
        List<String> block = usageBlock(capture(Main::usage));
        assertFalse(block.isEmpty(), "顶层 help 里没解析出用法块 —— 这条断言等于没跑");

        for (String sub : SUBCOMMANDS) {
            for (String line : usageTextOf(sub).split("\n")) {
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
    @DisplayName("顶层 help 顶部的版本号必须是真版本，不是过期的阶段标签")
    void topLevelShowsRealVersion() {
        String help = capture(Main::usage);
        String firstLine = help.split("\n", -1)[0];

        // 【为什么要断言「像一个版本号」而不是断言具体数字】
        //   断言具体数字 = 把版本号抄成第二份（第三份拷贝）→ 迟早与事实脱节。
        //   断言「形状」= 断言它**不是**阶段标签（像从前写死的「· M3-2」那种），
        //   而真版本从 tools/package-identity.properties 现算。
        assertTrue(firstLine.matches(".*·\\s*\\d+\\.\\d+\\.\\d+.*"),
                "顶层 help 顶部不是「产品名 · 版本号」的形状：" + firstLine
                        + "（版本号必须现算，不能写死 —— 写死的阶段标签过期了没人喊）");
        assertTrue(firstLine.contains(ProductIdentity.version()),
                "顶层 help 顶部的版本与产品身份表里的不一致：" + firstLine);
    }

    // ------------------------------------------------------ 未知开关层断言

    @Test
    @DisplayName("拼错的开关必须被报成未知开关并给建议，不能被当成文件名")
    void unknownSwitchIsReportedAsUnknownSwitch(@TempDir Path tmp) throws Exception {
        Path work = Files.createDirectory(tmp.resolve("unknown"));
        Path doc = work.resolve("合同.txt");
        Files.writeString(doc, "姓名 张三 身份证 110101199003074567\n", StandardCharsets.UTF_8);

        Run r = runCli(work, "redact", "合同.txt", "--ouput", "合同.txt");

        assertTrue(r.rc() != 0, "拼错的开关居然退出码 0");
        assertTrue(r.err().contains("未知开关"),
                "拼错的开关没有被报成「未知开关」（stderr：" + r.err().trim() + "）"
                        + " —— 它以前被当成了文件名，报的是「文件不存在」");
        assertTrue(r.err().contains("--output"),
                "拼错的开关没有给出「你是不是想写 --output」的建议（stderr：" + r.err().trim() + "）");
        // 关键：不能因为「开关」而把**后面的真实文档**也一起脱敏了。
        assertEquals(List.of("合同.txt"), listFiles(work),
                "拼错的开关导致文档还是被脱敏了 —— 解析层把开关当成了文件名，"
                        + "于是一路脱敏下去，最后才因为找不到那个「文件」而报错");
    }

    @Test
    @DisplayName("各个子命令对未知开关的报错口径必须一致（守卫只认一个词）")
    void unknownSwitchWordingIsConsistent(@TempDir Path tmp) throws Exception {
        for (String sub : List.of("redact", "restore", "rules", "serve", "uninstall")) {
            Path work = Files.createDirectory(tmp.resolve(sub + "-wording"));
            Run r = runCli(work, sub, "--zzz-not-a-real-option");
            assertTrue(r.err().contains("未知开关"),
                    sub + " 对未知开关的报错口径与其他子命令不一致（stderr：" + r.err().trim()
                            + "）—— 守卫要按一个统一的词去认「这是不是一个被报出来的未知开关」");
        }
    }
}
