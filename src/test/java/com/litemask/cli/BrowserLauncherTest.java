package com.litemask.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BrowserLauncher}：用系统命令开浏览器，替代 {@code java.awt.Desktop}。
 *
 * <p>本类存在的理由是省下 {@code java.desktop} 的 14.1MB，所以「它确实不碰 java.awt」
 * 本身就是一条要守住的性质——将来有人觉得 {@code Desktop} 更简洁而换回去，
 * 这条测试要立刻红。
 *
 * <p>只测纯函数（{@code commandFor} / {@code isLaunchable}），不真的启动浏览器。
 * 真启动的分支靠端到端验——e2e 里 {@code --no-browser} 与默认路径都跑过，
 * 而单测里真去调 {@code open} 会污染开发机。
 */
class BrowserLauncherTest {

    private static final String URL = "http://127.0.0.1:8712/";

    // ------------------------------------------------------------------ commandFor

    @Test
    @DisplayName("macOS 用 open -u：-u 强制按 URL 处理，否则可能被当文件")
    void macUsesOpenWithUrlFlag() {
        assertEquals(List.of("open", "-u", URL), BrowserLauncher.commandFor("Mac OS X", URL));
        assertEquals(List.of("open", "-u", URL), BrowserLauncher.commandFor("macOS", URL));
    }

    @Test
    @DisplayName("Windows 用 rundll32 url.dll,FileProtocolHandler")
    void windowsUsesRundll32() {
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", URL),
                BrowserLauncher.commandFor("Windows 11", URL));
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", URL),
                BrowserLauncher.commandFor("windows server 2022", URL));
    }

    @Test
    @DisplayName("Linux 用 xdg-open")
    void linuxUsesXdgOpen() {
        assertEquals(List.of("xdg-open", URL), BrowserLauncher.commandFor("Linux", URL));
        assertEquals(List.of("xdg-open", URL), BrowserLauncher.commandFor("AIX", URL));
    }

    @Test
    @DisplayName("不认识这个系统返回空命令，不瞎猜")
    void unknownOsYieldsNoCommand() {
        assertEquals(List.of(), BrowserLauncher.commandFor("Plan 9", URL));
        assertEquals(List.of(), BrowserLauncher.commandFor("", URL));
        assertEquals(List.of(), BrowserLauncher.commandFor(null, URL));
    }

    @Test
    @DisplayName("平台判定大小写不敏感")
    void osMatchIsCaseInsensitive() {
        assertEquals(List.of("open", "-u", URL), BrowserLauncher.commandFor("MAC OS X", URL));
        assertEquals(List.of("xdg-open", URL), BrowserLauncher.commandFor("LINUX", URL));
    }

    // ------------------------------------------------------------------ isLaunchable

    @Test
    @DisplayName("只放行 http/https")
    void onlyHttpIsLaunchable() {
        assertTrue(BrowserLauncher.isLaunchable("http://127.0.0.1:8712/"));
        assertTrue(BrowserLauncher.isLaunchable("https://example.com/a"));
        assertTrue(BrowserLauncher.isLaunchable("HTTPS://EXAMPLE.COM/"), "协议判断应大小写不敏感");
    }

    @Test
    @DisplayName("拒绝 file/javascript/自定义协议")
    void rejectsOtherSchemes() {
        // file:// 能打开本地文件是 Desktop 的能力，这里不需要——
        // 少一种能打开的东西，也少一条被诱导去读本地文件的路。
        assertFalse(BrowserLauncher.isLaunchable("file:///etc/passwd"));
        assertFalse(BrowserLauncher.isLaunchable("javascript:alert(1)"));
        assertFalse(BrowserLauncher.isLaunchable("ftp://example.com/x"));
        assertFalse(BrowserLauncher.isLaunchable("example.com"));
    }

    @Test
    @DisplayName("拒绝以 - 开头的地址：协议前缀本身就是防线")
    void rejectsDashLeading() {
        // 实测：open -weird-flag 报 "invalid option -- w" 并刷一屏用法，
        // 而调用方以为已经打开过了。
        //
        // 保护来自**协议前缀检查**，不是一条单独的 `-` 规则。
        // 第一版代码里写了 `url.startsWith("-")`，对照实验把它去掉后测试照样全绿——
        // 那段是不改变任何可达行为的死代码，已经删掉。
        // 所以这里测的是「协议前缀能否挡住 flag」这个真问题。
        assertFalse(BrowserLauncher.isLaunchable("-weird-flag"));
        assertFalse(BrowserLauncher.isLaunchable("-http://evil.example/"),
                "-http://… 整体不以 http:// 开头，必须被拒");
        assertFalse(BrowserLauncher.isLaunchable("-open"), "也不能是 open 的选项");
        assertFalse(BrowserLauncher.isLaunchable("  "));
        assertFalse(BrowserLauncher.isLaunchable(""));
        assertFalse(BrowserLauncher.isLaunchable(null));
    }

    @Test
    @DisplayName("合法地址原样进入命令，没有被转义或改写")
    void urlPassesThroughUnchanged() {
        String tricky = "http://127.0.0.1:8712/?t=a%20b&x=1#frag";
        assertEquals(List.of("open", "-u", tricky), BrowserLauncher.commandFor("Mac OS X", tricky));
    }

    // ------------------------------------------------------------------ 性质

    @Test
    @DisplayName("命令不存在时返回 false 而不是抛异常")
    void missingCommandIsSwallowed() {
        // 这一条必须在**任何平台**上都能测到。
        // 之前 open() 内部自己取 System.getProperty("os.name")，
        // 于是在 macOS 上「rundll32 不存在」这条分支永远走不到——
        // 对照实验「去掉 catch IOException」会得到 GREEN，而它本该是红的。
        // 所以命令必须能由调用方注入。
        //
        // **必须用 assertDoesNotThrow 包一层**，不是为了好看：
        // 变异 Ⓐ（把 IOException 抛出去而不是吞掉）会让 open() 直接把异常甩到测试方法上，
        // surefire 记的是 Error 而**不是** Failure，于是「断言失败 0 条」，
        // 对照实验按规矩判 INVALID——「抛异常红」不算测试咬住了实现。
        // 只有把「不许抛」写成一条断言，这条红灯的原因才是断言而不是崩溃。
        boolean missing = assertDoesNotThrow(
                () -> BrowserLauncher.open(URL, List.of("litemask-no-such-command")),
                "命令不存在必须被吞掉：不能把 IOException 抛给调用方");
        assertFalse(missing, "命令不存在必须返回 false");
        assertFalse(openQuietly(List.of()), "空命令必须返回 false");
        assertFalse(openQuietly(null), "null 命令必须返回 false");
        // 就算 URL 不合法，也不能走到启动那一步
        assertFalse(openQuietly("file:///etc/passwd", List.of("litemask-no-such-command")));
    }

    @Test
    @DisplayName("命令能启动时返回 true（用本机一定存在的命令）")
    void existingCommandReturnsTrue() {
        // 用真能在所有平台跑起来的 /bin/sh 或其等价物，把"成功路径"也钉住。
        // 只测失败路径的话，一个永远返回 false 的实现也能全绿。
        assertTrue(openQuietly(shellExitZero()), "能启动的命令必须返回 true");
    }

    @Test
    @DisplayName("非法地址即使命令可用也不会被启动")
    void illegalUrlIsNotLaunchedEvenWithWorkingCommand() {
        // 这条必须配一个**能真的跑起来的命令**。
        // 用「不存在的命令」去测的话，撤掉 isLaunchable 守卫后 open() 照样返回 false
        // （启动失败），测试仍然全绿——变异是空操作，对照实验会给出一个假的 GREEN。
        // 判据必须是「命令明明可用，结果却是 false」，那才说明地址校验起了作用。
        List<String> ok = shellExitZero();
        assertFalse(openQuietly("file:///etc/passwd", ok),
                "file:// 必须被地址校验挡住，不能交给系统打开");
        assertFalse(openQuietly("-http://evil.example/", ok),
                "- 开头的地址不能被当成 open 的选项");
        assertFalse(openQuietly("javascript:alert(1)", ok));
    }

    /**
     * 调 {@code BrowserLauncher.open} 并把「不许抛异常」变成一条真正的断言。
     *
     * <p>{@code open} 的契约是「失败一律吞掉」——打不开浏览器不该让后台服务起不来。
     * 契约写在了 javadoc 里，但只有被断言过的契约才是契约：
     * 直接裸调的话，违约的表现是测试 Error 而不是 Failure，
     * 对照实验只能把它判成「抛异常红、作废」，这条性质等于没测。
     */
    private static boolean openQuietly(List<String> cmd) {
        return openQuietly(URL, cmd);
    }

    private static boolean openQuietly(String url, List<String> cmd) {
        return assertDoesNotThrow(() -> BrowserLauncher.open(url, cmd),
                "open(url, cmd) 承诺「失败一律吞掉」，不该抛异常：url=" + url + " cmd=" + cmd);
    }

    private static List<String> shellExitZero() {
        return isWindows()
                ? List.of("cmd", "/c", "exit", "0")
                : List.of("/bin/sh", "-c", "exit 0");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @Test
    @DisplayName("open() 在当前系统上不会抛异常")
    void openNeverThrows() {
        // 实测：系统里没有 rundll32 时 ProcessBuilder.start() 抛 IOException，
        // 而不是「命令存在但不支持」。所以 open() 必须自己吞掉。
        //
        // 这一条是「单参 open() 也不许抛」的端到端版本：它取的是本机 os.name，
        // 换平台就跑不到「命令缺失」那条分支（这是 Ⓐ 那条变异存在的原因）。
        // 所以这里用真的本机命令，只断言「不抛」。
        assertDoesNotThrow(() -> BrowserLauncher.open(URL),
                "open(合法地址) 不该抛：打不开浏览器不该让服务起不来");
        assertFalse(BrowserLauncher.open("file:///etc/passwd"), "非 http 协议必须拒绝");
        assertFalse(BrowserLauncher.open("-bad"), "以 - 开头必须拒绝");
        assertFalse(BrowserLauncher.open(null), "null 必须拒绝");
    }

    @Test
    @DisplayName("编译产物里不再引用 java.awt")
    void noJavaAwtDependency() throws Exception {
        // 这条是"省 14.1MB"这件事唯一的自动守卫，换回 Desktop 的那一刻就该红。
        //
        // 扫的是 **class 常量池**而不是源文件：源文件里的注释必须允许写
        // 「为什么不用 Desktop」，而真正的依赖会以 "java/awt/Desktop" 这种
        // UTF8 常量出现在 class 里。读 class 还顺带绕开了「源码不一定在
        // 测试 classpath 上」这个前提——第一版就是读源码，直接报
        // "读不到源码"，于是这条守卫从未真正生效。
        for (String cls : List.of("com/litemask/cli/BrowserLauncher.class",
                "com/litemask/cli/ServeCommand.class")) {
            byte[] bytes;
            try (java.io.InputStream in = BrowserLauncherTest.class.getClassLoader()
                    .getResourceAsStream(cls)) {
                assertTrue(in != null, "classpath 上找不到 " + cls + "——这条守卫会永远为真");
                bytes = in.readAllBytes();
            }
            String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String forbidden : List.of("java/awt", "java.awt", "Desktop", "GraphicsEnvironment")) {
                assertFalse(pool.contains(forbidden),
                        cls + " 的常量池里出现了 " + forbidden + "：java.desktop 又被拖回来了");
            }
        }
    }
}
