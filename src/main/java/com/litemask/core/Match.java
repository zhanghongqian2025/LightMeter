package com.litemask.core;

/**
 * 正则命中的一次记录（尚未应用到文本）。
 *
 * @param occurrence 同一 {@code (规则键, 原值)} 在<b>整篇文档</b>里的第几次出现，0 基。
 *                   {@code -1} 表示这一轮没有编号（单段调用、未启用位置级排除）。
 */
public record Match(
        int start,
        int end,
        String text,
        String ruleKey,
        String tokenPrefix,
        int sortOrder,
        int occurrence) {

    /** 未编号。兼容不需要位置级排除的调用方。 */
    public static final int UNNUMBERED = -1;

    public Match(int start, int end, String text, String ruleKey,
                 String tokenPrefix, int sortOrder) {
        this(start, end, text, ruleKey, tokenPrefix, sortOrder, UNNUMBERED);
    }

    public int length() {
        return end - start;
    }

    /** 带上出现序号，位置不变。 */
    public Match numbered(int index) {
        return new Match(start, end, text, ruleKey, tokenPrefix, sortOrder, index);
    }
}
