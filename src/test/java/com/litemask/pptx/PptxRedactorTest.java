package com.litemask.pptx;

import com.litemask.core.RedactionEngine;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pptx 端到端脱敏。
 *
 * <p>重点是图表：pptx 的图表把数据内嵌在文件里，打开时<b>不会</b>重算，
 * 所以缓存值就是屏幕上显示的那一份，漏掉它等于把身份证留在坐标轴标签上。
 */
class PptxRedactorTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");
    private static final String ID_A = "11010519491231002X";
    private static final String ID_B = "310115198505201238";
    private static final String CARD = "62220212345678903";

    /**
     * 16 位数字：<b>单独看匹配不上任何规则</b>（Luhn 不过；VIN 要 17 位且必须含字母），
     * 但与一个字母拼起来就是一个形态合法的 VIN——
     * 这正是「跨部件续接」会制造的假标识符。
     */
    private static final String FRAGMENT = "6222021234567890";

    @TempDir
    Path dir;

    private Path redactTo(PptxFixture fixture, String name) throws IOException {
        Path in = fixture.writeTo(dir, name);
        Path out = dir.resolve(name.replace(".pptx", "-脱敏.pptx"));
        PptxRedactor.redact(in, out, RULES);
        return out;
    }

    @Test
    @DisplayName("幻灯片正文命中")
    void slideText() throws IOException {
        Path out = redactTo(new PptxFixture().slide(
                PptxFixture.shape(PptxFixture.paragraph("原告身份证号：" + ID_A))), "slide.pptx");
        String text = DocxFixtureText.of(out, "ppt/slides/slide1.xml");
        assertFalse(text.contains(ID_A), "未脱敏：" + text);
        assertTrue(text.contains("原告身份证号："), text);
    }

    /**
     * 身份证被<b>相邻两段</b>切断。
     *
     * <p>与 docx 的段落是同一个道理：切断后两段碎片各自都匹配不上任何规则，
     * 工具会报告「脱敏成功」而碎片原样留在幻灯片里。
     */
    @Test
    @DisplayName("身份证被相邻段落切断：两段碎片都被脱敏，类型是身份证")
    void idCardSplitAcrossParagraphs() throws IOException {
        // 每段两个 run：这样「全文按段落分段」与「全文按 run 分段」才是两种不同的结构，
        // 段落粒度一旦退化成 run 粒度，本用例立刻红（顺序对不上 → 续接被关掉 → 碎片留下）。
        Path out = redactTo(new PptxFixture().slide(
                PptxFixture.shape(PptxFixture.paragraph("身份证", "110105号")
                        + PptxFixture.paragraph("续", "19491231002X尾"))), "crosspara.pptx");
        String text = DocxFixtureText.of(out, "ppt/slides/slide1.xml");
        assertFalse(text.contains("110105"), "第一段碎片还在：" + text);
        assertFalse(text.contains("19491231002"), "第二段碎片还在：" + text);
        assertFalse(text.contains("<手机号"), "碎片仍被错标成手机号：" + text);
        assertTrue(text.contains("<身份证1>") && text.contains("<身份证2>"), "应当有两个身份证令牌：" + text);
    }

    /**
     * 单元文本<b>自带换行符</b>时，续接也必须生效。
     *
     * <p>旧写法由「全文按 {@code '\n'} 切」得出单元序列：一个自带换行的单元会被切成两段，
     * 于是单元序列与实际喂进去的文本对不上 → 续接被<b>静默关掉</b> →
     * 整篇演示文稿的碎片都漏（110105 残留、19491231002X 被错标成手机号）。
     * 与 docx / xlsx 的两条回归同源。
     */
    @Test
    @DisplayName("单元自带换行符：续接仍生效，碎片不漏")
    void continuationSurvivesNewlineInsideUnit() throws IOException {
        Path out = redactTo(new PptxFixture().slide(
                PptxFixture.shape(PptxFixture.paragraph("备注\n身份证110105号")
                        + PptxFixture.paragraph("续19491231002X尾"))), "newline.pptx");
        String text = DocxFixtureText.of(out, "ppt/slides/slide1.xml");
        assertFalse(text.contains("110105"), "第一段碎片还在：" + text);
        assertFalse(text.contains("19491231002"), "第二段碎片还在：" + text);
        assertFalse(text.contains("<手机号"), "碎片仍被错标成手机号：" + text);
        assertTrue(text.contains("<身份证1>"), "第一段应当是身份证令牌：" + text);
        assertTrue(text.contains("<身份证2>"), "第二段应当是身份证令牌：" + text);
    }

    /**
     * 跨 zip 部件<b>不得</b>续接。
     *
     * <p>幻灯片正文与备注页是两种容器：它们在 zip 里挨着，在语义上却是两件事。
     * 合成一组（块 13 的行为）时它们会被拼成一个<b>形态合法</b>的 VIN：
     * 幻灯片末尾的 16 位数字 + 备注开头的字母 X = {@code 6222021234567890X}。
     * 于是两段都被按车架号脱敏——<b>误判 + 标签错</b>。
     *
     * <p>所以每组 = 一个 zip 部件：只有<b>同一个部件</b>里的相邻段落才能续接。
     *
     * <p><b>两种部件顺序都要试</b>：顺序不同，拼接方向就不同，两种都能拼出 VIN 形态——
     * 只试一种顺序的话，另一种顺序下这条用例会<b>空转</b>。
     */
    @Test
    @DisplayName("幻灯片与备注页分属不同部件：不得跨部件续接（两种部件顺序都试）")
    void noContinuationAcrossParts() throws IOException {
        String slideShapes = PptxFixture.shape(PptxFixture.paragraph("编号" + FRAGMENT));
        String notesShapes = PptxFixture.shape(PptxFixture.paragraph("X是当事人"));
        for (boolean slideFirst : new boolean[]{true, false}) {
            PptxFixture fixture = slideFirst
                    ? new PptxFixture().slide(slideShapes).notes(notesShapes)
                    : new PptxFixture().notes(notesShapes).slide(slideShapes);
            Path in = fixture.writeTo(dir, "parts-" + slideFirst + ".pptx");
            Path out = dir.resolve("parts-" + slideFirst + "-脱敏.pptx");
            PptxRedactor.Report report = PptxRedactor.redact(in, out, RULES);

            String slide = DocxFixtureText.of(out, "ppt/slides/slide1.xml");
            String notes = DocxFixtureText.of(out, "ppt/notesSlides/notesSlide1.xml");
            assertTrue(slide.contains(FRAGMENT),
                    "幻灯片里的编号被跨部件续接误当成车架号碎片替换了（幻灯片在前=" + slideFirst + "）：" + slide);
            assertTrue(notes.contains("X是当事人"),
                    "备注里的 X 被跨部件续接误替换了（幻灯片在前=" + slideFirst + "）：" + notes);
            assertFalse(slide.contains("<车架号"), "幻灯片里冒出了车架号令牌：" + slide);
            assertFalse(notes.contains("<车架号"), "备注里冒出了车架号令牌：" + notes);
            assertEquals(0, report.hits().size(),
                    "这两段各自都匹配不上任何规则，不该有命中（幻灯片在前=" + slideFirst + "）：" + report.hits());
        }
    }

    @Test
    @DisplayName("实体横跨多个 run：仍然命中（PPT 同样会拆 run）")
    void splitAcrossRuns() throws IOException {
        Path out = redactTo(new PptxFixture().slide(PptxFixture.shape(
                PptxFixture.paragraph("号码：110105", "19491231", "002X"))), "split.pptx");
        assertEquals("号码：<身份证1>", DocxFixtureText.of(out, "ppt/slides/slide1.xml"));
    }

    @Test
    @DisplayName("同段多处命中：两个身份证分别占位")
    void multipleInOneParagraph() throws IOException {
        Path out = redactTo(new PptxFixture().slide(PptxFixture.shape(
                PptxFixture.paragraph("甲 " + ID_A + " 乙 " + ID_B))), "multi.pptx");
        assertEquals("甲 <身份证1> 乙 <身份证2>", DocxFixtureText.of(out, "ppt/slides/slide1.xml"));
    }

    @Test
    @DisplayName("备注页被脱敏（放映时看不见，文件里却真实存在）")
    void notesSlide() throws IOException {
        Path out = redactTo(new PptxFixture()
                .slide(PptxFixture.shape(PptxFixture.paragraph("正文")))
                .notes(PptxFixture.shape(PptxFixture.paragraph("备注电话 " + CARD))), "notes.pptx");
        assertFalse(DocxFixtureText.of(out, "ppt/notesSlides/notesSlide1.xml").contains(CARD), "备注未脱敏");
    }

    @Test
    @DisplayName("图表字符串缓存被脱敏")
    void chartStringCache() throws IOException {
        Path out = redactTo(new PptxFixture()
                .slide(PptxFixture.shape(PptxFixture.paragraph("标题")))
                .chart("类别" + CARD, "1"), "chartstr.pptx");
        assertFalse(PptxFixture.allText(out).contains(CARD), "图表字符串缓存未脱敏");
    }

    @Test
    @DisplayName("图表数值缓存被脱敏并单独计数（PowerPoint 会提示需刷新数据）")
    void chartNumericCache() throws IOException {
        Path in = new PptxFixture()
                .slide(PptxFixture.shape(PptxFixture.paragraph("标题")))
                .chart("普通类别", ID_B)
                .writeTo(dir, "chartnum.pptx");
        Path out = dir.resolve("chartnum-脱敏.pptx");
        PptxRedactor.Report report = PptxRedactor.redact(in, out, RULES);
        assertEquals(1, report.numericChartValuesRedacted(), "应报告 1 个图表数值缓存被改");
        assertFalse(PptxFixture.allText(out).contains(ID_B), "图表数值缓存未脱敏");
        assertTrue(PptxFixture.allText(out).contains("普通类别"), "无关类别被破坏");
    }

    @Test
    @DisplayName("文档属性被清空")
    void metadataCleared() throws IOException {
        Path in = new PptxFixture().slide(PptxFixture.shape(PptxFixture.paragraph("正文")))
                .part("docProps/core.xml",
                        "<?xml version=\"1.0\"?>"
                                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                                + "<dc:creator>张伟</dc:creator><dc:title>某某案</dc:title></cp:coreProperties>")
                .writeTo(dir, "meta.pptx");
        Path out = dir.resolve("meta-脱敏.pptx");
        PptxRedactor.Report report = PptxRedactor.redact(in, out, RULES);
        assertFalse(DocxFixtureText.of(out, "docProps/core.xml").contains("张伟"), "作者未清空");
        assertFalse(DocxFixtureText.of(out, "docProps/core.xml").contains("某某案"), "标题未清空");
        assertTrue(report.metadataFieldsCleared() >= 2);
    }

    @Test
    @DisplayName("首尾空格保留")
    void spacePreserved() throws IOException {
        Path out = redactTo(new PptxFixture().slide(PptxFixture.shape(
                PptxFixture.paragraph(" 身份证 " + ID_A + " "))), "space.pptx");
        assertTrue(DocxFixtureText.of(out, "ppt/slides/slide1.xml").contains(" 身份证 "), "空格丢失");
        assertTrue(PptxFixture.textPart(out, "ppt/slides/slide1.xml").contains("xml:space=\"preserve\""));
    }

    @Test
    @DisplayName("还原可逆：脱敏件按映射还原后与原文一致")
    void roundTrip() throws IOException {
        String original = "身份证：" + ID_A + "，账号 " + CARD;
        Path in = new PptxFixture().slide(PptxFixture.shape(
                PptxFixture.paragraph("身份证：", ID_A, "，账号 " + CARD)))
                .writeTo(dir, "rt.pptx");
        Path out = dir.resolve("rt-脱敏.pptx");
        Map<String, String> mapping = PptxRedactor.redact(in, out, RULES).mapping();
        assertEquals(2, mapping.size(), "两条映射，实得 " + mapping);

        Path back = dir.resolve("rt-还原.pptx");
        PptxRedactor.restore(out, back, mapping);
        assertEquals(original, DocxFixtureText.of(back, "ppt/slides/slide1.xml"));
    }

    @Test
    @DisplayName("跨幻灯片同值同符")
    void sameTokenAcrossSlides() throws IOException {
        PptxFixture f = new PptxFixture().slide(PptxFixture.shape(PptxFixture.paragraph("甲 " + ID_A)))
                .part("ppt/slides/slide2.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                                + " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                                + "<p:cSld><p:spTree>" + PptxFixture.shape(PptxFixture.paragraph("乙 " + ID_A))
                                + "</p:spTree></p:cSld></p:sld>");
        Path out = redactTo(f, "two.pptx");
        String text = PptxFixture.allText(out);
        assertEquals(2, count(text, "<身份证1>"), "两处同值应复用同一令牌");
        assertFalse(text.contains("<身份证2>"), text);
    }

    @Test
    @DisplayName("未改动的二进制 part 逐字节不变")
    void untouchedBinaryUnchanged() throws IOException {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Path in = new PptxFixture().slide(PptxFixture.shape(PptxFixture.paragraph("无敏感信息")))
                .binary("ppt/media/image1.png", png)
                .writeTo(dir, "bin.pptx");
        Path out = dir.resolve("bin-脱敏.pptx");
        PptxRedactor.redact(in, out, RULES);
        assertArrayEquals(png, PptxFixture.rawPart(out, "ppt/media/image1.png"));
    }

    @Test
    @DisplayName("报告能定位到具体 part")
    void reportLocatesHits() throws IOException {
        Path in = new PptxFixture().slide(PptxFixture.shape(PptxFixture.paragraph("电话 " + CARD)))
                .writeTo(dir, "rep.pptx");
        Path out = dir.resolve("rep-脱敏.pptx");
        PptxRedactor.Report report = PptxRedactor.redact(in, out, RULES);
        assertEquals(1, report.hits().size());
        assertEquals("bank_card", report.hits().get(0).ruleKey());
        assertTrue(report.hits().get(0).part().startsWith("ppt/slides/"));
    }

    @Test
    @DisplayName("fail-closed：非 zip 文件报错且不留输出")
    void rejectsNonZip() throws IOException {
        Path fake = dir.resolve("fake.pptx");
        Files.writeString(fake, "这不是 pptx");
        Path out = dir.resolve("fake-脱敏.pptx");
        assertThrows(IOException.class, () -> PptxRedactor.redact(fake, out, RULES));
        assertFalse(Files.exists(out));
    }

    @Test
    @DisplayName("fail-closed：XML 损坏时报错而不是原样放行")
    void rejectsMalformedXml() throws IOException {
        Path in = new PptxFixture().part("ppt/slides/slide1.xml", "<p:sld><p:cSld>未闭合")
                .writeTo(dir, "broken.pptx");
        assertThrows(IOException.class,
                () -> PptxRedactor.redact(in, dir.resolve("broken-脱敏.pptx"), RULES));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** 极简读取器：取出 part 里所有 a:t 文本并反转义。 */
    static final class DocxFixtureText {
        static String of(Path pptx, String part) throws IOException {
            String xml = PptxFixture.textPart(pptx, part);
            StringBuilder sb = new StringBuilder();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<a:t(?:\\s[^>]*)?>(.*?)</a:t>", java.util.regex.Pattern.DOTALL).matcher(xml);
            while (m.find()) {
                sb.append(PptxFixture.unescape(m.group(1)));
            }
            return sb.toString();
        }
    }
}
