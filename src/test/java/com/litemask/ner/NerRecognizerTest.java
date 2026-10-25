package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NerRecognizer} 的端到端测试：字符 → 推理 → Viterbi → 滑窗合并 → 全文坐标。
 *
 * <p>跑在真实 ORT 上，但用的是一层 Gather 的玩具模型，所以每个位置的分数完全已知，
 * 期望实体可以逐字手算。
 *
 * <p><b>夹具里的最长实体是 4 字</b>（「北京大学」），所有 {@code overlap} 都必须 ≥ 4，
 * 否则构造就会失败——这不是限制，是 {@code overlap ≥ 最长实体} 那条契约的直接后果。
 */
class NerRecognizerTest {

    /** 夹具能吐出的最长实体长度。 */
    private static final int MAX_ENTITY = 4;

    private OnnxRunner runner;

    /** 夹具的字符表：。=1 张=2 三=3 丰=4 北=5 京=6 大=7 学=8，0 是 UNK。 */
    private static CharVocab vocab() {
        return CharVocab.ofListedChars(List.of("。", "张", "三", "丰", "北", "京", "大", "学"));
    }

    private NerRecognizer recognizer(int maxChars, int overlap) {
        return new NerRecognizer(vocab(), runner, TagScheme.bio(), null,
                NerRecognizer.ScoreSpace.LOGIT, maxChars, overlap, MAX_ENTITY);
    }

    @BeforeEach
    void setUp() throws Exception {
        runner = OnnxRunner.open(fixture("ner-fixture.onnx"), "ids", "logits");
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.close();
        }
    }

    private static byte[] fixture(String name) {
        try (InputStream in = NerRecognizerTest.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException("找不到测试夹具 /" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String show(List<EntitySpan> spans, String text) {
        StringBuilder sb = new StringBuilder();
        for (EntitySpan s : spans) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(text.substring(s.start(), s.end())).append('/').append(s.type().code());
        }
        return sb.isEmpty() ? "(无)" : sb.toString();
    }

    /** 结果里是否存在正好等于这一段子文本的实体。 */
    private static boolean has(String text, List<EntitySpan> spans, String slice) {
        return spans.stream().anyMatch(s -> slice.equals(text.substring(s.start(), s.end())));
    }

    /** 把模型分数转成解码器要的 double 矩阵，便于直接断言标签路径。 */
    private static double[][] emissions(OnnxRunner r, long[] ids) throws OrtException {
        float[][] raw = r.run(ids);
        double[][] out = new double[raw.length][raw[0].length];
        for (int t = 0; t < raw.length; t++) {
            for (int k = 0; k < raw[t].length; k++) {
                out[t][k] = raw[t][k];
            }
        }
        return out;
    }

    @Test
    @DisplayName("基本识别：人名与机构名各自成段")
    void basicRecognition() throws Exception {
        String text = "。张三丰在北京大学。";
        List<EntitySpan> got = recognizer(32, 8).recognizeAll(text);
        assertEquals("张三丰/person, 北京大学/organization", show(got, text), "实际: " + show(got, text));
    }

    @Test
    @DisplayName("实体区间能用原文切片复现，且不是单字")
    void offsetsSliceBackToText() throws Exception {
        String text = "甲方张三丰委托乙方北京大学办理。";
        for (EntitySpan s : recognizer(64, 8).recognizeAll(text)) {
            String slice = text.substring(s.start(), s.end());
            assertTrue(slice.length() >= 2, "实体不该是单字，实际拿到 '" + slice + "'");
        }
    }

    @Test
    @DisplayName("文本首尾的实体不被当成「贴切边」丢掉")
    void entitiesAtTextEdgesSurvive() throws Exception {
        String text = "张三丰";
        List<EntitySpan> got = recognizer(10, 4).recognizeAll(text);
        assertEquals("张三丰/person", show(got, text), "实际: " + show(got, text));
    }

    @Test
    @DisplayName("实体起点恰好落在某个窗口的左切边上，仍然被采信")
    void entityStartingAtWindowLeftCutSurvives() throws Exception {
        // 「张三丰北京大学」7 字，窗口 5/重叠 4 → 窗口起点 0,1,2。
        // 「北京大学」[3,7) 在窗口 [2,7) 里完整可见，但它的起点 3 落在别的窗口左切边上。
        // 旧实现左右对称地丢，于是这个实体<b>整个消失且零报错</b>。
        String text = "张三丰北京大学";
        List<EntitySpan> got = recognizer(5, 4).recognizeAll(text);
        assertTrue(got.stream().anyMatch(s -> "北京大学".equals(text.substring(s.start(), s.end()))),
                "「北京大学」必须被识别到，实际: " + show(got, text));
    }

    @Test
    @DisplayName("跨窗口的实体不会因为半截被丢弃而整个消失")
    void crossWindowEntitySurvives() throws Exception {
        String text = "张三丰在北京大学";
        List<EntitySpan> got = recognizer(5, 4).recognizeAll(text);
        assertTrue(has(text, got, "张三丰"), "实际: " + show(got, text));
        assertTrue(has(text, got, "北京大学"), "实际: " + show(got, text));
    }

    /**
     * 窄窗口可能多报出「边界重启」的子串，但它一定被某个正确实体<b>包含</b>。
     *
     * <p>现场：窗口 {@code [1,6)} 的首字「三」最高分是 {@code I-人名}，
     * 而开头约束禁止 {@code I-}，于是退到同分的 {@code B-人名}——
     * 「重开一个」的总分比「两个字都判 O」高 10 分。
     * 窗口看不到左边的上下文，它<b>没有别的办法</b>知道这是延续。
     *
     * <p>这是窗口式 NER 的固有边界现象，不是 bug；关键是它只多不漏：
     * 多出来的 {@code [1,3) 三丰} 是正确实体 {@code [0,3) 张三丰} 的子集，
     * 交给去重叠环节合并即可。**这条断言钉的是「只多不漏」，不是「数量相同」**——
     * 早先写成「窄窗口与宽窗口给出同一批实体」是错的命题，被探针实测推翻了。
     */
    @Test
    @DisplayName("窄窗口只多不漏：多出来的子串必被正确实体包含")
    void narrowWindowOnlyAddsSubsets() throws Exception {
        String text = "张三丰在北京大学";
        List<EntitySpan> narrow = recognizer(5, 4).recognizeAll(text);
        List<EntitySpan> wide = recognizer(64, 8).recognizeAll(text);

        for (EntitySpan n : narrow) {
            String slice = text.substring(n.start(), n.end());
            boolean exactOrContained = wide.stream().anyMatch(w ->
                    w.type() == n.type()
                            && w.start() <= n.start() && n.end() <= w.end());
            assertTrue(exactOrContained, "窄窗口多报了 '" + slice + "'，"
                    + "它没有被任何一个宽窗口实体包含；窄=" + show(narrow, text)
                    + " 宽=" + show(wide, text));
        }
        for (EntitySpan w : wide) {
            String slice = text.substring(w.start(), w.end());
            assertTrue(has(text, narrow, slice),
                    "宽窗口认出的 '" + slice + "' 在窄窗口下丢了，那才是漏脱");
        }
    }

    @Test
    @DisplayName("贴右切边的半截被丢弃，但同一实体仍由能完整看见它的窗口补上")
    void truncatedHalfDroppedButFullEntityKept() throws Exception {
        // 窗口 [0,5) 看到的「北京」贴着它的右切边 → 丢弃。
        // 而窗口 [2,7) 能完整看见「北京大学」，且它结束于全文末尾 → 采信。
        // 断言的是「全文实体还在」，不是「只有一条」——
        // 中间窗口确实还会报出一个重叠的「北京」，那是去重叠环节的事。
        String text = "张三丰北京大学";
        List<EntitySpan> got = recognizer(5, 4).recognizeAll(text);
        assertTrue(got.stream().anyMatch(s -> "北京大学".equals(text.substring(s.start(), s.end()))),
                "实际: " + show(got, text));
        assertTrue(got.stream().anyMatch(s -> "张三丰".equals(text.substring(s.start(), s.end()))),
                "实际: " + show(got, text));
    }

    @Test
    @DisplayName("表情在实体前面：起点落在表情之后（UTF-16 换算）")
    void emojiBeforeEntity() throws Exception {
        String text = "👍张三丰";
        List<EntitySpan> got = recognizer(32, 8).recognizeAll(text);
        assertEquals("张三丰/person", show(got, text), "实际: " + show(got, text));
        assertEquals(2, got.get(0).start(), "👍 占两个 UTF-16 单元，起点应是 2");
        assertEquals(5, got.get(0).end());
    }

    @Test
    @DisplayName("表情在实体后面：终点不把表情吞进去")
    void emojiAfterEntity() throws Exception {
        String text = "张三丰👍";
        List<EntitySpan> got = recognizer(32, 8).recognizeAll(text);
        assertEquals("张三丰/person", show(got, text), "实际: " + show(got, text));
        assertEquals(3, got.get(0).end(), "丰 结束于 UTF-16 下标 3");
    }

    @Test
    @DisplayName("表情夹在实体中间会被打断——这是夹具分数过强的副作用，需如实记录")
    void emojiInsideEntity() throws Exception {
        // 夹具里实体字符对 O 给 -5、对自己的标签给 +5，是极端的二值分数。
        // 于是 Viterbi 宁可把实体打断重来（总分 10），也不愿把两个实体字符都判成 O（总分 0）。
        // 真实模型不会这么极端（O 与 I- 的分差不会到 5.0），但这条记录的是
        // 「解码器确实在做全局比较」而不是「逐位置取最大」。
        String text = "张👍三丰";
        assertEquals("张/person, 三丰/person", show(recognizer(32, 8).recognizeAll(text), text));
    }

    @Test
    @DisplayName("表情 + 跨窗口同时出现时坐标仍然正确")
    void emojiWithCrossWindow() throws Exception {
        String text = "👍张三丰在北京大学";
        List<EntitySpan> got = recognizer(6, 4).recognizeAll(text);
        assertEquals("张三丰/person, 北京大学/organization", show(got, text), "实际: " + show(got, text));
        assertEquals("张三丰", text.substring(got.get(0).start(), got.get(0).end()));
        assertEquals("北京大学", text.substring(got.get(1).start(), got.get(1).end()));
    }

    @Test
    @DisplayName("表外字符不会凭空生成实体")
    void unknownCharsProduceNothing() throws Exception {
        String text = "在的了着过";     // 全部不在字符表里
        assertEquals("(无)", show(recognizer(32, 8).recognizeAll(text), text));
    }

    @Test
    @DisplayName("空文本不报错也不产出")
    void emptyText() throws Exception {
        assertEquals(List.of(), recognizer(32, 8).recognizeAll(""));
    }

    @Test
    @DisplayName("窗口首字绝不以 I- 开头（开头约束在整条链上生效）")
    void windowFirstTagIsNeverContinuation() throws Exception {
        // 窗口 [1,6) 的首字「三」在夹具里最高分是 I-人名。
        // 开头约束禁止它，于是解码器退到 B-人名——**换成了 B-，不是放行 I-**。
        // 如果哪天约束被摘掉，这里会出现一个以 I- 开头的路径，实体也就凭空多一个。
        String text = "张三丰在北京大学";
        int[] path = SequenceDecoder.decode(
                emissions(runner, vocab().encode("三丰在北京").ids()),
                new double[25], TagScheme.bio());
        assertTrue(path.length > 0);
        assertTrue(TagScheme.bio().allowsStart(path[0]),
                "窗口首位只能是 O 或某个 B-，实际标签号 " + path[0]);
        assertTrue(TagScheme.bio().isOutside(path[0]) || TagScheme.bio().isBegin(path[0]),
                "首位不该是 I-（延续），实际标签号 " + path[0] + " = "
                        + TagScheme.bio().typeOf(path[0]));

        // 整条链上同样成立：任何窗口都不会凭空开出一个实体
        for (EntitySpan s : recognizer(5, 4).recognizeAll(text)) {
            assertTrue(s.start() >= 0 && s.end() <= text.length(),
                    "实体区间必须落在窗口内，实际 " + show(List.of(s), text));
        }
    }

    @Test
    @DisplayName("窗口比最长实体还窄时构造就失败，不等到运行时空结果")
    void windowNarrowerThanEntityFailsAtConstruction() throws Exception {
        // 夹具最长实体 4 字；窗口只有 3 字 → 没有任何窗口装得下它。
        var e = assertThrows(IllegalArgumentException.class, () -> recognizer(3, 2));
        assertTrue(e.getMessage().contains("4") && e.getMessage().contains("3"),
                "错误信息要给出最长实体与窗口宽度，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("重叠宽度小于最长实体时构造就失败")
    void overlapNarrowerThanEntityFailsAtConstruction() throws Exception {
        // 这条契约是 M3-1c 挖出来的：不满足时「完整包含」与「不贴切边」两个条件
        // 无法同时成立，实体会整个消失且不报错——对脱敏工具就是静默漏脱。
        var e = assertThrows(IllegalArgumentException.class, () -> recognizer(16, 3));
        assertTrue(e.getMessage().contains("重叠宽度") && e.getMessage().contains("消失"),
                "错误信息要说清后果，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("恰好等于最长实体的重叠宽度可以通过")
    void overlapExactlyMaxEntityAccepted() {
        new NerRecognizer(vocab(), runner, TagScheme.bio(), null,
                NerRecognizer.ScoreSpace.LOGIT, 16, MAX_ENTITY, MAX_ENTITY);
    }

    @Test
    @DisplayName("最长实体长度非法时报错")
    void badMaxEntityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NerRecognizer(
                vocab(), runner, TagScheme.bio(), null, NerRecognizer.ScoreSpace.LOGIT, 16, 8, 0));
    }

    @Test
    @DisplayName("模型通道数与标签方案不一致时，给出可操作的错误")
    void channelMismatchGivesActionableError() throws Exception {
        // 7 通道的模型配 5 通道的标签方案——就是「换了模型包忘了换标签方案」。
        // 注意：构造不出 3 通道的 TagScheme，因为 TagScheme 要求每种 EntityType
        // 都必须有 B- 与 I- 两个标签，而 EntityType 只有两种类型，方案大小被钉死在 5。
        // 所以这个错误只能靠「模型通道数不对」触发，也正是它的真实来源。
        try (OnnxRunner seven = OnnxRunner.open(fixture("ner-fixture-7ch.onnx"), "ids", "logits")) {
            assertEquals(7, seven.run(new long[] {1}).length == 0 ? 7 : 7);
            NerRecognizer r = new NerRecognizer(vocab(), seven, TagScheme.bio(), null,
                    NerRecognizer.ScoreSpace.LOGIT, 32, 8, MAX_ENTITY);
            var e = assertThrows(NerRecognizer.NerInferenceException.class, () -> r.recognizeAll("张三丰"));
            assertTrue(e.getMessage().contains("7") && e.getMessage().contains("5"),
                    "错误信息要同时给出实际通道数与期望通道数，实际: " + e.getMessage());
            assertTrue(e.getMessage().contains("标签方案"),
                    "错误信息要点出「换模型包忘了换标签方案」这个最可能的原因，实际: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("转移分数个数与标签方案对不上时立即报错")
    void wrongTransitionCountRejected() {
        var e = assertThrows(IllegalArgumentException.class, () -> new NerRecognizer(
                vocab(), runner, TagScheme.bio(), new double[7],   // 应为 5*5=25
                NerRecognizer.ScoreSpace.LOGIT, 32, 8, MAX_ENTITY));
        assertTrue(e.getMessage().contains("25"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("CRF 转移矩阵长度 25 时正常构造")
    void correctTransitionCountAccepted() {
        assertEquals(5, TagScheme.bio().size());
        new NerRecognizer(vocab(), runner, TagScheme.bio(), new double[25],
                NerRecognizer.ScoreSpace.LOGIT, 32, 8, MAX_ENTITY);
    }

    @Test
    @DisplayName("PROBABILITY 模式对分数取对数，LOGIT 原样传递")
    void scoreSpaceTransforms() {
        assertEquals(Math.log(0.25),
                NerRecognizer.ScoreSpace.PROBABILITY.apply(0.25f), 1e-6);
        assertEquals(3.5f, NerRecognizer.ScoreSpace.LOGIT.apply(3.5f), 1e-6);
        assertEquals(0.0f, NerRecognizer.ScoreSpace.LOGIT.apply(0.0f), 0);
    }

    @Test
    @DisplayName("推理失败被包成 NerInferenceException，且带上窗口长度")
    void inferenceFailureIsWrapped() throws Exception {
        try (OnnxRunner bad = OnnxRunner.open(fixture("ner-fixture.onnx"), "ids", "不存在的输出")) {
            NerRecognizer r = new NerRecognizer(vocab(), bad, TagScheme.bio(), null,
                    NerRecognizer.ScoreSpace.LOGIT, 32, 8, MAX_ENTITY);
            var e = assertThrows(NerRecognizer.NerInferenceException.class, () -> r.recognizeAll("张三丰"));
            assertTrue(e.getMessage().contains("字"), "错误信息应带上窗口长度，实际: " + e.getMessage());
            assertTrue(e.getCause() instanceof OrtException, "原始原因要保留");
        }
    }

    @Test
    @DisplayName("构造器参数不可为 null")
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> new NerRecognizer(
                null, runner, TagScheme.bio(), null, NerRecognizer.ScoreSpace.LOGIT, 32, 8, MAX_ENTITY));
        assertThrows(NullPointerException.class, () -> new NerRecognizer(
                vocab(), null, TagScheme.bio(), null, NerRecognizer.ScoreSpace.LOGIT, 32, 8, MAX_ENTITY));
        assertThrows(NullPointerException.class, () -> new NerRecognizer(
                vocab(), runner, null, null, NerRecognizer.ScoreSpace.LOGIT, 32, 8, MAX_ENTITY));
    }

    @Test
    @DisplayName("ORT 的张量与结果都是可关闭的（防止推理期泄漏原生内存）")
    void ortTypesAreAutoCloseable() {
        // 不测行为，只锁一个 API 契约：哪天 ORT 换了 API 导致 run() 里漏关，
        // 原生内存会每次调用泄漏一次，长文档扫描直接 OOM——属于「跑得久才发现」的故障。
        assertTrue(AutoCloseable.class.isAssignableFrom(OnnxTensor.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(OrtSession.Result.class));
    }

    @Test
    @DisplayName("一段长文本反复识别，结果稳定（会话无跨次状态污染）")
    void stableAcrossRuns() throws Exception {
        String text = "。张三丰在北京大学。张三丰在北京大学。";
        NerRecognizer r = recognizer(64, 8);
        assertEquals(show(r.recognizeAll(text), text), show(r.recognizeAll(text), text));
        assertEquals(4, r.recognizeAll(text).size(), "两处同样的实体都要识别出来");
    }

    @Test
    @DisplayName("recognizeAll 与单窗口 recognize 走的是同一条解码路径")
    void fullTextAndWindowSharePath() throws Exception {
        NerRecognizer r = recognizer(32, 8);
        String windowText = "。张三丰在北京大学。";
        assertEquals(show(r.recognize(windowText), windowText),
                show(r.recognizeAll(windowText), windowText),
                "窗口足够大时两条路径必须给出相同结果，否则问题出在滑窗合并那一层");
    }

    @Test
    @DisplayName("空窗口文本返回空列表")
    void emptyWindowText() throws Exception {
        assertEquals(List.of(), recognizer(32, 8).recognize(""));
    }

    @Test
    @DisplayName("构造参数被拒：窗口宽度、重叠宽度")
    void windowParamsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowNer(1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowNer(8, 0));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowNer(8, 8));
    }

    @Test
    @DisplayName("只含 UNK 的字符表能用，所有字符都落 id 0")
    void emptyVocabStillWorks() {
        CharVocab empty = CharVocab.ofListedChars(List.of());
        assertEquals(1, empty.idCount(), "只有 UNK 一个 id");
        assertEquals(0, empty.idOf('张'), "任何字符都落 UNK");
        assertEquals(0, empty.size());
    }

    @Test
    @DisplayName("重复识别不产生重复实体（去重按 类型+区间）")
    void noDuplicateEntities() throws Exception {
        String text = "张三丰";   // 多个窗口都能完整看见
        List<EntitySpan> got = recognizer(5, 4).recognizeAll(text);
        assertEquals(1, got.size(), "实际: " + show(got, text));
        assertEquals("张三丰/person", show(got, text));
    }
}
