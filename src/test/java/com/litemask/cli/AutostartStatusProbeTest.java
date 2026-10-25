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
 * {@code autostart status} 必须区分「装没装」与「服务<b>真的</b>在不在对外服务」。
 *
 * <p><b>这个类防的是什么缺陷（2026-10-21 实测复现）</b>：
 * 占住端口后装自启，launchd 认得这个服务（{@code launchctl print} 的 state 是
 * {@code spawn scheduled} / {@code active}），
 * 但服务每次起来都立刻退出，{@code /api/health} 是 <b>timeout</b>。
 * 老实现的 {@code status} 此时报「运行状态 <b>已装载</b>」、退出码 <b>0</b> ——
 * 用户据此以为服务在跑，界面却打不开。
 *
 * <p><b>为什么判据落在 status 这一层</b>：这不是服务端的问题
 * （服务端起不来是端口被占造成的，且 launchd 会自动重试到就位），
 * 也不是 launchd 的问题（launchd 认不认这个服务与它起不起来是两回事）。
 * <b>说假话的是 status</b> —— 它拿「launchd 认得」冒充「服务在跑」。
 *
 * <p><b>标尺独立于被测实现</b>：探活被 {@link ServiceProbe} 换成假实现，
 * 单测里<b>不会真的起服务、也不会真的等超时</b>；
 * 断言的是「status 有没有真的去探、探的是哪个端口、结论与退出码对不对」。
 *
 * <p><b>判据不能恒真</b>：这里同时有<b>正样本</b>（服务在线 → 报在线、退出码 0）
 * 与<b>反样本</b>（服务不可用 → 报不可用、退出码非 0）。
 * 少了正样本，一个「永远报不可用」的实现也能让反样本全绿；
 * 少了反样本，一个「永远报在线」的实现能让正样本全绿。
 * 两条都在，才咬得住「只报已装载」这个缺陷。
 */
class AutostartStatusProbeTest {

    /** 假探活：记录探了哪个端口，按需给出结果。 */
    private static final class FakeProbe implements ServiceProbe {
        final List<Integer> probedPorts = new ArrayList<>();
        /** 【块 34】分档而不只是「在线/不可用」两档 —— 要能精确造出每一档。 */
        ServiceProbe.State state = ServiceProbe.State.ONLINE;
        String detail = "健康检查返回 200";
        /** 探了几次（healthSummary 会再探一次，所以计数会变）。 */
        int calls = 0;

        @Override
        public Result probe(int port) {
            probedPorts.add(port);
            calls++;
            return new Result(state, detail);
        }
    }

    /** 假 launchd。 */
    private static final class FakeSystem implements AutostartCommand.Launcher {
        final List<List<String>> calls = new ArrayList<>();
        int bootstrapRc = 0;
        String printOutput = "";
        int printRc = 0;

        @Override
        public int exec(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return cmd.contains("bootstrap") ? bootstrapRc : 0;
        }

        @Override
        public int execQuiet(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return cmd.contains("bootstrap") ? bootstrapRc : 0;
        }

        @Override
        public String output(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            if ("id".equals(cmd.get(0))) {
                return "501";
            }
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
    }

    private record Result(int code, String text) {
    }

    private Path home;
    private Path appDir;
    private FakeSystem sys;
    private FakeProbe probe;

    @BeforeEach
    void setUp(@TempDir Path root) throws Exception {
        home = root.resolve("home");
        Files.createDirectories(home);
        appDir = root.resolve("LightMeter.app");
        Path exe = appDir.resolve("Contents/MacOS/LightMeter");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        // 【2026-10-25 修掉的真缺陷：这套测试在 Windows 上整类红】
        //   Files.setPosixFilePermissions 在 Windows 上抛 UnsupportedOperationException，
        //   而它挂在 @BeforeEach 上 → 12 条用例**一条都没跑**就全红。
        //   而这个「可执行位」对被测逻辑是**无关**的：
        //   测试从不真的执行这个假 .app —— launchd 交互与探活都是注入的假实现。
        //   → 只在支持 POSIX 权限的文件系统上设，不删任何断言。
        if (Files.getFileStore(exe.getParent()).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        sys = new FakeSystem();
        probe = new FakeProbe();
    }

    private Result run(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int code = AutostartCommand.run(args, ps, ps, sys, home, "Mac OS X", probe);
        return new Result(code, buf.toString(StandardCharsets.UTF_8));
    }

    private Path plistPath() {
        return home.resolve("Library/LaunchAgents/com.litemask.lightmeter.plist");
    }

    /** 装上自启，并让 launchd「认得」这个服务。 */
    private void installLoaded(int port) {
        Result r = run("install", "--app", appDir.toString(), "--port", String.valueOf(port));
        assertEquals(0, r.code(), "install 应成功：" + r.text());
        sys.printOutput = "gui/501/com.litemask.lightmeter = {\n"
                + "\tstate = running\n"
                + "\truns = 1\n"
                + "\tpid = 4242\n"
                + "}";
    }

    // ---------------------------------------------------------------
    // 正样本：服务在线
    // ---------------------------------------------------------------

    @Test
    @DisplayName("服务在线时：status 报「在线」，退出码 0，并且真的去探了 plist 里那个端口")
    void statusReportsOnlineWhenServiceAnswers() {
        installLoaded(8090);
        probe.state = ServiceProbe.State.ONLINE;
        Result r = run("status");
        assertEquals(0, r.code(), "服务在线不该是故障：" + r.text());
        assertTrue(r.text().contains("在线"), "要说清服务在线：" + r.text());
        // 同样落在「服务检查」那一行上：全文 contains("在线") 会被别处满足。
        assertTrue(服务检查行(r.text()).contains("在线"),
                "「服务检查」那一行必须写在线：" + r.text());
        assertFalse(服务检查行(r.text()).contains("不可用"),
                "在线时「服务检查」那一行不该说不可用：" + r.text());
        assertTrue(r.text().contains("已装载"), "仍要报 launchd 的装载状态：" + r.text());
        // **真的探了**，且探的是 plist 里写的那个端口（不是默认值碰巧相同）。
        assertFalse(probe.probedPorts.isEmpty(), "status 必须真的去探一次服务：" + r.text());
        assertEquals(List.of(8090), probe.probedPorts,
                "探的端口必须来自 plist（唯一真源），不是猜的默认值");
    }

    @Test
    @DisplayName("端口从 plist 读：装在 12345 端口，status 就探 12345")
    void statusProbesThePortRecordedInPlist() {
        installLoaded(12345);
        probe.state = ServiceProbe.State.ONLINE;
        Result r = run("status");
        assertEquals(0, r.code(), r.text());
        assertTrue(probe.probedPorts.contains(12345),
                "必须探 plist 里的端口 12345，实际探了 " + probe.probedPorts);
        assertFalse(probe.probedPorts.contains(8090),
                "不能去探默认端口（那是另一个事实）：" + probe.probedPorts);
    }

    // ---------------------------------------------------------------
    // 反样本：服务不可用 —— 这才是本块要修的那个缺陷
    // ---------------------------------------------------------------

    @Test
    @DisplayName("服务不可用时：status 必须说「不可用」并给出非 0 退出码（不能还说「已装载」就收工）")
    void statusReportsUnavailableWithNonZeroExit() {
        installLoaded(8090);
        probe.state = ServiceProbe.State.DOWN;
        probe.detail = "连不上（端口 8090 上没有服务在听）";
        Result r = run("status");
        assertNotEquals(0, r.code(),
                "服务不可用必须是非 0 退出码（脚本要能据此报警）：" + r.text());
        assertTrue(服务检查行(r.text()).contains("不可用"),
                "「服务检查」那一行必须写不可用 —— 不能让用户以为服务在跑：" + r.text());
        assertTrue(r.text().contains("连不上"),
                "要给出**原因**，不只是「不可用」：" + r.text());
    }

    /**
     * 取出「服务检查」那一行的内容。
     *
     * <p><b>为什么要单独取这一行，而不是全文 {@code contains}</b>：
     * 自证时发现过一个**真的判据漏洞** —— 早先用
     * {@code text().contains("不可用")}，而错误信息里那句
     * 「开机自启已装载，但服务当前不可用」**也会满足它** →
     * 于是把「服务检查」那行改成永远「在线」，测试照样绿
     * （自证 5 条变异里只有这一条没咬住）。
     * → 判据必须落在**报这一事实的那一行**上。
     *
     * @param text 完整输出
     * @return 「服务检查」那一行的内容；找不到时返回空串
     */
    private static String 服务检查行(String text) {
        for (String line : text.split("\n")) {
            if (line.contains("服务检查")) {
                return line;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 【块 34】分档：不同档位要说出**不同的**下一步
    // ------------------------------------------------------------------

    @Test
    @DisplayName("端口上不是本工具的服务时：必须说清「不是它」，并给出该做什么")
    void statusDistinguishesForeignService() {
        installLoaded(8090);
        probe.state = ServiceProbe.State.NOT_OURS;
        probe.detail = "端口 8090 上答的不是本工具的服务（它没报出服务名「litemask」）";
        Result r = run("status");
        assertNotEquals(0, r.code(), "不是本工具的服务必须是非 0 退出码：" + r.text());
        assertTrue(服务检查行(r.text()).contains("不是本工具"),
                "「服务检查」那一行必须说清不是它：" + r.text());
        assertTrue(r.text().contains("别的程序"),
                "要给出该做什么（把占端口的人赶走）：" + r.text());
    }

    @Test
    @DisplayName("服务在但不健康时：必须与「没在监听」分开说")
    void statusDistinguishesUnhealthyFromDown() {
        installLoaded(8090);
        probe.state = ServiceProbe.State.UNHEALTHY;
        probe.detail = "连上了但没在 1500 毫秒内应答（服务在跑但卡住了）";
        Result r = run("status");
        assertNotEquals(0, r.code(), "不健康必须是非 0 退出码：" + r.text());
        assertTrue(服务检查行(r.text()).contains("不健康"),
                "「服务检查」那一行必须说清是在但不健康：" + r.text());
        assertFalse(服务检查行(r.text()).contains("没在监听"),
                "「在但不健康」与「没在监听」是两件事，不能混着说：" + r.text());
        assertTrue(r.text().contains("卡住"),
                "要给出该做什么（看日志/重启）：" + r.text());
    }

    @Test
    @DisplayName("每一档都要在「服务检查」那一行上分得开（不能全说同一句）")
    void everyStateHasItsOwnVerdict() {
        installLoaded(8090);
        java.util.Set<String> 见过 = new java.util.LinkedHashSet<>();
        for (ServiceProbe.State st : ServiceProbe.State.values()) {
            probe.state = st;
            probe.detail = "测试用的说明";
            Result r = run("status");
            String line = 服务检查行(r.text());
            assertFalse(line.isEmpty(), "「服务检查」那一行必须存在：" + r.text());
            assertTrue(见过.add(line),
                    "不同档位在「服务检查」那一行上必须说得出区别（撞了：" + line + "）：" + r.text());
        }
        assertEquals(ServiceProbe.State.values().length, 见过.size(),
                "每一档都要有自己的说法");
    }

    @Test
    @DisplayName("「已装载但服务不可用」时，输出里不能只出现「已装载」而没有「不可用」")
    void statusDoesNotStopAtLoadedWhenServiceIsDown() {
        // 这条是**本块要修的那个缺陷本身**：
        // 老实现只报 launchd 的装载状态，于是「装载着但起不来」被说成「一切正常」。
        installLoaded(8090);
        probe.state = ServiceProbe.State.DOWN;
        probe.detail = "连上了但没响应（超时 1500 毫秒）";
        Result r = run("status");
        assertTrue(r.text().contains("已装载"),
                "launchd 确实认得它，这句仍然要留着（是事实）：" + r.text());
        assertTrue(服务检查行(r.text()).contains("不可用"),
                "但「服务检查」那一行必须写不可用 —— 两句都要在，才没说假话：" + r.text());
    }

    @Test
    @DisplayName("服务不可用时要给出可行动的下一步（不能只说一句「不可用」）")
    void statusGivesActionableHintWhenServiceIsDown() {
        installLoaded(8090);
        probe.state = ServiceProbe.State.DOWN;
        probe.detail = "连不上（端口 8090 上没有服务在听）";
        Result r = run("status");
        assertTrue(r.text().contains("/api/health"),
                "要告诉用户怎么自己核对：" + r.text());
        assertTrue(r.text().contains("8090"), "要说出是哪个端口：" + r.text());
    }

    // ---------------------------------------------------------------
    // 前提不成立时：必须显式说「不知道」，不许猜
    // ---------------------------------------------------------------

    @Test
    @DisplayName("plist 里读不出端口时：明说「不知道」，不许猜一个默认端口去探")
    void statusSaysUnknownWhenPortCannotBeRead() throws Exception {
        installLoaded(8090);
        // 把 plist 里的 --port 改坏（模拟「plist 被手改过 / 形态不认识」）。
        String xml = Files.readString(plistPath(), StandardCharsets.UTF_8);
        Files.writeString(plistPath(), xml.replace("<string>--port</string>", "<string>--port-x</string>"),
                StandardCharsets.UTF_8);
        Result r = run("status");
        assertTrue(r.text().contains("跳过") || r.text().contains("未知"),
                "读不出端口就该说不知道：" + r.text());
        assertTrue(probe.probedPorts.isEmpty(),
                "读不出端口时**不许去探某个端口**（探了就是在编造事实）：" + probe.probedPorts);
        assertNotEquals(0, r.code(), "「不知道服务在不在」不能报成功：" + r.text());
    }

    @Test
    @DisplayName("没装时：说没装、退出码 0，且不去探任何端口")
    void statusDoesNotProbeWhenNotInstalled() {
        Result r = run("status");
        assertEquals(0, r.code(),
                "没装是**正常状态**不是故障，退出码应是 0：" + r.text());
        assertTrue(r.text().contains("没装") || r.text().contains("不在"), r.text());
        assertTrue(probe.probedPorts.isEmpty(),
                "没装时没什么可探的，不该去探：" + probe.probedPorts);
    }

    // ---------------------------------------------------------------
    // 读端口这个纯函数本身
    // ---------------------------------------------------------------

    @Test
    @DisplayName("从 plist 读端口：读得到、读不到、乱写都分得清")
    void parsePortDistinguishesReadableFromNot() {
        String good = "  <key>ProgramArguments</key>\n  <array>\n"
                + "    <string>/x/serve</string>\n"
                + "    <string>--port</string>\n"
                + "    <string>8090</string>\n"
                + "  </array>\n";
        assertEquals(8090, LaunchAgentPlist.parsePort(good), "正常形态要读得出：");
        assertEquals(-1, LaunchAgentPlist.parsePort("<string>x</string>"),
                "没有 --port 就是读不到：");
        assertEquals(-1, LaunchAgentPlist.parsePort(
                        "<string>--port</string>\n<integer>8090</integer>"),
                "形态不认识就是读不到（不许从别的标签里猜）：");
        assertEquals(-1, LaunchAgentPlist.parsePort(
                        "<string>--port</string>\n<string>abc</string>"),
                "不是数字就是读不到：");
        assertEquals(-1, LaunchAgentPlist.parsePort(
                        "<string>--port</string>\n<string>99999</string>"),
                "越界的端口就是读不到：");
        assertEquals(-1, LaunchAgentPlist.parsePort(null), "null 就是读不到：");
    }

    @Test
    @DisplayName("plist 里 --port 后面必须紧跟字符串值：夹了别的不算")
    void parsePortRequiresAdjacentStringValue() {
        // 渲染出来的形态是「一行一个键」，但格式不该被当成契约。
        // 关键是「--port 后面紧邻的那个字符串」。
        String xml = "<string>--port</string>\n    <string>9000</string>";
        assertEquals(9000, LaunchAgentPlist.parsePort(xml), "换行缩进不算「不紧邻」：");
        String notAdjacent = "<string>--port</string>\n<key>x</key>\n<string>9000</string>";
        assertEquals(-1, LaunchAgentPlist.parsePort(notAdjacent),
                "中间夹了别的东西就不许取那个数字：");
    }
}
