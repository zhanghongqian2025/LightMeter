package com.litemask.pdf;

import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 侧的跨块续接：标识符被<b>文本块边界</b>切断时的脱敏。
 *
 * <p><b>为什么 PDF 侧要有这组用例</b>：块 13 把跨单元续接做进了 OOXML 三件套，
 * 并明确「PDF 明确不开这个开关」。理由当时是「PDF 的单元口径与全文对不上」。
 * 那个理由后来被实测推翻了一半：口径可以对齐（两边都用同一个块切分），
 * 但<b>对齐口径 ≠ 可以安全续接</b>——
 * PDF 里相邻的两个块可能是同一页<b>两栏</b>的尾巴与开头（空间相邻、语义无关），
 * 而 OOXML 里相邻的段落是<b>顺序</b>相邻（上一句接着下一句）。
 *
 * <p>实测（收尾链前用探针跑过）：身份证被切成 14 + 3 时，
 * PDF 侧是 <b>0 处命中、整张身份证原样留在交付物里</b>——那是泄漏，不是「标签不符」。
 * 本组用例守住修好之后的行为，并且把「不续接」的那些情形也钉住
 * （跨页不续接、无校验位的规则不续接）——<b>钉住「不续接」同样重要</b>：
 * 它是「我们有意不冒这个险」的凭据。
 */
class PdfCrossUnitTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");

    /** 校验位正确的身份证（GB 11643）。 */
    private static final String ID = "11010519491231002X";

    /** 同一页里的两个文本块：各自一次 Tm 定位 ⇒ 内容流里是两个块。 */
    private static String twoBlocks(String first, String second) {
        return "BT\n"
                + "/F1 12 Tf\n"
                + "1 0 0 1 50 760 Tm\n"
                + "(" + first + ") Tj\n"
                + "1 0 0 1 50 740 Tm\n"
                + "(" + second + ") Tj\n"
                + "ET\n";
    }

    /**
     * 一页里<b>只有一个</b>文本块。
     *
     * <p>「跨内容流不续接」那条用例必须用这个形状：两个碎片要在内容流里
     * <b>彼此相邻</b>（第一页的最后一个块紧接第二页的第一个块），
     * 唯一的阻隔是「它们分属两个内容流」。
     * 若中间还夹着别的块，续接本来就不会发生（续接只接相邻单元），
     * 那条用例就<b>测不到分组</b>——它会一直是绿的。
     */
    private static String oneBlock(String text) {
        return "BT\n"
                + "/F1 12 Tf\n"
                + "1 0 0 1 50 760 Tm\n"
                + "(" + text + ") Tj\n"
                + "ET\n";
    }

    @Test
    @DisplayName("PDF：身份证被文本块切断时，两段碎片都按身份证脱敏（不再整张留着）")
    void splitIdCardIsRedacted() throws Exception {
        Path dir = PdfFixtures.tempDir();
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"),
                twoBlocks("11010519491231", "002X"));
        Path out = dir.resolve("out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, RULES);

        String after = PdfFixtures.extractText(out);
        assertFalse(after.contains("11010519491231"),
                "第一段碎片还在交付物里（整张身份证原样留着）：" + after);
        assertFalse(after.contains("002X"), "第二段碎片还在交付物里：" + after);
        assertEquals(2, report.changeCount(), "两段碎片应当各产生一条改动，实际改动数："
                + report.changeCount());

        // 类型必须是身份证：令牌是 ASCII 的 <ID_n>（PDF 侧不用中文令牌）
        Map<String, String> mapping = report.mapping();
        assertEquals(2, mapping.size(), "映射里应当有两段碎片，实际：" + mapping);
        assertTrue(mapping.containsValue("11010519491231"),
                "映射里没有第一段碎片（说明它没被当成敏感值）：" + mapping);
        assertTrue(mapping.containsValue("002X"),
                "映射里没有第二段碎片（说明它没被当成敏感值）：" + mapping);
        for (String token : mapping.keySet()) {
            assertTrue(token.startsWith("<ID_"),
                    "碎片的令牌类型应当是身份证（<ID_n>），实际：" + token);
        }
    }

    @Test
    @DisplayName("PDF：6+12 切法不再被错标成手机号")
    void splitIdCardIsNotLabelledAsPhone() throws Exception {
        Path dir = PdfFixtures.tempDir();
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"),
                twoBlocks("110105", "19491231002X"));
        Path out = dir.resolve("out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, RULES);

        String after = PdfFixtures.extractText(out);
        assertFalse(after.contains("110105"), "区划码还在交付物里：" + after);
        assertFalse(after.contains("19491231002"), "后半段还在交付物里：" + after);
        for (String token : report.mapping().keySet()) {
            assertFalse(token.contains("PHONE"),
                    "碎片被错标成手机号了（那是块 13 之前 PDF 侧的真实行为）：" + token);
        }
    }

    @Test
    @DisplayName("PDF：还原逐字一致（碎片也能还原回原样）")
    void restoresExactly() throws Exception {
        Path dir = PdfFixtures.tempDir();
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"),
                twoBlocks("11010519491231", "002X"));
        Path out = dir.resolve("out.pdf");
        Path restored = dir.resolve("restored.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, RULES);
        PdfRedactor.restore(out, restored, report.mapping());

        String back = PdfFixtures.extractText(restored);
        assertTrue(back.contains("11010519491231"), "还原后第一段不在：" + back);
        assertTrue(back.contains("002X"), "还原后第二段不在：" + back);
    }

    @Test
    @DisplayName("PDF：跨内容流（两页）不续接——两页尾巴与开头多半是两件不相干的事")
    void doesNotContinueAcrossContentStreams() throws Exception {
        Path dir = PdfFixtures.tempDir();
        // 第一页放前 14 位，第二页放后 3 位：这是「跨流」的情形。
        // 每页<b>只有一个</b>块 ⇒ 两页的块在各自内容流里彼此相邻，
        // 唯一的阻隔是「分属两个内容流」。若在中间夹别的块，
        // 续接本来就不发生（只接相邻单元），这条用例会一直绿、测不到分组。
        Path src = PdfFixtures.writeLatinPages(dir.resolve("in.pdf"),
                List.of(oneBlock("11010519491231"),
                        oneBlock("002X")));
        Path out = dir.resolve("out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, RULES);

        // 断言的是「没有把两页的碎片认成一个值」——而不是「值还在」（那是已知缺口）
        Map<String, String> mapping = report.mapping();
        assertFalse(mapping.containsValue("11010519491231"),
                "跨内容流不该续接（两页的尾巴与开头多半不相干）：" + mapping);
        assertFalse(mapping.containsValue("002X"),
                "跨内容流不该续接：" + mapping);
    }

    @Test
    @DisplayName("PDF：手机号被切断不续接（它没有校验位，续接等于把两栏的数字拼成假手机号）")
    void phoneWithoutCheckDigitIsNotContinued() throws Exception {
        Path dir = PdfFixtures.tempDir();
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"),
                twoBlocks("1380013800", "0"));
        Path out = dir.resolve("out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, RULES);

        Map<String, String> mapping = report.mapping();
        assertFalse(mapping.containsValue("1380013800"),
                "无校验位的规则在 PDF 侧不参与续接：" + mapping);
        assertFalse(mapping.containsValue("0"),
                "无校验位的规则在 PDF 侧不参与续接：" + mapping);
    }

    @Test
    @DisplayName("PDF：还原后的内容流里，算子必须仍然以空白或分隔符结束（不能出现「Tz100」这种粘连算子）")
    void restoredContentStreamKeepsOperatorsWellFormed() throws Exception {
        // 这条盯的是**还原**那一步，不是脱敏。
        //
        // 背景（2026-10-20 实测）：还原时要把「切回原缩放」写回去（<原 Tz> Tz），
        // 而这段是**紧跟在前面那个算子后面**的。前面若是「68.2317 Tz」，
        // 两者一粘就成了「68.2317 Tz100 Tz」——
        // 「Tz100」是个谁也不认识的算子，读者会忽略它，
        // 原文于是按 68% 的宽度画回去（版面歪了）。
        // 我们自己的扫描器也读不懂它，于是版面自检直接崩掉，
        // 这个缺陷就这样藏了很久。
        //
        // 触发的条件是「令牌比原文宽」（于是令牌被横向压缩过，Tz ≠ 100）：
        // 身份证被切成 14 + 3 时，第二段「002X」只有 3 个字符，
        // 而令牌「<ID_2>」有 6 个字符 → 压缩 → 命中。
        Path dir = PdfFixtures.tempDir();
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"),
                twoBlocks("11010519491231", "002X"));
        Path out = dir.resolve("out.pdf");
        Path restored = dir.resolve("restored.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, RULES);
        PdfRedactor.Report restoredReport = PdfRedactor.restore(out, restored, report.mapping());

        List<String> bad = PdfFixtures.malformedOperators(PdfFixtures.readFirstPageContent(restored));
        assertTrue(bad.isEmpty(),
                "还原后的内容流里出现了没被正确终止的算子：" + bad
                        + "\n  内容流：" + PdfFixtures.readFirstPageContent(restored));

        // 版面自检必须**跑完**而且**没有差异**。
        // 自检一崩，它报的是「无法确认版面是否保持原样」——
        // 那是「没查」，不是「查过没问题」，两者不能混为一谈。
        assertTrue(restoredReport.warnings().isEmpty(),
                "还原不该有版面告警（版面自检必须跑完且无差异），实际告警："
                        + restoredReport.warnings());
    }

    @Test
    @DisplayName("PDF：完整身份证（没被切断）照常脱敏，续接不改变既有行为")
    void wholeIdCardStillWorks() throws Exception {
        Path dir = PdfFixtures.tempDir();
        Path src = PdfFixtures.writeLatin(dir.resolve("in.pdf"), twoBlocks(ID, "plain text"));
        Path out = dir.resolve("out.pdf");

        PdfRedactor.Report report = PdfRedactor.redact(src, out, RULES);

        String after = PdfFixtures.extractText(out);
        assertFalse(after.contains(ID), "完整身份证还在交付物里：" + after);
        assertEquals(1, report.changeCount(), "只该有一处改动");
        assertNotNull(report.mapping().get("<ID_1>"), "令牌应当是 <ID_1>，实际：" + report.mapping());
    }
}
