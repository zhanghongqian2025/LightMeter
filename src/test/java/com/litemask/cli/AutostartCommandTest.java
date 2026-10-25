package com.litemask.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code autostart} 子命令：装、卸、看状态。
 *
 * <p><b>这里防的是两条真实存在的缺陷</b>：
 * <ol>
 *   <li><b>装上了但没生效</b> —— plist 写错、launchd 没装载，
 *       而命令却报「成功」。所以装完必须<b>真的去 launchctl bootstrap</b>，
 *       失败要报出来。</li>
 *   <li><b>卸载后自启还在</b> —— 只删文件不 bootout，
 *       launchd 里那个服务还活着，重启后照旧自己起来。
 *       所以卸载必须<b>两件事都做</b>，而且 plist 也要进
 *       {@code uninstall} 的清单。</li>
 * </ol>
 *
 * <p><b>标尺独立于被测实现</b>：launchd 的交互被 {@link AutostartCommand.Launcher}
 * 换成了假实现，单测里<b>不会真的往本机 launchd 里装东西</b>
 * （那会留下一个常驻服务）；断言的是「我们要求它发哪些命令」，
 * 而不是「launchd 最后怎么样」。
 */
class AutostartCommandTest {

    /** 假 launchd：记录被调过的命令，按需给出退出码与输出。 */
    private static final class FakeSystem implements AutostartCommand.Launcher {
        final List<List<String>> calls = new ArrayList<>();
        int bootstrapRc = 0;
        /** {@code launchctl print} 的输出；空串 = 服务不存在。 */
        String printOutput = "";
        /** {@code launchctl print} 的退出码；非 0 = 失败（output 必须返回空串）。 */
        int printRc = 0;

        @Override
        public int exec(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            if (cmd.contains("bootstrap")) {
                return bootstrapRc;
            }
            return 0;
        }

        @Override
        public int execQuiet(List<String> cmd) {
            // 安静通道与普通通道的区别只在「输出去哪」，命令本身一样。
            calls.add(List.copyOf(cmd));
            if (cmd.contains("bootstrap")) {
                return bootstrapRc;
            }
            return 0;
        }

        @Override
        public String output(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            if ("id".equals(cmd.get(0))) {
                return "501";
            }
            // **契约**：命令失败（退出码非 0）时返回空串 ——
            // 本机实测 `launchctl print` 对不存在的服务退出码是 113，
            // 而错误信息走 stderr（真实现把 stderr 并进了 stdout，
            // 所以「失败」必须由退出码来判定，不能靠文本）。
            if (printRc != 0) {
                return "";
            }
            return printOutput;
        }

        @Override
        public AutostartCommand.ExecResult execCapture(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            if (printRc != 0) {
                return new AutostartCommand.ExecResult(printRc, "");
            }
            return new AutostartCommand.ExecResult(0, printOutput);
        }

        boolean called(String... needle) {
            for (List<String> c : calls) {
                if (c.containsAll(List.of(needle))) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Result(int code, String text) {
    }

    private Path home;
    private Path appDir;
    private FakeSystem sys;

    @BeforeEach
    void setUp(@TempDir Path root) throws Exception {
        home = root.resolve("home");
        Files.createDirectories(home);
        // 造一个「假 .app」：Contents/MacOS/LightMeter 且可执行。
        appDir = root.resolve("LightMeter.app");
        Path exe = appDir.resolve("Contents/MacOS/LightMeter");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        // 【2026-10-25 修掉的真缺陷：这套测试在 Windows 上整类红】
        //   Files.setPosixFilePermissions 在 Windows 上抛 UnsupportedOperationException，
        //   而它挂在 @BeforeEach 上 → 12 条用例**一条都没跑**就全红。
        //   而这个「可执行位」对被测逻辑是**无关**的：
        //   测试从不真的执行这个假 .app —— 与 launchd 的交互走的是注入的假 Launcher。
        //   → 只在支持 POSIX 权限的文件系统上设。
        //     不让「这个平台没有可执行位这个概念」变成「整类测试红」，
        //     也不删任何断言。
        if (Files.getFileStore(exe.getParent()).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        sys = new FakeSystem();
    }

    private Result run(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int code = AutostartCommand.run(args, ps, ps, sys, home, "Mac OS X");
        return new Result(code, buf.toString(StandardCharsets.UTF_8));
    }

    private Path plistPath() {
        return home.resolve("Library/LaunchAgents/com.litemask.lightmeter.plist");
    }

    @Test
    @DisplayName("install 会写 plist 并真的去 launchctl bootstrap")
    void installWritesPlistAndBootstraps() {
        Result r = run("install", "--app", appDir.toString());
        assertEquals(0, r.code(), "install 应成功，实际输出：" + r.text());
        assertTrue(Files.exists(plistPath()), "plist 应该被写出来：" + plistPath());
        assertTrue(sys.called("launchctl", "bootstrap"), "必须真的去装载：" + sys.calls);
        // 装的是自己的 plist，而不是别的路径
        boolean rightPath = false;
        for (List<String> c : sys.calls) {
            if (c.contains("bootstrap") && c.contains(plistPath().toString())) {
                rightPath = true;
            }
        }
        assertTrue(rightPath, "bootstrap 的目标应该是自己的 plist：" + sys.calls);
    }

    @Test
    @DisplayName("install 幂等：装两次内容不变，第二次明说「已经是这个配置」")
    void installIsIdempotent() throws Exception {
        assertEquals(0, run("install", "--app", appDir.toString()).code());
        String first = Files.readString(plistPath(), StandardCharsets.UTF_8);
        Result second = run("install", "--app", appDir.toString());
        assertEquals(0, second.code());
        assertEquals(first, Files.readString(plistPath(), StandardCharsets.UTF_8),
                "第二次装不该改内容");
        assertTrue(second.text().contains("已经是这个配置"),
                "第二次要说明内容没变，实际输出：" + second.text());
    }

    @Test
    @DisplayName("uninstall 既 bootout 又删 plist")
    void uninstallRemovesPlistAndBootsOut() {
        assertEquals(0, run("install", "--app", appDir.toString()).code());
        Result r = run("uninstall");
        assertEquals(0, r.code(), r.text());
        assertTrue(sys.called("launchctl", "bootout"), "必须 bootout，否则 launchd 里还留着服务：" + sys.calls);
        assertFalse(Files.exists(plistPath()), "plist 应该被删掉");
    }

    @Test
    @DisplayName("label 里带路径分隔符一律拒绝（否则能写出 LaunchAgents 之外的地方）")
    void labelWithTraversalIsRejected() {
        Result r = run("install", "--app", appDir.toString(), "--label", "../../evil");
        assertEquals(2, r.code(), "必须拒绝，实际输出：" + r.text());
        // 不能在 LaunchAgents 之外落任何文件
        Path outside = home.resolve("Library/LaunchAgents/../../evil.plist").normalize();
        assertFalse(Files.exists(outside), "不该写到 LaunchAgents 之外：" + outside);
    }

    @Test
    @DisplayName("既不是 macOS 也不是 Windows 时明确报错，不静默假装成功")
    void unsupportedPlatformIsRejectedExplicitly() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        // 注意：这里用 Linux 而不是 Windows ——
        // Windows 侧在块 18 里已经实现了（注册表 Run 键），走的是另一条分支，
        // 由 WindowsAutostartTest 覆盖。
        int code = AutostartCommand.run(new String[]{"install", "--app", appDir.toString()},
                ps, ps, sys, home, "Linux");
        String text = buf.toString(StandardCharsets.UTF_8);
        assertEquals(2, code, "不支持的平台必须报错，实际输出：" + text);
        assertTrue(text.contains("macOS"), "要说清支持哪些平台：" + text);
        assertTrue(text.contains("Windows"), "要说清支持哪些平台：" + text);
        assertFalse(Files.exists(plistPath()), "不该写 plist");
    }

    @Test
    @DisplayName("--app 指到不存在的 .app 要明确报错，不能装个指向空气的自启")
    void installFailsWhenAppIsWrong() {
        Result r = run("install", "--app", home.resolve("不存在.app").toString());
        assertEquals(2, r.code(), r.text());
        assertTrue(r.text().contains("找不到可执行文件"), r.text());
        assertFalse(Files.exists(plistPath()), "不该写 plist");
    }

    @Test
    @DisplayName("launchctl bootstrap 失败必须报出来（退出码 0 但没装上 = 骗人）")
    void bootstrapFailureIsReported() {
        sys.bootstrapRc = 1;
        Result r = run("install", "--app", appDir.toString());
        assertNotEquals(0, r.code(), "装载失败不能报成功：" + r.text());
        assertTrue(r.text().contains("bootstrap 失败"), r.text());
        // 但 plist 已经写了 —— 要让用户能自己 launchctl bootstrap 救回来
        assertTrue(Files.exists(plistPath()), "plist 已写，用户可以手动装载");
    }

    @Test
    @DisplayName("status 在没装时明说没装")
    void statusReportsNotInstalled() {
        Result r = run("status");
        assertEquals(0, r.code(), r.text());
        assertTrue(r.text().contains("没装") || r.text().contains("不在"),
                "要说清楚没装：" + r.text());
        assertTrue(r.text().contains("未装载"), "也要说清运行状态：" + r.text());
    }

    @Test
    @DisplayName("status 在 launchd 里有这个服务时，报出进程号")
    void statusReportsLoadedAndPid() {
        // **照抄本机 launchctl print 的真实格式**（2026-10-21 实测，macOS 27.2.0）：
        // 开头是 `gui/<uid>/<label> = {`，**没有** `"label" = "..."` 那一行
        // （那是旧格式）。曾经按旧格式去匹配 → 服务在跑却报「未装载」。
        //
        // 【2026-10-21 块 23 改了一处：先真的装上 plist】
        //   原来这个用例只设了 printOutput（假装 launchd 认得服务），
        //   **但没装 plist** —— 那是「顺手」，不是刻意构造的场景。
        //   块 23 给 status 加了「真的去探一次服务」之后，
        //   「plist 不在 → 读不出端口 → 探不了 → 退出码 3」，这个用例就红了。
        //   → 改成**先 install 再看状态**（真实场景：装了自启、服务在跑）。
        //
        // 【为什么这里不断言「服务在线」】
        //   本用例用的是**真探活**（没注入假 probe），而本机并没有服务在跑 →
        //   探活必然失败 → 断言「在线」就是一条**恒假断言**。
        //   「服务在不在」由 AutostartStatusProbeTest 用假 probe 精确覆盖各档。
        //   → 这里只断言这条用例**原本要验的东西**：进程号报出来了。
        Result install = run("install", "--app", appDir.toString());
        assertEquals(0, install.code(), install.text());
        sys.printOutput = "gui/501/com.litemask.lightmeter = {\n"
                + "\tactive count = 1\n"
                + "\tpath = /Users/x/Library/LaunchAgents/com.litemask.lightmeter.plist\n"
                + "\ttype = LaunchAgent\n"
                + "\tstate = running\n"
                + "\tprogram = /Applications/LightMeter.app/Contents/MacOS/LightMeter\n"
                + "\tdomain = gui/501 [100021]\n"
                + "\truns = 1\n"
                + "\tpid = 4242\n"
                + "}";
        Result r = run("status");
        assertTrue(r.text().contains("已装载"), r.text());
        assertTrue(r.text().contains("4242"), "要报出进程号：" + r.text());
    }

    @Test
    @DisplayName("launchctl print 失败时不能把错误信息当成「服务在」")
    void statusTreatsFailedPrintAsNotLoaded() {
        // 本机实测：服务不存在时 launchctl print 退出码是 113，错误信息走 stderr。
        // 旧实现把 stderr 并进 stdout → 「服务不存在」返回了一段非空文本
        // → status 判成「已装载」。现在以**退出码**为准。
        sys.printRc = 113;
        sys.printOutput = "Bad request.\nCould not find service \"com.litemask.lightmeter\"";
        Result r = run("status");
        assertEquals(0, r.code(), r.text());
        assertTrue(r.text().contains("未装载"),
                "print 失败就该说未装载（不能把错误信息当服务存在）：" + r.text());
    }

    @Test
    @DisplayName("plist 必须落在 ~/Library/LaunchAgents 里")
    void plistPathStaysInsideLaunchAgents() {
        assertEquals(0, run("install", "--app", appDir.toString()).code());
        Path dir = plistPath().getParent();
        assertEquals(home.resolve("Library/LaunchAgents"), dir);
    }

    @Test
    @DisplayName("日志目录会被建出来（launchd 不会替你建）")
    void logDirectoryIsCreated() {
        assertEquals(0, run("install", "--app", appDir.toString()).code());
        assertTrue(Files.isDirectory(home.resolve(".litemask/logs")),
                "日志目录要存在，否则 launchd 起进程时写日志会失败");
    }
}
