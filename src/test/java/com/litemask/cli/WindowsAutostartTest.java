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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WindowsAutostart}：Windows 开机自启的流程（装、卸、看状态）。
 *
 * <p><b>这里防的是四条真实存在的缺陷</b>：
 * <ol>
 *   <li><b>装上了但内容不对</b> —— {@code reg add} 返回 0 只说明「写进去了」，
 *       不说明「写进去的是我们想要的那条命令行」。引号被吃掉是真实存在的失败模式，
 *       症状是「自启条目在，但登录时弹『找不到文件』」，而且极难查。
 *       → 所以装完必须<b>读回来逐字核对</b>。</li>
 *   <li><b>平台判定写错方向</b> —— 用 {@code contains("win")} 判 Windows 的话，
 *       {@code "Darwin"} 会被当成 Windows（d-a-r-<b>win</b>），
 *       于是 macOS 上走进 Windows 分支、macOS 的 plist 反而没人管。
 *       → 这条必须有专门的判据咬住。</li>
 *   <li><b>查不了当成没装</b> —— {@code reg.exe} 跑不起来与「值不存在」，
 *       在只看输出文本时长得一模一样。→ 必须按<b>退出码</b>分开。</li>
 *   <li><b>卸载后自启还在</b> —— 注册表里的值只要还在，下次登录就会自己起来。</li>
 * </ol>
 *
 * <p><b>标尺独立于被测实现</b>：{@code reg.exe} 被换成了假实现，
 * 单测里<b>不会真的往注册表里写东西</b>；断言的是「我们要求它发哪些命令、
 * 怎么解释它的返回」，而不是「注册表最后变成什么样」。
 */
class WindowsAutostartTest {

    /**
     * 假 {@code reg.exe}：按 argv 语义模拟注册表。
     *
     * <p><b>它模拟的是 {@code reg.exe} 的契约</b>（键 /v 值名 /t REG_SZ /d 数据 /f、
     * 查询不到时退出码非 0），而不是被测实现的行为 ——
     * 被测实现只负责「发对命令、读对结果」。
     */
    private static final class FakeReg implements AutostartCommand.Launcher {
        final List<List<String>> calls = new ArrayList<>();
        /** 模拟注册表：值名 → 内容。 */
        final Map<String, String> registry = new LinkedHashMap<>();
        /** reg add 的退出码（非 0 = 写失败）。 */
        int addRc = 0;
        /** reg delete 的退出码（非 0 = 删失败）。 */
        int deleteRc = 0;
        /** reg query 的退出码（非 0 = 查不到这个值）。 */
        int queryRc = 0;
        /** 模拟 {@code reg.exe} 根本跑不起来。 */
        boolean regMissing = false;
        /** 模拟 {@code reg.exe} 把值里的双引号吃掉（真实的失败模式）。 */
        boolean mangleQuotes = false;

        @Override
        public int exec(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return 0;
        }

        @Override
        public int execQuiet(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            if (regMissing) {
                return -1;
            }
            if (cmd.contains("add")) {
                if (addRc != 0) {
                    return addRc;
                }
                String name = cmd.get(cmd.indexOf("/v") + 1);
                String data = cmd.get(cmd.indexOf("/d") + 1);
                // 【2026-10-25 真 Windows 实测：假 reg.exe 必须按**真实行为**建模】
                //   真实的 reg.exe 收到 `\"C:\path\app.exe\" args` 时，
                //   存进注册表的是 **`"C:\path\app.exe" args`** ——
                //   它会把转义的双引号**还原**成字面引号。
                //   （本机实测：不转义 → 引号被吃掉；转义 → 引号原样存住。）
                //   → 这里照着做：先把 \" 还原成 "，再考虑「引号被吃掉」那种失败形态。
                String stored = data.replace("\\\"", "\"");
                registry.put(name, mangleQuotes ? stored.replace("\"", "") : stored);
                return 0;
            }
            if (cmd.contains("delete")) {
                if (deleteRc != 0) {
                    return deleteRc;
                }
                registry.remove(cmd.get(cmd.indexOf("/v") + 1));
                return 0;
            }
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
            if (regMissing) {
                return new AutostartCommand.ExecResult(-1, "");
            }
            if (!cmd.contains("query")) {
                return new AutostartCommand.ExecResult(0, "");
            }
            String name = cmd.get(cmd.indexOf("/v") + 1);
            String data = registry.get(name);
            if (queryRc != 0 || data == null) {
                // reg query 找不到时的真实形态：退出码非 0，错误信息走 stderr。
                return new AutostartCommand.ExecResult(
                        queryRc != 0 ? queryRc : 1,
                        "ERROR: The system was unable to find the specified registry key or value.\n");
            }
            String out = "\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\n"
                    + "    " + name + "    REG_SZ    " + data + "\n";
            return new AutostartCommand.ExecResult(0, out);
        }

        boolean calledWith(String... needle) {
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

    private FakeReg reg;
    private ByteArrayOutputStream buf;

    @BeforeEach
    void setUp() {
        reg = new FakeReg();
        buf = new ByteArrayOutputStream();
    }

    private Result call(int code) {
        return new Result(code, buf.toString(StandardCharsets.UTF_8));
    }

    private PrintStream ps() {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static final Path EXE = Path.of("C:\\Program Files\\LightMeter\\LightMeter.exe");

    // ------------------------------------------------------------------
    // 装
    // ------------------------------------------------------------------

    @Test
    @DisplayName("install 写出注册表值，内容就是「带引号的 exe + serve --no-browser --port」")
    void installWritesTheExpectedValue() {
        Result r = call(WindowsAutostart.install(reg, ps(), ps(),
                "com.litemask.lightmeter", EXE, 8090));
        assertEquals(0, r.code(), "install 应成功，实际输出：" + r.text());
        assertEquals("\"C:\\Program Files\\LightMeter\\LightMeter.exe\" serve --no-browser --port 8090",
                reg.registry.get("com.litemask.lightmeter"));
        assertTrue(reg.calledWith("reg", "add"), "必须真的去写注册表：" + reg.calls);
        assertTrue(r.text().contains("已安装开机自启"), r.text());
    }

    @Test
    @DisplayName("install 之后必须读回来核对：内容被改过就不能报成功")
    void installFailsWhenStoredValueDiffers() {
        // 模拟 reg.exe 把引号吃掉 —— 这是真实存在的失败模式。
        reg.mangleQuotes = true;
        Result r = call(WindowsAutostart.install(reg, ps(), ps(),
                "com.litemask.lightmeter", EXE, 8090));
        assertNotEquals(0, r.code(),
                "存进去的内容不对时不能报成功（退出码 0 但证据不符 = 判失败）：" + r.text());
        assertTrue(r.text().contains("与预期不一致"), r.text());
        // 报错里必须带上「预期 vs 实际」，否则没法定位。
        assertTrue(r.text().contains("预期"), r.text());
        assertTrue(r.text().contains("实际"), r.text());
    }

    @Test
    @DisplayName("reg add 失败必须报出来（退出码 0 但没装上 = 骗人）")
    void installFailsWhenRegAddFails() {
        reg.addRc = 5;
        Result r = call(WindowsAutostart.install(reg, ps(), ps(),
                "com.litemask.lightmeter", EXE, 8090));
        assertNotEquals(0, r.code(), "写失败不能报成功：" + r.text());
        assertTrue(r.text().contains("reg add"), r.text());
    }

    @Test
    @DisplayName("install 幂等：装两次内容不变，第二次明说「已经是这个配置」")
    void installIsIdempotent() {
        assertEquals(0, call(WindowsAutostart.install(reg, ps(), ps(),
                "com.litemask.lightmeter", EXE, 8090)).code());
        String first = reg.registry.get("com.litemask.lightmeter");
        Result second = call(WindowsAutostart.install(reg, ps(), ps(),
                "com.litemask.lightmeter", EXE, 8090));
        assertEquals(0, second.code(), second.text());
        assertEquals(first, reg.registry.get("com.litemask.lightmeter"), "第二次装不该改内容");
        assertTrue(second.text().contains("已经是这个配置"),
                "第二次要说明内容没变，实际输出：" + second.text());
    }

    @Test
    @DisplayName("相对路径的 exe 一律拒绝（登录时按不确定的工作目录解析 = 永远起不来）")
    void installRejectsRelativeExecutable() {
        Result r = call(WindowsAutostart.install(reg, ps(), ps(),
                "com.litemask.lightmeter", Path.of("LightMeter.exe"), 8090));
        assertNotEquals(0, r.code(), "必须拒绝，实际输出：" + r.text());
        assertTrue(r.text().contains("绝对路径"), r.text());
    }

    // ------------------------------------------------------------------
    // 卸
    // ------------------------------------------------------------------

    @Test
    @DisplayName("uninstall 删掉注册表里的那个值")
    void uninstallRemovesTheValue() {
        WindowsAutostart.install(reg, ps(), ps(), "com.litemask.lightmeter", EXE, 8090);
        Result r = call(WindowsAutostart.uninstall(reg, ps(), ps(), "com.litemask.lightmeter"));
        assertEquals(0, r.code(), r.text());
        assertTrue(reg.calledWith("reg", "delete"), "必须去删：" + reg.calls);
        assertFalse(reg.registry.containsKey("com.litemask.lightmeter"), "值应该没了");
        assertTrue(r.text().contains("已卸载开机自启"), r.text());
    }

    @Test
    @DisplayName("本来没装时不去删、也不报错（幂等）")
    void uninstallWhenNotInstalledIsHarmless() {
        Result r = call(WindowsAutostart.uninstall(reg, ps(), ps(), "com.litemask.lightmeter"));
        assertEquals(0, r.code(), r.text());
        assertFalse(reg.calledWith("reg", "delete"),
                "没装就不该发删除命令（不要碰别人的东西）：" + reg.calls);
        assertTrue(r.text().contains("本来就没装"), r.text());
    }

    @Test
    @DisplayName("reg delete 失败必须报出来")
    void uninstallFailsWhenRegDeleteFails() {
        WindowsAutostart.install(reg, ps(), ps(), "com.litemask.lightmeter", EXE, 8090);
        reg.deleteRc = 1;
        Result r = call(WindowsAutostart.uninstall(reg, ps(), ps(), "com.litemask.lightmeter"));
        assertNotEquals(0, r.code(), "删失败不能报成功：" + r.text());
        assertTrue(r.text().contains("reg delete"), r.text());
    }

    // ------------------------------------------------------------------
    // 看状态
    // ------------------------------------------------------------------

    /**
     * 假探活：<b>总是说在线</b>。
     *
     * <p><b>为什么既有测试要补上这个前提（2026-10-21 块 24）</b>：
     * 块 24 起，{@code status} 不只报「装没装」，还要报「服务在不在」，
     * 而「装了但服务不可用」的退出码是 <b>3</b>（非 0）。
     * 下面那三条断言断言的是「装没装」的语义（退出码 0），
     * 它们<b>隐含的前提</b>是「服务在线」——
     * 于是这里把那个前提<b>显式补上</b>，而<b>断言一个字不动</b>。
     *
     * <p><b>为什么不是「总是不可用」</b>：那会让那三条断言的退出码从 0 变成 3，
     * 变成<b>改断言</b> —— 那就超出了「只改设定」的范围了。
     */
    private static ServiceProbe alwaysOnline() {
        return port -> new ServiceProbe.Result(ServiceProbe.State.ONLINE, "健康检查返回 200");
    }

    @Test
    @DisplayName("status 在装着时报出内容")
    void statusReportsInstalled() {
        WindowsAutostart.install(reg, ps(), ps(), "com.litemask.lightmeter", EXE, 8090);
        Result r = call(WindowsAutostart.status(reg, ps(), ps(), "com.litemask.lightmeter", alwaysOnline()));
        assertEquals(0, r.code(), r.text());
        assertTrue(r.text().contains("已安装"), r.text());
        assertTrue(r.text().contains("LightMeter.exe"), r.text());
    }

    @Test
    @DisplayName("status 在没装时明说没装")
    void statusReportsNotInstalled() {
        Result r = call(WindowsAutostart.status(reg, ps(), ps(), "com.litemask.lightmeter", alwaysOnline()));
        assertEquals(0, r.code(), r.text());
        assertTrue(r.text().contains("未安装"), r.text());
        assertTrue(r.text().contains("autostart install"), r.text());
    }

    @Test
    @DisplayName("查不了注册表时不能当成「没装」（fail-closed）")
    void statusFailsWhenRegistryCannotBeQueried() {
        // reg.exe 跑不起来 —— 与「值不存在」是两回事。
        reg.regMissing = true;
        Result r = call(WindowsAutostart.status(reg, ps(), ps(), "com.litemask.lightmeter", alwaysOnline()));
        assertNotEquals(0, r.code(), "查不了不能报成「一切正常」：" + r.text());
        assertFalse(r.text().contains("未安装"), "不能把「查不了」说成「没装」：" + r.text());
    }

    @Test
    @DisplayName("卸载时查不了注册表要说「可能还留着」，不能静默当成功")
    void removeIfPresentSaysWhenQueryFails() {
        reg.regMissing = true;
        boolean removed = WindowsAutostart.removeIfPresent(reg, ps(), "com.litemask.lightmeter");
        assertFalse(removed, "查不了就不该说删掉了");
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("可能还留着"),
                buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("卸载时的删除只碰自己那个值名，不碰键里的其它值")
    void removeIfPresentOnlyTouchesOurValue() {
        WindowsAutostart.install(reg, ps(), ps(), "com.litemask.lightmeter", EXE, 8090);
        reg.registry.put("别人的自启", "C:\\other\\x.exe");
        WindowsAutostart.removeIfPresent(reg, ps(), "com.litemask.lightmeter");
        assertFalse(reg.registry.containsKey("com.litemask.lightmeter"));
        assertTrue(reg.registry.containsKey("别人的自启"), "别人的自启不能被删掉");
    }

    // ------------------------------------------------------------------
    // 平台判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("平台判定只认 windows：Darwin 里藏着 win，不能被当成 Windows")
    void windowsDetectionDoesNotCatchDarwin() {
        assertTrue(WindowsAutostart.isWindows("Windows 11"));
        assertTrue(WindowsAutostart.isWindows("Windows Server 2022"));
        assertFalse(WindowsAutostart.isWindows("Darwin"),
                "\"Darwin\" 里含 \"win\" —— 用 contains(\"win\") 判会把 macOS 认成 Windows");
        assertFalse(WindowsAutostart.isWindows("Mac OS X"));
        assertFalse(WindowsAutostart.isWindows("Linux"));
        assertFalse(WindowsAutostart.isWindows(null));
        assertFalse(WindowsAutostart.isWindows(""));
    }

    // ------------------------------------------------------------------
    // 找可执行文件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("--app 给目录：找到里面的 LightMeter.exe")
    void resolveExecutableFromDirectory(@TempDir Path root) throws Exception {
        Path dir = root.resolve("LightMeter");
        Files.createDirectories(dir);
        Path exe = dir.resolve("LightMeter.exe");
        Files.writeString(exe, "x");
        assertEquals(exe, WindowsAutostart.resolveExecutable(dir));
    }

    @Test
    @DisplayName("--app 给文件：就是它自己")
    void resolveExecutableFromFile(@TempDir Path root) throws Exception {
        Path exe = root.resolve("LightMeter.exe");
        Files.writeString(exe, "x");
        assertEquals(exe, WindowsAutostart.resolveExecutable(exe));
    }

    @Test
    @DisplayName("--app 指到没有 exe 的地方：返回 null（调用方要明确报错，不能猜）")
    void resolveExecutableReturnsNullWhenMissing(@TempDir Path root) throws Exception {
        Path dir = root.resolve("空的");
        Files.createDirectories(dir);
        assertNull(WindowsAutostart.resolveExecutable(dir));
        assertNull(WindowsAutostart.resolveExecutable(root.resolve("不存在.exe")));
        assertNull(WindowsAutostart.resolveExecutable(null));
    }

    // ------------------------------------------------------------------
    // 与 CLI 的接线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CLI 在 Windows 上走注册表分支：不写 plist，只发 reg 命令")
    void cliOnWindowsUsesRegistry(@TempDir Path root) throws Exception {
        Path dir = root.resolve("LightMeter");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("LightMeter.exe"), "x");

        Result r = runCli(root.resolve("home"), "Windows 11", "install", "--app", dir.toString());
        assertEquals(0, r.code(), "Windows 上 install 应成功：" + r.text());
        assertTrue(reg.calledWith("reg", "add"), "应该发 reg add：" + reg.calls);
        assertFalse(Files.exists(root.resolve("home/Library/LaunchAgents")),
                "Windows 上不该造 macOS 的 plist 目录");
    }

    @Test
    @DisplayName("CLI 在 macOS 上走 plist 分支：Darwin 不能被误判成 Windows")
    void cliOnDarwinUsesPlist(@TempDir Path root) throws Exception {
        Path app = root.resolve("LightMeter.app");
        Path exe = app.resolve("Contents/MacOS/LightMeter");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "x");
        exe.toFile().setExecutable(true);

        Result r = runCli(root.resolve("home"), "Darwin", "install", "--app", app.toString());
        assertEquals(0, r.code(), "macOS 上 install 应成功：" + r.text());
        assertTrue(Files.exists(root.resolve("home/Library/LaunchAgents/com.litemask.lightmeter.plist")),
                "macOS 上应该写 plist：" + r.text());
        assertFalse(reg.calledWith("reg"), "macOS 上不该发 reg 命令：" + reg.calls);
    }

    @Test
    @DisplayName("CLI 在既不是 macOS 也不是 Windows 的系统上明确报错，不静默假装成功")
    void cliOnOtherPlatformFailsClosed(@TempDir Path root) throws Exception {
        Path dir = root.resolve("LightMeter");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("LightMeter.exe"), "x");

        Result r = runCli(root.resolve("home"), "Linux", "install", "--app", dir.toString());
        assertEquals(2, r.code(), "必须报错退出 2：" + r.text());
        assertTrue(r.text().contains("macOS"), r.text());
        assertTrue(r.text().contains("Windows"), r.text());
        assertFalse(reg.calledWith("reg"), "不该发任何命令：" + reg.calls);
    }

    @Test
    @DisplayName("CLI 在 Windows 上也校验 label（label 会进注册表值名）")
    void cliOnWindowsValidatesLabel(@TempDir Path root) throws Exception {
        Path dir = root.resolve("LightMeter");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("LightMeter.exe"), "x");

        Result r = runCli(root.resolve("home"), "Windows 11", "install",
                "--app", dir.toString(), "--label", "../evil");
        assertEquals(2, r.code(), "必须拒绝，实际输出：" + r.text());
        assertFalse(reg.calledWith("reg"), "不该发任何命令：" + reg.calls);
    }

    /** 跑一次 {@code autostart} 子命令（注入假的 reg.exe 与主目录）。 */
    private Result runCli(Path home, String osName, String... args) {
        try {
            Files.createDirectories(home);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        int code = AutostartCommand.run(args, ps, ps, reg, home, osName);
        return new Result(code, out.toString(StandardCharsets.UTF_8));
    }
}
