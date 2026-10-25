package com.litemask.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * Windows 侧的 {@code autostart status} 必须区分「装没装」与「服务<b>真的</b>在不在」。
 *
 * <p><b>这个类防的是什么缺陷（2026-10-21 块 24）</b>：
 * 块 23 把 macOS 侧的 status 改成了两段式，并在阶段文档 §27.12 明确记下
 * 「<b>Windows 侧没做</b>」。Windows 侧那时候的 status 只报
 * 「装没装 / 值里存的是什么」，<b>退出码恒 0</b> ——
 * 用户看到「已安装」就以为服务一直在跑。
 *
 * <p><b>Windows 侧比 macOS 侧更严重</b>：
 * <ul>
 *   <li>macOS 侧有 {@code KeepAlive}，服务挂了 launchd 会自动重试；</li>
 *   <li>Windows 的 Run 键<b>只在登录时触发一次</b>，而且<b>没有自愈</b> ——
 *       服务挂了、被关了，<b>永远不会自己回来</b>。</li>
 * </ul>
 * → 「已安装」在 Windows 上几乎<b>不代表</b>「现在能用」。
 *
 * <p><b>端口的唯一真源是「已写进 Run 键的那条命令行」</b>：
 * 与 macOS 侧「已写出的 plist」对称 —— 同一个事实，两个平台各自的真源。
 * 若改用「猜一个默认端口」，就会去探一个不是本服务在用的端口，
 * 探不通 → 报「不可用」，而真相是「探错了地方」—— 那是<b>编造事实</b>。
 *
 * <p><b>标尺独立于被测实现</b>：{@code reg.exe} 被换成假实现，
 * 探活被 {@link ServiceProbe} 换成假实现 —— 单测里<b>不会真的写注册表、
 * 不会真的起服务、也不会真的等超时</b>。
 *
 * <p><b>判据不能恒真</b>：这里同时有<b>正样本</b>（服务在线 → 报在线、退出码 0）
 * 与<b>反样本</b>（服务不可用 → 报不可用、退出码 3）。
 * 少了正样本，一个「永远报不可用」的实现也能让反样本全绿；
 * 少了反样本，一个「永远报在线」的实现能让正样本全绿。
 *
 * <p><b>判据落在「报这一事实的那一行」上</b>（块 23 的教训）：
 * 全文 {@code contains("不可用")} 会被「开机自启已安装，但服务当前不可用」
 * 那句<b>错误信息</b>满足 → 于是把「服务检查」那行改成永远「在线」，测试照样绿。
 */
class WindowsAutostartStatusTest {

    private static final String LABEL = "com.litemask.lightmeter";
    private static final Path EXE = Path.of("C:\\Program Files\\LightMeter\\LightMeter.exe");

    /**
     * 假 {@code reg.exe}：按 argv 语义模拟注册表。
     *
     * <p>它模拟的是 {@code reg.exe} 的<b>契约</b>（{@code /v} 值名、{@code /t REG_SZ}、
     * {@code /d} 数据；查询不到时退出码非 0），不是被测实现的行为。
     */
    private static final class FakeReg implements AutostartCommand.Launcher {
        final List<List<String>> calls = new ArrayList<>();
        /** 模拟注册表：值名 → 内容。 */
        final Map<String, String> registry = new LinkedHashMap<>();
        /** 模拟 {@code reg.exe} 根本跑不起来。 */
        boolean regMissing = false;
        /** 模拟「值在，但输出格式不认识」——读不出内容，**不等于没装**。 */
        boolean unreadableOutput = false;

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
                // 【2026-10-25 真 Windows 实测】真实的 reg.exe 收到 `\"...\"` 时
                //   会把转义的双引号**还原**成字面引号再存进去。
                //   假 reg.exe 必须照着做，否则这条测试就在验一个不存在的 reg.exe。
                registry.put(cmd.get(cmd.indexOf("/v") + 1),
                        cmd.get(cmd.indexOf("/d") + 1).replace("\\\"", "\""));
                return 0;
            }
            if (cmd.contains("delete")) {
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
            if (unreadableOutput) {
                // 退出码 0（值在），但输出格式不认识 → 「装了但读不出内容」。
                return new AutostartCommand.ExecResult(0, "格式不认识的一段输出\n");
            }
            String name = cmd.get(cmd.indexOf("/v") + 1);
            String data = registry.get(name);
            if (data == null) {
                return new AutostartCommand.ExecResult(1,
                        "ERROR: The system was unable to find the specified registry key or value.\n");
            }
            String out = "\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\n"
                    + "    " + name + "    REG_SZ    " + data + "\n";
            return new AutostartCommand.ExecResult(0, out);
        }
    }

    /** 假探活：记录探了哪个端口，按需给出结果。 */
    private static final class FakeProbe implements ServiceProbe {
        final List<Integer> probedPorts = new ArrayList<>();
        /** 【块 34】分档而不只是「在线/不可用」两档 —— 要能精确造出每一档。 */
        ServiceProbe.State state = ServiceProbe.State.ONLINE;
        String detail = "健康检查返回 200";

        @Override
        public Result probe(int port) {
            probedPorts.add(port);
            return new Result(state, detail);
        }
    }

    private record Result(int code, String text) {
    }

    private FakeReg reg;
    private FakeProbe probe;
    private ByteArrayOutputStream buf;

    @BeforeEach
    void setUp() {
        reg = new FakeReg();
        probe = new FakeProbe();
        buf = new ByteArrayOutputStream();
    }

    private PrintStream ps() {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private Result status() {
        int code = WindowsAutostart.status(reg, ps(), ps(), LABEL, probe);
        return new Result(code, buf.toString(StandardCharsets.UTF_8));
    }

    /** 装上自启（走真实的 install 路径，Run 键里存的就是这条命令行）。 */
    private void install(int port) {
        int rc = WindowsAutostart.install(reg, ps(), ps(), LABEL, EXE, port);
        assertEquals(0, rc, "install 应成功：" + buf.toString(StandardCharsets.UTF_8));
        buf.reset();
    }

    /**
     * 取出「报这一事实的那一行」的内容。
     *
     * @param text 完整输出
     * @param 关键字 行里要含的关键字
     * @return 第一个含该关键字的行；找不到时返回空串
     */
    private static String 行(String text, String 关键字) {
        for (String line : text.split("\n")) {
            if (line.contains(关键字)) {
                return line;
            }
        }
        return "";
    }

    // ---------------------------------------------------------------
    // 正样本：服务在线
    // ---------------------------------------------------------------

    @Test
    @DisplayName("服务在线时：status 报「在线」、退出码 0，且真的去探了 Run 键里那个端口")
    void statusReportsOnlineWhenServiceAnswers() {
        install(8090);
        probe.state = ServiceProbe.State.ONLINE;
        Result r = status();
        assertEquals(0, r.code(), "服务在线不该是故障：" + r.text());
        assertTrue(行(r.text(), "服务检查").contains("在线"),
                "「服务检查」那一行必须写在线：" + r.text());
        assertFalse(行(r.text(), "服务检查").contains("不可用"),
                "在线时「服务检查」那一行不该说不可用：" + r.text());
        assertTrue(r.text().contains("已安装"), "仍要报装没装：" + r.text());
        assertFalse(probe.probedPorts.isEmpty(), "status 必须真的去探一次服务：" + r.text());
        assertEquals(List.of(8090), probe.probedPorts,
                "探的端口必须来自 Run 键（唯一真源），不是猜的默认值");
    }

    // ------------------------------------------------------------------
    // 【块 34】分档：不同档位要说出**不同的**下一步（与 macOS 侧同一个判据）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("端口上不是本工具的服务时：必须说清「不是它」，并给出该做什么")
    void statusDistinguishesForeignService() {
        install(8090);
        probe.state = ServiceProbe.State.NOT_OURS;
        probe.detail = "端口 8090 上答的不是本工具的服务（它没报出服务名「litemask」）";
        Result r = status();
        assertNotEquals(0, r.code(), "不是本工具的服务必须是非 0 退出码：" + r.text());
        assertTrue(行(r.text(), "服务检查").contains("不是本工具"),
                "「服务检查」那一行必须说清不是它：" + r.text());
        assertTrue(r.text().contains("别的程序"),
                "要给出该做什么（把占端口的人赶走）：" + r.text());
    }

    @Test
    @DisplayName("服务在但不健康时：必须与「没在监听」分开说")
    void statusDistinguishesUnhealthyFromDown() {
        install(8090);
        probe.state = ServiceProbe.State.UNHEALTHY;
        probe.detail = "连上了但没在 1500 毫秒内应答（服务在跑但卡住了）";
        Result r = status();
        assertNotEquals(0, r.code(), "不健康必须是非 0 退出码：" + r.text());
        assertTrue(行(r.text(), "服务检查").contains("不健康"),
                "「服务检查」那一行必须说清是在但不健康：" + r.text());
        assertFalse(行(r.text(), "服务检查").contains("没在监听"),
                "「在但不健康」与「没在监听」是两件事，不能混着说：" + r.text());
        assertTrue(r.text().contains("卡住"),
                "要给出该做什么（重启服务）：" + r.text());
    }

    @Test
    @DisplayName("每一档都要在「服务检查」那一行上分得开（不能全说同一句）")
    void everyStateHasItsOwnVerdict() {
        install(8090);
        java.util.Set<String> 见过 = new java.util.LinkedHashSet<>();
        for (ServiceProbe.State st : ServiceProbe.State.values()) {
            probe.state = st;
            probe.detail = "测试用的说明";
            // 【为什么每次都要清缓冲】本类的 status() 往**同一个**缓冲区里写
            //   （它被设计成「一次 status 调用」的用法），多次调用会累积 →
            //   取「第一个含『服务检查』的行」会拿到**上一次**的输出。
            buf.reset();
            Result r = status();
            String line = 行(r.text(), "服务检查");
            assertFalse(line.isEmpty(), "「服务检查」那一行必须存在：" + r.text());
            assertTrue(见过.add(line),
                    "不同档位在「服务检查」那一行上必须说得出区别（撞了：" + line + "）：" + r.text());
        }
        assertEquals(ServiceProbe.State.values().length, 见过.size(),
                "每一档都要有自己的说法");
    }

    @Test
    @DisplayName("端口从 Run 键读：装在 12345 端口，status 就探 12345，不探默认端口")
    void statusProbesThePortRecordedInRunKey() {
        install(12345);
        probe.state = ServiceProbe.State.ONLINE;
        Result r = status();
        assertEquals(0, r.code(), r.text());
        assertTrue(probe.probedPorts.contains(12345),
                "必须探 Run 键里的端口 12345，实际探了 " + probe.probedPorts);
        assertFalse(probe.probedPorts.contains(8090),
                "不能去探默认端口（那是另一个事实）：" + probe.probedPorts);
    }

    // ---------------------------------------------------------------
    // 反样本：服务不可用 —— 这才是本块要修的那个缺陷
    // ---------------------------------------------------------------

    @Test
    @DisplayName("服务不可用时：status 必须说「不可用」并给出非 0 退出码（不能还说「已安装」就收工）")
    void statusReportsUnavailableWithNonZeroExit() {
        install(8090);
        probe.state = ServiceProbe.State.DOWN;
        probe.detail = "连不上（端口 8090 上没有服务在听）";
        Result r = status();
        assertEquals(3, r.code(),
                "「装了但服务不可用」是第三档退出码（脚本要能据此报警）：" + r.text());
        assertTrue(行(r.text(), "服务检查").contains("不可用"),
                "「服务检查」那一行必须写不可用 —— 不能让用户以为服务在跑：" + r.text());
        assertTrue(r.text().contains("连不上"),
                "要给出**原因**，不只是「不可用」：" + r.text());
    }

    @Test
    @DisplayName("「已安装但服务不可用」时，输出里不能只有「已安装」而没有「不可用」")
    void statusDoesNotStopAtInstalledWhenServiceIsDown() {
        // 这条是**本块要修的那个缺陷本身**：
        // 老实现只报装没装，于是「装着但起不来」被说成「一切正常」。
        install(8090);
        probe.state = ServiceProbe.State.DOWN;
        Result r = status();
        assertTrue(r.text().contains("已安装"),
                "Run 键里确实有它，这句仍然要留着（是事实）：" + r.text());
        assertTrue(行(r.text(), "服务检查").contains("不可用"),
                "但「服务检查」那一行必须写不可用 —— 两句都要在，才没说假话：" + r.text());
    }

    // ---------------------------------------------------------------
    // Windows 特有：Run 键没有自愈
    // ---------------------------------------------------------------

    @Test
    @DisplayName("服务不可用时必须说明「Run 键不会自己回来」（Windows 没有 KeepAlive）")
    void statusSaysRunKeyHasNoSelfHealing() {
        // macOS 侧 launchd 会按 KeepAlive 自动重试，Windows 侧**不会**。
        // 不说这一句，用户会以为「等一会儿就好了」—— 那是不会发生的。
        install(8090);
        probe.state = ServiceProbe.State.DOWN;
        Result r = status();
        String 注意 = 行(r.text(), "注意");
        assertFalse(注意.isEmpty(), "不可用时必须有一句「注意」说明：" + r.text());
        assertTrue(注意.contains("不会自己回来"),
                "必须明说「不会自己回来」（Run 键只在登录时触发一次）：" + r.text());
    }

    @Test
    @DisplayName("服务不可用时给出可执行的手工启动命令（Run 键不会自愈，用户得自己救）")
    void statusGivesManualStartCommand() {
        install(8090);
        probe.state = ServiceProbe.State.DOWN;
        Result r = status();
        String 手工 = 行(r.text(), "手工启动");
        assertFalse(手工.isEmpty(), "必须给出手工启动命令：" + r.text());
        assertTrue(手工.contains("LightMeter.exe"),
                "命令里要带上真正的可执行文件路径：" + r.text());
        assertTrue(手工.contains("serve --no-browser --port 8090"),
                "命令要和当初写进 Run 键的是同一条（同一个端口）：" + r.text());
    }

    @Test
    @DisplayName("服务不可用时要告诉用户怎么自己核对")
    void statusGivesManualCheckCommand() {
        install(8090);
        probe.state = ServiceProbe.State.DOWN;
        Result r = status();
        assertTrue(r.text().contains("/api/health"),
                "要告诉用户怎么自己核对：" + r.text());
        assertTrue(r.text().contains("8090"), "要说出是哪个端口：" + r.text());
    }

    // ---------------------------------------------------------------
    // 前提不成立时：必须显式说「不知道」，不许猜
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Run 键里读不出端口时：明说「不知道」，不许猜一个默认端口去探")
    void statusSaysUnknownWhenPortCannotBeRead() {
        install(8090);
        // 模拟「Run 键里的值被手工改过 / 形态不认识」。
        reg.registry.put(LABEL, "\"C:\\Program Files\\LightMeter\\LightMeter.exe\" serve --no-browser");
        Result r = status();
        assertTrue(r.text().contains("跳过") || r.text().contains("未知"),
                "读不出端口就该说不知道：" + r.text());
        assertTrue(probe.probedPorts.isEmpty(),
                "读不出端口时**不许去探某个端口**（探了就是在编造事实）：" + probe.probedPorts);
        assertEquals(3, r.code(), "「不知道服务在不在」不能报成功：" + r.text());
    }

    @Test
    @DisplayName("Run 键里没有 --port 但有别的端口参数时，也不能瞎探")
    void statusDoesNotProbeWhenOnlyOtherFlagsExist() {
        install(8090);
        reg.registry.put(LABEL, "\"C:\\x\\LightMeter.exe\" serve --no-browser --verbose");
        Result r = status();
        assertTrue(probe.probedPorts.isEmpty(),
                "没有 --port 就不许探（不许拿别的端口顶上）：" + probe.probedPorts);
        assertEquals(3, r.code(), r.text());
    }

    @Test
    @DisplayName("装了但 Run 键内容读不出来时：说读不出、退出码非 0，且不探任何端口")
    void statusSaysUnknownWhenStoredValueUnreadable() {
        install(8090);
        reg.unreadableOutput = true;
        Result r = status();
        assertTrue(r.text().contains("读不出来"),
                "「装了但读不出内容」与「没装」是两回事，要分得清：" + r.text());
        assertTrue(probe.probedPorts.isEmpty(),
                "读不出内容就不许探（那是在编造事实）：" + probe.probedPorts);
        assertEquals(3, r.code(), "「不知道服务在不在」不能报成功：" + r.text());
    }

    @Test
    @DisplayName("没装时：说没装、退出码 0，且不探任何端口（连「服务检查」这一行都不该有）")
    void statusDoesNotProbeWhenNotInstalled() {
        Result r = status();
        assertEquals(0, r.code(),
                "没装是**正常状态**不是故障，退出码应是 0：" + r.text());
        assertTrue(r.text().contains("未安装"), r.text());
        assertTrue(r.text().contains("autostart install"), r.text());
        assertTrue(probe.probedPorts.isEmpty(),
                "没装时没什么可探的，不该去探：" + probe.probedPorts);
        assertTrue(行(r.text(), "服务检查").isEmpty(),
                "没装时不该出现「服务检查」那一行（没服务可查）：" + r.text());
    }

    @Test
    @DisplayName("连注册表都查不了时：退出码 1，且不能当成「没装」")
    void statusFailsClosedWhenRegistryCannotBeQueried() {
        // reg.exe 跑不起来 —— 与「值不存在」是两回事。
        reg.regMissing = true;
        Result r = status();
        assertEquals(1, r.code(),
                "「连装没装都不知道」是第三档（比「服务不可用」更基本）：" + r.text());
        assertFalse(r.text().contains("未安装"), "不能把「查不了」说成「没装」：" + r.text());
        assertFalse(r.text().contains("已安装"), "不能把「查不了」说成「已安装」：" + r.text());
        assertTrue(probe.probedPorts.isEmpty(),
                "查不了注册表就不该去探：" + probe.probedPorts);
    }

    // ---------------------------------------------------------------
    // 解析端口这个纯函数本身
    // ---------------------------------------------------------------

    @Test
    @DisplayName("从 Run 键的命令行解析端口：读得到、读不到、越界都分得清")
    void parsePortDistinguishesReadableFromNot() {
        assertEquals(8090, WindowsRunKey.parsePort(
                        "\"C:\\Program Files\\LightMeter\\LightMeter.exe\" serve --no-browser --port 8090"),
                "正常形态要读得出：");
        assertEquals(-1, WindowsRunKey.parsePort(null), "null 就是读不到：");
        assertEquals(-1, WindowsRunKey.parsePort(""), "空串就是读不到：");
        assertEquals(-1, WindowsRunKey.parsePort("\"C:\\x\\a.exe\" serve --no-browser"),
                "没有 --port 就是读不到：");
        assertEquals(-1, WindowsRunKey.parsePort("\"C:\\x\\a.exe\" serve --port"),
                "--port 后面没有值就是读不到：");
        assertEquals(-1, WindowsRunKey.parsePort("\"C:\\x\\a.exe\" serve --port8090"),
                "--port8090 不是端口参数（后面必须跟值）：");
        assertEquals(-1, WindowsRunKey.parsePort("\"C:\\x\\a.exe\" serve --port 8090abc"),
                "不是纯数字就是读不到：");
        assertEquals(-1, WindowsRunKey.parsePort("\"C:\\x\\a.exe\" serve --port 0"),
                "0 不是合法端口：");
        assertEquals(-1, WindowsRunKey.parsePort("\"C:\\x\\a.exe\" serve --port 65536"),
                "越界的端口就是读不到：");
        assertEquals(65535, WindowsRunKey.parsePort("\"C:\\x\\a.exe\" serve --port 65535"),
                "上限本身是合法的：");
        assertEquals(1, WindowsRunKey.parsePort("\"C:\\x\\a.exe\" serve --port 1"),
                "下限本身是合法的：");
    }

    @Test
    @DisplayName("引号里的路径即使含「--port」也不算数（那是路径，不是参数）")
    void parsePortIgnoresPortInsideQuotedPath() {
        assertEquals(-1, WindowsRunKey.parsePort(
                        "\"C:\\Users\\my --port 9999\\LightMeter.exe\" serve --no-browser"),
                "引号段里的 --port 是路径的一部分，不能当端口：");
        assertEquals(8090, WindowsRunKey.parsePort(
                        "\"C:\\Users\\my --port 9999\\LightMeter.exe\" serve --port 8090"),
                "引号段之外的 --port 才是参数：");
    }

    // ---------------------------------------------------------------
    // 取可执行文件路径（给「手工启动」那条命令用）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("取可执行文件：认引号里的那段，形态不认识就返回 null（不猜）")
    void parseExecutableOnlyAcceptsQuotedForm() {
        assertEquals("C:\\Program Files\\LightMeter\\LightMeter.exe",
                WindowsRunKey.parseExecutable(
                        "\"C:\\Program Files\\LightMeter\\LightMeter.exe\" serve --no-browser --port 8090"),
                "引号里的第一段就是可执行文件路径：");
        assertNull(WindowsRunKey.parseExecutable(
                        "C:\\Program Files\\LightMeter\\LightMeter.exe serve --port 8090"),
                "没有引号就是形态不认识 —— 猜出来的路径可能根本不存在：");
        assertNull(WindowsRunKey.parseExecutable(
                        "\"C:\\Program Files\\LightMeter\\LightMeter.exe serve --port 8090"),
                "引号没闭合就是形态不认识：");
        assertNull(WindowsRunKey.parseExecutable("\"\" serve --port 8090"),
                "空引号就是形态不认识：");
        assertNull(WindowsRunKey.parseExecutable(null), "null 就是形态不认识：");
    }
}
