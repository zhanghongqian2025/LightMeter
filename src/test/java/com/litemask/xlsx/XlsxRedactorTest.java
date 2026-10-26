package com.litemask.xlsx;

import com.litemask.core.RedactionEngine;
import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import com.litemask.server.DocumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * xlsx 端到端脱敏。
 *
 * <p>重点放在"看起来正常、实际漏脱"的位置：共享字符串被多单元格引用、数值单元格存身份证、
 * 工作表名带姓名、公式缓存结果。
 */
class XlsxRedactorTest {

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

    private Path redactTo(XlsxFixture fixture, String name) throws IOException {
        Path in = fixture.writeTo(dir, name);
        Path out = dir.resolve(name.replace(".xlsx", "-脱敏.xlsx"));
        XlsxRedactor.redact(in, out, RULES);
        return out;
    }

    @Test
    @DisplayName("DocumentService 结果如实提示 Excel 未覆盖内容")
    void coverageNoteIsReturned() throws IOException {
        Path in = new XlsxFixture().sharedStrings("电话 " + CARD).sheetData(
                "<row r=\"1\">" + XlsxFixture.sharedCell("A1", 0) + "</row>")
                .writeTo(dir, "coverage.xlsx");
        DocumentService.Result result = DocumentService.redact(in, dir.resolve("coverage-out.xlsx"),
                DocumentService.Format.XLSX, RULES, Exclusions.NONE);
        String notes = String.join(" | ", result.notes());
        assertTrue(notes.contains("图片像素") && notes.contains("嵌入对象")
                        && notes.contains("公式源文本") && notes.contains("图表缓存"), notes);
    }

    @Test
    @DisplayName("共享字符串里的身份证被脱敏，引用它的单元格跟着变")
    void sharedString() throws IOException {
        Path out = redactTo(new XlsxFixture()
                .sharedStrings("公民身份号码：" + ID_A, "普通备注")
                .sheetData("<row r=\"1\">" + XlsxFixture.sharedCell("A1", 0)
                        + XlsxFixture.sharedCell("B1", 1) + "</row>"), "shared.xlsx");
        String text = XlsxFixture.allText(out);
        assertFalse(text.contains(ID_A), "共享字符串未脱敏：" + text);
        assertTrue(text.contains("公民身份号码："), "上下文被破坏：" + text);
        assertTrue(XlsxFixture.textPart(out, "xl/sharedStrings.xml").contains("普通备注"), "无关字符串被改");
        assertEquals(1, count(XlsxFixture.textPart(out, "xl/worksheets/sheet1.xml"), "<v>1</v>"),
                "引用下标不应被改动");
    }

    @Test
    @DisplayName("富文本共享字符串：身份证横跨多个片段仍能命中")
    void richTextSharedString() throws IOException {
        XlsxFixture fixture = new XlsxFixture()
                .part("xl/sharedStrings.xml",
                        "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                                + "<si><r><t>号码：110105</t></r><r><t>19491231</t></r><r><t>002X</t></r></si></sst>")
                .sheetData("<row r=\"1\">" + XlsxFixture.sharedCell("A1", 0) + "</row>");
        Path out = redactTo(fixture, "rich.xlsx");
        String text = XlsxFixture.allText(out);
        assertFalse(text.contains("110105") || text.contains("002X"), "跨片段身份证未脱敏：" + text);
        assertTrue(text.contains("<身份证1>"), text);
    }

    /**
     * 身份证被<b>相邻两个单元格</b>切断。
     *
     * <p>与 docx 的段落是同一个道理：切断后两段碎片各自都匹配不上任何规则，
     * 工具会报告「脱敏成功」而碎片原样留在表格里。
     */
    @Test
    @DisplayName("身份证被相邻单元格切断：两段碎片都被脱敏，类型是身份证")
    void idCardSplitAcrossCells() throws IOException {
        Path out = redactTo(new XlsxFixture()
                .sheetData("<row r=\"1\">" + XlsxFixture.inlineCell("A1", "身份证110105号")
                        + XlsxFixture.inlineCell("B1", "续19491231002X尾") + "</row>"), "crosscell.xlsx");
        String text = XlsxFixture.allText(out);
        assertFalse(text.contains("110105"), "第一段碎片还在：" + text);
        assertFalse(text.contains("19491231002"), "第二段碎片还在：" + text);
        assertFalse(text.contains("<手机号"), "碎片仍被错标成手机号：" + text);
        assertTrue(text.contains("<身份证1>") && text.contains("<身份证2>"), "应当有两个身份证令牌：" + text);
    }

    /**
     * 单元序列必须与「编号路径真正喂的文本」一致，<b>与 zip 里部件的先后无关</b>。
     *
     * <p>块 14 的回归就出在这里：工作表名在 {@code xl/workbook.xml} 里，真实 Excel 文件里
     * 它<b>排在工作表前面</b>。旧的单元序列（全文按 {@code '\n'} 切）于是第一个是工作表名，
     * 而第一个喂进去的却是单元格文本 → 对不上 → 续接被静默关掉 → 碎片原样留在包里。
     * 之前的夹具把工作表名放在最后（{@link XlsxFixture#sheetData} 之后没有 workbook），
     * 所以单测一直是绿的——<b>顺序依赖被夹具掩盖了</b>。
     *
     * <p>所以这条用例<b>故意</b>把工作表名排在工作表前面。
     */
    @Test
    @DisplayName("工作表名排在工作表前面时，跨单元续接仍然生效")
    void continuationIndependentOfPartOrder() throws IOException {
        Path out = redactTo(new XlsxFixture()
                // 先写 workbook.xml → zip 里它排在工作表前面
                .sheetName("联络13800138000")
                .sheetData("<row r=\"1\">" + XlsxFixture.inlineCell("A1", "身份证110105号")
                        + XlsxFixture.inlineCell("B1", "续19491231002X尾") + "</row>"), "order.xlsx");
        String text = XlsxFixture.allText(out);
        assertFalse(text.contains("110105"), "第一段碎片还在：" + text);
        assertFalse(text.contains("19491231002"), "第二段碎片还在：" + text);
        assertFalse(text.contains("<手机号"), "碎片仍被错标成手机号：" + text);
        assertTrue(text.contains("<身份证1>") && text.contains("<身份证2>"), "应当有两个身份证令牌：" + text);
        // 工作表名照旧要脱敏（隐藏路径：改了内容但不进命中清单、不占出现序号）
        assertFalse(XlsxFixture.textPart(out, "xl/workbook.xml").contains("13800138000"),
                "工作表名没脱敏");
    }

    /**
     * 同一份内容换个 zip 部件顺序，脱敏结果必须一样。
     *
     * <p>这是上面那条的更强形式：不是「某一种顺序下对了」，而是<b>顺序本身不影响结论</b>。
     * 比的是文本集合而不是拼接结果——部件顺序不同，包里部件的先后就不同，
     * 直接比整串会把「顺序不同」误判成「结果不同」。
     */
    @Test
    @DisplayName("换个 zip 部件顺序，脱敏结果必须一样")
    void sameResultRegardlessOfPartOrder() throws IOException {
        String sheetData = "<row r=\"1\">" + XlsxFixture.inlineCell("A1", "身份证110105号")
                + XlsxFixture.inlineCell("B1", "续19491231002X尾") + "</row>";
        Path first = redactTo(new XlsxFixture()
                .sheetName("联络13800138000")
                .sheetData(sheetData), "order-a.xlsx");
        Path second = redactTo(new XlsxFixture()
                .sheetData(sheetData)
                .sheetName("联络13800138000"), "order-b.xlsx");
        assertEquals(sortedLines(XlsxFixture.allText(first)), sortedLines(XlsxFixture.allText(second)),
                "部件顺序不该影响脱敏结果");
    }

    /**
     * 跨 zip 部件<b>不得</b>续接。
     *
     * <p>共享字符串与工作表单元格是两种容器：它们在 zip 里挨着，在语义上却是两件事。
     * 合成一组（块 13 的行为）时它们会被拼成一个<b>形态合法</b>的 VIN：
     * 共享字符串末尾的 16 位数字 + 单元格开头的字母 X = {@code 6222021234567890X}。
     * 于是两段都被按车架号脱敏——<b>误判 + 标签错</b>。
     *
     * <p>所以每组 = 一个 zip 部件：只有<b>同一个容器</b>里的相邻单元才能续接
     * （共享字符串与共享字符串之间、工作表与工作表之间）。
     *
     * <p><b>两种部件顺序都要试</b>：顺序不同，拼接方向就不同，两种都能拼出 VIN 形态——
     * 只试一种顺序的话，另一种顺序下这条用例会<b>空转</b>。
     */
    @Test
    @DisplayName("共享字符串与工作表分属不同部件：不得跨部件续接（两种部件顺序都试）")
    void noContinuationAcrossParts() throws IOException {
        String shared = "<?xml version=\"1.0\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<si><t>编号" + FRAGMENT + "</t></si></sst>";
        String sheet = "<row r=\"1\">" + XlsxFixture.inlineCell("A1", "X是当事人") + "</row>";
        for (boolean sharedFirst : new boolean[]{true, false}) {
            XlsxFixture fixture = sharedFirst
                    ? new XlsxFixture().part("xl/sharedStrings.xml", shared).sheetData(sheet)
                    : new XlsxFixture().sheetData(sheet).part("xl/sharedStrings.xml", shared);
            Path in = fixture.writeTo(dir, "parts-" + sharedFirst + ".xlsx");
            Path out = dir.resolve("parts-" + sharedFirst + "-脱敏.xlsx");
            XlsxRedactor.Report report = XlsxRedactor.redact(in, out, RULES);

            String text = XlsxFixture.allText(out);
            assertTrue(text.contains(FRAGMENT),
                    "共享字符串里的编号被跨部件续接误当成车架号碎片替换了（共享字符串在前=" + sharedFirst + "）：" + text);
            assertTrue(text.contains("X是当事人"),
                    "单元格里的 X 被跨部件续接误替换了（共享字符串在前=" + sharedFirst + "）：" + text);
            assertFalse(text.contains("<车架号"), "冒出了车架号令牌：" + text);
            assertEquals(0, report.hits().size(),
                    "这两段各自都匹配不上任何规则，不该有命中（共享字符串在前=" + sharedFirst + "）：" + report.hits());
        }
    }

    private static List<String> sortedLines(String text) {
        return text.lines().sorted().toList();
    }

    @Test
    @DisplayName("内联字符串被脱敏")
    void inlineString() throws IOException {
        Path out = redactTo(new XlsxFixture()
                .sharedStrings()
                .sheetData("<row r=\"1\">" + XlsxFixture.inlineCell("A1", "电话 " + CARD) + "</row>"), "inline.xlsx");
        assertFalse(XlsxFixture.allText(out).contains(CARD));
    }

    @Test
    @DisplayName("数值单元格存银行卡号：转成文本并计数上报")
    void numericCellConvertedToText() throws IOException {
        Path in = new XlsxFixture().sharedStrings()
                .sheetData("<row r=\"1\">" + XlsxFixture.numericCell("A1", CARD) + "</row>")
                .writeTo(dir, "num.xlsx");
        Path out = dir.resolve("num-脱敏.xlsx");
        XlsxRedactor.Report report = XlsxRedactor.redact(in, out, RULES);
        assertEquals(1, report.numericCellsConverted(), "应报告 1 个数值单元格被转成文本");
        String xml = XlsxFixture.textPart(out, "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("t=\"inlineStr\""), "单元格类型未转换：" + xml);
        assertFalse(XlsxFixture.allText(out).contains(CARD), "数值内容未脱敏");
    }

    @Test
    @DisplayName("公式单元格的缓存结果被脱敏，但公式本身不动")
    void formulaCachedResultRedacted() throws IOException {
        Path out = redactTo(new XlsxFixture().sharedStrings()
                .sheetData("<row r=\"1\">" + XlsxFixture.formulaCell("A1", "CONCATENATE(B1,C1)", ID_A) + "</row>"),
                "formula.xlsx");
        String xml = XlsxFixture.textPart(out, "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("CONCATENATE(B1,C1)"), "公式被改动：" + xml);
        assertFalse(XlsxFixture.allText(out).contains(ID_A), "缓存结果未脱敏");
    }

    @Test
    @DisplayName("工作表名里的账号：被替换为占位符")
    void sheetNameRedacted() throws IOException {
        // 注意：这里用银行卡号而不是中文人名。当前规则库是纯正则的，
        // 「张三的账户」识别不了——中文人名要等 M3 的 NER 才谈得上脱敏。
        // 这里测的是"工作表名这个位置确实被处理了"，不是"人名能脱敏"。
        Path out = redactTo(new XlsxFixture().sharedStrings()
                .sheet("账户" + CARD, "<row r=\"1\">" + XlsxFixture.inlineCell("A1", "余额") + "</row>"),
                "sheetname.xlsx");
        String workbook = unescape(XlsxFixture.textPart(out, "xl/workbook.xml"));
        assertFalse(workbook.contains(CARD), "工作表名未脱敏：" + workbook);
        assertTrue(workbook.contains("<银行卡号1>"), workbook);
    }

    @Test
    @DisplayName("工作表名里的令牌不会生成 Excel 非法字符")
    void sheetNameStaysValid() throws IOException {
        Path out = redactTo(new XlsxFixture().sharedStrings()
                .sheet("客户 " + CARD, "<row r=\"1\"/>"), "sheetvalid.xlsx");
        String name = XlsxFixture.textPart(out, "xl/workbook.xml")
                .replaceAll("(?s).*<sheet name=\"([^\"]*)\".*", "$1");
        assertTrue(name.length() <= 31, "工作表名超长：" + name);
        assertFalse(name.matches(".*[:\\\\/?*\\[\\]].*"), "含 Excel 非法字符：" + name);
    }

    @Test
    @DisplayName("定义名称里的姓名被脱敏")
    void definedNameRedacted() throws IOException {
        Path out = redactTo(new XlsxFixture().sharedStrings()
                .workbook("<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + "<sheets><sheet name=\"表1\" sheetId=\"1\"/></sheets>"
                        + "<definedNames><definedName name=\"n1\">区域_" + ID_A + "</definedName></definedNames>"
                        + "</workbook>")
                .sheetData("<row r=\"1\"/>"), "defined.xlsx");
        assertFalse(XlsxFixture.allText(out).contains(ID_A), "定义名称未脱敏");
    }

    @Test
    @DisplayName("批注内容被脱敏")
    void commentRedacted() throws IOException {
        Path out = redactTo(new XlsxFixture().sharedStrings()
                .part("xl/comments1.xml",
                        "<?xml version=\"1.0\"?><comments xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                                + "<authors><author>张伟</author></authors>"
                                + "<commentList><comment ref=\"A1\"><text><t>电话 " + CARD + "</t></text></comment></commentList>"
                                + "</comments>")
                .sheetData("<row r=\"1\"/>"), "comment.xlsx");
        assertFalse(XlsxFixture.allText(out).contains(CARD), "批注未脱敏");
    }

    @Test
    @DisplayName("文档属性被清空（作者名留在属性面板等于没脱）")
    void metadataCleared() throws IOException {
        Path in = new XlsxFixture().sharedStrings()
                .part("docProps/core.xml",
                        "<?xml version=\"1.0\"?>"
                                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                                + "<dc:creator>张伟</dc:creator><cp:lastModifiedBy>李明</cp:lastModifiedBy>"
                                + "</cp:coreProperties>")
                .sheetData("<row r=\"1\"/>")
                .writeTo(dir, "meta.xlsx");
        Path out = dir.resolve("meta-脱敏.xlsx");
        XlsxRedactor.Report report = XlsxRedactor.redact(in, out, RULES);
        assertFalse(XlsxFixture.textPart(out, "docProps/core.xml").contains("张伟"));
        assertFalse(XlsxFixture.textPart(out, "docProps/core.xml").contains("李明"));
        assertTrue(report.metadataFieldsCleared() >= 2);
    }

    @Test
    @DisplayName("共享字符串首尾空格保留（丢失 xml:space 会让还原对不上）")
    void spacePreserved() throws IOException {
        Path in = new XlsxFixture().sharedStrings(" 身份证 " + ID_A + " ")
                .sheetData("<row r=\"1\">" + XlsxFixture.sharedCell("A1", 0) + "</row>")
                .writeTo(dir, "space.xlsx");
        Path out = dir.resolve("space-脱敏.xlsx");
        XlsxRedactor.redact(in, out, RULES);
        String xml = XlsxFixture.textPart(out, "xl/sharedStrings.xml");
        assertTrue(xml.contains("xml:space=\"preserve\""), "缺少 xml:space：" + xml);
        assertTrue(XlsxFixture.allText(out).contains(" 身份证 "), "首尾空格丢失");
    }

    @Test
    @DisplayName("跨表同值同符：两个工作表里的同一身份证用同一个令牌")
    void sameTokenAcrossSheets() throws IOException {
        XlsxFixture f = new XlsxFixture().sharedStrings("甲 " + ID_A, "乙 " + ID_A)
                .sheetData("<row r=\"1\">" + XlsxFixture.sharedCell("A1", 0) + "</row>")
                .part("xl/worksheets/sheet2.xml",
                        "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                                + "<sheetData><row r=\"1\">"
                                + XlsxFixture.sharedCell("A1", 1) + "</row></sheetData></worksheet>");
        Path out = redactTo(f, "sheets.xlsx");
        String shared = XlsxFixture.textPart(out, "xl/sharedStrings.xml");
        assertFalse(shared.contains(ID_A));
        String text = unescape(shared);
        assertEquals(2, count(text, "<身份证1>"), "两处同值都应复用 <身份证1>");
        assertFalse(text.contains("<身份证2>"), "同值不该拿到第二个编号：" + text);
    }

    @Test
    @DisplayName("不同工作表的不同实体拿到不同令牌，并各自可逆")
    void differentTokensAcrossSheets() throws IOException {
        XlsxFixture f = new XlsxFixture().sharedStrings("甲 " + ID_A, "乙 " + ID_B)
                .sheetData("<row r=\"1\">" + XlsxFixture.sharedCell("A1", 0) + "</row>")
                .part("xl/worksheets/sheet2.xml",
                        "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                                + "<sheetData><row r=\"1\">"
                                + XlsxFixture.sharedCell("A1", 1) + "</row></sheetData></worksheet>");
        Path in = f.writeTo(dir, "twosh.xlsx");
        Path out = dir.resolve("twosh-脱敏.xlsx");
        Map<String, String> mapping = XlsxRedactor.redact(in, out, RULES).mapping();
        assertEquals(2, mapping.size(), "两条映射，实得 " + mapping);
        assertTrue(XlsxFixture.allText(out).contains("<身份证1>"));
        assertTrue(XlsxFixture.allText(out).contains("<身份证2>"));
    }

    @Test
    @DisplayName("还原可逆：脱敏件按映射还原后文本与原文一致")
    void roundTrip() throws IOException {
        XlsxFixture f = new XlsxFixture()
                .sharedStrings("身份证：" + ID_A, "银行卡 " + CARD)
                .sheetData("<row r=\"1\">" + XlsxFixture.sharedCell("A1", 0)
                        + XlsxFixture.sharedCell("B1", 1) + "</row>");
        Path in = f.writeTo(dir, "rt.xlsx");
        Path out = dir.resolve("rt-脱敏.xlsx");
        Map<String, String> mapping = XlsxRedactor.redact(in, out, RULES).mapping();
        assertEquals(2, mapping.size());

        Path back = dir.resolve("rt-还原.xlsx");
        XlsxRedactor.restore(out, back, mapping);
        String restored = XlsxFixture.textPart(back, "xl/sharedStrings.xml");
        assertTrue(restored.contains(ID_A), "身份证未还原：" + restored);
        assertTrue(restored.contains(CARD), "银行卡未还原：" + restored);
    }

    @Test
    @DisplayName("未改动的 part 与二进制 part 逐字节不变")
    void untouchedPartsUnchanged() throws IOException {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        Path in = new XlsxFixture().sharedStrings("无敏感信息")
                .sheetData("<row r=\"1\">" + XlsxFixture.sharedCell("A1", 0) + "</row>")
                .binary("xl/media/image1.png", png)
                .writeTo(dir, "bin.xlsx");
        Path out = dir.resolve("bin-脱敏.xlsx");
        XlsxRedactor.redact(in, out, RULES);
        assertArrayEquals(png, XlsxFixture.rawPart(out, "xl/media/image1.png"), "图片被改动");
        assertEquals(XlsxFixture.textPart(in, "xl/worksheets/sheet1.xml"),
                XlsxFixture.textPart(out, "xl/worksheets/sheet1.xml"), "未改动的工作表被重新序列化");
    }

    @Test
    @DisplayName("报告能定位到具体 part 与单元格")
    void reportLocatesHits() throws IOException {
        Path in = new XlsxFixture().sharedStrings()
                .sheetData("<row r=\"1\">" + XlsxFixture.inlineCell("C7", CARD) + "</row>")
                .writeTo(dir, "report.xlsx");
        Path out = dir.resolve("report-脱敏.xlsx");
        XlsxRedactor.Report report = XlsxRedactor.redact(in, out, RULES);
        assertEquals(1, report.hits().size());
        assertEquals("cell C7", report.hits().get(0).locator());
        assertEquals("bank_card", report.hits().get(0).ruleKey());
    }

    @Test
    @DisplayName("fail-closed：不是 zip 的文件直接报错，不产出输出")
    void rejectsNonZip() throws IOException {
        Path fake = dir.resolve("fake.xlsx");
        java.nio.file.Files.writeString(fake, "这不是一个 xlsx");
        Path out = dir.resolve("fake-脱敏.xlsx");
        assertThrows(IOException.class, () -> XlsxRedactor.redact(fake, out, RULES));
        assertFalse(java.nio.file.Files.exists(out), "失败时不应留下输出文件");
    }

    @Test
    @DisplayName("fail-closed：XML 损坏时报错而不是原样放行")
    void rejectsMalformedXml() throws IOException {
        Path in = new XlsxFixture().part("xl/sharedStrings.xml", "<sst><si><t>未闭合")
                .writeTo(dir, "broken.xlsx");
        Path out = dir.resolve("broken-脱敏.xlsx");
        assertThrows(IOException.class, () -> XlsxRedactor.redact(in, out, RULES));
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
