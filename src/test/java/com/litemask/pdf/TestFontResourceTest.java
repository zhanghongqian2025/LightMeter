package com.litemask.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试字体资源的守卫。
 *
 * <p>这一组用例守的不是产品功能，而是<b>测试本身能不能被执行</b>。
 *
 * <p>背景是一次实测出来的 Windows 侧硬伤：中文 PDF 的单测原来从系统字体列表里挑一个
 * 中文字体，挑不到就 {@code Assumptions} 跳过。Windows 上唯一的候选是
 * {@code C:/Windows/Fonts/msyh.ttc}，那是<b>字体集</b>，而 PDFBox 的普通加载路径读不了它——
 * {@code PDType0Font.load} 直接抛 {@code IOException: 'head' table is mandatory}。
 * 于是同一份代码在 macOS 上是「绿灯（跑了）」，在 Windows 上是「红灯（抛异常）」，
 * 而 macOS 上某些机器还会「绿灯（跳过了）」。三种机器三种结果，没有一种能让人相信这条路径被测过。
 *
 * <p>现在字体随仓库提供（{@code tools/make_test_font.py} 生成，OFL 1.1 可再分发），
 * 于是：<b>没有「跳过」这条路</b>，只有「资源坏了」这一种红。
 */
class TestFontResourceTest {

    /** {@code PdfRedactorTest.chineseRoundTrip} 里逐字用到的那几行。 */
    private static final List<String> CJK_LINES_USED_IN_TESTS = List.of(
            "民事起诉状",
            "原告：张三，男，1985 年 3 月 12 日出生，汉族。",
            "身份证号：11010519491231002X",
            "联系电话：13800138000，邮箱 zhang@example.com",
            "此致",
            "北京市朝阳区人民法院");

    @Test
    @DisplayName("仓库自带的中文字体子集存在、可被 PDFBox 加载，且真的含中文字形")
    void bundledFontIsPresentAndCarriesChineseGlyphs() throws IOException {
        Path font = PdfFixtures.cjkFontFile();

        assertTrue(Files.isRegularFile(font), "打包字体必须落到一个真实文件上：" + font);
        assertTrue(Files.size(font) > 10_000,
                "打包字体只有 " + Files.size(font) + " 字节，太小了——多半是生成脚本出了问题：" + font);

        try (PDDocument doc = new PDDocument()) {
            PDType0Font loaded = PdfFixtures.loadCjkFont(doc, font);
            assertNotNull(loaded, "字体必须能加载");
            // 「民」不在 ASCII 里，能编出来才说明子集里真的有中文字形
            assertDoesNotThrow(() -> loaded.encode("民"),
                    "子集字体编不出「民」，说明根本没裁到中文字形");
        }
    }

    @Test
    @DisplayName("打包字体覆盖单测里用到的每一个字（缺一个字就在这里红，不留到断言里才发现）")
    void bundledFontCoversEveryCharacterUsedInTests() throws IOException {
        Path font = PdfFixtures.cjkFontFile();
        try (PDDocument doc = new PDDocument()) {
            PDType0Font loaded = PdfFixtures.loadCjkFont(doc, font);
            for (String line : CJK_LINES_USED_IN_TESTS) {
                assertDoesNotThrow(() -> PdfFixtures.assertCovers(loaded, line),
                        "打包字体覆盖不到这一行：" + line
                                + "。用 python3 tools/make_test_font.py --source <NotoSansSC-VF.ttf> 重新生成。");
            }
        }
    }

    @Test
    @DisplayName("覆盖检查会把缺字一次列全，而不是只报第一个")
    void coverageCheckListsEveryMissingCharacter() throws IOException {
        Path font = PdfFixtures.cjkFontFile();
        try (PDDocument doc = new PDDocument()) {
            PDType0Font loaded = PdfFixtures.loadCjkFont(doc, font);
            String probe = firstAbsentChar(loaded);
            assertNotNull(probe, "在 CJK 常用区间里没找到字体缺的字——子集范围变了，得重新挑一个探测字符");

            IllegalArgumentException boom = assertThrows(IllegalArgumentException.class,
                    () -> PdfFixtures.assertCovers(loaded, "民" + probe + "事"),
                    "覆盖检查必须对缺字抛异常");
            String msg = boom.getMessage();
            assertTrue(msg.contains(String.format("U+%04X", (int) probe.charAt(0))),
                    "报错必须点名缺的是哪个字，实际：" + msg);
            assertTrue(msg.contains("make_test_font.py"),
                    "报错必须说清怎么修（重新生成子集），实际：" + msg);
        }
    }

    @Test
    @DisplayName("字体集（.ttc）被明确拒绝，而不是甩一句 'head table is mandatory'")
    void ttcIsRejectedWithAnActionableMessage() throws IOException {
        Path ttc = pathOfTtc();
        assertTrue(Files.isRegularFile(ttc), "守卫用的字体集必须随仓库提供：" + ttc);

        try (PDDocument doc = new PDDocument()) {
            IllegalArgumentException boom = assertThrows(IllegalArgumentException.class,
                    () -> PdfFixtures.loadCjkFont(doc, ttc),
                    "字体集必须被明确拒绝");
            String msg = boom.getMessage();
            assertTrue(msg.contains(".ttc"), "报错必须说明这是字体集问题，实际：" + msg);
            assertTrue(msg.contains("TrueTypeCollection"),
                    "报错必须给出正确做法（取出集合里的单个字体），实际：" + msg);
            assertTrue(msg.contains("不能直接加载"),
                    "报错必须是自己的解释，而不是把底层异常原样转述，实际：" + msg);
            // 底层异常可以**被引用**（那有助于排障），但不能是全部内容
            assertTrue(msg.length() > 80,
                    "报错只有 " + msg.length() + " 个字，太短，多半只是转述了底层异常：" + msg);
        }
    }

    @Test
    @DisplayName("夹具遇到缺字时立刻失败，而不是产出 .notdef 后继续跑")
    void fixtureFailsLoudlyOnMissingGlyph(@TempDir Path dir) throws IOException {
        Path font = PdfFixtures.cjkFontFile();
        Path out = dir.resolve("missing-glyph.pdf");

        String probe;
        try (PDDocument doc = new PDDocument()) {
            probe = firstAbsentChar(PdfFixtures.loadCjkFont(doc, font));
        }
        assertNotNull(probe, "在 CJK 常用区间里没找到字体缺的字——子集范围变了，得重新挑一个探测字符");

        IllegalArgumentException boom = assertThrows(IllegalArgumentException.class,
                () -> PdfFixtures.writeCjk(out, font, List.of(probe)),
                "夹具必须因为缺字而失败");
        String msg = boom.getMessage();
        assertTrue(msg.contains(String.format("U+%04X", (int) probe.charAt(0))),
                "必须点名缺的是哪个字，实际：" + msg);
        // PDFBox 自己对缺字也抛 IllegalArgumentException（"No glyph for U+xxxx in font ..."），
        // 所以光断言「抛了 IllegalArgumentException」分不出是**我们的**检查在起作用还是
        // PDFBox 在替我们报错。必须断言我们自己的提示词在。
        assertTrue(msg.contains("make_test_font.py"),
                "必须给出自己的修复提示（重新生成子集），而不是让 PDFBox 的异常原样冒出来，实际：" + msg);
    }

    /**
     * 找一个字体里<b>真的没有</b>的字。
     *
     * <p>为什么不写死码位：字体子集是 {@code tools/make_test_font.py} 从**源码里出现过的字**
     * 扫出来生成的，所以只要测试文件里写了某个字（哪怕只是在注释里提一句
     * 「鿕 U+9FD5 不在子集里」），它就会被收进字体，写死的探测码位当场失效。
     * 实测踩过：探测字 U+9FD5 被自己写进源码 → 重新生成后它就在字体里了 →
     * 「缺字必须报错」那条用例变成永远为真。
     *
     * <p>动态挑就自愈了：字体里有什么就跳到下一个，测试永远在找一个真缺的字。
     *
     * <p>判据是 {@code encode} 抛异常——实测 PDFBox 3.0.3 在
     * {@code embedSubset} 两种模式下都对缺字抛 {@code IllegalArgumentException}，
     * 而 {@code hasGlyph} / {@code codeToGID} 对 CJK 一律给 false / 0，不能用。
     */
    private static String firstAbsentChar(PDType0Font font) throws IOException {
        for (int c = 0x4E00; c <= 0x9FFF; c++) {
            try {
                font.encode(String.valueOf((char) c));
            } catch (IllegalArgumentException e) {
                return String.valueOf((char) c);
            }
        }
        return null;
    }

    @Test
    @DisplayName("夹具产出的 PDF 真的自带字体，且中文能原样抽回")
    void writtenPdfCarriesItsFontAndRoundTripsText(@TempDir Path dir) throws IOException {
        Path font = PdfFixtures.cjkFontFile();
        Path pdf = dir.resolve("cjk.pdf");
        List<String> lines = List.of("民事起诉状", "原告：张三", "身份证号：11010519491231002X");

        PdfFixtures.writeCjk(pdf, font, lines);

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            var f = doc.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            assertNotNull(f, "页面里必须有 F1 字体");
            assertNotNull(f.getFontDescriptor(), "字体必须有描述符");
            assertNotNull(f.getFontDescriptor().getFontFile2(),
                    "PDF 里没有嵌入字体文件（FontFile2）——这份 PDF 换个机器打开就是乱码，"
                            + "或者干脆显示成替代字体。夹具用 getFont() 取回的副本编码就会出现这个症状。");
            assertTrue(f.isEmbedded(), "字体必须是嵌入态");

            String extracted = new PDFTextStripper().getText(doc);
            for (String line : lines) {
                assertTrue(extracted.contains(line),
                        "夹具写出的 PDF 里抽不回这一行：" + line + "；实际抽到：" + extracted);
            }
        }
    }

    private static Path pathOfTtc() {
        try {
            return Path.of(PdfFixtures.class.getResource("/fonts/litemask-cjk-test.ttc").toURI());
        } catch (Exception e) {
            throw new IllegalStateException("字体集资源定位失败", e);
        }
    }
}
