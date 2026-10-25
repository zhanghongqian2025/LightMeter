package com.litemask.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户手工补充的敏感词 → 脱敏规则。
 *
 * <p><b>为什么要有这个</b>：本工具的内置规则只认**有固定形态**的东西
 * （身份证、银行卡、手机号…）。但卷宗里有大量**没有固定形态**的敏感信息 ——
 * 某个当事人名字、某个案号、某个当事人自己写的小名、内部代号。
 * 规则认不出来，界面上就只能显示「零命中」，
 * 而用户**看得见**那些字就在文档里。
 * <p>过去界面只能做「排除误报」，做不了「补上漏检」——
 * 那等于把「人工复核」这个环节的一半能力拿掉了。
 * → 这里给它补上：用户补的词<b>接进现有的引擎、复核清单与映射</b>，
 * 不是另开一条旁路（旁路会让令牌编号对不上、还原不回去）。
 *
 * <p><b>为什么是「规则」而不是「另一条替换通路」</b>：规则是引擎唯一认的东西。
 * 走规则才能自动获得：同值同符、位置、出现序号、整值/逐处排除、进入映射。
 * 另开一条通路的话，这些全都要重做一遍，而且大概率做不全。
 *
 * <p><b>为什么限长、限量、限最短长度</b>：
 * <ul>
 *   <li><b>最短 2 个字</b>：引擎没有最短长度保护，
 *       补一个「张」会把全文的「张」都换掉 —— 那不是脱敏，是毁文件。</li>
 *   <li><b>最长 64 个字</b>：再长就不像「敏感词」了，
 *       而且一条超长正则会让每次扫描都多花时间。</li>
 *   <li><b>一次最多 50 个</b>：防止有人把整个词表贴进来。</li>
 * </ul>
 */
public final class UserTerms {

    /**
     * 规则键。
     *
     * <p><b>几个补充词共用一个键</b>：令牌的序号是按「前缀」递增的
     * （见 {@link TokenAllocator}），共用一个前缀才能拿到 {@code <补充1>、<补充2>…}
     * 这样连着的编号，而不是一堆各自从 1 开始的编号。
     * 同时 PDF 侧的 ASCII 令牌也短（{@code <USER_TERM_1>}）。
     */
    public static final String RULE_KEY = "user_term";

    /** 令牌前缀（界面上显示成 {@code <补充1>}）。 */
    public static final String TOKEN_PREFIX = "补充";

    /** 规则名（界面上这一行的来源显示）。 */
    public static final String RULE_NAME = "用户补充";

    /** 一个词的最短长度。 */
    public static final int MIN_TERM_LENGTH = 2;

    /** 一个词的最长长度。 */
    public static final int MAX_TERM_LENGTH = 64;

    /** 一次最多能补多少个词。 */
    public static final int MAX_TERMS = 50;

    /**
     * 优先级：<b>比内置规则高</b>。
     *
     * <p>数值越大越先生效（见 {@link Rule}）。内置规则里最高的是 120 上下，
     * 这里给 1000 —— 用户手工指认的东西应当**压过**通用形态规则，
     * 否则「本案当事人李某」可能被身份证规则切走一半。
     */
    public static final int SORT_ORDER = 1000;

    private UserTerms() {
    }

    /**
     * 把用户补充的词变成规则。
     *
     * @param terms 词表（已校验）
     * @return 可直接交给引擎的规则
     */
    public static List<Rule> toRules(List<String> terms) {
        List<Rule> out = new ArrayList<>();
        if (terms == null) {
            return out;
        }
        for (String t : terms) {
            if (t == null || t.isBlank()) {
                continue;
            }
            out.add(new Rule(
                    RULE_KEY,
                    RULE_NAME,
                    "用户手工补充的敏感词（本次处理内有效）",
                    // 字面量匹配：用户给的词里有正则元字符（. * + ? 等）时不能当正则解释。
                    Pattern.quote(t),
                    TOKEN_PREFIX,
                    SORT_ORDER,
                    true,
                    Category.REGEX,
                    Validator.NONE));
        }
        return out;
    }

    /**
     * 校验并规范化用户补的词。
     *
     * <p><b>为什么在这里校验而不是在界面上</b>：界面只是入口，
     * 命令行与任何别的调用方都要走同一条校验 —— 否则「界面上不让补」
     * 只是一层界面上的限制，换个入口就绕过去了。
     *
     * @param raw 用户给的词表
     * @return 规范化后的词表（去空白、去重、保序）
     * @throws IllegalArgumentException 有词不合法
     */
    public static List<String> validate(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("请至少输入一个要补充的敏感词。");
        }
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() < MIN_TERM_LENGTH) {
                throw new IllegalArgumentException("「" + t + "」太短了（至少 "
                        + MIN_TERM_LENGTH + " 个字）："
                        + "一个字的词会命中全文的每一个字，那不是脱敏，是把文件毁掉。");
            }
            if (t.length() > MAX_TERM_LENGTH) {
                throw new IllegalArgumentException("「" + t.substring(0, 12) + "…」太长了（最多 "
                        + MAX_TERM_LENGTH + " 个字）。");
            }
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("请至少输入一个要补充的敏感词。");
        }
        if (out.size() > MAX_TERMS) {
            throw new IllegalArgumentException("一次最多补 " + MAX_TERMS + " 个词（现在 "
                    + out.size() + " 个）。");
        }
        return out;
    }
}
