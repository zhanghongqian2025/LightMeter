package com.litemask.ofd;

import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OFD 脱敏。
 *
 * <p>断言一律打在<b>输出的原始 XML 字符串</b>上，不经过本项目的解析代码：
 * 「文件里还有没有那个号码」是个字节级问题，用字节级问题该有的方式问。
 * 「改完的文件别人还读不读得动」交给 ofdrw——它不是我们写的，是唯一的独立裁判。
 */
class OfdRedactorTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");

    // ------------------------------------------------------------------ 正文

    @Test
    void bodyValuesAreRedacted() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        String page = OfdFixtures.decode(OfdFixtures.readTextParts(out).get("Doc_0/Pages/Page_0/Content.xml"));
        assertFalse(page.contains(OfdFixtures.PHONE), "正文手机号还在");
        assertFalse(page.contains(OfdFixtures.ID), "正文身份证还在");
        assertFalse(page.contains(OfdFixtures.EMAIL), "正文邮箱还在");
        assertFalse(page.contains(OfdFixtures.CARD), "正文银行卡还在");
        assertTrue(hasToken(page, "手机号"), "没有写出手机号令牌");
        assertTrue(hasToken(page, "身份证"), "没有写出身份证令牌");
        assertTrue(hasToken(page, "邮箱"), "没有写出邮箱令牌");
        assertTrue(hasToken(page, "银行卡号"), "没有写出银行卡令牌");
    }

    /**
     * 号码被拆进同一个 TextObject 的两个 TextCode。
     *
     * <p>这是本测试类里最该有牙齿的一条：单独看任一槽都只有「1390013」「9000」，
     * 任何规则都匹配不上，逐槽跑正则的结果是「零命中、脱敏成功」而号码还在文件里。
     * 独立读取器也证实了这一点——ofdrw 自己抽出来也是两行，不会帮你拼。
     */
    @Test
    void valueSplitAcrossTextCodesInOneObjectIsRedacted() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        String page = OfdFixtures.decode(OfdFixtures.readTextParts(out).get("Doc_0/Pages/Page_0/Content.xml"));
        assertFalse(page.contains(OfdFixtures.PHONE_SPLIT), "跨 TextCode 的号码整体还在");
        assertFalse(page.contains("1390013"), "前半段还在，说明没有按对象拼接");
        assertFalse(page.contains(">9000<"), "后半段还在");
        assertTrue(hasToken(page, "手机号"), "没有写出令牌");
    }

    // ------------------------------------------------------------------ 续接：跨 TextObject

    /**
     * 一个标识符被切在<b>两个紧邻的 TextObject</b> 之间（同一行，前者的右边缘就是后者的左边缘）。
     *
     * <p>不续接时的后果实测过：前半「110105」原样留在交付物里（<b>漏脱</b>），
     * 而后半「19491231002X」会被手机号规则错标成手机号（<b>标签错</b>）——
     * 与 xlsx 侧块 14 修的那个问题同构。
     *
     * <p>续接之后两段<b>各拿各的令牌</b>，文本一个字节都不动：
     * 位置不变（OFD 是绝对定位版式，把两段合并成一个令牌会把版面改坏），
     * 还原时按令牌换回原文，仍然逐字一致。
     */
    @Test
    void valueSplitAcrossAdjacentObjectsIsRedacted() throws Exception {
        Path src = OfdFixtures.layout(tmp(), "紧邻.ofd", List.of(List.of(
                OfdFixtures.textObjectAt(2, 20, 30, 60, "身份证：" + OfdFixtures.ID_HEAD),
                OfdFixtures.textObjectAt(3, 80, 30, 60, OfdFixtures.ID_TAIL))));
        Path target = out(src);
        OfdRedactor.Report report = OfdRedactor.redact(src, target, RULES);

        String page = OfdFixtures.decode(OfdFixtures.readTextParts(target).get("Doc_0/Pages/Page_0/Content.xml"));
        assertFalse(page.contains(OfdFixtures.ID_HEAD), "前半段还在：没有跨对象续接");
        assertFalse(page.contains(OfdFixtures.ID_TAIL), "后半段还在");
        assertTrue(hasToken(page, "身份证"), "没有写出身份证令牌");
        // 前半的令牌写回它自己那个 TextObject —— 位置没变，版面没坏
        assertTrue(Pattern.compile("身份证：<身份证\\d>").matcher(page).find(),
                "前半的令牌没有写回它原来的那个 TextObject");
        assertEquals(2, pageIdCardHits(report), "应当有两处按身份证脱敏，实际："
                + report.hits().stream().map(OfdRedactor.Hit::ruleKey).toList());

        Path restored = out(src, ".restored");
        OfdRedactor.restore(target, restored, report.mapping());
        String back = OfdFixtures.decode(OfdFixtures.readTextParts(restored).get("Doc_0/Pages/Page_0/Content.xml"));
        assertTrue(back.contains(OfdFixtures.ID_HEAD), "还原后前半没了");
        assertTrue(back.contains(OfdFixtures.ID_TAIL), "还原后后半没了");
    }

    /**
     * 左右两栏：同 y、x 相距很远。
     *
     * <p>这是 OFD 侧<b>绝对不能</b>拼的一对——两栏的 y 完全相同，
     * 只看「顺序相邻」会把两栏拼成一条不存在的字符串。
     * 几何判据（水平紧邻）必须挡住它。
     */
    @Test
    void valueInTwoColumnsIsNotContinued() throws Exception {
        Path src = OfdFixtures.layout(tmp(), "两栏.ofd", List.of(List.of(
                OfdFixtures.textObjectAt(2, 20, 30, 40, "身份证：" + OfdFixtures.ID_HEAD),
                OfdFixtures.textObjectAt(3, 120, 30, 40, OfdFixtures.ID_TAIL))));
        Path target = out(src);
        OfdRedactor.Report report = OfdRedactor.redact(src, target, RULES);

        assertEquals(0, pageIdCardHits(report),
                "两栏被拼成了身份证：OFD 是绝对定位版式，跨栏拼接是版面事故");
    }

    /** 同行但中间隔着 30 毫米：那不是「同一段被切开」，那是两段各自独立的内容。 */
    @Test
    void valueAcrossWideGapIsNotContinued() throws Exception {
        Path src = OfdFixtures.layout(tmp(), "宽间隙.ofd", List.of(List.of(
                OfdFixtures.textObjectAt(2, 20, 30, 60, "身份证：" + OfdFixtures.ID_HEAD),
                OfdFixtures.textObjectAt(3, 110, 30, 60, OfdFixtures.ID_TAIL))));
        Path target = out(src);
        OfdRedactor.Report report = OfdRedactor.redact(src, target, RULES);

        assertEquals(0, pageIdCardHits(report), "隔了 30 毫米还续接：紧邻判据失效了");
    }

    /**
     * 跨页：两个相邻页面里位置完全相同的两个对象。
     *
     * <p>坐标一样不代表是同一段文字——它们在<b>不同部件</b>里。
     * 组不能跨部件，否则相邻两页的同位置内容会被当成一行。
     */
    @Test
    void valueSplitAcrossPagesIsNotContinued() throws Exception {
        Path src = OfdFixtures.layout(tmp(), "跨页.ofd", List.of(
                List.of(OfdFixtures.textObjectAt(2, 20, 30, 60, "身份证：" + OfdFixtures.ID_HEAD)),
                List.of(OfdFixtures.textObjectAt(3, 80, 30, 60, OfdFixtures.ID_TAIL))));
        Path target = out(src);
        OfdRedactor.Report report = OfdRedactor.redact(src, target, RULES);

        assertEquals(0, pageIdCardHits(report), "跨页续接了：组不该跨部件");
    }

    /** 拿不到边界就不许合并：宁可漏脱，也不错配。 */
    @Test
    void valueNextToObjectWithoutBoundaryIsNotContinued() throws Exception {
        Path src = OfdFixtures.layout(tmp(), "无边界.ofd", List.of(List.of(
                OfdFixtures.textObjectWithoutBoundary(2, "身份证：" + OfdFixtures.ID_HEAD),
                OfdFixtures.textObjectAt(3, 80, 30, 60, OfdFixtures.ID_TAIL))));
        Path target = out(src);
        OfdRedactor.Report report = OfdRedactor.redact(src, target, RULES);

        assertEquals(0, pageIdCardHits(report), "没有 Boundary 的对象被拿去续接了");
    }

    /**
     * x 方向紧挨着、但<b>不在同一行</b>（y 差 40 毫米）。
     *
     * <p>那是上下两段文字，不是被切开的一段。只看「水平紧邻」会把它拼起来。
     */
    @Test
    void valueOnDifferentLineIsNotContinued() throws Exception {
        Path src = OfdFixtures.layout(tmp(), "不同行.ofd", List.of(List.of(
                OfdFixtures.textObjectAt(2, 20, 30, 60, "身份证：" + OfdFixtures.ID_HEAD),
                OfdFixtures.textObjectAt(3, 80, 70, 60, OfdFixtures.ID_TAIL))));
        Path target = out(src);
        OfdRedactor.Report report = OfdRedactor.redact(src, target, RULES);

        assertEquals(0, pageIdCardHits(report), "上下两行被拼起来了：同行判据失效了");
    }

    /**
     * 拿不到边界，<b>只是不许续接</b>，不是把这个对象整个漏掉。
     *
     * <p>这条守的是「fail-closed 的边界」：几何判据不成立时，
     * 退化成「这个对象自己算一段」，而不是「这个对象不处理」。
     */
    @Test
    void objectWithoutBoundaryIsStillRedacted() throws Exception {
        Path src = OfdFixtures.layout(tmp(), "无边界完值.ofd", List.of(List.of(
                OfdFixtures.textObjectWithoutBoundary(2, "身份证：" + OfdFixtures.ID))));
        Path target = out(src);
        OfdRedactor.Report report = OfdRedactor.redact(src, target, RULES);

        String page = OfdFixtures.decode(OfdFixtures.readTextParts(target).get("Doc_0/Pages/Page_0/Content.xml"));
        assertFalse(page.contains(OfdFixtures.ID), "没有边界的对象整个漏掉了");
        assertEquals(1, pageIdCardHits(report), "应当有一处按身份证脱敏");
    }

    /**
     * 页面部件里的身份证命中数。
     *
     * <p><b>为什么要按部件数</b>：标准夹具的模板部件（Tpls）里本来就放着一个<b>完整</b>的
     * 身份证，它当然会被脱敏、也会算一个 id_card 命中。数「全部 id_card 命中」的话，
     * 那个模板里的命中会混进来，断言就测不出「续接有没有发生」了。
     */
    private static long pageIdCardHits(OfdRedactor.Report report) {
        return report.hits().stream()
                .filter(h -> "id_card".equals(h.ruleKey()) && h.part().startsWith("Doc_0/Pages/"))
                .count();
    }

    /** 没有 TextObject 祖先的 TextCode：规范不该允许，但漏掉它不会有任何报错。 */
    @Test
    void orphanTextCodeIsRedacted() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        String page = OfdFixtures.decode(OfdFixtures.readTextParts(out).get("Doc_0/Pages/Page_0/Content.xml"));
        assertFalse(page.contains(OfdFixtures.ID), "孤儿 TextCode 里的身份证还在");
        assertTrue(hasToken(page, "身份证"), "孤儿 TextCode 里没有写出令牌");
        assertTrue(page.contains("证件："), "命中前缀不该被抹掉，替换只该覆盖敏感值本身");
    }

    // ------------------------------------------------------------------ 不显示的位置

    /** 模板页：屏幕上看不见，但字就在文件里。任何只看正文的实现都会漏掉它。 */
    @Test
    void templateTextIsRedactedEvenThoughNoPageShowsIt() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        Map<String, String> parts = OfdFixtures.readTextParts(out);
        assertTrue(parts.containsKey("Doc_0/Tpls/Tpl_0/Content.xml"), "模板部件消失了");
        assertFalse(OfdFixtures.decode(parts.get("Doc_0/Tpls/Tpl_0/Content.xml")).contains(OfdFixtures.ID_TEMPLATE),
                "模板里的身份证还在");
    }

    @Test
    void annotationTextIsRedacted() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        String annot = OfdFixtures.decode(OfdFixtures.readTextParts(out).get("Doc_0/Annots/Page_0/Annotation.xml"));
        assertFalse(annot.contains(OfdFixtures.EMAIL), "批注里的邮箱还在");
        assertTrue(hasToken(annot, "邮箱"), "批注没有写出令牌");
    }

    @Test
    void keywordAttributeIsRedacted() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        String page = OfdFixtures.decode(OfdFixtures.readTextParts(out).get("Doc_0/Pages/Page_0/Content.xml"));
        // TextCode@Text 是规范里的可选关键字属性：正文改了而它不改，检索仍能搜出原文
        assertFalse(page.contains("Text=\"" + OfdFixtures.EMAIL + "\""), "关键字属性里的邮箱还在");
    }

    // ------------------------------------------------------------------ 文档属性

    @Test
    void docInfoCustomDataIsRedacted() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        String ofd = OfdFixtures.decode(OfdFixtures.readTextParts(out).get("OFD.xml"));
        assertFalse(ofd.contains(OfdFixtures.USCC), "纳税人识别号还在文档属性里");
        assertTrue(hasToken(ofd, "信用代码"), "文档属性没有写出令牌");
    }

    @Test
    void docInfoAuthorIsClearedAndReported() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        OfdRedactor.Report report = OfdRedactor.redact(src, out(src), RULES);

        String ofd = OfdFixtures.decode(OfdFixtures.readTextParts(out(src)).get("OFD.xml"));
        assertFalse(ofd.contains("张洪千"), "作者姓名还在");
        assertTrue(report.clearedFields().stream().anyMatch(f -> f.endsWith("DocInfo/Author")),
                "清空了却没有记进报告： " + report.clearedFields());
    }

    /**
     * {@code DocID} 是文档标识符，一串 18 位十六进制完全可能碰巧通过身份证校验位。
     * 把它当正文脱敏会造成一次莫名其妙的误脱，还会改掉文档标识。
     * 这条测试是为了把这个<b>刻意的不脱</b>钉住——它是决定，不是遗漏。
     */
    @Test
    void docIdIsNotRedactedEvenWhenItPassesIdCardValidation() throws Exception {
        Path src = OfdFixtures.standardWithDocId(tmp(), "带证件号标识.ofd", OfdFixtures.ID);
        Path out = redact(src);

        String ofd = OfdFixtures.decode(OfdFixtures.readTextParts(out).get("OFD.xml"));
        assertTrue(ofd.contains("<ofd:DocID>" + OfdFixtures.ID + "</ofd:DocID>"),
                "DocID 被改了：" + ofd);
    }

    // ------------------------------------------------------------------ 附件

    @Test
    void attachmentEntryIsRenamedAndItsBytesKept() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        List<String> names = OfdFixtures.entryNames(out);
        assertFalse(names.contains("Doc_0/Attachs/" + OfdFixtures.PHONE + "_申请书.pdf"),
                "zip 条目名里还带着手机号");
        String renamed = names.stream()
                .filter(n -> n.startsWith("Doc_0/Attachs/") && n.endsWith("_申请书.pdf"))
                .findFirst().orElseThrow(() -> new AssertionError("附件没被改名，条目=" + names));
        // 改名不是「把附件删掉」：内容必须还在，否则引用会指向空文件
        assertEquals("fake-pdf-bytes", readEntry(out, renamed), "改名后附件内容丢了");
    }

    @Test
    void attachmentReferenceFollowsTheRename() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path out = redact(src);

        Map<String, String> parts = OfdFixtures.readTextParts(out);
        String attachments = OfdFixtures.decode(parts.get("Doc_0/Attachments.xml"));
        assertFalse(attachments.contains(OfdFixtures.PHONE), "附件引用里还带着手机号");
        List<String> names = OfdFixtures.entryNames(out);
        String renamed = names.stream()
                .filter(n -> n.startsWith("Doc_0/Attachs/") && n.endsWith("_申请书.pdf"))
                .findFirst().orElseThrow();
        assertTrue(attachments.contains(renamed),
                "引用没有跟着改名，会指向不存在的附件。引用=" + attachments + " 新名=" + renamed);
        // 附件显示名也是人读的信息，同样要脱
        assertFalse(attachments.contains("\"" + OfdFixtures.PHONE + "的申请书\""), "附件显示名里还带着手机号");
    }

    // ------------------------------------------------------------------ 签章

    @Test
    void signaturePartsAreDroppedAndReferenceRemoved() throws Exception {
        Path src = OfdFixtures.signed(tmp(), "签名.ofd");
        Path out = out(src);
        OfdRedactor.Report report = OfdRedactor.redact(src, out, RULES);

        for (String name : OfdFixtures.entryNames(out)) {
            assertFalse(name.startsWith("Doc_0/Signs/"), "签章部件还在: " + name);
        }
        assertEquals(4, report.signatureEntriesDropped(), "没有如实报告移除了几个签章条目");
        String ofd = OfdFixtures.decode(OfdFixtures.readTextParts(out).get("OFD.xml"));
        assertFalse(ofd.contains("ofd:Signatures"), "签章引用还在，读者会去找一个不存在的文件");
    }

    // ------------------------------------------------------------------ 往返

    /**
     * 往返用独立读取器比。
     *
     * <p>比的是<b>去掉空白后的字符序列</b>，不是逐行相同：号码横跨两个 TextCode 时，
     * 还原后整串会落进第一个槽（见 {@link OfdRedactor} 类注释里的已知限制），
     * 独立读取器输出的行结构因此会变。字符内容必须一模一样，行结构不在承诺范围内——
     * 把这一点写进断言，而不是把断言改松到看不出来。
     */
    @Test
    void restoreReturnsTheOriginalVisibleText() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path redacted = out(src, ".redacted");
        Path restored = out(src, ".restored");

        String before = squeeze(OfdFixtures.extractAll(src));
        // 前置条件：夹具本身能被独立读取器读出原文，否则下面的相等是空转
        assertTrue(before.contains(OfdFixtures.PHONE), "夹具自检失败：原始样本里读不到手机号");

        OfdRedactor.Report report = OfdRedactor.redact(src, redacted, RULES);
        String during = squeeze(OfdFixtures.extractAll(redacted));
        assertNotEquals(before, during, "脱敏后读出来的正文和原文一样，说明根本没改");
        assertFalse(during.contains(OfdFixtures.PHONE), "脱敏后独立读取器仍能读出手机号");
        assertTrue(hasToken(during, "手机号"), "脱敏后独立读取器读不到令牌");

        int n = OfdRedactor.restore(redacted, restored, report.mapping());
        assertTrue(n > 0, "还原报告说一处都没改");
        assertEquals(before, squeeze(OfdFixtures.extractAll(restored)), "还原后的正文与原文不一致");
    }

    @Test
    void restoreBringsBackAttachmentNameAndReferenceTogether() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path redacted = out(src, ".redacted");
        Path restored = out(src, ".restored");

        OfdRedactor.Report report = OfdRedactor.redact(src, redacted, RULES);
        OfdRedactor.restore(redacted, restored, report.mapping());

        List<String> names = OfdFixtures.entryNames(restored);
        assertTrue(names.contains("Doc_0/Attachs/" + OfdFixtures.PHONE + "_申请书.pdf"),
                "条目名没有跟着还原回来: " + names);
        assertEquals("fake-pdf-bytes",
                readEntry(restored, "Doc_0/Attachs/" + OfdFixtures.PHONE + "_申请书.pdf"),
                "还原后附件内容丢了");
        String attachments = OfdFixtures.decode(OfdFixtures.readTextParts(restored).get("Doc_0/Attachments.xml"));
        assertTrue(attachments.contains("/Doc_0/Attachs/" + OfdFixtures.PHONE + "_申请书.pdf"),
                "还原后引用没有指回原名: " + attachments);
    }

    /**
     * DeltaX 是逐字字距表。
     *
     * <p>本实现刻意<b>不动</b>它：一旦按新长度裁剪，还原时就再也回不到原值了
     * （剩下的只是前 N 个值）。留着它，脱敏后数量可能多于字符数，换来的是还原后与原文件一致。
     * 这条测试就是为了防止将来有人「顺手优化」把它裁掉。
     */
    @Test
    void deltaXSurvivesRedactionAndRestore() throws Exception {
        Path src = OfdFixtures.standard(tmp(), "标准.ofd");
        Path redacted = out(src, ".redacted");
        Path restored = out(src, ".restored");
        String part = "Doc_0/Pages/Page_0/Content.xml";

        List<String> originalDelta = deltaXValues(OfdFixtures.readTextParts(src).get(part));
        assertFalse(originalDelta.isEmpty(), "夹具自检失败：样本里没有 DeltaX");

        OfdRedactor.Report report = OfdRedactor.redact(src, redacted, RULES);
        assertEquals(originalDelta, deltaXValues(OfdFixtures.readTextParts(redacted).get(part)),
                "脱敏把 DeltaX 改了");

        OfdRedactor.restore(redacted, restored, report.mapping());
        assertEquals(originalDelta, deltaXValues(OfdFixtures.readTextParts(restored).get(part)),
                "还原后 DeltaX 和原文不一致");
    }

    // ------------------------------------------------------------------ 独立读取器

    @Test
    void redactedFileIsStillReadableByAnIndependentReader() throws Exception {
        Path src = OfdFixtures.signed(tmp(), "签名.ofd");
        Path out = redact(src);

        String text = OfdFixtures.extractAll(out);
        assertTrue(text.contains("<手机号"), "独立读取器读不出令牌: " + text);
        assertFalse(text.contains(OfdFixtures.PHONE), "独立读取器还能读出手机号");
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    void nonOfdZipIsRejected() throws Exception {
        Path dir = tmp();
        Path fake = dir.resolve("其实是zip.ofd");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(fake))) {
            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("hi".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        IOException e = assertThrows(IOException.class,
                () -> OfdRedactor.redact(fake, dir.resolve("out.ofd"), RULES));
        assertTrue(e.getMessage().contains("OFD.xml"),
                "报错没有说清缺什么: " + e.getMessage());
    }

    /** 解析不了就报错，且不产出任何文件——宁可失败，也不能悄悄交付一份没脱干净的文档。 */
    @Test
    void malformedXmlIsRejectedAndNoOutputIsWritten() throws Exception {
        Path dir = tmp();
        Path broken = dir.resolve("坏.ofd");
        Map<String, String> parts = OfdFixtures.standardParts();
        parts.put("Doc_0/Pages/Page_0/Content.xml", "<ofd:Page><unclosed>");
        OfdFixtures.writePackage(broken, parts);

        Path out = dir.resolve("out.ofd");
        assertThrows(IOException.class, () -> OfdRedactor.redact(broken, out, RULES));
        assertFalse(Files.exists(out), "解析失败却还是写出了文件");
    }

    // ------------------------------------------------------------------ 工具

    private Path tmp() throws IOException {
        return Files.createTempDirectory("ofd-test");
    }

    private Path out(Path src) {
        return out(src, "");
    }

    private Path out(Path src, String suffix) {
        String name = src.getFileName().toString();
        String base = name.endsWith(".ofd") ? name.substring(0, name.length() - 4) : name;
        return src.resolveSibling(base + suffix + ".out.ofd");
    }

    private Path redact(Path src) throws IOException {
        Path target = out(src);
        OfdRedactor.redact(src, target, RULES);
        return target;
    }

    private static String readEntry(Path zipFile, String entry) throws IOException {
        try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
            try (var in = zip.getInputStream(zip.getEntry(entry))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static final Pattern DELTA_X = Pattern.compile("DeltaX=\"([^\"]*)\"");

    private static List<String> deltaXValues(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = DELTA_X.matcher(xml);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 令牌编号由分配顺序决定，不能写死；只断言「出现了这个前缀的令牌」。 */
    private static boolean hasToken(String xml, String prefix) {
        return Pattern.compile(prefix + "\\d").matcher(xml).find();
    }

    private static String squeeze(String text) {
        return text.replaceAll("\\s+", "");
    }
}
