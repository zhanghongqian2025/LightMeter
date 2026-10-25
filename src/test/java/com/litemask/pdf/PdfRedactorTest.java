package com.litemask.pdf;

import com.litemask.core.Change;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 脱敏端到端测试。
 *
 * <p>三条主张各有各的验证方式，不能互相顶替：
 * <ul>
 *   <li><b>「原文没了」</b>由 PDFBox 自己的文本引擎抽取原文来验——不复用被测的解码逻辑，
 *       否则解码写错时测试会跟着错。</li>
 *   <li><b>「版面没动」</b>由 PDFBox 自己算出的每个文本片段坐标来验：同一段没被改的文字，
 *       在原件与脱敏件里必须落在同一个坐标上。</li>
 *   <li><b>「没命中的地方一个字没动」</b>由内容流字节比对来验。</li>
 * </ul>
 */
class PdfRedactorTest {

    private static List<Rule> rules;

    @BeforeAll
    static void loadRules() {
        rules = RuleLoader.loadFromClasspath("rules/core-rules.json");
    }

    private static final String ID_CARD = "11010519491231002X";
    private static final String EMAIL = "zhang@example.com";
    private static final String PHONE = "13800138000";
    private static final String IP = "192.168.1.100";

    private static final String SAMPLE =
            "BT\n"
            + "/F1 12 Tf\n"
            + "1 0 0 1 50 760 Tm\n"
            + "(ID: ) Tj\n"
            + "(" + ID_CARD + ") Tj\n"
            + "( belongs to ) Tj\n"
            + "(" + EMAIL + ") Tj\n"
            + "1 0 0 1 50 740 Tm\n"
            + "(Phone " + PHONE + " from ) Tj\n"
            + "(" + IP + " is the gateway) Tj\n"
            + "(Trailing text) Tj\n"
            + "ET\n";

    // ---------------------------------------------------------------- 主干

    @Test
    @DisplayName("四处敏感信息全部被换掉，原文在文本层里再也抽不出来")
    void redactsAllHits(@TempDir Path dir) throws IOException {
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"), SAMPLE);
        Path out = dir.resolve("out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, rules);

        assertEquals(4, report.changeCount(), "四处敏感信息应各产生一条改动");
        assertTrue(report.hasTextLayer());
        assertFalse(report.hasInvisibleText());
        assertEquals(1, report.pageCount());

        String after = PdfFixtures.extractText(out);
        assertFalse(after.contains(ID_CARD), "原文身份证号仍可抽取");
        assertFalse(after.contains(EMAIL), "原文邮箱仍可抽取");
        assertFalse(after.contains(PHONE), "原文手机号仍可抽取");
        assertFalse(after.contains(IP), "原文 IP 仍可抽取");
        assertTrue(after.contains("<ID_1>"), "应写入 PDF 侧 ASCII 令牌，实际：" + after);
        assertTrue(after.contains("<MAIL_1>"));
        assertTrue(after.contains("<PHONE_1>"));
        assertTrue(after.contains("<IP_1>"));
        // 未命中的文字必须原样保留
        assertTrue(after.contains("Trailing text"));
        assertTrue(after.contains("belongs to"));
    }

    @Test
    @DisplayName("同一段没被改动的文字，在原件与脱敏件里落在同一个坐标上")
    void layoutIsPreserved(@TempDir Path dir) throws IOException {
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"), SAMPLE);
        Path out = dir.resolve("out.pdf");
        PdfRedactor.redact(src, out, rules);

        // 逐字形比对。用 PDFTextStripper 按行合并后再比是不行的：令牌会把一行切成几段，
        // 分段边界不同就没法一一对应。落到字形上，「同一串字」在两份文件里的起点必须完全一致。
        for (String needle : List.of("ID: ", " belongs to ", "Phone ", " from ", " is the gateway",
                "Trailing text")) {
            double[] before = PdfFixtures.firstGlyphPosition(src, needle);
            double[] after = PdfFixtures.firstGlyphPosition(out, needle);
            assertNotNull(before, "原件里找不到：" + needle);
            assertNotNull(after, "脱敏件里找不到（被改坏或丢失）：" + needle);
            assertEquals(before[0], after[0], 0.05, "「" + needle + "」的横坐标被改动，版面会错位");
            assertEquals(before[1], after[1], 0.01, "「" + needle + "」的纵坐标被改动");
        }
    }

    @Test
    @DisplayName("未命中文档的内容流一个字节都不动")
    void cleanDocumentUntouched(@TempDir Path dir) throws IOException {
        String clean = "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n(Nothing sensitive here.) Tj\nET\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("clean.pdf"), clean);
        Path out = dir.resolve("clean-out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, rules);

        assertEquals(0, report.changeCount());
        assertEquals(stripTrailingNewline(PdfFixtures.pageContent(src)),
                stripTrailingNewline(PdfFixtures.pageContent(out)),
                "没有命中就不该重写内容流");
    }

    @Test
    @DisplayName("还原后抽出来的文本与原件逐字一致")
    void roundTrip(@TempDir Path dir) throws IOException {
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"), SAMPLE);
        Path red = dir.resolve("red.pdf");
        Path back = dir.resolve("back.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, red, rules);
        PdfRedactor.restore(red, back, report.mapping());

        assertEquals(normalize(PdfFixtures.extractText(src)),
                normalize(PdfFixtures.extractText(back)),
                "还原后的文本必须与原件逐字相同");
    }

    // ---------------------------------------------------------------- 切分与结构

    @Test
    @DisplayName("敏感信息被排版切成多个字符串算子时，仍被识别为一条并只写一个令牌")
    void splitAcrossShowOps(@TempDir Path dir) throws IOException {
        // 一个手机号被拆成 4 个 Tj，中间夹着字距调整，模拟真实排版器的输出
        String content = "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n"
                + "(Tel ) Tj\n(1380) Tj\n[(013) -120 (8000)] TJ\n(done) Tj\nET\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("split.pdf"), content);
        Path out = dir.resolve("split-out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, rules);

        assertEquals(1, report.changeCount(), "跨算子拼接后的手机号应当只算一次命中");
        // 逐字比对整段抽取文本，而不是只查「完整的手机号还在不在」。
        // 只查完整串的话，手机号被切成 (1380) + (013) + (8000) 三段时，漏掉任何一段都不会让
        // 「不含完整手机号」变红——而那恰恰是最难发现的静默泄露。
        assertEquals("Tel <PHONE_1> done", squeeze(PdfFixtures.extractText(out)),
                "脱敏后的文本必须逐字就是「Tel <PHONE_1> done」，多出一个数字都是泄露");
        assertEquals(1, countOccurrences(PdfFixtures.extractText(out), "<PHONE_1>"), "令牌不得重复绘制");
    }

    @Test
    @DisplayName("跨算子续段被删后必须补回位移，后面的文字仍落在原来的坐标上")
    void splitAcrossShowOpsKeepsLayout(@TempDir Path dir) throws IOException {
        String content = "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n"
                + "(Tel ) Tj\n(1380) Tj\n[(013) -120 (8000)] TJ\n(done) Tj\nET\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("split.pdf"), content);
        Path out = dir.resolve("split-out.pdf");
        PdfRedactor.redact(src, out, rules);

        // 续段删掉却不补位移的话，后面的字会整体前移，落点与原件不同。
        // 而且在此之前，先落地的那段数字还会压在令牌上，抽取出来是交错的乱码。
        for (String needle : List.of("Tel", "done")) {
            double[] before = PdfFixtures.firstGlyphPosition(src, needle);
            double[] after = PdfFixtures.firstGlyphPosition(out, needle);
            assertNotNull(before, "原件里找不到：" + needle);
            assertNotNull(after, "脱敏件里找不到：" + needle);
            assertEquals(before[0], after[0], 0.05, "「" + needle + "」横坐标被改动，版面会错位");
        }
    }

    @Test
    @DisplayName("跨算子命中的往返：还原后文本与版面都与原件一致")
    void splitAcrossShowOpsRoundTrip(@TempDir Path dir) throws IOException {
        String content = "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n"
                + "(Tel ) Tj\n(1380) Tj\n[(013) -120 (8000)] TJ\n(done) Tj\nET\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("split.pdf"), content);
        Path red = dir.resolve("red.pdf");
        Path back = dir.resolve("back.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, red, rules);
        PdfRedactor.restore(red, back, report.mapping());

        assertEquals(normalize(PdfFixtures.extractText(src)),
                normalize(PdfFixtures.extractText(back)), "还原后的文本必须与原件逐字相同");
        // 版面也要回来：删字符的后续算子里留着各自的补偿，还原时令牌那边必须把它们的和
        // 补上。两边都补、或者两边都不补，都会让「done」跑偏。
        double[] before = PdfFixtures.firstGlyphPosition(src, "done");
        double[] after = PdfFixtures.firstGlyphPosition(back, "done");
        assertEquals(before[0], after[0], 0.05, "还原后「done」的横坐标没回到原位");
    }

    /**
     * 令牌必须塞进它顶替的那段文字里，不能压到后面的字。
     *
     * <p>判据是「同一行的字形横坐标沿流顺序单调不减」：不压就单调，
     * 压上去就会出现坐标倒退。这个判据只依赖 PDFBox 自己算的坐标（外部标尺），
     * 不用我们自己的宽度函数，所以「压缩算错了」时它不会跟着错。
     *
     * <p>夹具里 {@code Tz 45} 把手机号那段压窄到 33pt，而 {@code <PHONE_1>} 不压要 70pt——
     * 这正是真实场景：两三个汉字的人名槽位撑不起一个 ASCII 令牌。
     */
    @Test
    @DisplayName("窄槽位里的令牌被压到恰好放下，不压到后面的字上")
    void tokenIsCondensedToFitItsSlot(@TempDir Path dir) throws IOException {
        String content = "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n"
                + "(A:) Tj\n45 Tz\n(" + PHONE + ") Tj\n100 Tz\n(B) Tj\nET\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("narrow.pdf"), content);
        Path out = dir.resolve("narrow-out.pdf");

        PdfRedactor.redact(src, out, rules);

        List<PdfFixtures.Box> boxes = PdfFixtures.glyphBoxes(out);
        StringBuilder text = new StringBuilder();
        for (PdfFixtures.Box b : boxes) {
            text.append(b.text());
        }
        assertTrue(text.toString().contains("<PHONE_1>"),
                "手机号应被换成 PDF 侧 ASCII 令牌，实际抽到：" + text);
        for (int i = 1; i < boxes.size(); i++) {
            double prevEnd = boxes.get(i - 1).end();
            double curX = boxes.get(i).x();
            assertTrue(curX >= prevEnd - 0.01,
                    "第 " + i + " 个字形（'" + boxes.get(i).text() + "'）落在前一个字形"
                            + "（'" + boxes.get(i - 1).text() + "'）的里面，说明令牌压到了它："
                            + text + "；x " + prevEnd + " → " + curX);
        }
    }

    /**
     * 原件里 {@code Tz} 不等于 100 的文档，往返后必须回到原坐标。
     *
     * <p>这条测的是本次挖出的两个潜缺陷：还原时只写回 {@code Tf} 不写回 {@code Tz}，
     * 以及把令牌那个 TJ 调整数原样搬到另一种水平缩放下执行。两个都会让原文按错误的宽度画回去，
     * 后面整段偏出几个点——而**往返文本仍然逐字一致**，只有坐标能看出来。
     */
    @Test
    @DisplayName("原件 Tz 不为 100 时，往返的文本与坐标都要回到原样")
    void nonDefaultTzRoundTrips(@TempDir Path dir) throws IOException {
        String content = "BT\n/F1 12 Tf\n80 Tz\n1 0 0 1 50 700 Tm\n"
                + "(Tel ) Tj\n(" + PHONE.substring(0, 4) + ") Tj\n"
                + "[(" + PHONE.substring(4, 7) + ") -120 (" + PHONE.substring(7) + ")] TJ\n"
                + "(done) Tj\nET\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("tz.pdf"), content);
        Path red = dir.resolve("tz-red.pdf");
        Path back = dir.resolve("tz-back.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, red, rules);
        PdfRedactor.restore(red, back, report.mapping());

        assertEquals(normalize(PdfFixtures.extractText(src)),
                normalize(PdfFixtures.extractText(back)),
                "往返文本必须与原件逐字相同");
        for (String needle : List.of("Tel", "done")) {
            double[] before = PdfFixtures.firstGlyphPosition(src, needle);
            double[] after = PdfFixtures.firstGlyphPosition(back, needle);
            assertNotNull(before, "原件里找不到：" + needle);
            assertNotNull(after, "还原件里找不到：" + needle);
            assertEquals(before[0], after[0], 0.05, "「" + needle + "」的横坐标没回到原位");
        }
    }

    @Test
    @DisplayName("NER 在 PDF 侧用短前缀 PER/ORG——长前缀会把版面撑爆")
    void nerUsesShortAsciiPrefix() {
        // <NER_ORGANIZATION_1> 是 19 个字符，顶替的常常只有两三个汉字。
        // 实测渲染：长前缀下一行里的四个令牌会叠成一团不可读的东西。
        assertEquals("<PER_1>", PdfTokens.asciiToken(
                new Change(PHONE, "<人名1>", 0, PHONE.length(), "ner_person")));
        assertEquals("<ORG_1>", PdfTokens.asciiToken(
                new Change(PHONE, "<机构名1>", 0, PHONE.length(), "ner_organization")));
    }

    @Test
    @DisplayName("表单 XObject 里的文字同样被处理，且两个内容流都被回写")
    void formXObjectIsRedacted(@TempDir Path dir) throws IOException {
        Path src = PdfFixtures.writeFormWithSensitiveForm(dir.resolve("form.pdf"));
        Path out = dir.resolve("form-out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, rules);

        assertEquals(1, report.changeCount(), "表单里的手机号应被命中");
        assertEquals(2, report.unitCount(), "页面内容流 + 表单 XObject，两条都要处理");
        String after = PdfFixtures.extractText(out);
        assertFalse(after.contains(PHONE), "表单内的原文仍可抽取：" + after);
        assertTrue(after.contains("<PHONE_1>"));
    }

    @Test
    @DisplayName("不可见文本（Tr=3，扫描件上的 OCR 层）同样被脱敏，并被单独报出来")
    void invisibleTextLayer(@TempDir Path dir) throws IOException {
        String content = "BT\n/F1 12 Tf\n3 Tr\n1 0 0 1 50 700 Tm\n(" + PHONE + ") Tj\nET\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("invisible.pdf"), content);
        Path out = dir.resolve("invisible-out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, rules);

        assertTrue(report.hasInvisibleText(), "不可见文本量必须被统计出来");
        assertEquals(PHONE.length(), report.invisibleChars());
        assertEquals(1, report.changeCount());
        String after = PdfFixtures.extractText(out);
        assertFalse(after.contains(PHONE), "隐藏 OCR 层里的原文仍可抽取——这是最典型的静默泄露");
    }

    @Test
    @DisplayName("零命中但没有文本层时，明确报出「无文本层」，不把「没得脱」说成「已脱干净」")
    void noTextLayerIsReported(@TempDir Path dir) throws IOException {
        String content = "q 100 0 0 100 50 600 cm /Im0 Do Q\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("scan.pdf"), content);
        Path out = dir.resolve("scan-out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, rules);

        assertEquals(0, report.changeCount());
        assertFalse(report.hasTextLayer(),
                "扫描件没有文本层，必须报出来，否则「零命中」会被读成「文档干净」");
    }

    @Test
    @DisplayName("敏感信息被排版切成同一个 TJ 里的多个字符串时，只写一个令牌")
    void splitWithinOneArray(@TempDir Path dir) throws IOException {
        // 与 splitAcrossShowOps 不同：这里的两段在**同一个** TJ 算子内。
        // 两条路径都会走到回写器，但漏判的位置不同——这里要防的是
        // 「每个字符串元素都写一次令牌」，结果是页面上出现两个令牌、还原时被换回两遍。
        String content = "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n"
                + "[ (Tel 1380013) -60 (8000 done) ] TJ\nET\n";
        Path src = PdfFixtures.writeLatin(dir.resolve("split1.pdf"), content);
        Path out = dir.resolve("split1-out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, rules);

        assertEquals(1, report.changeCount(), "同一个 TJ 里的两段应当只算一次命中");
        String after = PdfFixtures.extractText(out);
        assertEquals(1, countOccurrences(after, "<PHONE_1>"),
                "令牌必须只画一遍，画两遍的话还原会被换回两次：" + after);
        assertTrue(after.contains("done"), "同算子内的未命中文字被吞掉了：" + after);
    }

    @Test
    @DisplayName("还原后报告出的处数与实际还原的一致")
    void restoreReportsWhatItDid(@TempDir Path dir) throws IOException {
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"), SAMPLE);
        Path red = dir.resolve("red.pdf");
        Path back = dir.resolve("back.pdf");

        PdfRedactor.Report redacted = PdfRedactor.redact(src, red, rules);
        PdfRedactor.Report restored = PdfRedactor.restore(red, back, redacted.mapping());

        // 报告「还原 0 处」而文件里已经换回去了，用户就没法判断该不该信这个工具
        assertEquals(redacted.changeCount(), restored.changeCount(),
                "报告的还原处数必须与实际替换的令牌数一致");
        assertTrue(restored.changeCount() > 0);
    }

    // ---------------------------------------------------------------- 中文字形

    /**
     * 中文字形路径。
     *
     * <p>PDF 的字符串是**字体编码**而不是 Unicode，所以中文这一路和拉丁文完全不同：
     * 令牌画的是 ASCII（配标准 14 字体，任何阅读器都自带），而**还原必须换回原字体**——
     * 用令牌字体去编码「张三」，Helvetica 里根本没有这两个字形，结果是一份看着正常、
     * 打开却缺字的文档。这条断言就是为了守住那个「换回原字体」。
     */
    @Test
    @DisplayName("中文 PDF：脱敏后原文消失、版面不动，还原后中文原样回来")
    void chineseRoundTrip(@TempDir Path dir) throws IOException {
        Path font = PdfFixtures.cjkFontFile();
        String[] lines = {
                "民事起诉状",
                "原告：张三，男，1985 年 3 月 12 日出生，汉族。",
                "身份证号：" + ID_CARD,
                "联系电话：" + PHONE + "，邮箱 " + EMAIL,
                "此致",
                "北京市朝阳区人民法院",
        };
        Path src = PdfFixtures.writeCjk(dir.resolve("cjk.pdf"), font, List.of(lines));
        Path red = dir.resolve("cjk-red.pdf");
        Path back = dir.resolve("cjk-back.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, red, rules);

        assertEquals(3, report.changeCount(), "身份证、邮箱、手机号三处");
        String after = PdfFixtures.extractText(red);
        assertFalse(after.contains(ID_CARD), "中文页里的身份证仍可抽取：" + after);
        assertFalse(after.contains(EMAIL), "中文页里的邮箱仍可抽取：" + after);
        assertFalse(after.contains(PHONE), "中文页里的手机号仍可抽取：" + after);
        assertTrue(after.contains("<ID_1>"), "应写入 ASCII 令牌：" + after);
        assertTrue(after.contains("原告"), "未命中的中文必须原样保留：" + after);
        assertTrue(after.contains("北京市朝阳区人民法院"), "末行丢失：" + after);

        // 版面：令牌是 ASCII，比原文窄，补偿必须把后面的中文推回原位
        for (String needle : List.of("此致", "北京市朝阳区人民法院")) {
            double[] before = PdfFixtures.firstGlyphPosition(src, needle);
            double[] afterPos = PdfFixtures.firstGlyphPosition(red, needle);
            assertNotNull(before, "原件里找不到：" + needle);
            assertNotNull(afterPos, "脱敏件里找不到：" + needle);
            assertEquals(before[0], afterPos[0], 0.05, "「" + needle + "」横坐标被改动");
        }

        // 还原：中文必须用**原字体**写回，不能拿令牌字体去编码
        PdfRedactor.restore(red, back, report.mapping());
        String restored = PdfFixtures.extractText(back);
        for (String line : lines) {
            assertTrue(restored.contains(line), "还原后这一行没能原样回来：" + line
                    + "；实际：" + restored);
        }
        assertEquals(squeeze(PdfFixtures.extractText(src)), squeeze(restored),
                "往返后整份文本必须与原件一致");
    }

    /**
     * 中文解码必须**在有限时间内结束**。
     *
     * <p>这条是看门狗，不是功能断言。原因是缺陷的形态：PDFBox 的
     * {@code PDType0Font.readCode} 在输入耗尽时返回 0 而不是 -1，于是按「码位是否为负」
     * 判断终止的循环永不退出，{@code StringBuilder} 一直涨到 OOM。
     *
     * <p>不加看门狗的话，这类回归只能等进程被 OOM 杀掉才暴露——那既慢，又拿不到
     * 「哪一条断言不成立」的证据。在守护线程里跑，超时就明确失败。
     */
    @Test
    @DisplayName("中文 PDF 的脱敏在有限时间内结束（解码循环不会空转）")
    void chineseRedactionTerminates(@TempDir Path dir) throws Exception {
        Path font = PdfFixtures.cjkFontFile();
        Path src = PdfFixtures.writeCjk(dir.resolve("cjk.pdf"), font,
                List.of("民事起诉状", "身份证号：" + ID_CARD, "联系电话：" + PHONE));
        Path out = dir.resolve("cjk-out.pdf");
        long start = System.currentTimeMillis();
        AtomicReference<Throwable> boom = new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger(-1);
        Thread worker = new Thread(() -> {
            try {
                changes.set(PdfRedactor.redact(src, out, rules).changeCount());
            } catch (Throwable t) {
                boom.set(t);
            }
        }, "cjk-redact");
        worker.setDaemon(true);
        worker.start();
        worker.join(60_000);
        if (worker.isAlive()) {
            // 别 join，直接断言失败：线程会自己被守护线程机制带走
            assertTrue(false, "中文 PDF 脱敏 60 秒仍未结束——解码循环在输入耗尽后没有停下来");
        }
        if (boom.get() != null) {
            throw new AssertionError("中文 PDF 脱敏抛异常", boom.get());
        }
        assertEquals(2, changes.get(), "身份证与手机号两处，耗时 "
                + (System.currentTimeMillis() - start) + "ms");
    }

    // 原来这里有一个 cjkFont()：从系统字体列表里挑一个，挑不到就 Assumptions 跳过。
    // 那个「跳过」是个长期静默的覆盖洞——中文 PDF 这条路径在很多机器上根本没跑过，
    // 而全量单测对此完全无感。Windows 上更糟：唯一候选 msyh.ttc 是字体集，
    // PDFBox 读不了，于是那条测试是**报错**而不是跳过。
    // 现在统一用仓库自带的字体子集（见 PdfFixtures.cjkFontFile()），没有「跳过」这条路。

    // ---------------------------------------------------------------- 元数据与元数据泄露

    @Test
    @DisplayName("元数据、批注作者、内嵌附件、JavaScript、表单值被清掉并逐条记账")
    void metadataIsScrubbed(@TempDir Path dir) throws IOException {
        Path src = PdfFixtures.writeWithHiddenData(dir.resolve("meta.pdf"));
        Path out = dir.resolve("meta-out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, rules);

        assertFalse(report.removedMetadata().isEmpty(), "必须报出清理了哪些东西");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            var info = doc.getDocumentInformation();
            assertTrue(isBlank(info.getAuthor()), "作者名残留：" + info.getAuthor());
            assertTrue(isBlank(info.getCreator()), "创建软件残留：" + info.getCreator());
            assertTrue(isBlank(info.getTitle()));
            assertEquals(0, info.getMetadataKeys().size(), "自定义元数据键位必须一并清掉：" + info.getMetadataKeys());
            var catalog = doc.getDocumentCatalog().getCOSObject();
            assertEquals(null, catalog.getItem(org.apache.pdfbox.cos.COSName.METADATA), "XMP 残留");
            assertEquals(null, catalog.getItem(org.apache.pdfbox.cos.COSName.OPEN_ACTION), "OpenAction 残留");
            assertEquals(null, catalog.getItem(org.apache.pdfbox.cos.COSName.getPDFName("AcroForm")), "表单残留");
            var names = catalog.getDictionaryObject(org.apache.pdfbox.cos.COSName.NAMES);
            if (names instanceof org.apache.pdfbox.cos.COSDictionary d) {
                assertEquals(null, d.getItem(org.apache.pdfbox.cos.COSName.getPDFName("EmbeddedFiles")),
                        "内嵌附件残留");
                assertEquals(null, d.getItem(org.apache.pdfbox.cos.COSName.getPDFName("JavaScript")),
                        "JavaScript 残留");
            }
            // 批注作者名清空、正文脱敏
            org.apache.pdfbox.cos.COSArray annots = (org.apache.pdfbox.cos.COSArray)
                    doc.getPage(0).getCOSObject()
                            .getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("Annots"));
            assertEquals(1, annots.size());
            var annot = ((org.apache.pdfbox.cos.COSDictionary) annots.getObject(0));
            assertTrue(isBlank(((org.apache.pdfbox.cos.COSString) annot.getItem(org.apache.pdfbox.cos.COSName.T)).getString()),
                    "批注作者名残留");
            assertFalse(annot.getItem(org.apache.pdfbox.cos.COSName.getPDFName("Contents")).toString()
                    .contains(PHONE), "批注正文里的手机号未脱敏");
        }
    }

    @Test
    @DisplayName("增量保存留下的旧版本原文不会跟着复制到输出文件")
    void incrementalHistoryIsDropped(@TempDir Path dir) throws IOException {
        Path src = PdfFixtures.writeLatin(dir.resolve("inc.pdf"), SAMPLE);
        Path mid = dir.resolve("mid.pdf");
        PdfRedactor.redact(src, mid, rules);

        // 在已脱敏文件上做一次增量追加，把旧内容又写回去（模拟被删掉的修订历史）
        Path out = dir.resolve("inc-out.pdf");
        try (PDDocument doc = Loader.loadPDF(mid.toFile())) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            try (java.io.OutputStream os = Files.newOutputStream(out)) {
                doc.saveIncremental(os);
            }
        }

        byte[] bytes = Files.readAllBytes(out);
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("/Prev"), "本测试的前提：增量文件确实带着 /Prev");
        // 用本工具重新处理，输出必须是整体重写而不是增量
        Path finalOut = dir.resolve("final.pdf");
        PdfRedactor.redact(out, finalOut, rules);
        byte[] finalBytes = Files.readAllBytes(finalOut);
        String finalRaw = new String(finalBytes, StandardCharsets.ISO_8859_1);
        assertFalse(finalRaw.contains("/Prev"), "整体重写后不应残留 /Prev 旧版本链");
    }

    // ---------------------------------------------------------------- 拒绝处理

    @Test
    @DisplayName("需要口令的 PDF 一律拒绝处理，而不是「处理了但不知道处理成什么样」")
    void encryptedIsRejected(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("enc.pdf");
        try (PDDocument doc = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            page.setContents(new org.apache.pdfbox.pdmodel.common.PDStream(doc));
            doc.addPage(page);
            // 用户口令必须是真口令：否则 PDFBox 能空口令解开，我们就碰不到「打不开」这条路径
            doc.protect(new StandardProtectionPolicy("owner", "s3cret",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            doc.save(src.toFile());
        }
        IOException e = assertThrows(IOException.class,
                () -> PdfRedactor.redact(src, dir.resolve("enc-out.pdf"), rules));
        assertTrue(e.getMessage().contains("加密"), "报错要说清是加密问题：" + e.getMessage());
    }

    @Test
    @DisplayName("坏掉的内容流不猜、不糊，直接拒绝")
    void malformedContentIsRejected(@TempDir Path dir) throws IOException {
        Path src = PdfFixtures.writeLatin(dir.resolve("bad.pdf"),
                "BT /F1 12 Tf (unterminated string Tj ET\n");
        IOException e = assertThrows(IOException.class,
                () -> PdfRedactor.redact(src, dir.resolve("bad-out.pdf"), rules));
        assertTrue(e.getMessage().contains("畸形"), "应报内容流畸形：" + e.getMessage());
    }

    // ---------------------------------------------------------------- 工具

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").trim();
    }

    /**
     * 把抽取文本里的空白压成单个空格。
     *
     * <p>PDFTextStripper 会按字形之间的几何间隙插空格，那是它自己的分段行为、不是文件里的内容。
     * 比对时要把它排除掉，否则「令牌比原文宽/窄导致间隙跨过阈值」就会误报成版面问题；
     * 但排除的只是空白的多寡，**任何多出来的非空白字符照样会让比对变红**。
     */
    private static String squeeze(String s) {
        return normalize(s).replaceAll("\\s+", " ");
    }

    private static String stripTrailingNewline(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1).strip();
    }

}
