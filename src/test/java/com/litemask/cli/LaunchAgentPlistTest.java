package com.litemask.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LaunchAgentPlist}：生成的 plist 逐字段对不对。
 *
 * <p><b>为什么用 XML 解析器读回来再断言</b>：plist 的正确性不取决于文本长什么样，
 * 而取决于 <b>launchd 读它时看到什么</b>。
 * 直接断言字符串容易漏掉「拼错了一个标签名」这类问题 ——
 * 而那种错误的现场表现是「装上了，但 launchd 什么都不说也不做」，极难查。
 * 所以这里解析 XML、按<b>结构</b>取值。
 *
 * <p><b>为什么连布尔值也要按结构取</b>（2026-10-21 块 17 实测）：
 * 原来断言「RunAtLoad 那一行往后 120 个字符里有没有 {@code <true/>}」。
 * 而 plist 里紧跟着的 {@code KeepAlive → Crashed} 里也有一个 {@code <true/>}，
 * 落在那个窗口里 → 把 RunAtLoad 改成 {@code <false/>}，这条断言**照样绿**。
 * 实测：对照实验里「RunAtLoad 改成 false」这条变异**没被咬住**（判 GREEN），
 * 也就是说这条断言当时是<b>恒真</b>的。
 * → 现在改成「取到该 key 后面那个元素的<b>标签名</b>」：
 * {@code true / false / dict / array / string / integer}，一个字都不含糊。
 *
 * <p><b>标尺独立</b>：期望值在这里<b>手写</b>，
 * 不用实现里的常量拼出来 —— 否则改坏了常量，期望值跟着坏，测试照样绿。
 */
class LaunchAgentPlistTest {

    /** 解析 plist 文本。 */
    private static org.w3c.dom.Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** 下一个元素兄弟（跳过文本节点与注释）。 */
    private static org.w3c.dom.Element nextElement(org.w3c.dom.Node node) {
        org.w3c.dom.Node sib = node.getNextSibling();
        while (sib != null && sib.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
            sib = sib.getNextSibling();
        }
        return sib == null ? null : (org.w3c.dom.Element) sib;
    }

    /** 从 plist 文本里按 key 取字符串值。 */
    private static String stringOf(String xml, String key) throws Exception {
        var doc = parse(xml);
        var nodes = doc.getElementsByTagName("key");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (key.equals(nodes.item(i).getTextContent())) {
                var sib = nextElement(nodes.item(i));
                return sib == null ? null : sib.getTextContent();
            }
        }
        return null;
    }

    /**
     * 从 plist 文本里按 key 取值的<b>元素名</b>。
     *
     * @return {@code true} / {@code false} / {@code dict} / {@code array} / {@code string} / {@code integer}；
     *         没有这个 key 时返回 null
     */
    private static String valueTagOf(String xml, String key) throws Exception {
        var doc = parse(xml);
        var nodes = doc.getElementsByTagName("key");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (key.equals(nodes.item(i).getTextContent())) {
                var sib = nextElement(nodes.item(i));
                return sib == null ? null : sib.getTagName();
            }
        }
        return null;
    }

    /** 从 plist 文本里按 key 取整个数组的字符串列表。 */
    private static List<String> arrayOf(String xml, String key) throws Exception {
        var doc = parse(xml);
        var nodes = doc.getElementsByTagName("key");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (key.equals(nodes.item(i).getTextContent())) {
                var sib = nextElement(nodes.item(i));
                if (sib == null) {
                    return null;
                }
                var items = ((org.w3c.dom.Element) sib).getElementsByTagName("string");
                List<String> out = new ArrayList<>();
                for (int j = 0; j < items.getLength(); j++) {
                    out.add(items.item(j).getTextContent());
                }
                return out;
            }
        }
        return null;
    }

    private static String render(Path exe, int port, Path outLog, Path errLog, Path workDir) {
        return LaunchAgentPlist.render(new LaunchAgentPlist.Spec(
                "com.litemask.test", exe, port, outLog, errLog, workDir));
    }

    @Test
    @DisplayName("plist 是合法 XML（launchd 读得进去）")
    void plistIsWellFormedXml(@TempDir Path dir) throws Exception {
        String xml = render(dir.resolve("LightMeter"), 8090,
                dir.resolve("out.log"), dir.resolve("err.log"), dir);
        // 能解析出来 = 结构合法；解析不出来 launchd 会静默不做事。
        var doc = parse(xml);
        assertEquals("plist", doc.getDocumentElement().getTagName());
        assertEquals("1.0", doc.getDocumentElement().getAttribute("version"));
    }

    @Test
    @DisplayName("ProgramArguments 恰好是 [可执行文件, serve, --no-browser, --port, 端口]")
    void programArgumentsAreExact(@TempDir Path dir) throws Exception {
        Path exe = dir.resolve("LightMeter");
        String xml = render(exe, 8123,
                dir.resolve("out.log"), dir.resolve("err.log"), dir);
        List<String> expected = List.of(exe.toString(), "serve", "--no-browser", "--port", "8123");
        assertEquals(expected, arrayOf(xml, "ProgramArguments"));
    }

    @Test
    @DisplayName("RunAtLoad 为真 —— 开机要真起")
    void runAtLoadIsTrue(@TempDir Path dir) throws Exception {
        String xml = render(dir.resolve("LightMeter"), 8090,
                dir.resolve("out.log"), dir.resolve("err.log"), dir);
        // **按结构取值**：不能写成「附近 120 个字符里有没有 <true/>」——
        // 紧跟着的 KeepAlive → Crashed 里也有 <true/>，那个窗口会把它算进来，
        // 于是把 RunAtLoad 改成 false 这条断言照样绿（实测：CE 判它 GREEN，没咬住）。
        assertEquals("true", valueTagOf(xml, "RunAtLoad"),
                "RunAtLoad 自己的值必须是 true（不是看它附近有没有别的 true）");
    }

    @Test
    @DisplayName("KeepAlive 是无条件 true —— 崩了要自愈（实测 {Crashed:true} 在本机从不重启）")
    void keepAliveAlwaysRestarts(@TempDir Path dir) throws Exception {
        String xml = render(dir.resolve("LightMeter"), 8090,
                dir.resolve("out.log"), dir.resolve("err.log"), dir);
        // 实测矩阵（本机 macOS 27.2.0，2026-10-21）：
        //   {Crashed: true}  → SIGKILL / exit0 / exit1 一律**不重启**（与手册相反）
        //   {Crashed: false} → 一律重启
        //   true             → 一律重启
        // 要的是「崩了自愈」，唯一稳妥的写法是无条件 true。
        // 注意这里断言的是**元素名**（true/false/dict），
        // 不能写成「附近有没有 <true/>」——KeepAlive 之后紧跟着的 ThrottleInterval 等项
        // 里也有 <true/>，那种写法会漏判（实测：RunAtLoad 那条就是这么漏判过）。
        assertEquals("true", valueTagOf(xml, "KeepAlive"),
                "KeepAlive 必须是无条件 true：实测 {Crashed:true} 在本机从不重启，崩了就永久下线");
    }

    @Test
    @DisplayName("重试有频率上限：ThrottleInterval=10（没有它就是每几秒启一次 JVM）")
    void retryIntervalIsBounded(@TempDir Path dir) throws Exception {
        String xml = render(dir.resolve("LightMeter"), 8090,
                dir.resolve("out.log"), dir.resolve("err.log"), dir);
        // 端口被占时服务会正常退出、launchd 会再拉它 —— 这是要的（端口一腾出来就自动就位），
        // 但必须有频率上限，否则是无限重试。
        assertEquals("10", stringOf(xml, "ThrottleInterval"),
                "ThrottleInterval 必须是 10 秒（重试频率上限）");
    }

    @Test
    @DisplayName("Label 与日志路径都写进去了")
    void labelAndLogsAreSet(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("serve.out.log");
        Path err = dir.resolve("serve.err.log");
        String xml = render(dir.resolve("LightMeter"), 8090, out, err, dir);
        assertEquals("com.litemask.test", stringOf(xml, "Label"));
        assertEquals(out.toString(), stringOf(xml, "StandardOutPath"));
        assertEquals(err.toString(), stringOf(xml, "StandardErrorPath"));
        assertEquals(dir.toString(), stringOf(xml, "WorkingDirectory"));
    }

    @Test
    @DisplayName("路径里的 & < > 被转义（不转义就是坏 plist，而坏 plist 静默不做事）")
    void pathsAreXmlEscaped(@TempDir Path dir) throws Exception {
        // 【2026-10-25 真 Windows 实测踩到的】原来这里用 `a&b<c>d` 当路径。
        //   Windows 的文件名**不允许**出现 < 与 >（InvalidPathException），
        //   而这个测试在 @TempDir 之后立刻 resolve → 在 Windows 上直接抛异常，
        //   断言一条都没跑。
        // → 路径部分改用两个平台都合法的字符（`&` 与 `'`：在 XML 里同样必须转义/或至少要验证）；
        //   `<` `>` 的转义改成**直接断言转义函数** ——
        //   覆盖面一条没少，只是把「验证入口」从「造一个真路径」换成了「直接测函数」。
        Path weird = dir.resolve("a&b'c");
        String xml = render(weird, 8090,
                dir.resolve("o&ut.log"), dir.resolve("e&rr.log"), dir);
        // 原始文本里不能出现未转义的 & （XML 里那是实体起始）
        assertFalse(xml.contains("a&b"), "路径里的 & 必须转义：" + xml);
        assertTrue(xml.contains("a&amp;b"), "转义形态不对：" + xml);
        // < 与 > 的转义：直接测转义函数（Windows 上造不出含它们的文件名）
        assertEquals("a&amp;b&lt;c&gt;d", LaunchAgentPlist.esc("a&b<c>d"),
                "转义函数必须把 & < > 三个都转义掉");
        // 解析回来必须还是原来那个路径
        assertEquals(weird.toString(), arrayOf(xml, "ProgramArguments").get(0));
    }

    @Test
    @DisplayName("默认端口是 8090（与端口占用报错文案里建议的一致）")
    void defaultPortMatchesTheAdviceInErrorMessage() {
        assertEquals(8090, LaunchAgentPlist.DEFAULT_PORT);
    }

    @Test
    @DisplayName("默认 label 是 com.litemask.lightmeter")
    void defaultLabelIsStable() {
        assertEquals("com.litemask.lightmeter", LaunchAgentPlist.DEFAULT_LABEL);
    }
}
