package com.litemask.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 跨单元续接：一个标识符被段落／单元格边界切断时的识别与归类。
 *
 * <p><b>为什么需要它</b>：身份证这类标识符被段落切断是常事。切断之后，
 * 每一段碎片单独看都「不像」完整标识符——规则匹配不上，工具却照样报告「脱敏成功」，
 * 碎片原样留在交付物里。实测里最坏的一种切法是 14 位 + 3 位：
 * 两片都匹配不上任何规则，<b>0 处命中，整张身份证原样留在文档里</b>。
 * 这不是标签错，是泄漏。
 *
 * <p><b>为什么不做跨段合并</b>：把两段拼成一个字符串再整体替换，会毁掉还原——
 * 原文里那个值本来就是断开的，合并成一个令牌后还原不回去（要还原就得把两段重新拆回去，
 * 而拆回的位置信息在合并那一刻已经丢了）。并且多栏版式下相邻两段可能分属两栏，
 * 拼起来是一个「不存在的字符串」。所以这里<b>只归类，不合并</b>：
 *
 * <ol>
 *   <li>识别出「上一单元的最后一段字母数字 + 下一单元的第一段字母数字」拼起来
 *       是一个完整标识符（且过校验器）；</li>
 *   <li>把<b>每一段碎片按这个类型</b>脱敏，各拿各的令牌；</li>
 *   <li>文本本身一个字节都不动 ⇒ 还原仍然逐字一致。</li>
 * </ol>
 *
 * <p>代价是一个身份证被切成两段时会拿到两个令牌（{@code <身份证1>} / {@code <身份证2>}）。
 * 这是「不合并」的必然结果，也是可接受的：漏脱是泄漏，多一个令牌只是多一次还原映射。
 *
 * <p><b>误判方向</b>：误判只会造成「多脱一点」（把两段无关的数字串按某个类型脱敏），
 * 不会造成漏脱——因为一旦判定成立，两段都按该类型脱敏。所以精度要求可以放宽，
 * 但仍然要求拼接串<b>完整匹配</b>某条规则、<b>过校验器</b>、且<b>两段各自都不是完整值</b>。
 */
public final class CrossUnitContinuation {

    /**
     * 一个值最多允许跨几个单元。
     *
     * <p>身份证被切成三段已经少见，四段以上基本是排版噪声。链越长，误判概率越高
     * （每一段都要「整个单元都是字母数字」才接得上），所以这里截断。
     */
    public static final int MAX_CHAIN = 4;

    /**
     * 覆盖：某个单元里的这一段应当按 {@code ruleKey} 脱敏。
     *
     * @param unitIndex 单元序号
     * @param start     段内起始偏移
     * @param end       段内结束偏移（不含）
     */
    public record Override(int unitIndex, int start, int end,
                           String ruleKey, String tokenPrefix, int sortOrder) {
    }

    /** 单元内的一段连续字符。 */
    public record Span(int start, int end) {
        public int length() {
            return end - start;
        }

        public boolean isEmpty() {
            return end <= start;
        }
    }

    private CrossUnitContinuation() {
    }

    /**
     * 单元里<b>最后一段</b>连续字母数字。
     *
     * <p>注意是「单元里最后一段」，不是「单元末尾的那一段」：真实文档里
     * 「身份证110105号」这样的段落，碎片在中间而不在末尾（末尾是「号」）。
     * 只认末尾那一段的话，这种最常见的写法根本不会被认出来。
     *
     * <p>「极大」很关键：片段前后都不是字母数字，于是「这一段 + 下一单元的第一段」
     * 拼起来就是两个相邻单元之间<b>可能</b>连续的那部分，
     * 而不会把一个完整的 11 位手机号再加上下一段的首位凑成 12 位去匹配手机号规则。
     */
    public static Span lastRun(String unit) {
        if (unit == null || unit.isEmpty()) {
            return new Span(0, 0);
        }
        int bestStart = -1;
        int bestEnd = -1;
        int i = 0;
        while (i < unit.length()) {
            if (!isWordChar(unit.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < unit.length() && isWordChar(unit.charAt(i))) {
                i++;
            }
            bestStart = start;
            bestEnd = i;
        }
        return bestStart < 0 ? new Span(0, 0) : new Span(bestStart, bestEnd);
    }

    /** 单元里<b>第一段</b>连续字母数字。见 {@link #lastRun}。 */
    public static Span firstRun(String unit) {
        if (unit == null || unit.isEmpty()) {
            return new Span(0, 0);
        }
        int i = 0;
        while (i < unit.length() && !isWordChar(unit.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < unit.length() && isWordChar(unit.charAt(i))) {
            i++;
        }
        return i == start ? new Span(0, 0) : new Span(start, i);
    }

    /** 整个单元是否都是字母数字（链的中间段必须满足，否则接不上）。 */
    public static boolean isWholeFragment(String unit) {
        if (unit == null || unit.isEmpty()) {
            return false;
        }
        for (int i = 0; i < unit.length(); i++) {
            if (!isWordChar(unit.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 算出全部续接覆盖（不分组：整个单元序列当成一组）。
     *
     * @param units 单元序列，按脱敏顺序
     * @param rules 规则集
     * @return 覆盖列表（同一单元内按 {@code start} 升序；单元之间按单元序号升序）
     */
    public static List<Override> detect(List<String> units, List<Rule> rules) {
        return detectGroups(List.of(units == null ? List.of() : units), rules, ContinuationScope.ANY);
    }

    /**
     * 同上，但整个单元序列当成<b>一组</b>（组间不续接在这里没有意义），
     * 且受 {@link ContinuationScope} 约束。
     */
    public static List<Override> detect(List<String> units, List<Rule> rules, ContinuationScope scope) {
        return detectGroups(List.of(units == null ? List.of() : units), rules, scope);
    }

    /**
     * 算出全部续接覆盖，<b>续接只在同一组内发生</b>。
     *
     * <p><b>为什么要分组</b>：续接的前提是「两段本来就该是连着的」。
     * 在<b>顺序</b>相邻的载体上这个前提成立（上一句接着下一句）；
     * 在<b>空间</b>相邻的载体上不成立——PDF 同一页上左栏末尾与右栏开头也是「相邻」，
     * 跨页、跨内容流更是毫无关系。分组把「可以连着」的边界显式说出来：
     * <b>组内可以续接，组间一律不续接</b>。
     *
     * <p>PDF 的分组 = <b>一个内容流</b>（页或表单 XObject），
     * 组内单元 = 该内容流里的各个文本块。OOXML 的分组 = <b>一个 zip 部件</b>（块 15 起），
     * 组内单元 = 该部件里的各个段落／单元格／共享字符串。
     *
     * @param groups 单元分组，组内按脱敏顺序；各组按脱敏顺序
     * @param rules  规则集
     * @param scope  哪些规则允许参与续接（见 {@link ContinuationScope}）
     * @return 覆盖列表（单元序号是<b>跨组连续</b>的全局序号）
     */
    public static List<Override> detectGroups(List<List<String>> groups, List<Rule> rules,
                                              ContinuationScope scope) {
        List<Override> out = new ArrayList<>();
        if (groups == null || groups.isEmpty() || rules == null || rules.isEmpty()) {
            return out;
        }
        int offset = 0;
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            // 【压实必须在算序号之前做，且与对齐用的单元序列用同一份实现】
            //   序号有两处含义：detect 产出的 unitIndex（覆盖落在哪个单元）、
            //   会话对齐时比对的「第 N 个单元」。
            //   两处只要有一处把空单元算进去、另一处不算，序号就会整体错位
            //   ——覆盖会落到别的单元上，把 A 的碎片按 B 的类型脱掉。
            List<String> compact = compact(group);
            if (compact.isEmpty()) {
                continue;
            }
            out.addAll(detectInGroup(compact, rules, scope, offset));
            offset += compact.size();
        }
        return dedupe(out);
    }

    /**
     * 压实单元序列：丢掉空单元（{@code null} 与空串）。
     *
     * <p>空单元不会产生任何命中，各 redactor 也不会把空文本交给会话，
     * 所以两边都跳过，序号才对得上。<b>覆盖计算与对齐计算必须用同一份</b>。
     */
    public static List<String> compact(List<String> units) {
        if (units == null || units.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(units.size());
        for (String unit : units) {
            if (unit != null && !unit.isEmpty()) {
                out.add(unit);
            }
        }
        return List.copyOf(out);
    }

    /** 单独一组内部的续接检测；{@code offset} 是这一组第一个单元的全局序号。 */
    private static List<Override> detectInGroup(List<String> units, List<Rule> rules,
                                                ContinuationScope scope, int offset) {
        List<Override> out = new ArrayList<>();
        int n = units.size();
        for (int i = 0; i < n; i++) {
            String unit = units.get(i);
            Span tail = lastRun(unit);
            if (tail.isEmpty()) {
                continue;
            }
            String left = unit.substring(tail.start(), tail.end());
            // 链：从单元 i 的最后一段起，向右逐个单元接上去。
            // 中间单元必须「整个都是字母数字」——它若含有非字母数字字符，
            // 说明值在那里已经断了，接上去的只是一个巧合的拼接。
            StringBuilder candidate = new StringBuilder(left);
            int limit = Math.min(n - 1, i + MAX_CHAIN - 1);
            for (int j = i + 1; j <= limit; j++) {
                String seg = units.get(j);
                boolean whole = isWholeFragment(seg);
                Span head = firstRun(seg);
                String right = whole ? seg : (head.isEmpty() ? "" : seg.substring(head.start(), head.end()));
                if (right.isEmpty()) {
                    break;
                }
                candidate.append(right);
                Rule rule = matchRule(rules, candidate.toString(), scope);
                if (rule != null) {
                    // 有一段本身就是完整的该类型值 ⇒ 值没被切断，不该续接。
                    if (coversWhole(rule.pattern(), left) || coversWhole(rule.pattern(), right)) {
                        break;
                    }
                    out.add(new Override(offset + i, tail.start(), tail.end(),
                            rule.key(), rule.tokenPrefix(), rule.sortOrder()));
                    for (int m = i + 1; m < j; m++) {
                        out.add(new Override(offset + m, 0, units.get(m).length(),
                                rule.key(), rule.tokenPrefix(), rule.sortOrder()));
                    }
                    out.add(new Override(offset + j, head.isEmpty() ? 0 : head.start(),
                            head.isEmpty() ? right.length() : head.end(),
                            rule.key(), rule.tokenPrefix(), rule.sortOrder()));
                    break;
                }
                if (!whole) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 判定拼接串是否是一个完整的标识符；是则返回<b>优先级最高</b>的那条规则。
     *
     * <p>「完整匹配」= 正则在拼接串上找到的命中<b>盖住整串</b>。
     * 只要求「命中里包含它」是不够的：那会让任意长度的数字串都被当成半个手机号。
     *
     * <p>规则里的<b>后顾断言会自然落空</b>（{@code (?<=发票号码[：:\s]{0,5})} 这类）：
     * 拼接串前面没有上下文，断言不成立 ⇒ 这类依赖上下文的规则不会参与续接，这是对的——
     * 它们本来就是「看着标签才认」的规则。
     */
    public static Rule matchRule(List<Rule> rules, String candidate) {
        return matchRule(rules, candidate, ContinuationScope.ANY);
    }

    /**
     * 同上，但受 {@link ContinuationScope} 约束：不在范围内的规则不参与。
     *
     * <p>范围的作用点在<b>选规则</b>这一步，而不是「匹配之后再否决」——
     * 否则一条不在范围内的规则仍可能通过「过校验器」这一关（无校验位的规则
     * 什么都收），把两段无关文本拼成一个假的标识符。
     */
    public static Rule matchRule(List<Rule> rules, String candidate, ContinuationScope scope) {
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }
        ContinuationScope effective = scope == null ? ContinuationScope.ANY : scope;
        Rule best = null;
        for (Rule rule : rules) {
            if (rule == null || !rule.active() || rule.category() != Category.REGEX) {
                continue;
            }
            if (!effective.allows(rule)) {
                continue;
            }
            if (!coversWhole(rule.pattern(), candidate)) {
                continue;
            }
            if (!rule.validator().isValid(candidate)) {
                continue;
            }
            if (best == null
                    || rule.sortOrder() > best.sortOrder()
                    || (rule.sortOrder() == best.sortOrder() && rule.key().compareTo(best.key()) < 0)) {
                best = rule;
            }
        }
        return best;
    }

    /** 正则在 candidate 上的命中是否盖住整串。 */
    public static boolean coversWhole(String pattern, String candidate) {
        if (pattern == null || candidate == null || candidate.isEmpty()) {
            return false;
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            return false;
        }
        Matcher matcher = compiled.matcher(candidate);
        return matcher.find() && matcher.start() == 0 && matcher.end() == candidate.length();
    }

    /** 同一单元同一区间只留一条（同优先级同键时保留先来的那条）。 */
    private static List<Override> dedupe(List<Override> raw) {
        List<Override> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingInt(Override::unitIndex)
                .thenComparingInt(Override::start)
                .thenComparingInt(Override::end));
        List<Override> out = new ArrayList<>(sorted.size());
        for (Override o : sorted) {
            boolean dup = false;
            for (Override seen : out) {
                if (seen.unitIndex() == o.unitIndex() && seen.start() == o.start()
                        && seen.end() == o.end() && seen.ruleKey().equals(o.ruleKey())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(o);
            }
        }
        return out;
    }

    private static boolean isWordChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
