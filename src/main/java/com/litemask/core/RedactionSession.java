package com.litemask.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨多段文本的脱敏会话：整篇文档共用一个令牌分配器。
 *
 * <p>纯文本一次 {@link RedactionEngine#redact} 就够了，但文档类载体（docx/xlsx/pptx/pdf）
 * 必须分段调用——每段是独立的文本槽，切出来的偏移只在段内有效。分段调用若各自新建分配器，
 * 会出现两个问题：同一个身份证在不同段落拿到不同编号；还原映射里同一个原值对应多个令牌。
 * 所以这里持有分配器，把各段结果合并成一份全局映射。
 *
 * <p><b>构造顺序有要求</b>：分配器需要在分配前扫一遍全文，避免撞上原文里本来就有的
 * {@code <身份证3>} 之类占位符。因此 {@code fullSourceText} 必须是<b>整篇文档</b>拼接出的文本，
 * 而不是第一段。调用方需要先扫一遍拿到全文，再逐段 redact。
 *
 * <p>本类非线程安全，按单篇文档顺序使用即可。
 */
public final class RedactionSession {

    private final List<Rule> rules;
    private final List<EntitySource> sources;
    private final TokenAllocator allocator;
    private final Exclusions exclusions;
    private final Map<String, String> mapping = new LinkedHashMap<>();
    private final List<Change> changes = new ArrayList<>();
    private final Map<String, Integer> occurrenceCounters = new LinkedHashMap<>();

    /**
     * 是否启用「跨单元续接」。
     *
     * <p>默认<b>关</b>。开启需要两个前提同时成立：
     * <ol>
     *   <li>全文是用 {@code '\n'} 把各单元拼起来的（各 redactor 的 {@code extractFullText}
     *       都是这么拼的）；</li>
     *   <li>脱敏时遍历单元的顺序与全文里的顺序<b>完全一致</b>。</li>
     * </ol>
     * 这两条对 PDF 与 OFD 不成立（PDF 的单元是内容流里的文本块，OFD 的单元是 TextCode
     * 与属性值，混在一起且顺序不同），所以它们不开启。
     */
    private boolean crossUnitEnabled;

    /** 全文按 {@code '\n'} 切出来的非空单元，按文档顺序。 */
    private final List<String> units;

    /** 每个单元的续接覆盖。 */
    private final Map<Integer, List<CrossUnitContinuation.Override>> overridesByUnit = new LinkedHashMap<>();

    /** 下一个待处理单元在 {@link #units} 里的序号。 */
    private int unitCursor;

    /** 对齐失败的次数。0 表示「遍历顺序与全文顺序完全一致」。 */
    private int alignmentFailures;

    /**
     * 本会话已处理了多少个文本单元。进度上报用，界面上显示「已处理 N 段」。
     *
     * <p>它是<b>会话级</b>的：一个会话覆盖整篇文档，所以这个计数是全文的累计，
     * 不是某个部件的局部计数。隐藏路径（{@link #redactHidden}）也算进去——
     * 它同样是实实在在处理过的一段。
     */
    private int unitCounter;

    /**
     * @param fullSourceText 整篇文档的文本，仅用于扫描既有占位符；允许为 null
     * @param rules          规则集
     */
    public RedactionSession(String fullSourceText, List<Rule> rules) {
        this(fullSourceText, rules, Exclusions.NONE);
    }

    /**
     * 带排除项的会话。
     *
     * <p>排除项必须<b>贯穿整篇文档</b>：同一份文件里被排除的值在每一段都要保持原样。
     * 所以它是会话级状态而不是逐段传入——逐段传入的话，调用方得记得每段都传同一份，
     * 漏一处就变成「只脱了一半」，而那正是这个功能最不能出的错。
     */
    public RedactionSession(String fullSourceText, List<Rule> rules, Exclusions exclusions) {
        this(fullSourceText, rules, exclusions, List.of());
    }

    /**
     * 带非正则来源的会话（NER 之类）。
     *
     * <p>来源挂在<b>会话</b>而不是逐段传入，原因与排除项一样：来源必须贯穿整篇。
     * 漏传一段的后果不是「那段少脱一点」，而是那一段的 NER 命中**完全不参与**编号与去重，
     * 于是同一个机构名在第 1 段发 <机构名1>、在漏传的第 3 段又发一个新的 <机构名1>——
     * 还原映射里一个原值对应两个令牌，而用户看到的是一份看着正常、实则还原不回去的文档。
     *
     * <p>来源与规则共用同一条「算序号 → 按排除过滤 → 解析重叠 → 替换」通路，
     * 不给 AI 开第二条替换路径。两条路径的结果在措辞、编号、排除行为上一旦有差异，
     * 用户就会拿到一份规则与 AI 表现不一致的文档，而这种不一致极难解释。
     */
    public RedactionSession(String fullSourceText, List<Rule> rules, Exclusions exclusions,
                            List<EntitySource> sources) {
        this(fullSourceText, rules, exclusions, sources, false);
    }

    /**
     * 带「跨单元续接」开关的会话。
     *
     * <p>开启后，会话会在构造时把全文按 {@code '\n'} 切成单元序列，
     * 算出哪些单元的边界处有一个被切断的标识符（见 {@link CrossUnitContinuation}），
     * 并在脱敏对应单元时把这些碎片按<b>该标识符的类型</b>注入。
     *
     * <p>为什么开关放在会话而不是「自动判断」：自动判断需要一个可靠的办法区分
     * 「全文就是按单元拼的」与「全文是别的东西」——而后者（PDF、OFD）确实存在。
     * 与其猜，不如让每个 redactor 明说自己是不是这种结构。猜错的代价是
     * 在不该续接的地方续接（多栏版式里把两栏拼成一个标识符）。
     *
     * @param crossUnit 是否启用跨单元续接
     */
    public RedactionSession(String fullSourceText, List<Rule> rules, Exclusions exclusions,
                            List<EntitySource> sources, boolean crossUnit) {
        this(fullSourceText, rules, exclusions, sources,
                crossUnit ? List.of(splitUnits(fullSourceText)) : List.of(),
                ContinuationScope.ANY);
    }

    /**
     * 用<b>显式的单元分组</b>构造会话（跨单元续接专用）。
     *
     * <p>与「全文按 {@code '\n'} 切」的构造方式相比，本构造让调用方自己说清楚
     * <b>哪些单元之间允许续接</b>：
     * <ul>
     *   <li>组内允许续接，<b>组间一律不续接</b>（见 {@link CrossUnitContinuation#detect}）；</li>
     *   <li>{@code scope} 决定哪些规则允许参与（见 {@link ContinuationScope}）。</li>
     * </ul>
     * PDF 侧用它：分组 = 一个内容流（页或表单 XObject），组内单元 = 该内容流里的
     * 各个文本块。OOXML 侧（块 15 起）也用它：分组 = 一个 zip 部件，
     * 组内单元 = 该部件里的各个段落／单元格／共享字符串。
     *
     * <p><b>遍历顺序必须与分组顺序一致</b>：会话按顺序比对「当前文本」与
     * 「第 N 个单元」，对不上就整体关掉续接（见 {@link #continuationAlignmentFailures()}）。
     * 这条比对是这套机制的安全底座：宁可漏脱也不错配。
     *
     * @param fullSourceText 整篇文档文本（给分配器扫既有占位符用）
     * @param unitGroups      单元分组；空列表 = 不启用跨单元续接
     * @param scope           哪些规则允许参与续接
     */
    public RedactionSession(String fullSourceText, List<Rule> rules, Exclusions exclusions,
                            List<EntitySource> sources, List<List<String>> unitGroups,
                            ContinuationScope scope) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.allocator = new TokenAllocator(fullSourceText);
        this.exclusions = exclusions == null ? Exclusions.NONE : exclusions;
        this.units = flatten(unitGroups);
        this.crossUnitEnabled = !this.units.isEmpty();
        if (this.crossUnitEnabled) {
            for (CrossUnitContinuation.Override o
                    : CrossUnitContinuation.detectGroups(unitGroups, this.rules, scope)) {
                overridesByUnit.computeIfAbsent(o.unitIndex(), k -> new ArrayList<>()).add(o);
            }
        }
    }

    /** 把分组拍平成一条全局单元序列（单元序号是跨组连续的）。 */
    private static List<String> flatten(List<List<String>> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (List<String> group : groups) {
            // 压实用 CrossUnitContinuation 里的同一份实现：
            // 覆盖计算与对齐计算的单元序号必须完全一致（见那里的说明）。
            out.addAll(CrossUnitContinuation.compact(group));
        }
        return List.copyOf(out);
    }

    /**
     * 全文按 {@code '\n'} 切成单元序列，丢掉空段。
     *
     * <p>丢掉空段是因为各 redactor 都不会把空文本交给会话（空文本不产生任何命中），
     * 而全文里对应位置是一个空段。两边都跳过，序号才对得上。
     */
    private static List<String> splitUnits(String fullSourceText) {
        if (fullSourceText == null || fullSourceText.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        int len = fullSourceText.length();
        while (start <= len) {
            int nl = fullSourceText.indexOf('\n', start);
            int end = nl < 0 ? len : nl;
            String seg = fullSourceText.substring(start, end);
            if (!seg.isEmpty()) {
                out.add(seg);
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return List.copyOf(out);
    }

    /**
     * 对齐失败次数。0 表示「脱敏遍历的单元顺序与全文里的顺序完全一致」。
     *
     * <p>不为 0 意味着续接在某个单元之后被关掉了——那一段之后的跨单元标识符
     * 仍会按段内形状处理（也就是可能漏脱）。这个数被端到端脚本用来盯着：
     * 它一旦不是 0，说明「全文提取」与「脱敏遍历」的结构对不上了。
     */
    public int continuationAlignmentFailures() {
        return alignmentFailures;
    }

    /** 本会话是否仍然在做跨单元续接。 */
    public boolean crossUnitActive() {
        return crossUnitEnabled;
    }

    /** 本会话的排除项。 */
    public Exclusions exclusions() {
        return exclusions;
    }

    /** 本会话的非正则来源。 */
    public List<EntitySource> sources() {
        return sources;
    }

    /** 脱敏一段文本，结果并入全局映射。这段参与出现序号编号。 */
    public RedactionResult redact(String text) {
        return redactInternal(text, true);
    }

    /**
     * 脱敏一段<b>不报给界面</b>的文本：不消耗出现序号。
     *
     * <p>给「改了内容但命中不进清单」的路径用：PDF 的 Author/Creator 等元数据、
     * xlsx 的工作表名 / 定义名称 / 批注文本、OFD 的附件文件名。
     *
     * <p><b>为什么它们不能消耗序号</b>：界面上「这个值出现 3 次，第 2 次在正文里」
     * 是按清单数出来的，而这些路径的命中根本不在清单里。让它们消耗序号，
     * 用户数的序号就会比文件里的实际位置少一截，勾「第 2 次」勾到的不是那一处——
     * 而用户完全看不出来，拿到的是一份按他要求处理过、但处理错了位置的文档。
     *
     * <p>它们<b>照样</b>执行值级排除（{@link HitRef#ANY}）：用户在界面上把这个值整条排除时，
     * 元数据里那一份也该一起留下。位置级排除在这里无从谈起，也不该有。
     *
     * <p>隐藏路径<b>照样</b>跑非正则来源：PDF 的 Author 字段里常常就是一个人的姓名，
     * xlsx 的工作表名里可能是当事人，OFD 的附件文件名里可能带着公司名。
     * 「不进清单」说的是不消耗出现序号，不是「不做脱敏」——这两件事第一版被混成了一件。
     */
    public RedactionResult redactHidden(String text) {
        return redactInternal(text, false);
    }

    private RedactionResult redactInternal(String text, boolean numbered) {
        // 取消检查点 + 进度上报，都放在这一段。
        //
        // 「每段」是管线里最自然的进度单位：docx 的一段、xlsx 的一个位置、
        // pptx 的一页、pdf 的一个文本块、ofd 的一个 TextCode。
        // 报出去的 done 是**累计段数**，total 传 -1（各格式的段总数事先都不知道，
        // 拿估算值糊出一个百分比比「不知道」更糟）。
        TaskContext ctx = TaskContext.current();
        ctx.checkCancelled();
        unitCounter++;
        ctx.progress("脱敏", unitCounter, -1);
        // numbering 传 null 表示不编号，对应引擎的 4 参重载语义；
        // sources 两条路径都要传——隐藏路径不编号，但不等于不脱敏。
        //
        // 【隐藏路径为什么不碰续接游标】
        //   隐藏路径处理的是**元数据**（工作表名、定义名称、批注、附件名、PDF 作者），
        //   它们**不在遍历单元序列里**。而续接的对齐检查是「当前文本必须就是第 N 个单元」，
        //   元数据文本当然不是 → 一次就对不上 → 整个续接被关掉。
        //   后果是「续接能不能生效」取决于 zip 部件的处理顺序（工作表名在
        //   sharedStrings 之前还是之后），**偶发且无提示**。
        //   → 隐藏路径不注入续接命中，也不推进游标。
        RedactionResult result = RedactionEngine.redact(
                text, rules, allocator, exclusions,
                numbered ? this::nextOccurrence : null, sources,
                numbered ? injectedMatches(text) : List.of());
        if (result.isChanged()) {
            // putIfAbsent：令牌全局唯一，重复登记只会掩盖真实的分配冲突
            result.mapping().forEach(mapping::putIfAbsent);
            changes.addAll(result.changes());
        }
        return result;
    }

    /**
     * 取出当前单元的续接覆盖，并推进单元游标。
     *
     * <p><b>对齐是这套机制的安全底座</b>：覆盖是按「单元序号」算出来的，
     * 而覆盖要落在哪个单元上，取决于脱敏遍历的顺序与全文里的顺序是否一致。
     * 两者不一致时，序号就会指错单元——把 A 的碎片按 B 的类型脱掉。
     * 所以这里<b>逐单元核对</b>：当前文本必须就是全文里的第 N 个单元，不符就
     * 把续接整个关掉（宁可漏脱也不错配），并记一次对齐失败。
     *
     * <p>空文本不推进游标：空段在切分时已经被丢掉了，而各 redactor 也不会
     * 把空文本交给会话（空文本不产生任何命中）。两边都跳过，序号才对得上。
     */
    private List<Match> injectedMatches(String text) {
        if (!crossUnitEnabled) {
            return List.of();
        }
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (unitCursor >= units.size() || !units.get(unitCursor).equals(text)) {
            alignmentFailures++;
            crossUnitEnabled = false;
            return List.of();
        }
        int index = unitCursor;
        unitCursor++;
        List<CrossUnitContinuation.Override> overrides = overridesByUnit.get(index);
        if (overrides == null || overrides.isEmpty()) {
            return List.of();
        }
        List<Match> out = new ArrayList<>(overrides.size());
        for (CrossUnitContinuation.Override o : overrides) {
            out.add(new Match(o.start(), o.end(), text.substring(o.start(), o.end()),
                    o.ruleKey(), o.tokenPrefix(), o.sortOrder()));
        }
        return out;
    }

    /**
     * 取某个 {@code (规则键, 原值)} 的下一个出现序号。
     *
     * <p>键用「规则键 + 单位隔符 + 原值」拼成字符串而不是直接拿原值当键：
     * {@code ("phone", "138")} 与 {@code ("phone1", "38")} 拼出来才是不同的一串。
     * 直接用嵌套 Map 当然也行，但这里的原值可能含任何字符，多一个分隔符维度
     * 就少一类「键撞了导致序号串台」的难查故障。
     */
    private int nextOccurrence(String ruleKey, String original) {
        // 分隔符是 NUL（源码里必须写成八进制转义 '\0'）。
        //
        // **不要把它"清理"成空串**。空串分隔下 ("phone", "138") 与 ("phone1", "38")
        // 拼出来是同一串，于是两个不同规则的序号共用一个计数器——用户勾「第 2 次」
        // 勾到的可能是另一个规则的那一处，界面看不出任何异常。
        //
        // 为什么是八进制转义而不是 '\u0000'：Java 的 Unicode 转义在**词法分析之前**
        // 就被替换成真实字符，源文件里照样会留下一个裸 NUL 字节。后果是
        // grep/file/git 把这个 .java 当二进制（于是 grep 静默不输出内容）、
        // diff 变成"二进制文件有差异"、编辑器可能把它吃掉。
        // 这个坑已经踩过一次：源文件里存的就是一个裸 0x00 字节，
        // 全项目搜索 RedactionSession 都搜不到内容。八进制转义是唯一能让
        // 「源码里看不见、文件里不是字节」的写法。
        return occurrenceCounters.merge(ruleKey + '\0' + original, 1, Integer::sum) - 1;
    }

    /** 用本会话的全局映射还原任意文本。 */
    public String restore(String text) {
        return RedactionEngine.restore(text, mapping);
    }

    /** 占位符 → 原文，跨全部段落。 */
    public Map<String, String> mapping() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
    }

    /**
     * 全部替换明细。注意 {@link Change#start()} / {@link Change#end()} 是
     * <b>段内</b>偏移，没有携带段落坐标——需要定位到具体位置的下游应自行记录上下文。
     */
    public List<Change> changes() {
        return List.copyOf(changes);
    }

    public int changeCount() {
        return changes.size();
    }

    public boolean isChanged() {
        return !changes.isEmpty();
    }
}
