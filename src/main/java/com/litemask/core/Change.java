package com.litemask.core;

/**
 * 一次替换的记录：原文片段 → 占位符，以及它在文本中的位置。
 *
 * @param occurrence 同一 {@code (规则键, 原值)} 在<b>整篇文档</b>里的第几次出现，0 基。
 *                   {@link Match#UNNUMBERED} 表示这一轮没有编号（单段调用、未启用位置级排除）。
 *
 * <p><b>为什么序号要跟着命中一起出来</b>：界面上「只保留第 N 处」勾的是这个序号，
 * 引擎判「是不是第 N 次」用的也必须是同一个数。如果服务端自己按清单顺序重新数一遍，
 * 那就是<b>同一个事实定义了两处</b>——两边只要有一处顺序不同源（归组行、
 * 被排除掉的命中、多格式混排），用户勾的「第 2 处」就会落到别的位置上去，
 * 而且界面看不出任何异常。所以序号由引擎分配、由这里原样带出去。
 */
public record Change(
        String original,
        String token,
        int start,
        int end,
        String ruleKey,
        int occurrence) {

    /** 未编号的兼容构造：不需要位置级排除的调用方不必关心序号。 */
    public Change(String original, String token, int start, int end, String ruleKey) {
        this(original, token, start, end, ruleKey, Match.UNNUMBERED);
    }
}