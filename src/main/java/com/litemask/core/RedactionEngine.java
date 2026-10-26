package com.litemask.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 脱敏引擎：正则命中 → 占位符替换 → 可逆还原。
 *
 * <p>替换分两步，顺序不能颠倒：
 * <ol>
 *   <li><b>按优先级消解重叠</b>：多条规则命中同一段时，高 sortOrder 胜出；</li>
 *   <li><b>从文末向前应用</b>：这样先替换的位置不会因后续替换而偏移。</li>
 * </ol>
 * 如果把优先级和位置混在一个排序里（先优先级后位置），靠前的替换会让靠后的偏移全部失效——
 * 这是这类引擎最容易写错的地方。
 */
public final class RedactionEngine {

    /** 已占位化的片段：再次脱敏时必须跳过，否则会套娃成 <b>&lt;身份证1&gt;</b>。 */
    private static final Pattern TOKEN_LIKE = Pattern.compile("^<[^<>\\s]+>$");
    private static final Pattern BRACKET_TOKEN_LIKE = Pattern.compile("^\\[[^\\[\\]\\s]+_\\d+]$");

    private RedactionEngine() {
    }

    public static RedactionResult redact(String text, List<Rule> rules) {
        if (text == null) {
            return new RedactionResult("", List.of(), Map.of());
        }
        return redact(text, rules, new TokenAllocator(text));
    }

    /**
     * 用<b>共享</b>分配器脱敏一段文本。
     *
     * <p>文档类场景（docx/xlsx/pptx/pdf）必须走这个入口：整篇文档由若干段落组成，
     * 若每段各自 {@code new TokenAllocator}，同一个身份证在不同段落会拿到不同编号，
     * 既破坏「同值同符」，也让还原映射自相矛盾。
     */
    public static RedactionResult redact(String text, List<Rule> rules, TokenAllocator allocator) {
        return redact(text, rules, allocator, Exclusions.NONE);
    }

    /**
     * 带排除项的脱敏：{@code exclusions} 里的命中原样保留，不分配令牌、不进映射。
     *
     * <p><b>排除必须在重叠消解之前生效，而且要保护整个跨度。</b>
     * 两件事的顺序反了都会出用户看不出来的错：
     * <ul>
     *   <li>先消解重叠再排除：被排除的那段让给了低优先级规则，用户明明点了「别脱」，
     *       文档里却还是被脱了（只是换了个令牌）。</li>
     *   <li>只按 (规则, 值) 排除而不挡跨度：用户排除身份证 {@code 11010519491231002X}，
     *       手机号规则仍可能从里面抠出 {@code 19491231002} 脱掉——
     *       <b>用户排除了整段，文档里却还剩半段</b>。</li>
     * </ul>
     * 所以这里先把「被排除的命中所在的跨度」收集成保护区，再让保护区挡住所有重叠命中。
     */
    public static RedactionResult redact(String text, List<Rule> rules, TokenAllocator allocator,
                                         Exclusions exclusions) {
        return redact(text, rules, allocator, exclusions, null);
    }

    /**
     * 带位置级排除的脱敏。
     *
     * <p><b>顺序是这件事的全部要害，一步都不能换：</b>
     * <ol>
     *   <li><b>先算序号</b>——按「不排除」的口径解析一遍重叠，剩下的就是界面上看得见的那一批。
     *       手机号规则从身份证里抠出的 11 位会被身份证规则挡掉，用户在界面上根本看不到它，
     *       就不该给它占一个序号。</li>
     *   <li><b>再按排除项过滤</b>——这一步<b>必须在解析重叠之前</b>。反过来的话，被排除的那段
     *       已经先让给了高优先级规则，用户明明点了「别脱」，文档里却还是被脱了
     *       （只是换了个令牌）；而且只按值排除不挡跨度时，赢家会留下来，
     *       用户排除了整段、文档里却还剩半段。</li>
     *   <li><b>最后解析重叠</b>。用第 1 步算好的序号而不是现算：现算的话，
     *       排除掉一条会让后面的序号整体前移，用户勾的第 2 条就变成了第 1 条。</li>
     * </ol>
     * @param numbering 跨段序号计数器；传 {@code null} 表示这一轮不编号（CLI、纯文本单段）
     */
    public static RedactionResult redact(String text, List<Rule> rules, TokenAllocator allocator,
                                         Exclusions exclusions, OccurrenceNumbering numbering) {
        return redact(text, rules, allocator, exclusions, numbering, List.of());
    }

    /**
     * 同时跑正则规则与非正则实体来源（本地 NER）。
     *
     * <p>两边的命中在这里<b>汇合成同一种 {@link Match}</b>，之后
     * 「算序号 → 按排除过滤 → 解析重叠 → 替换」全都走下面那一份实现。
     * 刻意不给 AI 命中开第二条替换路径：两条路径各自算偏移，
     * 现场是正文里少字或多字，而报告一切正常。
     *
     * @param sources 非正则来源，可为空列表。任一来源抛异常都会向上传播，
     *                不会被当成「这段没有实体」
     */
    public static RedactionResult redact(String text, List<Rule> rules, TokenAllocator allocator,
                                         Exclusions exclusions, OccurrenceNumbering numbering,
                                         List<EntitySource> sources) {
        return redact(text, rules, allocator, exclusions, numbering, sources, List.of());
    }

    /**
     * 同时跑正则规则、非正则实体来源，并接受<b>外部注入的命中</b>。
     *
     * <p>注入的命中用于「跨单元续接」：某个标识符被段落边界切成两段以后，
     * 每一段单独看都不匹配任何规则。调用方（{@link RedactionSession}）识别出
     * 「两段拼起来是一个完整标识符」，就把每一段作为一条命中注入进来，类型取那个标识符的类型。
     *
     * <p><b>注入的命中和普通命中走完全同一条路</b>：同样参与序号编号、同样参与排除、
     * 同样参与重叠消解。因此一条手机号形状的碎片被判定为身份证的一部分时，
     * 身份证规则（优先级更高）会自然盖住它——不需要在这里写任何「谁盖谁」的特例。
     *
     * <p>注入的命中<b>不再过规则自己的校验器</b>：校验器判的是「这 18 位是不是身份证」，
     * 而注入的片段本身只有 6 位，按段内形状过不了校验是正常的。校验是在<b>拼接后的整串</b>上做的，
     * 那是 {@link CrossUnitContinuation} 的事。
     *
     * @param injected 外部注入的命中；越界或与原文对不上时直接报错，不静默丢弃
     */
    public static RedactionResult redact(String text, List<Rule> rules, TokenAllocator allocator,
                                         Exclusions exclusions, OccurrenceNumbering numbering,
                                         List<EntitySource> sources, List<Match> injected) {
        List<EntitySource> srcs = sources == null ? List.of() : sources;
        boolean noRules = rules == null || rules.isEmpty();
        // 取消检查点。放在最前面：早退路径（空文本、没规则）也要能被取消，
        // 否则「取消一个空任务」会一直返回成功。
        TaskContext ctx = TaskContext.current();
        ctx.checkCancelled();
        // 早退条件必须把来源算进去。只写 `rules.isEmpty()` 的话，
        // 「只启用 NER、不启用任何正则」这条配置会直接返回原文——
        // 一个字都不脱，且界面上显示「0 处命中」，没有任何报错指向它。
        // 注入的命中同样要算进去：只启用续接、不启用任何正则时，
        // 跨单元的碎片仍然必须被脱敏。
        boolean noInjections = injected == null || injected.isEmpty();
        if (text == null || text.isEmpty() || (noRules && srcs.isEmpty() && noInjections)) {
            return new RedactionResult(text == null ? "" : text, List.of(), Map.of());
        }
        Exclusions excl = exclusions == null ? Exclusions.NONE : exclusions;
        List<Match> collected = noRules ? new ArrayList<>() : collectMatches(text, rules, ctx);
        collected.addAll(collectFromSources(text, srcs, ctx));
        if (injected != null && !injected.isEmpty()) {
            collected.addAll(verifyInjected(text, injected));
        }

        if (numbering != null) {
            collected = attachOccurrences(collected,
                    assignOccurrences(resolveOverlaps(collected), numbering));
        }

        List<Match> kept = excl.isEmpty() ? collected : applyExclusions(collected, excl);
        kept = resolveOverlaps(kept);

        if (kept.isEmpty()) {
            return new RedactionResult(text, List.of(), Map.of());
        }
        return apply(text, kept, allocator);
    }

    /**
     * 按优先级占坑。<b>落败命中只丢重叠的那一段，独有部分仍然脱敏。</b>
     *
     * <p>把落败命中整条丢掉，只在「落败区间完全被胜出区间盖住」时是对的——
     * 那是 NER 的机构名 ⊃ 规则命中的「北京」，规则整段落在里面，残余为空。
     * 一旦两者<b>互不包含</b>就会漏：模型把拼音邮箱的头 4 个字认成人名
     * （NER sortOrder 900 &gt; 邮箱规则 120），邮箱整段 [0,20) 因重叠被丢，
     * 只有被盖住的前 4 个字换成令牌，尾巴 {@code gsan@corp.example.com}
     * 就原样躺在交付物里。
     *
     * <p>落败的含义是「不单独占一个令牌」，<b>不是「不用脱」</b>。
     *
     * <p>残余片段放回<b>队尾</b>继续参与，而不是直接接受：它仍可能与后面才被接受的
     * 命中重叠。队列是 FIFO 且排序键固定，所以同一组输入总得到同一组输出。
     */
    private static List<Match> resolveOverlaps(List<Match> matches) {
        List<Match> byPriority = new ArrayList<>(matches);
        byPriority.sort(Comparator.comparingInt(Match::sortOrder).reversed()
                .thenComparingInt(Match::start)
                .thenComparing(Comparator.comparingInt(Match::length).reversed()));
        List<Match> accepted = new ArrayList<>();
        Deque<Match> pending = new ArrayDeque<>(byPriority);
        while (!pending.isEmpty()) {
            Match candidate = pending.poll();
            List<int[]> holes = overlapRanges(accepted, candidate);
            if (holes.isEmpty()) {
                accepted.add(candidate);
                continue;
            }
            pending.addAll(remainderOf(candidate, holes));
        }
        return accepted;
    }

    /** 候选与已接受集合的重叠区间，按起点升序（{@link #remainderOf} 依赖这个次序）。 */
    private static List<int[]> overlapRanges(List<Match> accepted, Match candidate) {
        List<int[]> out = new ArrayList<>();
        for (Match m : accepted) {
            int s = Math.max(m.start(), candidate.start());
            int e = Math.min(m.end(), candidate.end());
            if (s < e) {
                out.add(new int[] { s, e });
            }
        }
        out.sort(Comparator.comparingInt((int[] r) -> r[0]));
        return out;
    }

    /**
     * 候选减去若干区间后剩下的片段，按起点升序。
     *
     * <p>不要求 holes 互不重叠、也不要求已合并：{@code cursor} 只增不减，
     * 被前面区间盖住的部分天然被跳过。
     */
    private static List<Match> remainderOf(Match candidate, List<int[]> holes) {
        List<Match> out = new ArrayList<>(2);
        int cursor = candidate.start();
        for (int[] hole : holes) {
            if (hole[0] > cursor) {
                out.add(slice(candidate, cursor, hole[0]));
            }
            cursor = Math.max(cursor, hole[1]);
        }
        if (cursor < candidate.end()) {
            out.add(slice(candidate, cursor, candidate.end()));
        }
        return out;
    }

    /** 截取候选的一段，规则键、令牌前缀、优先级原样带过去。 */
    private static Match slice(Match m, int from, int to) {
        return new Match(from, to, m.text().substring(from - m.start(), to - m.start()),
                m.ruleKey(), m.tokenPrefix(), m.sortOrder());
    }

    /**
     * 按访问顺序给每组 {@code (规则键, 原值)} 的出现编 0 基序号。
     *
     * <p>先按 start 排序再编号：{@code collectMatches} 是「按规则逐条扫」出来的，
     * 顺序与位置无关。不排的话，同一个值的两处出现可能按规则顺序而不是阅读顺序编号，
     * 界面上「第 2 段那个」就会指到第 3 段去。
     *
     * <p>这里的「访问顺序」是各 redactor 遍历部件的顺序，<b>不等于</b>文件在 Word 里
     * 翻页的顺序（docx 的部件按 zip 条目顺序走）。真正要保证的是：编号与命中清单里
     * 位置说明的顺序一致 —— 界面按清单编号、按位置说明显示，两边对得上就行。
     */
    private static Map<Match, Integer> assignOccurrences(List<Match> accepted,
                                                         OccurrenceNumbering numbering) {
        List<Match> byPosition = new ArrayList<>(accepted);
        byPosition.sort(Comparator.comparingInt(Match::start));
        Map<Match, Integer> ordinals = new LinkedHashMap<>();
        for (Match m : byPosition) {
            ordinals.put(m, numbering.next(m.ruleKey(), m.text()));
        }
        return ordinals;
    }

    /** 把先前算好的序号回填到幸存的命中上。找不到的保持「未编号」。 */
    private static List<Match> attachOccurrences(List<Match> kept, Map<Match, Integer> ordinals) {
        if (ordinals.isEmpty()) {
            return kept;
        }
        List<Match> out = new ArrayList<>(kept.size());
        for (Match m : kept) {
            Integer ordinal = ordinals.get(m);
            out.add(ordinal == null ? m : m.numbered(ordinal));
        }
        return out;
    }

    /** 剔除被排除的命中，并挡掉任何与被排除跨度重叠的命中。 */
    private static List<Match> applyExclusions(List<Match> matches, Exclusions exclusions) {
        List<Match> protectedSpans = new ArrayList<>();
        for (Match m : matches) {
            if (exclusions.covers(m.ruleKey(), m.text(), m.occurrence())) {
                protectedSpans.add(m);
            }
        }
        if (protectedSpans.isEmpty()) {
            return matches;               // 排除项没对上任何命中：不做无谓的过滤
        }
        List<Match> kept = new ArrayList<>(matches.size());
        for (Match m : matches) {
            if (exclusions.covers(m.ruleKey(), m.text(), m.occurrence())) {
                continue;
            }
            boolean covered = false;
            for (Match p : protectedSpans) {
                if (m.start() < p.end() && p.start() < m.end()) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                kept.add(m);
            }
        }
        return kept;
    }

    /**
     * 发令牌并回写。
     *
     * <p>入参已由调用方解析过重叠（{@link #resolveOverlaps}），这里只负责令牌与替换。
     */
    private static RedactionResult apply(String text, List<Match> matches, TokenAllocator allocator) {
        // 第一步：先按文档顺序分配令牌，再从文末向前应用替换。
        // 两件事必须分开做：替换要从尾向前（否则前面的替换会让后面的偏移失效），
        // 但令牌编号必须按**出现顺序**发——否则读者会看到「甲 <身份证2> 乙 <身份证1>」。
        // 合成一趟做的话，编号就跟着应用顺序走，反了。
        List<Match> byPosition = new ArrayList<>(matches);
        byPosition.sort(Comparator.comparingInt(Match::start));
        Map<Integer, String> tokenByStart = new HashMap<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Match match : byPosition) {
            String original = text.substring(match.start(), match.end());
            String token = allocator.allocate(original, match.tokenPrefix());
            tokenByStart.put(match.start(), token);
            mapping.putIfAbsent(token, original);
        }

        // 第二步：替换倒着做（偏移才不会失效），但**记录按访问顺序**发出。
        // 发出顺序不是实现细节：命中清单的每一处都带着一个位置说明（哪一段、哪个单元格），
        // 界面按这个顺序编号、用户按这个顺序勾「第 N 处」。倒序发出去的话，
        // 界面上「第 2 处」指的就不是清单里第 2 条位置说明指的那一处了。
        StringBuilder sb = new StringBuilder(text);
        for (int i = byPosition.size() - 1; i >= 0; i--) {
            Match match = byPosition.get(i);
            sb.replace(match.start(), match.end(), tokenByStart.get(match.start()));
        }
        List<Change> changes = new ArrayList<>(byPosition.size());
        for (Match match : byPosition) {
            changes.add(new Change(text.substring(match.start(), match.end()),
                    tokenByStart.get(match.start()), match.start(), match.end(), match.ruleKey(),
                    match.occurrence()));
        }
        return new RedactionResult(sb.toString(), changes, mapping);
    }

    /**
     * 核对注入的命中确实落在原文的对应位置上。
     *
     * <p><b>为什么要核对</b>：注入的命中带着偏移，偏移错了就会脱错一段——
     * 而脱错的那一段在报告里看起来完全正常（命中数对、令牌对、映射对），
     * 只有把还原后的文本和原文逐字比对才看得出来。静默丢弃更糟：
     * 丢弃掉一条注入命中，效果等于「续接没生效」，工具照样报告成功。
     * 所以这里直接报错，让调用方（会话）在开发期就撞上。
     */
    private static List<Match> verifyInjected(String text, List<Match> injected) {
        List<Match> out = new ArrayList<>(injected.size());
        for (Match m : injected) {
            if (m == null) {
                continue;
            }
            if (m.start() < 0 || m.end() > text.length() || m.start() >= m.end()) {
                throw new IllegalArgumentException(
                        "注入的命中区间越界 [" + m.start() + ", " + m.end() + ")，文本长度 " + text.length());
            }
            String actual = text.substring(m.start(), m.end());
            if (!actual.equals(m.text())) {
                throw new IllegalArgumentException("注入的命中与原文对不上：声明「" + m.text()
                        + "」，实际是「" + actual + "」");
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 收集非正则来源的命中，并做与正则命中<b>完全相同</b>的清洗。
     *
     * <p>共用 {@link #isTokenLike} 过滤已占位化的片段（{@code <身份证1>} / {@code [P_1]}）。
     * 上一轮脱敏的结果若再次被处理，NER 仍会在里面认出「某某公司」这类片段，
     * 而它已经带着令牌——再脱一次就是套娃。这条检查以前只对正则生效，
     * 补上之后 AI 命中与正则命中的口径才一致。
     *
     * <p>一个来源返回 null 视为「没有命中」而不是失败：接口文档要求返回空列表，
     * 但这里放一道兜底，免得某个第三方实现把整个脱敏任务打挂。
     * <b>注意这条兜底只针对 null，不针对「识别失败」</b>——后者必须抛。
     */
    private static List<Match> collectFromSources(String text, List<EntitySource> sources,
                                                  TaskContext ctx) {
        if (sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }
        List<Match> out = new ArrayList<>();
        for (EntitySource source : sources) {
            // 检查点放在每个来源之前：NER 推理是整条管线里最慢的一步，
            // 放在这里才能让「取消」在最贵的地方真的生效。
            ctx.checkCancelled();
            List<Match> found = source.collect(text);
            if (found == null) {
                continue;
            }
            for (Match m : found) {
                if (m == null || m.text() == null || m.text().isEmpty()) {
                    continue;
                }
                if (isTokenLike(m.text())) {
                    continue;
                }
                out.add(m);
            }
        }
        return out;
    }

    /** 命中文本是否已经是令牌。正则与 AI 来源共用这一个判据。 */
    private static List<Match> collectMatches(String text, List<Rule> rules, TaskContext ctx) {
        List<Match> out = new ArrayList<>();
        for (Rule rule : rules) {
            // 每条规则一个检查点。一段文本要跑十几条规则，
            // 规则越多，单条规则占的比重越小——但检查点只多几纳秒，
            // 换来的是「取消」在长文档上也能在可接受的时间内生效。
            ctx.checkCancelled();
            if (!rule.active() || rule.category() != Category.REGEX) {
                continue;
            }
            Pattern pattern;
            try {
                pattern = Pattern.compile(rule.pattern(), Pattern.DOTALL);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("规则 " + rule.key() + " 正则非法: " + rule.pattern(), e);
            }
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String raw = matcher.group();
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                // 修剪首尾空白并同步修正偏移
                int from = 0;
                int to = raw.length();
                while (from < to && Character.isWhitespace(raw.charAt(from))) {
                    from++;
                }
                while (to > from && Character.isWhitespace(raw.charAt(to - 1))) {
                    to--;
                }
                if (from >= to) {
                    continue;
                }
                String hit = raw.substring(from, to);
                if (isTokenLike(hit)) {
                    continue;
                }
                if (!rule.validator().isValid(hit)) {
                    continue;
                }
                out.add(new Match(matcher.start() + from, matcher.end() - (raw.length() - to),
                        hit, rule.key(), rule.tokenPrefix(), rule.sortOrder()));
            }
        }
        return out;
    }

    /**
     * 还原：单次扫描替换，不做级联。
     *
     * <p>逐个 {@code replace} 会让先还原出来的原文被后续 token 再替换一次（原文里若恰好含有
     * 另一个 token 字面量就会连锁损坏）。合成一个正则一次扫描可以杜绝这种情况。
     */
    public static String restore(String text, Map<String, String> mapping) {
        return restoreCounted(text, mapping).text();
    }

    /**
     * 还原，并报出<b>实际替换了几处</b>。
     *
     * <p>为什么要有这个：界面与命令行都要告诉用户「还原了 N 处」。
     * 少了这个数，用户只能靠「文件看起来变了没有」来判断还原有没有生效——
     * 而还原一份没被脱过的东西，文件会**一模一样**，那正是最该被指出来的情况。
     *
     * <p>计的是<b>实际发生的替换次数</b>（非重叠匹配），不是映射里有多少条：
     * 同一个值在文档里出现三次就替换三处，报「1」会让用户以为还有两处没还原。
     */
    public record Restored(String text, int count) {
    }

    public static Restored restoreCounted(String text, Map<String, String> mapping) {
        if (text == null || text.isEmpty() || mapping == null || mapping.isEmpty()) {
            return new Restored(text, 0);
        }
        String alternation = mapping.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Pattern pattern = Pattern.compile("(?:" + alternation + ")");
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int count = 0;
        while (matcher.find()) {
            TaskContext.current().checkCancelled();
            String matched = matcher.group();
            String replacement = mapping.get(matched);
            TaskContext.current().reserveRestoreGrowth(
                    Math.max(0L, (long) replacement.length() - matched.length()));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            count++;
        }
        matcher.appendTail(sb);
        return new Restored(sb.toString(), count);
    }

    static boolean isTokenLike(String s) {
        return TOKEN_LIKE.matcher(s).matches() || BRACKET_TOKEN_LIKE.matcher(s).matches();
    }
}
