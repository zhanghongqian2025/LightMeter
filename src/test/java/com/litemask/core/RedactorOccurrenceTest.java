package com.litemask.core;

import com.litemask.docx.DocxFixture;
import com.litemask.docx.DocxRedactor;
import com.litemask.ofd.OfdFixtures;
import com.litemask.ofd.OfdRedactor;
import com.litemask.pdf.PdfFixtures;
import com.litemask.pdf.PdfRedactor;
import com.litemask.pptx.PptxFixture;
import com.litemask.pptx.PptxRedactor;
import com.litemask.server.DocumentService;
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
 * 位置级排除<b>穿过整条管线</b>的验证，重点是<b>序号对得上位置</b>。
 *
 * <p>{@link OccurrenceExclusionTest} 证明引擎的序号语义对；这里证明的是另一件事：
 * 界面上「第 N 次」这句话，必须真的落在输出文件的第 N 处。
 *
 * <p><b>为什么这件事要单独测</b>：序号和位置说明在五个 redactor 里是<b>分别</b>产生的
 * ——序号由引擎的计数器给，位置说明由各自的 redactor 写。两边只要有一处没对上，
 * 用户勾的「第 2 处」就会作用到别的地方，而输出文件本身仍是「看起来正常」的：
 * 值还在、令牌也在、报告数字漂亮。没有任何报错指向这个错。
 *
 * <p>所以判据一律是<b>排除第 N 次之后，去输出文件里数还剩几处原值</b>，
 * 而不是「接口说排除了 1 条」。
 */
class RedactorOccurrenceTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");
    private static final String PHONE = "13800138000";

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ docx

    @Test
    @DisplayName("docx：三段同值，排除第 1 次后只有中间那段留着原值")
    void docx() throws IOException {
        Path in = DocxFixture.simple(
                DocxFixture.paragraph(DocxFixture.run(null, "甲 " + PHONE))
                        + DocxFixture.paragraph(DocxFixture.run(null, "乙 " + PHONE))
                        + DocxFixture.paragraph(DocxFixture.run(null, "丙 " + PHONE)))
                .writeTo(dir, "a.docx");

        DocumentService.HitItem hit = 只扫一处(in, DocumentService.Format.DOCX);
        assertEquals(3, hit.occurrences(), "同一个值出现三次");
        assertEquals(List.of("word/document.xml 第0段", "word/document.xml 第1段", "word/document.xml 第2段"),
                位置文本(hit), "位置说明必须按出现顺序排列，否则界面上的序号指不到地方");
        assertEquals(List.of(0, 1, 2),
                出现序号(hit), "每处位置必须带着自己的出现序号："
                        + "界面上勾的「第 N 处」与引擎判「第 N 次」用的必须是同一个数");

        Path out = dir.resolve("a-out.docx");
        DocxRedactor.redact(in, out, RULES, 排除第(1));
        String xml = DocxFixture.text(out, "word/document.xml");

        assertEquals(1, 出现次数(xml, PHONE), "只应保留第 1 次: " + xml);
        assertTrue(xml.contains("乙 " + PHONE), "保留的应是中间那段: " + xml);
        assertFalse(xml.contains("甲 " + PHONE) && xml.contains("丙 " + PHONE),
                "前后两段都应被脱敏: " + xml);
    }

    // ------------------------------------------------------------------ xlsx

    @Test
    @DisplayName("xlsx：三个单元格同值，排除第 1 次后只有 B1 留着原值")
    void xlsx() throws IOException {
        Path in = new XlsxFixture()
                .sheetData("<row r=\"1\">"
                        + XlsxFixture.inlineCell("A1", PHONE)
                        + XlsxFixture.inlineCell("B1", PHONE)
                        + XlsxFixture.inlineCell("C1", PHONE) + "</row>")
                .writeTo(dir, "a.xlsx");

        DocumentService.HitItem hit = 只扫一处(in, DocumentService.Format.XLSX);
        assertEquals(3, hit.occurrences());
        assertEquals(List.of(
                        "xl/worksheets/sheet1.xml cell A1",
                        "xl/worksheets/sheet1.xml cell B1",
                        "xl/worksheets/sheet1.xml cell C1"),
                位置文本(hit), "位置说明必须按出现顺序排列（A1→B1→C1），否则界面上的序号指不到地方");
        assertEquals(List.of(0, 1, 2),
                出现序号(hit), "序号必须与位置一一对应："
                        + "勾「cell C1」要能落到第 2 次上，而不是靠界面上数出来的序号");

        Path out = dir.resolve("a-out.xlsx");
        XlsxRedactor.redact(in, out, RULES, 排除第(1));
        String text = XlsxFixture.allText(out);

        assertEquals(1, 出现次数(text, PHONE), "只应保留 B1: " + text);
    }

    @Test
    @DisplayName("xlsx：表名排在单元格之前时，仍不占用单元格的序号")
    void xlsx表名不占序号() throws IOException {
        // sheetName 写在前面 = workbook.xml 在 zip 里排在 worksheets 之前，
        // redactor 遍历部件时先碰到表名。这个顺序是本用例的全部意义：
        // 若表名消耗序号，单元格那处就被顶到第 1 次，勾「第 0 次」会落空。
        Path in = new XlsxFixture()
                .sheetName("联系方式" + PHONE)
                .sheetData("<row r=\"1\">" + XlsxFixture.inlineCell("A1", PHONE) + "</row>")
                .writeTo(dir, "b.xlsx");

        DocumentService.HitItem hit = 只扫一处(in, DocumentService.Format.XLSX);
        assertEquals(1, hit.occurrences(), "表名那处不进清单，清单里只有单元格这一处");
        assertEquals(List.of("xl/worksheets/sheet1.xml cell A1"), 位置文本(hit));
        assertEquals(List.of(0), 出现序号(hit),
                "表名走的是隐形路径，不消费序号，所以这处仍是第 0 次");

        Path out = dir.resolve("b-out.xlsx");
        XlsxRedactor.redact(in, out, RULES, 排除第(0));

        String text = XlsxFixture.allText(out);
        assertTrue(text.contains(PHONE),
                "单元格那处应是第 0 次并被放过；表名若占了序号，这里会变成第 1 次而照常脱敏: " + text);
    }

    @Test
    @DisplayName("ofd：值级排除同样覆盖附件名这类隐形路径")
    void ofd值级排除覆盖附件名() throws IOException {
        // OfdFixtures.standard 造了一份 Attachs/<手机号>_申请书.pdf
        Path in = OfdFixtures.standard(dir, "c.ofd");
        assertTrue(附件名(in).contains(PHONE), "前提校验：附件名里应含这个号码");

        Path out = dir.resolve("c-out.ofd");
        OfdRedactor.redact(in, out, RULES, 排除整条());

        assertTrue(附件名(out).contains(PHONE),
                "用户在界面上把这个值整条排除了，藏在附件名里的那一份也该留下: " + 附件名(out));
    }

    /** 列出 ofd 包里所有附件条目的文件名。 */
    private static String 附件名(Path ofd) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(ofd.toFile())) {
            for (java.util.zip.ZipEntry e : zip.stream().toList()) {
                if (e.getName().contains("Attachs/")) {
                    sb.append(e.getName()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ pptx

    @Test
    @DisplayName("pptx：三个形状同值，排除第 2 次后只有第三个留着原值")
    void pptx() throws IOException {
        Path in = new PptxFixture().slide(
                PptxFixture.shape(PptxFixture.paragraph("甲 " + PHONE))
                        + PptxFixture.shape(PptxFixture.paragraph("乙 " + PHONE))
                        + PptxFixture.shape(PptxFixture.paragraph("丙 " + PHONE)))
                .writeTo(dir, "a.pptx");

        DocumentService.HitItem hit = 只扫一处(in, DocumentService.Format.PPTX);
        assertEquals(3, hit.occurrences());

        Path out = dir.resolve("a-out.pptx");
        PptxRedactor.redact(in, out, RULES, 排除第(2));
        String text = PptxFixture.allText(out);

        assertEquals(1, 出现次数(text, PHONE), "只应保留第 2 次: " + text);
        assertTrue(text.contains("丙 " + PHONE), "保留的应是第三段: " + text);
    }

    // ------------------------------------------------------------------ ofd

    // ------------------------------------------------------------------ txt

    /**
     * txt 曾经<b>整体不参与编号</b>：{@code DocumentService} 的 TXT 分支调的是
     * {@code redact(text, rules, allocator, exclusions)} 四参重载，numbering 恒为 null，
     * 于是命中序号永远是 -1，位置级排除一条都匹配不上。
     *
     * <p>它特别难被发现：值级排除照常工作（那条走的是另一套判断），
     * 接口返回 200，报告数字也正常，只有「勾第 N 次」这一项静默失效。
     */
    @Test
    @DisplayName("txt：同值三处，排除第 1 次后只剩那一处原值")
    void txt() throws IOException {
        Path in = dir.resolve("a.txt");
        Files.writeString(in, "第一处 " + PHONE + "\n第二处 " + PHONE + "\n第三处 " + PHONE + "\n",
                StandardCharsets.UTF_8);

        DocumentService.HitItem hit = 只扫一处(in, DocumentService.Format.TXT);
        assertEquals(3, hit.occurrences(), "txt 整篇一段，三处应合并成一条命中");
        assertEquals(3, hit.locators().size(), "每处都该有偏移位置");

        Path out = dir.resolve("a-out.txt");
        DocumentService.redact(in, out, DocumentService.Format.TXT, RULES, 排除第(1));
        String text = Files.readString(out, StandardCharsets.UTF_8);

        assertEquals(1, 出现次数(text, PHONE), "只应保留第 1 次: " + text);
        assertTrue(text.contains("第二处 " + PHONE), "保留的应是第二处: " + text);
    }

    // ------------------------------------------------------------------ ofd

    @Test
    @DisplayName("ofd：三个 TextObject 同值，排除第 0 次后只剩一处原值")
    void ofd() throws IOException {
        Path in = OfdFixtures.repeated(dir, "a.ofd", "电话：" + PHONE, 3);

        DocumentService.HitItem hit = 只扫一处(in, DocumentService.Format.OFD);
        assertEquals(3, hit.occurrences());

        Path out = dir.resolve("a-out.ofd");
        OfdRedactor.redact(in, out, RULES, 排除第(0));
        String page = OfdFixtures.decode(
                OfdFixtures.readTextParts(out).get("Doc_0/Pages/Page_0/Content.xml"));

        assertEquals(1, 出现次数(page, PHONE), "只应保留第 0 次: " + page);
    }

    // ------------------------------------------------------------------ pdf

    @Test
    @DisplayName("pdf：位置级排除能落到文件里，清单也报得出位置（第几页）")
    void pdf() throws IOException {
        Path in = PdfFixtures.writeLatin(dir.resolve("a.pdf"),
                "BT /F1 12 Tf 50 700 Td (A " + PHONE + ") Tj ET");
        Path out = dir.resolve("a-out.pdf");

        DocumentService.Result 扫描 = DocumentService.redact(
                in, dir.resolve("scan.pdf"), DocumentService.Format.PDF, RULES, Exclusions.NONE);
        DocumentService.HitItem hit = 扫描.hits().get(0);
        // PDF 现在有位置了（第几页的哪个文本块）。
        // 这一条以前是反的：「PDF 没有位置概念，清单不该报位置」——
        // 那句话在 M1-d 成立，但它的后果是用户在 PDF 上完全没法核对。
        assertTrue(hit.hasLocators(),
                "PDF 应当报位置信息：清单是用来核对的，核对不了位置的清单等于没有");
        assertTrue(hit.locators().get(0).locator().contains("第 1 页"),
                "位置必须说得出页码，实际: " + 位置文本(hit));

        PdfRedactor.redact(in, out, RULES, new PdfRedactor.Options(), 排除第(0));
        assertTrue(PdfFixtures.extractText(out).contains(PHONE),
                "引擎这一层仍然支持逐次排除（用 PDFBox 独立读回验证）");
    }

    // ------------------------------------------------------------------ 工具

    /** 每处位置说明的文本（不含序号）。 */
    private static List<String> 位置文本(DocumentService.HitItem hit) {
        return hit.locators().stream().map(DocumentService.HitItem.Loc::locator).toList();
    }

    /** 每处出现自己的序号。界面上「第 N 处」勾的就是它。 */
    private static List<Integer> 出现序号(DocumentService.HitItem hit) {
        return hit.locators().stream().map(DocumentService.HitItem.Loc::occurrence).toList();
    }

    private static Exclusions 排除第(int occurrence) {
        return Exclusions.of(List.of(Exclusions.HitRef.nth("phone", PHONE, occurrence)));
    }

    private static Exclusions 排除整条() {
        return Exclusions.of(List.of(new Exclusions.HitRef("phone", PHONE)));
    }

    /** 只跑一遍扫描，取出那个手机号对应的命中项。 */
    private static DocumentService.HitItem 只扫一处(Path in, DocumentService.Format format)
            throws IOException {
        Path scratch = in.resolveSibling(in.getFileName() + ".scan");
        DocumentService.Result r = DocumentService.redact(
                in, scratch, format, RULES, Exclusions.NONE);
        return r.hits().stream()
                .filter(h -> PHONE.equals(h.original()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没扫到 " + PHONE + "，命中清单: " + r.hits()));
    }

    private static int 出现次数(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
