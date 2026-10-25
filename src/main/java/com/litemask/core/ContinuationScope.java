package com.litemask.core;

/**
 * 跨单元续接的<b>适用范围</b>：哪些规则允许参与续接。
 *
 * <p><b>为什么续接要分范围</b>：续接靠「两段拼起来过校验器」来确认
 * 「它们本来是一个值」。这个推断在**顺序相邻**的载体上很可靠，
 * 在**空间相邻**的载体上不可靠。
 *
 * <ul>
 *   <li>OOXML（docx / xlsx / pptx）：相邻单元是<b>顺序</b>相邻——上一段的下一句就是
 *       下一段。两段拼起来成为一个合法标识符，多半真是被切断的同一个值。
 *       → {@link #ANY}：任何规则都可以参与。</li>
 *   <li>PDF：相邻块是<b>空间</b>相邻——同一页上左栏的末尾与右栏的开头也是「相邻」。
 *       两栏里各有一个不相干的数字串，拼起来也可能凑成一个合法手机号
 *       （11 位、以 1 开头、第二位 3-9，误判率不低）。
 *       → {@link #CHECK_DIGIT_ONLY}：只有带<b>真校验位</b>的规则参与
 *       （见 {@link Validator#hasCheckDigit()}）。</li>
 * </ul>
 *
 * <p><b>误判与漏判的代价不对称</b>：判成「不是一个值」→ 两段各自按段内形状归类，
 * 可能<b>整张留在交付物里</b>（泄漏）；判成「是一个值」→ 两段都被按该类型脱敏，
 * 代价只是多两个令牌、且映射里能还原回原值。方向上应当偏向前者风险更小的一侧，
 * 但不能偏到「宁可全都不续接」——那等于把泄漏放回来。
 */
public enum ContinuationScope {

    /** 任何规则都可以参与续接（校验器只要不否决就算通过）。 */
    ANY,

    /**
     * 只有<b>有真校验位</b>的规则参与续接（见 {@link Validator#hasCheckDigit()}）。
     *
     * <p>无校验位的规则（手机号、邮箱、IP）在这种载体上不参与：
     * 它们「过校验器」等于「什么都收」，会把两段无关文本拼成一个假的标识符。
     */
    CHECK_DIGIT_ONLY;

    /** 这条规则在本范围下能不能参与续接。 */
    public boolean allows(Rule rule) {
        if (rule == null) {
            return false;
        }
        if (this == ANY) {
            return true;
        }
        return rule.validator() != null && rule.validator().hasCheckDigit();
    }
}
