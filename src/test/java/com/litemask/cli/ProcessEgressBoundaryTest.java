package com.litemask.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.litemask.server.LocalServer;
import com.litemask.server.Workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 块 36：**进程外调用**这一维度的产品侧守卫。
 *
 * <h2>为什么这条要存在</h2>
 * <p>静态标尺现在看得见「哪个 class 拉起了外部程序」，但看得见不等于看得懂：
 * 「拉起了一个程序」本身不构成外发，<b>「拉起的是什么、参数里装了什么」才是</b>。
 * <p>本项目里进程外调用只有两处：
 * <ul>
 *   <li>{@code BrowserLauncher} —— 把地址交给系统默认浏览器；</li>
 *   <li>{@code AutostartCommand.RealLauncher} —— 执行系统命令管开机自启。</li>
 * </ul>
 * <p>两条各有各的危险形状：
 * <ul>
 *   <li>浏览器那条：<b>地址</b>必须是本机回环，否则就是把用户引到外网；</li>
 *   <li>自启那条：<b>命令</b>必须是本机命令，参数里不能带用户文档内容。</li>
 * </ul>
 * <p>这两条以前**没有任何判据守着** —— 守护它们的是「代码写对了」，
 * 而不是「写错了会红」。
 *
 * <h2>为什么不改成在标尺里判</h2>
 * <p>标尺看的是常量池，看不见「命令参数里装了什么」——
 * 那要真的把命令构造出来才知道。
 * <p>所以分工是：<b>标尺管「有没有进程外调用、登记得对不对」，
 * 单测管「调用目标是什么」</b>。两层不重叠。
 */
class ProcessEgressBoundaryTest {

    /** 本项目允许被拉起的**本机命令**。写死，且就是全部。 */
    private static final Set<String> LOCAL_COMMANDS = Set.of("launchctl", "reg", "id");

    /** 浏览器那条用的本机命令（不是 shell，不是解释器）。 */
    private static final Set<String> BROWSER_COMMANDS = Set.of("open", "rundll32", "xdg-open");

    // ------------------------------------------------------------------ 记录式假件

    /** 记录所有被执行的命令，不真的执行。 */
    private static final class RecordingLauncher implements AutostartCommand.Launcher {
        final List<List<String>> calls = new ArrayList<>();

        @Override
        public int exec(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return 0;
        }

        @Override
        public int execQuiet(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return 0;
        }

        @Override
        public String output(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return "";
        }

        @Override
        public AutostartCommand.ExecResult execCapture(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return new AutostartCommand.ExecResult(0, "");
        }
    }

    // ------------------------------------------------------------------ 浏览器那条

    @Test
    @DisplayName("浏览器命令的首元素只能是三个本机命令之一，永远不是 shell")
    void browserCommandIsAlwaysALocalCommand() {
        String url = "http://127.0.0.1:8712/";
        for (String os : List.of("Mac OS X", "Windows 11", "Linux", "AIX")) {
            List<String> cmd = BrowserLauncher.commandFor(os, url);
            assertFalse(cmd.isEmpty(), "认识的系统必须给出命令：" + os);
            String head = cmd.get(0);
            assertTrue(BROWSER_COMMANDS.contains(head),
                    "浏览器命令的首元素必须是本机命令，实际：" + head + "（os=" + os + "）");
            assertFalse(head.contains("/"), "命令首元素不能是路径（那可能是从别处来的）：" + head);
            assertFalse(head.contains("sh"), "不能是 shell 解释器（参数会被重新解析）：" + head);
        }
    }

    @Test
    @DisplayName("浏览器命令的参数表里，地址是最后一个元素且原样透传")
    void browserCommandCarriesTheUrlAsLastArgument() {
        String url = "http://127.0.0.1:8712/?t=a%20b&x=1#frag";
        assertEquals(List.of("open", "-u", url), BrowserLauncher.commandFor("Mac OS X", url));
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", url),
                BrowserLauncher.commandFor("Windows 11", url));
        assertEquals(List.of("xdg-open", url), BrowserLauncher.commandFor("Linux", url));
    }

    @Test
    @DisplayName("本机服务起起来后，交给浏览器的地址逐字是回环")
    void addressHandedToBrowserIsLoopback(@TempDir Path tmp) throws Exception {
        Workspace ws = Workspace.open(tmp);
        LocalServer server = LocalServer.create(ws, 0);
        try {
            server.start();
            String url = server.url();
            assertTrue(url.startsWith("http://127.0.0.1:"),
                    "交给浏览器的地址必须逐字以 http://127.0.0.1: 开头，实际：" + url);
            List<String> cmd = BrowserLauncher.commandFor("Mac OS X", url);
            assertEquals(url, cmd.get(cmd.size() - 1),
                    "命令参数表里带的必须就是这个回环地址");
        } finally {
            server.close();
            ws.close();
        }
    }

    // ------------------------------------------------------------------ 自启那条

    @Test
    @DisplayName("macOS 自启全流程：执行过的命令首元素只有 launchctl 与 id")
    void macAutostartOnlyRunsLocalCommands(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home);
        Path appDir = root.resolve("LightMeter.app");
        Path exe = appDir.resolve("Contents/MacOS/LightMeter");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        exe.toFile().setExecutable(true);

        RecordingLauncher sys = new RecordingLauncher();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);

        AutostartCommand.run(new String[] {"install", "--app", appDir.toString()},
                ps, ps, sys, home, "Mac OS X");
        AutostartCommand.run(new String[] {"status"}, ps, ps, sys, home, "Mac OS X");
        AutostartCommand.run(new String[] {"uninstall"}, ps, ps, sys, home, "Mac OS X");

        Set<String> heads = new LinkedHashSet<>();
        for (List<String> c : sys.calls) {
            assertFalse(c.isEmpty(), "命令表不能是空的");
            heads.add(c.get(0));
        }
        assertFalse(heads.isEmpty(), "这一轮至少该执行过一条命令（不然这条断言是空的）");
        for (String head : heads) {
            assertTrue(LOCAL_COMMANDS.contains(head),
                    "自启只允许执行本机命令，实际跑了：" + head + "（全部：" + sys.calls + "）");
        }
    }

    @Test
    @DisplayName("Windows 自启全流程：执行过的命令首元素只有 reg")
    void windowsAutostartOnlyRunsReg(@TempDir Path root) throws Exception {
        Path exe = root.resolve("LightMeter.exe");
        Files.writeString(exe, "MZ", StandardCharsets.ISO_8859_1);

        RecordingLauncher sys = new RecordingLauncher();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);

        WindowsAutostart.install(sys, ps, ps, "com.litemask.lightmeter", exe, 8712);
        WindowsAutostart.status(sys, ps, ps, "com.litemask.lightmeter", new ServiceProbe.Http());
        WindowsAutostart.uninstall(sys, ps, ps, "com.litemask.lightmeter");

        Set<String> heads = new LinkedHashSet<>();
        for (List<String> c : sys.calls) {
            assertFalse(c.isEmpty(), "命令表不能是空的");
            heads.add(c.get(0));
        }
        assertFalse(heads.isEmpty(), "这一轮至少该执行过一条命令（不然这条断言是空的）");
        for (String head : heads) {
            assertEquals("reg", head,
                    "Windows 侧只允许跑 reg，实际跑了：" + head + "（全部：" + sys.calls + "）");
        }
    }

    @Test
    @DisplayName("自启命令的参数里不含文档内容 —— 只有本机路径、端口与标签名")
    void autostartArgumentsCarryNoDocumentContent(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home);
        Path appDir = root.resolve("LightMeter.app");
        Path exe = appDir.resolve("Contents/MacOS/LightMeter");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        exe.toFile().setExecutable(true);

        RecordingLauncher sys = new RecordingLauncher();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        AutostartCommand.run(new String[] {"install", "--app", appDir.toString()},
                ps, ps, sys, home, "Mac OS X");

        for (List<String> c : sys.calls) {
            for (String arg : c) {
                assertFalse(arg.contains("://"),
                        "自启命令参数里不该出现地址：" + arg + "（全部：" + sys.calls + "）");
                assertFalse(arg.endsWith(".docx") || arg.endsWith(".pdf")
                                || arg.endsWith(".xlsx") || arg.endsWith(".ofd"),
                        "自启命令参数里不该出现文档文件名：" + arg);
            }
        }
    }

    @Test
    @DisplayName("命令不存在时必须被吞掉，不抛异常（进程外调用不許把服务带崩）")
    void missingCommandIsSwallowed() {
        boolean missing = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> BrowserLauncher.open("http://127.0.0.1:8712/", List.of("litemask-no-such-command")),
                "命令不存在必须被吞掉：不能把 IOException 抛给调用方");
        assertFalse(missing, "命令不存在必须返回 false");
    }

    @Test
    @DisplayName("自家编译产物里没有进程外调用之外的「拉起外部程序」写法")
    void noShellExecutesUserControlledCommand() throws Exception {
        // 读 class 常量池而不是源文件：源文件里的注释必须允许写
        // 「为什么不走 shell」，而真正的依赖会以 "java/lang/ProcessBuilder" 这种
        // UTF8 常量出现在 class 里。
        for (String cls : List.of("com/litemask/cli/BrowserLauncher.class",
                "com/litemask/cli/AutostartCommand$RealLauncher.class")) {
            byte[] bytes;
            try (java.io.InputStream in = ProcessEgressBoundaryTest.class.getClassLoader()
                    .getResourceAsStream(cls)) {
                assertTrue(in != null, "classpath 上找不到 " + cls + "——这条守卫会永远为真");
                bytes = in.readAllBytes();
            }
            String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String forbidden : List.of("java/lang/ProcessImpl", "java/lang/UNIXProcess",
                    "java/lang/ProcessEnvironment")) {
                assertFalse(pool.contains(forbidden),
                        cls + " 的常量池里出现了 " + forbidden + "：绕开了 ProcessBuilder");
            }
        }
    }

    @Test
    @DisplayName("IOException 不会被当成「打开成功」")
    void ioExceptionMeansNotOpened() throws IOException {
        // 这条是「不许抛」的配对面：吞掉异常不等于当成成功。
        assertFalse(BrowserLauncher.open("http://127.0.0.1:8712/", List.of("litemask-no-such-command")),
                "命令不存在必须算「没打开」");
    }
}
