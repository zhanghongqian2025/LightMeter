package com.litemask.core;

import com.litemask.docx.DocxFixture;
import com.litemask.docx.DocxRedactor;
import com.litemask.ofd.OfdFixtures;
import com.litemask.ofd.OfdRedactor;
import com.litemask.pdf.PdfFixtures;
import com.litemask.pdf.PdfRedactor;
import com.litemask.pptx.PptxFixture;
import com.litemask.pptx.PptxRedactor;
import com.litemask.xlsx.XlsxFixture;
import com.litemask.xlsx.XlsxRedactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排除能力<b>穿过整条管线</b>的验证。
 *
 * <p>{@link ExclusionTest} 只证明引擎的顺序语义对。这里证明的是另一件事：
 * 排除项从 HTTP 层一路传到 {@code new RedactionSession(...)}，最后<b>真的体现在输出文件里</b>。
 *
 * <p>为什么必须单独测：加一个重载参数是极其容易漏接的地方。漏了不会报错，
 * 编译照样通过、输出照样生成、报告照样漂亮——只是「用户点了别脱」这一项不起作用。
 * 这是本功能唯一会出现的失败形态，所以必须有一层测试直接看文件内容。
 *
 * <p>标尺全部是<b>外部读取器</b>：docx/xlsx/pptx 用测试里自己写的 zip+DOM 读取，
 * PDF 用 PDFBox，OFD 用 ofdrw。被测实现自己写的解析逻辑不参与判断。
 */
class RedactorExclusionTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");

    /** 被排除的值。 */
    private static final String KEEP_PHONE = "13800138000";
    /** 同一个文档里的另一个值，不排除，必须照常脱敏——排除不能误伤成「整个文档不脱」。 */
    private static final String REDACT_PHONE = "13900139000";

    private static Exclusions 排除手机(String phone) {
        return Exclusions.of(List.of(new Exclusions.HitRef("phone", phone)));
    }

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ docx

    @Test
    @DisplayName("docx：排除项落到输出文件里")
    void docx() throws IOException {
        Path in = DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "甲 " + KEEP_PHONE + " 乙 " + REDACT_PHONE)))
                .writeTo(dir, "a.docx");
        Path out = dir.resolve("a-out.docx");

        DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES, 排除手机(KEEP_PHONE));

        String xml = DocxFixture.text(out, "word/document.xml");
        assertTrue(xml.contains(KEEP_PHONE), "被排除的值应原样出现在输出里: " + xml);
        assertFalse(xml.contains(REDACT_PHONE), "未排除的值应被脱敏: " + xml);
        assertFalse(report.mapping().containsValue(KEEP_PHONE),
                "被排除的值不应进映射（否则还原会拿令牌换一个没被替换的串）: " + report.mapping());
        assertTrue(report.mapping().containsValue(REDACT_PHONE), String.valueOf(report.mapping()));
    }

    // ------------------------------------------------------------------ xlsx

    @Test
    @DisplayName("xlsx：排除项落到输出文件里")
    void xlsx() throws IOException {
        Path in = new XlsxFixture()
                .sheetData("<row r=\"1\">" + XlsxFixture.inlineCell("A1", KEEP_PHONE)
                        + XlsxFixture.inlineCell("B1", REDACT_PHONE) + "</row>")
                .writeTo(dir, "a.xlsx");
        Path out = dir.resolve("a-out.xlsx");

        XlsxRedactor.Report report = XlsxRedactor.redact(in, out, RULES, 排除手机(KEEP_PHONE));

        String text = XlsxFixture.allText(out);
        assertTrue(text.contains(KEEP_PHONE), "被排除的值应原样出现在输出里: " + text);
        assertFalse(text.contains(REDACT_PHONE), "未排除的值应被脱敏: " + text);
        assertFalse(report.mapping().containsValue(KEEP_PHONE), String.valueOf(report.mapping()));
    }

    // ------------------------------------------------------------------ pptx

    @Test
    @DisplayName("pptx：排除项落到输出文件里")
    void pptx() throws IOException {
        Path in = new PptxFixture().slide(PptxFixture.shape(
                PptxFixture.paragraph("甲 " + KEEP_PHONE + " 乙 " + REDACT_PHONE)))
                .writeTo(dir, "a.pptx");
        Path out = dir.resolve("a-out.pptx");

        PptxRedactor.Report report = PptxRedactor.redact(in, out, RULES, 排除手机(KEEP_PHONE));

        String text = PptxFixture.allText(out);
        assertTrue(text.contains(KEEP_PHONE), "被排除的值应原样出现在输出里: " + text);
        assertFalse(text.contains(REDACT_PHONE), "未排除的值应被脱敏: " + text);
        assertFalse(report.mapping().containsValue(KEEP_PHONE), String.valueOf(report.mapping()));
    }

    // ------------------------------------------------------------------ ofd

    @Test
    @DisplayName("ofd：排除项落到输出文件里")
    void ofd() throws IOException {
        Path in = OfdFixtures.standard(dir, "a.ofd");
        Path out = dir.resolve("a-out.ofd");

        OfdRedactor.Report report = OfdRedactor.redact(in, out, RULES, 排除手机(OfdFixtures.PHONE));

        String page = OfdFixtures.decode(
                OfdFixtures.readTextParts(out).get("Doc_0/Pages/Page_0/Content.xml"));
        assertTrue(page.contains(OfdFixtures.PHONE), "被排除的值应原样出现在输出里: " + page);
        assertFalse(page.contains(OfdFixtures.ID), "未排除的值应被脱敏: " + page);
        assertFalse(report.mapping().containsValue(OfdFixtures.PHONE),
                String.valueOf(report.mapping()));
    }

    // ------------------------------------------------------------------ pdf

    @Test
    @DisplayName("pdf：排除项落到输出文件里（用 PDFBox 独立读回）")
    void pdf() throws IOException {
        Path in = PdfFixtures.writeLatin(dir.resolve("a.pdf"),
                "BT /F1 12 Tf 50 700 Td (Call " + KEEP_PHONE + " now) Tj ET");
        Path out = dir.resolve("a-out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(in, out, RULES, new PdfRedactor.Options(), 排除手机(KEEP_PHONE));

        String text = PdfFixtures.extractText(out);
        assertTrue(text.contains(KEEP_PHONE),
                "被排除的值应原样出现在输出里: " + text);
        assertFalse(report.mapping().containsValue(KEEP_PHONE),
                String.valueOf(report.mapping()));
    }

    // ------------------------------------------------------- 往返与空排除对照

    @Test
    @DisplayName("不传排除项时输出与传 NONE 完全一致（重载没改变默认行为）")
    void 空排除与旧路径一致() throws IOException {
        Path in = DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "甲 " + KEEP_PHONE + " 乙 " + REDACT_PHONE)))
                .writeTo(dir, "b.docx");
        Path 三参 = dir.resolve("b-3.docx");
        Path 四参 = dir.resolve("b-4.docx");

        DocxRedactor.redact(in, 三参, RULES);
        DocxRedactor.redact(in, 四参, RULES, Exclusions.NONE);

        // 比文档内容，不比 zip 字节：压缩包里含时间戳，逐字节比必然不稳定
        assertEquals(DocxFixture.text(三参, "word/document.xml"),
                DocxFixture.text(四参, "word/document.xml"),
                "三参与四参（NONE）应产生同样的文档内容");
        assertFalse(DocxFixture.text(四参, "word/document.xml").contains(KEEP_PHONE),
                "前提校验：这条应当是「照常脱敏」，否则本测试证明不了等同性");
    }

    @Test
    @DisplayName("排除后仍能往返：没脱的保持原样，脱了的能还原")
    void 排除后往返() throws IOException {
        Path in = DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "甲 " + KEEP_PHONE + " 乙 " + REDACT_PHONE)))
                .writeTo(dir, "c.docx");
        Path out = dir.resolve("c-out.docx");

        DocxRedactor.Report report = DocxRedactor.redact(in, out, RULES, 排除手机(KEEP_PHONE));
        Path back = dir.resolve("c-back.docx");
        int n = DocxRedactor.restore(out, back, report.mapping());

        // 计数是「被改动的文本槽」而不是「值」的个数：
        // 甲乙两个手机号在同一个 run 里，只有未被排除的那个真被替换过，
        // 所以还原只动 1 处。写 2 会把「被排除的值也参与了还原」这种错误混进来。
        assertEquals(1, n, "只应还原那一处被替换的文本槽");
        String xml = DocxFixture.text(back, "word/document.xml");
        assertTrue(xml.contains(KEEP_PHONE), "被排除的值从头到尾都是原样: " + xml);
        assertTrue(xml.contains(REDACT_PHONE), "未排除的值应被还原回来: " + xml);
    }
}
