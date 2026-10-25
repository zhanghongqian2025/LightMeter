package com.litemask.ner;

import ai.onnxruntime.OrtException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 完整的 NER 识别器：分词 → 推理 → Viterbi 解码 → 跨窗口合并 → 全文坐标的实体。
 *
 * <p>它是 {@link SlidingWindowNer.WindowRecognizer} 的实现，
 * 因此滑窗切分、贴切边丢弃、跨窗口去重这些规则由 {@link SlidingWindowNer} 统一负责，
 * 本类只管「给定一段窗口文本，返回窗口局部坐标下的实体」。
 *
 * <p><b>坐标换算是本类最容易出错的地方</b>，值得单独说：
 *
 * <ul>
 *   <li>模型吃的是<b>码点</b>序列，实体区间也是码点坐标；</li>
 *   <li>而 {@link EntitySpan} 用的是原文的 <b>UTF-16 下标</b>
 *       （因为脱敏时是 {@code substring(start, end)}）；</li>
 *   <li>两者在纯中文文档上完全一致，因为汉字都在 BMP；</li>
 *   <li>一旦文档里有表情（代理对），两者就差 1。
 *       换算漏掉这一步，症状是「含表情的文档里，后面所有实体都偏移一个字」——
 *       脱敏时就会把实体后面那个字删掉，而报告一切正常。</li>
 * </ul>
 */
public final class NerRecognizer implements SlidingWindowNer.WindowRecognizer {

    /** 模型输出是原始分数还是概率。 */
    public enum ScoreSpace {
        /** 输出即 logit，可直接参与 Viterbi 打分（最大化 logit 等价于最大化后验概率）。 */
        LOGIT,
        /** 输出是概率，需要先取对数。直接把概率送进 Viterbi 是错的：
         *  分数不是「可加的对数似然」，路径之间的比较失去意义。 */
        PROBABILITY;

        double apply(float v) {
            return this == LOGIT ? (double) v : Math.log(v);
        }
    }

    private final CharVocab vocab;
    private final OnnxRunner runner;
    private final TagScheme scheme;
    private final double[] transitions;
    private final ScoreSpace scoreSpace;
    private final SlidingWindowNer window;

    /**
     * @param transitions 长度为 {@code C*C} 的 CRF 转移分数；为 {@code null} 表示模型不带 CRF，
     *                    此时用全 0——退化成只比较各位置分数的 Viterbi。
     * @param maxEntityChars <b>这个模型能吐出的最长实体是多少字</b>，来自模型包。
     *                       它和 {@code overlap} 的关系不是随便定的，见下面那条校验。
     */
    public NerRecognizer(CharVocab vocab, OnnxRunner runner, TagScheme scheme,
                         double[] transitions, ScoreSpace scoreSpace,
                         int maxChars, int overlap, int maxEntityChars) {
        this.vocab = Objects.requireNonNull(vocab, "vocab");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.scoreSpace = Objects.requireNonNull(scoreSpace, "scoreSpace");
        this.window = new SlidingWindowNer(maxChars, overlap);
        checkWindowContract(maxChars, overlap, maxEntityChars);

        int c = scheme.size();
        if (transitions == null) {
            this.transitions = new double[c * c];
        } else {
            if (transitions.length != c * c) {
                throw new IllegalArgumentException("转移分数个数应为 C*C = " + (c * c)
                        + "（C=" + c + "），实际 " + transitions.length);
            }
            this.transitions = transitions.clone();
        }
    }

    /**
     * {@code overlap ≥ maxEntityChars} 是「不丢实体」的充要条件，不满足就立刻报错。
     *
     * <p>推一遍：相邻窗口起点相隔 {@code step = maxChars - overlap}。
     * 长度 L、起点 a 的实体，能被某个起点 s ≤ a 的窗口完整包含，
     * 当且仅当 {@code maxChars ≥ L + (a - s)}；最坏情况下 {@code a - s = step - 1}，
     * 于是要求 {@code maxChars ≥ L + step - 1}，即 {@code overlap ≥ L}。
     *
     * <p>同一条件下，实体也<b>不可能</b>恰好结束在某个窗口的右切边上
     * （那需要 {@code L > overlap}），所以「贴右切边丢弃」这条规则也不会误伤。
     *
     * <p>不满足时的现场是<b>实体整个消失且不报错</b>：没有一个窗口同时满足
     * 「完整包含」与「不贴切边」，于是谁都不认领。
     * 对脱敏工具来说这是漏脱，比误脱严重得多，所以宁可启动失败也不静默继续。
     */
    private static void checkWindowContract(int maxChars, int overlap, int maxEntityChars) {
        if (maxEntityChars < 1) {
            throw new IllegalArgumentException("最长实体长度必须 ≥1，实际 " + maxEntityChars);
        }
        if (maxEntityChars >= maxChars) {
            throw new IllegalArgumentException("最长实体 " + maxEntityChars
                    + " 字不小于窗口宽度 " + maxChars + "：没有任何窗口装得下它，必然一个都识别不到");
        }
        if (overlap < maxEntityChars) {
            throw new IllegalArgumentException(
                    "重叠宽度 " + overlap + " 小于最长实体长度 " + maxEntityChars
                            + "：跨窗口的实体会「完整包含」与「不贴切边」两个条件同时不满足，"
                            + "结果是这个实体整个消失且不报错。"
                            + "重叠宽度至少要等于最长实体长度。");
        }
    }

    /** 标签方案。解码前用它校验模型的通道数是否对得上。 */
    public TagScheme scheme() {
        return scheme;
    }

    public CharVocab vocab() {
        return vocab;
    }

    public SlidingWindowNer window() {
        return window;
    }

    /** 识别整篇文本，返回全文 UTF-16 坐标下的实体区间，按起点升序。 */
    public List<EntitySpan> recognizeAll(String text) throws OrtException {
        return window.run(text, this);
    }

    /**
     * 单窗口识别（{@link SlidingWindowNer} 调用）。
     *
     * @return 窗口<b>局部</b> UTF-16 坐标下的实体
     */
    @Override
    public List<EntitySpan> recognize(String windowText) {
        Objects.requireNonNull(windowText, "windowText");
        if (windowText.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return decodeWindow(windowText);
        } catch (OrtException e) {
            // 窗口识别器的签名里没有受检异常，而滑窗已经把这个窗口的文本切好了。
            // 在这里吞掉会让用户拿到「少了一些实体」的结果，
            // 所以包成运行时异常向上抛：整篇失败并带明确原因，好过悄悄少脱。
            throw new NerInferenceException("NER 推理失败（窗口 " + windowText.length() + " 字）", e);
        }
    }

    private List<EntitySpan> decodeWindow(String windowText) throws OrtException {
        CharVocab.Encoding enc = vocab.encode(windowText);
        float[][] logits = runner.run(enc.ids());
        checkChannels(logits);

        double[][] emissions = new double[logits.length][logits[0].length];
        for (int t = 0; t < logits.length; t++) {
            for (int k = 0; k < logits[t].length; k++) {
                emissions[t][k] = scoreSpace.apply(logits[t][k]);
            }
        }

        List<EntitySpan> codePointSpans =
                SequenceDecoder.decodeToEntities(emissions, transitions, scheme);

        List<EntitySpan> out = new ArrayList<>(codePointSpans.size());
        for (EntitySpan s : codePointSpans) {
            int[] utf16 = vocab.toUtf16Range(enc, s.start(), s.end());
            out.add(new EntitySpan(utf16[0], utf16[1], s.type()));
        }
        return out;
    }

    /**
     * 模型输出通道数必须与标签方案一致。
     *
     * <p>对不上时如果直接丢给解码器，越界会在 DP 循环里炸成一个
     * {@code ArrayIndexOutOfBoundsException}，看不出是「模型包和标签方案不配套」。
     * 这是最可能发生在「换了模型包忘了换标签方案」的场景，必须给出可操作的错误。
     */
    private void checkChannels(float[][] logits) {
        if (logits.length == 0) {
            return;
        }
        int actual = logits[0].length;
        if (actual != scheme.size()) {
            throw new NerInferenceException("模型输出通道数 " + actual
                    + " 与标签方案 " + scheme.size() + " 不一致；"
                    + "多半是换了模型包但标签方案没跟着换");
        }
    }

    /** 推理期失败。独立于 {@link OrtException}，便于上层区分「模型跑不动」与「数据不合法」。 */
    public static final class NerInferenceException extends RuntimeException {
        public NerInferenceException(String message) {
            super(message);
        }

        public NerInferenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
