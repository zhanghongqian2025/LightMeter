package com.litemask.ner;

import ai.onnxruntime.OrtException;
import com.litemask.core.EntitySource;
import com.litemask.core.Match;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把 NER 的识别结果适配成引擎认识的 {@link Match}。
 *
 * <p>这是 NER 与脱敏引擎之间<b>唯一</b>的接缝。过了这一行，
 * 重叠消解、出现编号、位置级排除、替换全走引擎原有的那一份实现，
 * 不会再有「AI 与正则各走各的替换路径」这种偏移错位。
 *
 * <p><b>优先级为什么定成 900</b>（现有正则规则实测范围 76..120）：
 * NER 命中应当<b>压过</b>正则命中。典型的重叠是「北京某某科技有限公司」——
 * 若某条正则只命中了里面的「北京」，按正则优先就会把机构名切成两截，
 * 文档里出现 {@code <机构名1>某某科技有限<地名1>公司} 这种东西。
 * 反过来，NER 误报吞掉一个身份证号的概率极低（身份证几乎全是数字），
 * 而这个工具的既定取舍是<b>宁可多脱</b>，所以让 NER 赢。
 *
 * <p>优先级是<b>常量</b>而不是可配置项：它一旦能配，某个部署就会配出一份
 * 「正则压过 NER」的规则集，而那种配置下的错误现场是机构名被切碎、报告正常。
 */
public final class NerEntitySource implements EntitySource {

    /** NER 命中的优先级。高于全部正则规则（实测最高 120）。 */
    public static final int SORT_ORDER = 900;

    /** 人名规则的稳定标识，写进还原映射与排除键。 */
    public static final String RULE_PERSON = "ner_person";

    /** 机构名规则的稳定标识。 */
    public static final String RULE_ORGANIZATION = "ner_organization";

    private static final String PREFIX_PERSON = "人名";
    private static final String PREFIX_ORGANIZATION = "机构名";

    private final String name;
    private final TextRecognizer recognize;

    /**
     * 识别一段文本、返回全文 UTF-16 坐标下的实体。
     *
     * <p>抽成函数式接口而不是直接持有 {@code NerRecognizer}，是因为本类干的活
     * 只是「翻译」：把区间、类型翻成 ruleKey / tokenPrefix / 优先级。
     * 为测这一点去加载 ONNX 不但慢，还让「翻译写错了」和「模型跑不动了」
     * 两类故障在测试里混成一个——而这两者的修法完全不同。
     */
    @FunctionalInterface
    public interface TextRecognizer {
        List<EntitySpan> recognize(String text) throws OrtException;
    }

    public NerEntitySource(NerRecognizer recognizer) {
        this("ner", Objects.requireNonNull(recognizer, "recognizer")::recognizeAll);
    }

    public NerEntitySource(String name, TextRecognizer recognize) {
        this.name = Objects.requireNonNull(name, "name");
        this.recognize = Objects.requireNonNull(recognize, "recognize");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Match> collect(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            return List.of();
        }
        List<EntitySpan> spans;
        try {
            spans = recognize.recognize(text);
        } catch (OrtException e) {
            // 不吞：返回空列表会让调用方以为「这段没有实体」，
            // 结果是模型挂了而文档照出，少脱且无任何提示。
            throw new NerRecognizer.NerInferenceException(
                    "NER 推理失败（来源 " + name + "，文本 " + text.length() + " 字）", e);
        }
        if (spans == null) {
            throw new NerRecognizer.NerInferenceException(
                    "NER 来源 " + name + " 对 " + text.length() + " 字文本返回了 null；"
                            + "「没识别出来」必须返回空列表，返回 null 会把故障说成「没有实体」");
        }
        List<Match> out = new ArrayList<>(spans.size());
        for (EntitySpan s : spans) {
            checkSpan(s, text.length());
            out.add(new Match(s.start(), s.end(), text.substring(s.start(), s.end()),
                    ruleKey(s.type()), tokenPrefix(s.type()), SORT_ORDER));
        }
        return out;
    }

    /**
     * 越界区间一律拒绝，不截断、不丢弃。
     *
     * <p>丢掉等于少脱，而越界本身说明坐标换算出了问题（最可能是码点/UTF-16 没换算干净），
     * 那种错误应当让调用方知道，而不是悄悄少脱一段。
     */
    private static void checkSpan(EntitySpan s, int textLength) {
        if (s.start() < 0 || s.end() > textLength || s.end() <= s.start()) {
            throw new NerRecognizer.NerInferenceException(
                    "NER 报出的区间 [" + s.start() + "," + s.end() + ") 超出文本长度 " + textLength
                            + "，坐标换算有问题");
        }
    }

    private static String ruleKey(EntityType type) {
        return type == EntityType.PERSON ? RULE_PERSON : RULE_ORGANIZATION;
    }

    private static String tokenPrefix(EntityType type) {
        return type == EntityType.PERSON ? PREFIX_PERSON : PREFIX_ORGANIZATION;
    }
}
