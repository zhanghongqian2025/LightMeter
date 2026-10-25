package com.litemask.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「原文里严格相邻」的若干处命中归成一组，让界面按「一个标识符一条」展示。
 *
 * <p><b>为什么要有这一层</b>：重叠消解会把一个标识符切成好几段，各自拿到一个令牌。
 * 实测（见阶段文档 §1.19.8）：{@code a.b@x.cn} 被切成 {@code 人名:a.b}、
 * {@code 邮箱:@x.}、{@code 人名:cn} 三段，映射里是三条，界面上是三行——
 * 用户看到的是一个地址被拆成三条互不相干的命中。
 *
 * <p><b>为什么不改映射</b>：合并意味着要往映射里写 {@code a.b@x.cn} 这一个完整原值，
 * 而它<b>从未作为整体出现在源文件里</b>，是把不同规则各自认领的片段拼出来的。
 * 用户拿映射去核对源文件时找不到这个字符串；真当审计证据用，更是拿一条
 * 「工具自己编出来的原值」去证明「源文件里有这个值」。
 * <b>代价可见，编造不可见</b>——后者更危险。所以本类只动<b>呈现</b>，不动映射。
 *
 * <p><b>归组的三条硬条件</b>，少一条都可能骗到用户：
 * <ol>
 *   <li><b>严格相邻（gap=0）且同一个文本单元</b>。这样拼出来的串就是原文里
 *       <b>连续的一段</b>，不是把两处不相干的内容接起来。跨单元一律不归组——
 *       跨段拼出来的「标识符」在原文里根本不是连续的一段。</li>
 *   <li><b>每个成员的值在整篇文档里只出现一次</b>。当前排除语义是「按值整体保留」：
 *       勾上就等于这个值的所有出现处都别脱。若某个成员在别处还出现过，
 *       归组后的一行只显示其中一处，勾它却会连带留下别处——
 *       <b>勾选粒度骗人</b>，比不归组更糟。</li>
 *   <li><b>拼出来的串不能与文档里任何已有的命中原值相同</b>。否则界面上会出现
 *       两行一模一样的值（一个是归组行、一个是完整命中），用户无从分辨，
 *       排除项也会指向不明。</li>
 * </ol>
 *
 * <p><b>一段连续相邻的命中整体判定</b>：要么整段归组，要么一段都不归。
 * 不去试它的子段——「为什么这两段归了、那两段没归」这种解释不了的界面更糟。
 *
 * <p><b>PDF 侧的单元是「内容流 + 文本块」</b>：PDF 里的一段文字常被切成多个
 * Tj/TJ 算子，只有<b>同一文本块内、没有重新定位</b>的那些才属于同一段。
 * 所以两处命中只有在<b>同一条内容流的同一个文本块</b>里且严格相邻时才会归组——
 * 跨块（换了一行）或跨内容流（页面正文 vs 表单）的两处命中在原文里本来就不挨着，
 * 归它们就是造出一个原文里不存在的标识符。
 * 位置信息由 {@code PdfRedactor} 记录并透传过来（见 {@code DocumentService}）。
 *
 * <p>本类是纯函数，不可变，线程安全。
 */
public final class HitGrouping {

    /**
     * 一处带位置的命中。
     *
     * @param unit     所属文本单元的标识；<b>同一单元内的偏移才可以互相比较</b>。
     *                 {@code null} 表示不知道 → 永不归组。
     * @param start    在单元文本里的起点；{@code -1} 表示不知道 → 永不归组。
     * @param end      在单元文本里的终点（不含）。
     * @param occurrence 同一 {@code (规则键, 原值)} 在整篇文档里的第几次出现，0 基；
     *                   {@link Match#UNNUMBERED} 表示这一轮没有编号。
     *                   <b>由引擎分配，原样透传</b>：界面上的「第 N 处」用的就是它，
     *                   引擎判「是不是第 N 次」用的也必须是同一个数。
     */
    public record PositionedHit(String ruleKey, String original, String token,
                                 String locator, String unit, int start, int end, int occurrence) {

        /** 位置未知：不能参与归组判断。 */
        public boolean positioned() {
            return unit != null && !unit.isEmpty() && start >= 0 && end > start;
        }
    }

    /**
     * 归组结果：界面上的一个条目。
     *
     * @param members 组成这一条的命中。普通条目只有一个成员；归组条目有若干个，
     *                顺序即原文顺序。
     */
    public record Group(List<PositionedHit> members) {

        public Group {
            members = List.copyOf(members);
            if (members.isEmpty()) {
                throw new IllegalArgumentException("归组结果不能没有成员");
            }
        }

        /** 是否是「一个标识符被切成多段」归成的组。 */
        public boolean merged() {
            return members.size() > 1;
        }

        /** 这一条覆盖了几个值。 */
        public int valueCount() {
            return members.size();
        }

        /**
         * 展示用的原值：归组时是各段原值的<b>拼接</b>。
         *
         * <p>因为归组只发生在「严格相邻且同一单元」时，拼接结果就是原文里连续的一段，
         * <b>不是编出来的</b>。
         */
        public String original() {
            StringBuilder sb = new StringBuilder();
            for (PositionedHit m : members) {
                sb.append(m.original());
            }
            return sb.toString();
        }

        /**
         * 展示用的规则列：各成员规则键去重后按出现顺序用「 + 」连接。
         *
         * <p>不取「第一条的规则」：一个地址被邮箱规则与人名规则分别认领是常态，
         * 只显示其中一条会让用户以为另一条规则没生效。
         */
        public String ruleKey() {
            Set<String> keys = new LinkedHashSet<>();
            for (PositionedHit m : members) {
                keys.add(m.ruleKey());
            }
            return String.join(" + ", keys);
        }

        /**
         * 展示用的「文档中写入」列：各成员令牌<b>原样相连</b>，不加分隔符。
         *
         * <p>不加分隔符是因为这正是文档里的实际内容：归组只发生在严格相邻时，
         * 这些令牌在文档里就是紧挨着的。加分隔符反而会让人以为文档里真有空格。
         */
        public String token() {
            StringBuilder sb = new StringBuilder();
            for (PositionedHit m : members) {
                sb.append(m.token());
            }
            return sb.toString();
        }
    }

    /**
     * 值键：{@code (规则键, 原值)}。
     *
     * <p>用 record 而不是把两段拼成一个字符串：拼的话必须挑一个分隔符，
     * 而原值里可能有任意字符（空格、连字符…），挑不对就会把两个不同的值
     * 算成同一个。record 没有这个问题。
     */
    private record ValueKey(String ruleKey, String original) {
    }

    private HitGrouping() {
    }

    /**
     * 拼一个文本单元的标识。
     *
     * <p>用<b>长度前缀</b>而不是拿分隔符拼：部件名（zip 条目名）里可以出现任意字符，
     * 直接拼会撞车——部件 {@code a@b} 配局部名 {@code c} 与部件 {@code a}
     * 配局部名 {@code b@c} 拼出同一个串，于是两个不相干的单元被当成同一个，
     * 归组就会把两段不相干的内容拼成一个「标识符」。
     *
     * <p>长度前缀让解析无歧义：开头是部件名的长度，其后恰好这么多字符是部件名，
     * 剩下的全是局部名。
     *
     * @param part  部件名（zip 条目名 / 文档名）
     * @param local 单元在部件内的局部标识（段号、单元格、文本对象…）
     */
    public static String unitId(String part, String local) {
        String p = part == null ? "" : part;
        String l = local == null ? "" : local;
        return p.length() + ":" + p + l;
    }

    /**
     * 把命中归组成界面条目。
     *
     * <p>输出的顺序与输入一致（按首次出现的位置），所以界面上行的顺序稳定，
     * 刷新后复选框不会跑到别的行去。
     *
     * @param hits 全部命中，顺序即「界面清单顺序」
     */
    public static List<Group> group(List<PositionedHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<ValueKey, Integer> valueCounts = new HashMap<>();
        Set<String> allOriginals = new HashSet<>();
        for (PositionedHit h : hits) {
            valueCounts.merge(new ValueKey(h.ruleKey(), h.original()), 1, Integer::sum);
            allOriginals.add(h.original());
        }

        // 同一单元内的命中按起点排好序，才能找「严格相邻」的段
        Map<String, List<Integer>> byUnit = new HashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            PositionedHit h = hits.get(i);
            if (h.positioned()) {
                byUnit.computeIfAbsent(h.unit(), k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> idx : byUnit.values()) {
            idx.sort(Comparator.comparingInt(i -> hits.get(i).start()));
        }

        // accepted[这一组里最靠前的输入下标] = 这一组包含的下标
        Map<Integer, List<Integer>> accepted = new HashMap<>();
        Set<Integer> consumed = new HashSet<>();
        for (List<Integer> idx : byUnit.values()) {
            int i = 0;
            while (i < idx.size()) {
                int j = i + 1;
                while (j < idx.size() && 紧挨着(hits.get(idx.get(j - 1)), hits.get(idx.get(j)))) {
                    j++;
                }
                // 一段**连续相邻**的命中<b>整体判定</b>：要么整段归组，要么一段都不归。
                //
                // 不去试它的子段。子段能归而整段不能归时，界面上会出现
                // 「为什么这两段归了、那两段没归」——那种解释不了的界面比少归更糟。
                // 宁可少归，不可错归：少归只是行数多一点，错归是让用户在一个
                // 其实不相干的地方做决定。
                if (j - i >= 2) {
                    List<Integer> run = idx.subList(i, j);
                    if (可归组(hits, run, valueCounts, allOriginals)) {
                        // 这一组要挂在「输入顺序最靠前」的那个成员上：
                        // 输出是按输入下标走的，挂到位置排序的第一个成员上，
                        // 那个成员前面的成员会先被单独输出，于是组会散架。
                        int first = run.get(0);
                        for (int k : run) {
                            first = Math.min(first, k);
                        }
                        accepted.put(first, new ArrayList<>(run));
                        consumed.addAll(run);
                    }
                }
                i = j;
            }
        }

        List<Group> out = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            if (consumed.contains(i) && !accepted.containsKey(i)) {
                continue;                       // 已并入更靠前的那一组
            }
            List<Integer> run = accepted.get(i);
            if (run == null) {
                out.add(new Group(List.of(hits.get(i))));
            } else {
                List<PositionedHit> members = new ArrayList<>(run.size());
                for (int k : run) {
                    members.add(hits.get(k));
                }
                out.add(new Group(members));
            }
        }
        return out;
    }

    /**
     * 两处命中是否严格相邻：前一段的终点<b>正好</b>是后一段的起点。
     *
     * <p>用「正好等于」而不是「小于等于」：重叠（后一段起点落在前一段里面）不是相邻，
     * 那一段会在归组里被跳过而不是被拼起来。
     */
    private static boolean 紧挨着(PositionedHit a, PositionedHit b) {
        return a.end() == b.start();
    }

    /**
     * 这一段能不能归组。
     *
     * <p>三条硬条件全在这里，任何一条不满足就整段不归组——
     * <b>宁可少归，不可错归</b>：错归会让用户在一个其实不相干的地方做决定。
     */
    private static boolean 可归组(List<PositionedHit> hits, List<Integer> run,
                                   Map<ValueKey, Integer> valueCounts, Set<String> allOriginals) {
        for (int k : run) {
            PositionedHit h = hits.get(k);
            if (!h.positioned()) {
                return false;
            }
            // 条件二：这个值在整篇文档里只出现一次
            if (valueCounts.getOrDefault(new ValueKey(h.ruleKey(), h.original()), 0) > 1) {
                return false;
            }
        }
        // 条件三：拼出来的串不能撞上文档里已有的任何一个原值
        StringBuilder sb = new StringBuilder();
        for (int k : run) {
            sb.append(hits.get(k).original());
        }
        String joined = sb.toString();
        for (int k : run) {
            if (joined.equals(hits.get(k).original())) {
                return false;
            }
        }
        return !allOriginals.contains(joined);
    }
}
