package com.litemask.core;

/**
 * 给「同一 {@code (规则键, 原值)} 的各次出现」编号。
 *
 * <p><b>为什么需要它</b>：位置级排除要回答「第 N 次出现」，而引擎是<b>分段</b>跑的
 * （一个 docx 段落、一张 xlsx 单元格就是一个段），单看某一段不知道这是全文第几次。
 * 编号必须跨段累加，所以要有一个跨段的计数器 —— 就是本接口。
 *
 * <p><b>编号口径必须是「界面上看得见的那一批」。</b>界面上一个值出现几次，
 * 是把重叠消解<b>之后</b>数出来的：手机号规则从身份证里抠出的 11 位会被身份证规则挡掉，
 * 用户压根看不到它，就不该占用它前面那个序号。所以编号发生在解析重叠之后、
 * 排除过滤之前 —— 三者的顺序不能换。
 *
 * <p><b>哪些路径不参与编号</b>：会改内容但不把命中报给界面的路径（PDF 元数据、
 * xlsx 表名与定义名称、批注文本、OFD 附件名）。它们照样要脱敏、照样要记进还原映射，
 * 但不能消耗序号 —— 否则界面上「第 2 次出现」对不上文件里的第 2 处。
 * 那类路径调用 {@link RedactionSession#redactHidden}。
 */
@FunctionalInterface
public interface OccurrenceNumbering {

    /**
     * 取下一个序号并递增。
     *
     * @param ruleKey  命中它的规则键
     * @param original 命中的原值
     * @return 0 基的出现序号
     */
    int next(String ruleKey, String original);
}
