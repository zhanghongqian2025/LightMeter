package com.litemask.docx;

import com.litemask.core.RedactionEngine;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * docx 端到端脱敏。
 *
 * <p>覆盖的是"会静默漏脱"的位置：表格、页眉页脚、文本框、批注、修订留痕。
 * 这些地方逐段处理时很容易想当然地跳过，而跳过之后工具依然报告成功。
 */
class DocxRedactorTest {

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

    private Path redactTo(DocxFixture fixture, String name) throws IOException {
        Path in = fixture.writeTo(dir, name);
        Path out = dir.resolve(name.replace(".docx", "-脱敏.docx"));
        DocxRedactor.redact(in, out, RULES);
        return out;
    }

    @Test
    @DisplayName("正文命中：原值消失，占位符出现")
    void bodyText() throws IOException {
        Path out = redactTo(DocxFixture.simple(
                DocxFixture.paragraph(DocxFixture.run(null, "原告身份证号：" + ID_A))), "a.docx");
        String text = DocxFixture.text(out, "word/document.xml");
        assertFalse(text.contains(ID_A), "原身份证仍在文档中：" + text);
        assertTrue(text.contains("<身份证1>"), "未生成占位符：" + text);
    }

    @Test
    @DisplayName("实体横跨三个 run：仍然命中（逐槽处理在这里会完全漏掉）")
    void entitySplitAcrossRuns() throws IOException {
        Path out = redactTo(DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "公民身份号码：110105")
                        + DocxFixture.run(null, "19491231")
                        + DocxFixture.run(null, "002X"))), "split.docx");
        String text = DocxFixture.text(out, "word/document.xml");
        assertFalse(text.contains("110105") || text.contains("002X"), "跨 run 的身份证未被完整替换：" + text);
        assertEquals("公民身份号码：<身份证1>", text);
    }

    /**
     * 身份证被<b>段落</b>切断（不是 run，是段落）。
     *
     * <p>这是最严重的一种漏脱：切断后两段碎片各自都匹配不上任何规则，
     * 工具报告「0 处命中、脱敏成功」，而整张身份证原样留在交付物里。
     * 断言必须盯住三件事：碎片一个字符都不许剩、类型必须对、还原必须逐字一致。
     */
    @Test
    @DisplayName("身份证被段落切断：两段碎片都被脱敏，类型是身份证，还原逐字一致")
    void idCardSplitAcrossParagraphs() throws IOException {
        Path in = DocxFixture.simple(
                DocxFixture.paragraph(DocxFixture.run(null, "身份证110105号"))
                        + DocxFixture.paragraph(DocxFixture.run(null, "续19491231002X尾")))
                .writeTo(dir, "crosspara.docx");
        Path out = dir.resolve("crosspara-脱敏.docx");
        DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES);

        String text = DocxFixture.text(out, "word/document.xml");
        assertFalse(text.contains("110105"), "第一段碎片还在文档里：" + text);
        assertFalse(text.contains("19491231002"), "第二段碎片还在文档里：" + text);
        assertFalse(text.contains("<手机号"), "碎片仍被错标成手机号：" + text);
        assertTrue(text.contains("<身份证1>"), "第一段应当是身份证令牌：" + text);
        assertTrue(text.contains("<身份证2>"), "第二段应当是身份证令牌：" + text);
        assertEquals(2, report.hits().size(), "应当两处命中，实际：" + report.hits());
        for (DocxRedactor.Hit h : report.hits()) {
            assertEquals("id_card", h.ruleKey(), "命中类型不对：" + h);
        }

        Path back = dir.resolve("crosspara-还原.docx");
        DocxRedactor.restore(out, back, report.mapping());
        assertEquals(DocxFixture.text(in, "word/document.xml"),
                DocxFixture.text(back, "word/document.xml"), "还原后必须与原件逐字一致");
    }

    /**
     * 单元文本里带一个<b>真实的换行符</b>（不是 {@code <w:br/>}）时，续接仍然要生效。
     *
     * <p>Word 自己不会这么写（回车是 {@code <w:br/>}），但不少生成器会把多行文本
     * 直接写进同一个 {@code <w:t>}。而单元序列当时是「全文按 {@code '\n'} 切」得到的——
     * 单元里自带换行符就会被切成<b>两个</b>单元 → 与实际喂进去的文本对不上 →
     * 续接被静默关掉 → <b>整篇文档</b>的跨单元碎片都跟着漏（不是只漏那一段）。
     */
    @Test
    @DisplayName("单元文本里含换行符时，跨单元续接仍然生效")
    void continuationSurvivesNewlineInsideUnit() throws IOException {
        Path in = DocxFixture.simple(
                DocxFixture.paragraph("<w:r><w:t>备注\n身份证110105号</w:t></w:r>")
                        + DocxFixture.paragraph(DocxFixture.run(null, "续19491231002X尾")))
                .writeTo(dir, "newline.docx");
        Path out = dir.resolve("newline-脱敏.docx");
        DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES);

        String text = DocxFixture.text(out, "word/document.xml");
        assertFalse(text.contains("110105"), "第一段碎片还在文档里：" + text);
        assertFalse(text.contains("19491231002"), "第二段碎片还在文档里：" + text);
        assertFalse(text.contains("<手机号"), "碎片仍被错标成手机号：" + text);
        assertTrue(text.contains("<身份证1>"), "第一段应当是身份证令牌：" + text);
        assertTrue(text.contains("<身份证2>"), "第二段应当是身份证令牌：" + text);
        assertEquals(2, report.hits().size(), "应当两处命中，实际：" + report.hits());
    }

    /**
     * 跨 zip 部件<b>不得</b>续接。
     *
     * <p>页眉里的编号与正文第一句在文件里挨着，在语义上却是两件事。
     * 合成一组（块 13 的行为）时它们会被拼成一个<b>形态合法</b>的 VIN：
     * 页眉末尾的 16 位数字 + 正文开头的字母 X = {@code 6222021234567890X}。
     * 于是两段都被按车架号脱敏——<b>误判 + 标签错</b>：用户看到的类型与原文语义无关，
     * 而还原时会把一个订单编号还原成一个身份证号。
     *
     * <p>所以每组 = 一个 zip 部件：只有<b>同一个部件</b>里的相邻段落才能续接。
     *
     * <p><b>两种部件顺序都要试</b>：顺序不同，拼接方向就不同
     * （数字在前是 {@code 6222021234567890X}，字母在前是 {@code X6222021234567890}），
     * 两者都是合法的 VIN 形态——只试一种顺序的话，另一种顺序下这条用例会<b>空转</b>。
     */
    @Test
    @DisplayName("页眉与正文分属不同部件：不得跨部件续接（两种部件顺序都试）")
    void noContinuationAcrossParts() throws IOException {
        String headerXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + DocxFixture.paragraph(DocxFixture.run(null, "编号" + FRAGMENT))
                + "</w:hdr>";
        String bodyXml = DocxFixture.paragraph(DocxFixture.run(null, "X是当事人"));
        for (boolean headerFirst : new boolean[]{true, false}) {
            DocxFixture fixture = headerFirst
                    ? new DocxFixture().part("word/header1.xml", headerXml).document(bodyXml)
                    : new DocxFixture().document(bodyXml).part("word/header1.xml", headerXml);
            Path in = fixture.writeTo(dir, "parts-" + headerFirst + ".docx");
            Path out = dir.resolve("parts-" + headerFirst + "-脱敏.docx");
            DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES);

            String header = DocxFixture.text(out, "word/header1.xml");
            String body = DocxFixture.text(out, "word/document.xml");
            assertTrue(header.contains(FRAGMENT),
                    "页眉里的编号被跨部件续接误当成车架号碎片替换了（页眉在前=" + headerFirst + "）：" + header);
            assertTrue(body.contains("X是当事人"),
                    "正文开头的 X 被跨部件续接误替换了（页眉在前=" + headerFirst + "）：" + body);
            assertFalse(header.contains("<车架号"), "页眉里冒出了车架号令牌：" + header);
            assertFalse(body.contains("<车架号"), "正文里冒出了车架号令牌：" + body);
            assertEquals(0, report.hits().size(),
                    "这两段各自都匹配不上任何规则，不该有命中（页眉在前=" + headerFirst + "）：" + report.hits());
        }
    }

    /**
     * 切成 14 位 + 3 位：两段都匹配不上任何规则，正是整张泄漏的那种切法。
     */
    @Test
    @DisplayName("身份证被切成 14+3：从前 0 处命中（整张泄漏），现在必须两段都脱敏")
    void idCardSplitFourteenPlusThree() throws IOException {
        Path in = DocxFixture.simple(
                DocxFixture.paragraph(DocxFixture.run(null, "身份证11010519491231号"))
                        + DocxFixture.paragraph(DocxFixture.run(null, "续002X尾")))
                .writeTo(dir, "crosspara2.docx");
        Path out = dir.resolve("crosspara2-脱敏.docx");
        DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES);

        String text = DocxFixture.text(out, "word/document.xml");
        assertFalse(text.contains("11010519491231"), "14 位那一段还在文档里：" + text);
        assertFalse(text.contains("002X"), "3 位那一段还在文档里：" + text);
        assertEquals(2, report.hits().size(), "应当两处命中，实际：" + report.hits());
    }

    @Test
    @DisplayName("替换后样式存活：加粗/颜色/字号的 rPr 不受影响")
    void styleSurvivesRedaction() throws IOException {
        String rPr = "<w:b/><w:color w:val=\"FF0000\"/><w:sz w:val=\"36\"/>";
        Path in = DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(rPr, "账户：" + CARD + "，余额 100"))).writeTo(dir, "style.docx");
        Path out = dir.resolve("style-脱敏.docx");
        DocxRedactor.redact(in, out, RULES);
        String xml = DocxFixture.textPart(out, "word/document.xml");
        assertTrue(xml.contains("<w:b/>"), "加粗丢失");
        assertTrue(xml.contains("w:val=\"FF0000\""), "颜色丢失");
        assertTrue(xml.contains("w:val=\"36\""), "字号丢失");
        assertTrue(DocxFixture.text(out, "word/document.xml").contains("余额 100"), "非敏感内容被破坏");
    }

    @Test
    @DisplayName("表格单元格内的身份证被脱敏")
    void tableCell() throws IOException {
        String body = "<w:tbl><w:tr><w:tc><w:p>" + DocxFixture.run(null, "姓名：张伟，证件号 " + ID_A)
                + "</w:p></w:tc></w:tr></w:tbl>";
        Path out = redactTo(DocxFixture.simple(body), "table.docx");
        String text = DocxFixture.text(out, "word/document.xml");
        assertFalse(text.contains(ID_A), "表格内身份证未脱敏：" + text);
        assertTrue(text.contains("姓名：张伟"), "表格内非敏感内容被破坏：" + text);
    }

    @Test
    @DisplayName("页眉页脚被脱敏")
    void headerAndFooter() throws IOException {
        String wrap = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:%s xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "%s</w:%s>";
        Path in = DocxFixture.simple(DocxFixture.paragraph(DocxFixture.run(null, "正文无敏感信息")))
                .part("word/header1.xml", String.format(wrap, "hdr",
                        DocxFixture.paragraph(DocxFixture.run(null, "案号联系人电话 " + CARD)), "hdr"))
                .part("word/footer1.xml", String.format(wrap, "ftr",
                        DocxFixture.paragraph(DocxFixture.run(null, "开户行电话 " + CARD)), "ftr"))
                .writeTo(dir, "hf.docx");
        Path out = dir.resolve("hf-脱敏.docx");
        DocxRedactor.redact(in, out, RULES);
        assertFalse(DocxFixture.text(out, "word/header1.xml").contains(CARD), "页眉未脱敏");
        assertFalse(DocxFixture.text(out, "word/footer1.xml").contains(CARD), "页脚未脱敏");
        assertTrue(DocxFixture.text(out, "word/header1.xml").contains("案号联系人电话"), "页眉其他内容被破坏");
    }

    @Test
    @DisplayName("文本框内的身份证被脱敏（POI 方案最容易漏掉的位置）")
    void textBox() throws IOException {
        String body = DocxFixture.paragraph(DocxFixture.run(null, "正文"))
                + "<w:p><w:r><w:drawing><wps:txbx xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
                + "<w:txbxContent>" + DocxFixture.paragraph(DocxFixture.run(null, "框内身份证 " + ID_A))
                + "</w:txbxContent></wps:txbx></w:drawing></w:r></w:p>";
        Path out = redactTo(DocxFixture.simple(body), "textbox.docx");
        String text = DocxFixture.text(out, "word/document.xml");
        assertFalse(text.contains(ID_A), "文本框内身份证未脱敏：" + text);
        assertTrue(text.contains("框内身份证"), "文本框内其他内容被破坏：" + text);
    }

    @Test
    @DisplayName("批注内容被脱敏")
    void comments() throws IOException {
        Path in = DocxFixture.simple(DocxFixture.paragraph(DocxFixture.run(null, "正文")))
                .part("word/comments.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<w:comments xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                                + "<w:comment w:id=\"1\" w:author=\"张伟\">"
                                + DocxFixture.paragraph(DocxFixture.run(null, "电话 " + CARD))
                                + "</w:comment></w:comments>")
                .writeTo(dir, "comments.docx");
        Path out = dir.resolve("comments-脱敏.docx");
        DocxRedactor.redact(in, out, RULES);
        assertFalse(DocxFixture.text(out, "word/comments.xml").contains(CARD), "批注内容未脱敏");
    }

    @Test
    @DisplayName("修订留痕 delText 被脱敏（正常显示里看不见，正文里仍存在）")
    void trackedDeletionText() throws IOException {
        String body = "<w:p><w:del><w:r><w:delText>" + ID_A + "</w:delText></w:r></w:del>"
                + DocxFixture.run(null, "现文无敏感信息") + "</w:p>";
        Path out = redactTo(DocxFixture.simple(body), "del.docx");
        assertFalse(DocxFixture.text(out, "word/document.xml").contains(ID_A), "修订留痕里的身份证未脱敏");
    }

    @Test
    @DisplayName("文档属性被清空：作者姓名留在属性面板等于没脱")
    void metadataCleared() throws IOException {
        Path in = DocxFixture.simple(DocxFixture.paragraph(DocxFixture.run(null, "正文")))
                .part("docProps/core.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<cp:coreProperties"
                                + " xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                                + "<dc:creator>张伟</dc:creator>"
                                + "<cp:lastModifiedBy>李明</cp:lastModifiedBy>"
                                + "<dc:title>民事起诉状</dc:title>"
                                + "</cp:coreProperties>")
                .part("docProps/app.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                                + "<Company>华宇元典信息技术有限公司</Company><Manager>王五</Manager>"
                                + "</Properties>")
                .writeTo(dir, "meta.docx");
        Path out = dir.resolve("meta-脱敏.docx");
        DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES);
        assertFalse(DocxFixture.textPart(out, "docProps/core.xml").contains("张伟"), "作者未清空");
        assertFalse(DocxFixture.textPart(out, "docProps/core.xml").contains("李明"), "最后修改人未清空");
        assertFalse(DocxFixture.textPart(out, "docProps/app.xml").contains("华宇元典"), "公司名未清空");
        assertTrue(report.metadataFieldsCleared() >= 4, "应至少清空 4 个属性，实得 " + report.metadataFieldsCleared());
    }

    @Test
    @DisplayName("首尾空格保留：丢失 xml:space 会让 Word 重开后内容对不上")
    void spacePreserveAttribute() throws IOException {
        Path in = DocxFixture.simple(DocxFixture.paragraph(
                "<w:r><w:t> 身份证号 " + ID_A + " </w:t></w:r>")).writeTo(dir, "space.docx");
        Path out = dir.resolve("space-脱敏.docx");
        DocxRedactor.redact(in, out, RULES);
        String xml = DocxFixture.textPart(out, "word/document.xml");
        assertTrue(xml.contains("xml:space=\"preserve\""), "缺少 xml:space=preserve：" + xml);
        assertEquals(" 身份证号 <身份证1> ", DocxFixture.text(out, "word/document.xml"));
    }

    @Test
    @DisplayName("二进制 part 逐字节不变，且条目顺序不打乱")
    void untouchedPartsAreCopiedVerbatim() throws IOException {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF};
        Path in = DocxFixture.simple(DocxFixture.paragraph(DocxFixture.run(null, "正文无敏感信息")))
                .binary("word/media/image1.png", png)
                .part("word/styles.xml", "<styles>保留原样</styles>")
                .writeTo(dir, "bin.docx");
        Path out = dir.resolve("bin-脱敏.docx");
        DocxRedactor.redact(in, out, RULES);
        assertArrayEquals(png, DocxFixture.rawPart(out, "word/media/image1.png"), "图片被改动了");
        assertEquals(DocxFixture.partNames(in), DocxFixture.partNames(out), "条目集合或顺序被打乱");
    }

    @Test
    @DisplayName("还原可逆：脱敏文档按映射还原后与原文逐字符相同")
    void roundTrip() throws IOException {
        String original = "公民身份号码：" + ID_A + "，银行卡 " + CARD + "，余额 100 元。";
        Path in = DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "公民身份号码：")
                        + DocxFixture.run(null, ID_A)
                        + DocxFixture.run(null, "，银行卡 " + CARD + "，余额 100 元。"))).writeTo(dir, "rt.docx");
        Path out = dir.resolve("rt-脱敏.docx");
        DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES);
        String redacted = DocxFixture.text(out, "word/document.xml");
        Map<String, String> mapping = report.mapping();
        assertEquals(2, mapping.size(), "应有身份证与银行卡两条映射，实得 " + mapping);
        assertEquals(original, RedactionEngine.restore(redacted, mapping));
    }

    @Test
    @DisplayName("同值同符跨段落：两个段落里的同一身份证用同一个令牌")
    void sameTokenAcrossParagraphs() throws IOException {
        String body = DocxFixture.paragraph(DocxFixture.run(null, "甲 " + ID_A))
                + DocxFixture.paragraph(DocxFixture.run(null, "乙 " + ID_A));
        Path out = redactTo(DocxFixture.simple(body), "same.docx");
        String text = DocxFixture.text(out, "word/document.xml");
        assertEquals("甲 <身份证1>乙 <身份证1>", text);
    }

    @Test
    @DisplayName("不同段落的不同实体必须拿到不同令牌")
    void differentValuesGetDifferentTokensAcrossParagraphs() throws IOException {
        // 这一条才是"跨段落共用分配器"的真正判据。
        // 两个段各自新建分配器时，各自的首个身份证都会拿到 <身份证1>——
        // 文档看着没毛病，但同一个令牌对应两个不同原值，还原时必然还原错。
        // 只测"同值同符"测不出来：那种情况下两个分配器恰好发出相同的编号。
        String body = DocxFixture.paragraph(DocxFixture.run(null, "甲 " + ID_A + "；"))
                + DocxFixture.paragraph(DocxFixture.run(null, "乙 " + ID_B + "。"));
        Path out = redactTo(DocxFixture.simple(body), "diff.docx");
        assertEquals("甲 <身份证1>；乙 <身份证2>。", DocxFixture.text(out, "word/document.xml"));
    }

    @Test
    @DisplayName("跨段落往返可逆：两个身份证各自还原回自己的原值")
    void roundTripAcrossParagraphs() throws IOException {
        String original = "甲 " + ID_A + "；乙 " + ID_B + "。";
        Path in = DocxFixture.simple(
                        DocxFixture.paragraph(DocxFixture.run(null, "甲 " + ID_A + "；"))
                                + DocxFixture.paragraph(DocxFixture.run(null, "乙 " + ID_B + "。")))
                .writeTo(dir, "rt2.docx");
        Path out = dir.resolve("rt2-脱敏.docx");
        Map<String, String> mapping = DocxRedactor.redact(in, out, RULES).mapping();
        assertEquals(2, mapping.size(), "两条映射，实得 " + mapping);
        assertEquals(original, RedactionEngine.restore(DocxFixture.text(out, "word/document.xml"), mapping));
    }

    @Test
    @DisplayName("报告能定位到具体 part 与槽位")
    void reportLocatesHits() throws IOException {
        Path in = DocxFixture.simple(DocxFixture.paragraph(DocxFixture.run(null, "电话 " + CARD)))
                .writeTo(dir, "report.docx");
        Path out = dir.resolve("report-脱敏.docx");
        DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES);
        assertEquals(1, report.hits().size());
        DocxRedactor.Hit hit = report.hits().get(0);
        assertEquals("word/document.xml", hit.part());
        assertEquals("bank_card", hit.ruleKey());
        assertEquals(CARD, hit.original());
        assertTrue(report.partsRewritten() >= 1);
    }

    @Test
    @DisplayName("fail-closed：不是 zip 的文件直接报错，不产出任何输出")
    void rejectsNonZip() throws IOException {
        Path fake = dir.resolve("fake.docx");
        Files.writeString(fake, "这不是一个 docx");
        Path out = dir.resolve("fake-脱敏.docx");
        assertThrows(IOException.class, () -> DocxRedactor.redact(fake, out, RULES));
        assertFalse(Files.exists(out), "失败时不应留下输出文件");
    }

    @Test
    @DisplayName("fail-closed：part 内 XML 损坏时报错，而不是原样放行")
    void rejectsMalformedXml() throws IOException {
        Path in = DocxFixture.simple("<w:p><w:r><w:t>未闭合")
                .writeTo(dir, "broken.docx");
        Path out = dir.resolve("broken-脱敏.docx");
        IOException error = assertThrows(IOException.class, () -> DocxRedactor.redact(in, out, RULES));
        assertTrue(error.getMessage().contains("无法解析"), "错误信息应说明解析失败，实得：" + error.getMessage());
    }
}
