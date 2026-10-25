package com.litemask.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版面位移模拟器与版面比较器。
 *
 * <p>这个类存在的原因是「版面有没有被改坏」必须有可断言的定义，而不是靠看截图。
 * 它在 {@link PdfRedactor} 里是**产品级后置校验**，不是测试脚手架——
 * 所以这里除了验它算得对，还要验它**能报出差异**：
 * 一个永远返回「没问题」的比较器和一个不存在的比较器，对使用者是同一种东西。
 *
 * <p>期望值一律按 PDF 32000-1 9.4.4 的公式**在测试里手推**，不调用被测的宽度函数。
 * 两边共用同一个 {@code stringAdvance} 的话，算错时它们会一起错，
 * 于是测试变成自证——这是探针式测试最常见的自欺。
 */
class PdfLayoutSimulatorTest {

    /** 与 {@code writeLatin} 写入的字体一致：标准 14 Helvetica。 */
    private static org.apache.pdfbox.pdmodel.font.PDFont helvetica() throws Exception {
        return new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
    }

    private static ContentInterpreter.FontResolver resolver() throws Exception {
        org.apache.pdfbox.pdmodel.font.PDFont f = helvetica();
        return name -> f;
    }

    private static String stream(String body) {
        return "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n" + body + "\nET\n";
    }

    private static List<TextAdvanceSimulator.ShowStop> sim(String content) throws Exception {
        return TextAdvanceSimulator.simulate(content.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                resolver());
    }

    private static double[] ends(String content) throws Exception {
        return TextAdvanceSimulator.segmentEnds(sim(content));
    }

    @Test
    @DisplayName("TJ 的调整数往左移 n/1000×字号——量纲错了版面对不上")
    void tjNumberDisplacesLeftByFormula() throws Exception {
        // 期望值里不含任何字宽：两条内容流只差一个 TJ 数字，差值可以完全由公式推出。
        // delta = -(A - B)/1000 × 12，A 的数字更小 → 位移更靠左 → 累计位移更小。
        double[] a = ends(stream("[(abc) 250] TJ"));
        double[] b = ends(stream("[(abc) 100] TJ"));
        assertEquals(1, a.length);
        assertEquals(1, b.length);
        assertEquals(-(250.0 - 100.0) / 1000.0 * 12, a[0] - b[0], 1e-6);
    }

    @Test
    @DisplayName("字号翻倍则同一串 TJ 数字造成的位移也翻倍")
    void tjNumberScalesWithFontSize() throws Exception {
        double small = ends("BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n[(abc) 250] TJ\nET\n")[0];
        double big = ends("BT\n/F1 24 Tf\n1 0 0 1 50 700 Tm\n[(abc) 250] TJ\nET\n")[0];
        assertEquals(2 * small, big, 1e-4);
    }

    @Test
    @DisplayName("水平缩放 Tz 与水平位移成正比")
    void tzScalesDisplacement() throws Exception {
        double[] normal = ends(stream("[(abc)] TJ"));
        double[] half = ends("BT\n/F1 12 Tf\n50 Tz\n1 0 0 1 50 700 Tm\n[(abc)] TJ\nET\n");
        assertEquals(0.5, half[0] / normal[0], 1e-6);
    }

    @Test
    @DisplayName("定位算子把流切成若干段，段末尾位移各自从零起算")
    void segmentsRestartAtRepositioningOperators() throws Exception {
        List<TextAdvanceSimulator.ShowStop> stops = sim(
                stream("(abc) Tj\n1 0 0 1 50 600 Tm\n(def) Tj"));
        double[] segEnds = TextAdvanceSimulator.segmentEnds(stops);
        assertEquals(2, segEnds.length, "Tm 应把一条流切成两段：" + stops);
        // 第一段末尾应等于该段唯一那个算子的位移；第二段同理，两段互不携带。
        double firstOnly = ends(stream("(abc) Tj"))[0];
        double secondOnly = ends(stream("(def) Tj"))[0];
        assertEquals(firstOnly, segEnds[0], 1e-6);
        assertEquals(secondOnly, segEnds[1], 1e-6);
    }

    @Test
    @DisplayName("比较器放过浮点级差异，但必须报出真实位移偏差")
    void comparatorIgnoresFloatNoiseButReportsRealDrift() {
        // 3.05e-5 pt 是实测到的 float32 单 ULP 量级（见 docs/M3-3 §1.19），
        // 远小于容差，不该被当成版面问题报出去——否则用户每次都看到一条假告警。
        List<String> quiet = TextAdvanceSimulator.compareDisplacement(
                List.of(new TextAdvanceSimulator.ShowStop(0, 307.656005859, "x")),
                List.of(new TextAdvanceSimulator.ShowStop(0, 307.655975342, "y")),
                0.01);
        assertTrue(quiet.isEmpty(), "浮点末位差异不该报版面问题，实际报了：" + quiet);

        // 5.9 pt 是本次真实踩到的偏差（还原没换算 Tz 时后面整段偏左），必须被点名。
        List<String> loud = TextAdvanceSimulator.compareDisplacement(
                List.of(new TextAdvanceSimulator.ShowStop(0, 144.835968, "x")),
                List.of(new TextAdvanceSimulator.ShowStop(0, 138.937027, "y")),
                0.01);
        assertEquals(1, loud.size(), "真实位移偏差必须被报出来，实际：" + loud);
        assertTrue(loud.get(0).contains("第 0 段"), "告警要指明是哪一段：" + loud.get(0));
        assertTrue(loud.get(0).contains("pt"), "告警要带单位与量值：" + loud.get(0));
    }

    @Test
    @DisplayName("比较器对「段数变了」单独报一条——回写绝不该动定位算子")
    void comparatorFlagsSegmentCountChange() {
        List<String> out = TextAdvanceSimulator.compareDisplacement(
                List.of(new TextAdvanceSimulator.ShowStop(0, 10.0, "a"),
                        new TextAdvanceSimulator.ShowStop(1, 20.0, "b")),
                List.of(new TextAdvanceSimulator.ShowStop(0, 10.0, "a")),
                0.01);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("重定位段数变了"), out.get(0));
    }

    @Test
    @DisplayName("同一段里加一个显示算子不算版面变化（改写本就会把算子拆开）")
    void extraShowOpInSameSegmentIsNotDrift() {
        List<String> out = TextAdvanceSimulator.compareDisplacement(
                List.of(new TextAdvanceSimulator.ShowStop(0, 40.0, "a")),
                List.of(new TextAdvanceSimulator.ShowStop(0, 20.0, "a"),
                        new TextAdvanceSimulator.ShowStop(0, 40.0, "b")),
                0.01);
        assertTrue(out.isEmpty(), "段末尾一致就不该报差异，实际：" + out);
    }

    @Test
    @DisplayName("拟合缩放：槽位够宽就不压，压到底也不再压")
    void fitScaleClampsToFloor() {
        // 槽位比令牌宽 → 原样 100%
        assertEquals(1.0, PdfStreamRewriter.fitScale(50, 80), 1e-9);
        // 槽位只有令牌的一半 → 正好压到一半
        assertEquals(0.5, PdfStreamRewriter.fitScale(50, 25), 1e-9);
        // 槽位极窄 → 压到下限为止，绝不压成糊条
        assertEquals(PdfStreamRewriter.MIN_TOKEN_H_SCALE,
                PdfStreamRewriter.fitScale(50, 1), 1e-9);
        assertEquals(PdfStreamRewriter.TOKEN_H_SCALE,
                PdfStreamRewriter.fitScale(50, 50), 1e-9);
        // 退化输入不得返回 NaN——它会一路传播到内容流里写出 `NaN Tz`
        assertEquals(1.0, PdfStreamRewriter.fitScale(0, 10), 1e-9);
        assertEquals(1.0, PdfStreamRewriter.fitScale(10, 0), 1e-9);
        assertFalse(Double.isNaN(PdfStreamRewriter.fitScale(10, -5)));
        assertNotEquals(PdfStreamRewriter.TOKEN_H_SCALE,
                PdfStreamRewriter.fitScale(50, 25), "窄槽位必须真的被压，否则本测试没意义");
    }
}
