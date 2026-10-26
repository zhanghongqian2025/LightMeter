package com.litemask.pdf;

import com.litemask.core.EntitySource;
import com.litemask.core.Change;
import com.litemask.core.ContinuationScope;
import com.litemask.core.HitGrouping;
import com.litemask.core.RedactionSession;
import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import com.litemask.core.TaskContext;
import com.litemask.core.SlotTextMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 脱敏管线。
 *
 * <p>覆盖三处，缺一不可：
 * <ol>
 *   <li><b>页面内容流</b>与<b>表单 XObject</b>里的文本——这是「看得见的字」；</li>
 *   <li><b>版面</b>——令牌与原文宽窄不同，靠 TJ 位移补偿把后续文字按原位摆回去；</li>
 *   <li><b>不显示但读得到的东西</b>——元数据、批注作者、内嵌附件、表单值，见
 *       {@link PdfMetadataScrubber}。只做第一条就会出现「零命中，却仍能复制出身份证号」。</li>
 * </ol>
 *
 * <p>一条硬约束贯穿全流程：<b>解不出字符就不脱</b>。字体缺 ToUnicode 时字符无法还原成文本，
 * 此时继续下去等于「看着处理了、其实没处理」，因此直接拒绝并说明原因。
 */
public final class PdfRedactor {

    /** 表单 XObject 的最大递归深度。正常文档 1~2 层；再深即畸形或刻意构造。 */
    private static final int MAX_FORM_DEPTH = 8;

    /** 令牌字体在资源字典里的固定名字，方便重复运行时识别已登记过的。 */
    private static final String TOKEN_FONT_NAME = "LitemaskToken";

    public static final class Options {
        /**
         * 令牌字体。必须是标准 14 字体：任何阅读器都自带，无需内嵌、零体积、绝无缺字形。
         * 用中文字体就得往安装包里塞几 MB 字体，还要面对子集缺字形导致的「豆腐块」。
         */
        public Standard14Fonts.FontName tokenFont = Standard14Fonts.FontName.HELVETICA;
        /** 是否清理元数据等隐藏信息。 */
        public boolean scrubMetadata = true;
    }

    public record Report(RedactionResultView result,
                         Map<String, String> mapping,
                         List<String> warnings,
                         List<String> removedMetadata,
                         int pageCount,
                         int unitCount,
                         int textChars,
                         int invisibleChars,
                         List<Hit> hits) {

        /** 文档根本没有文本层（通常是扫描件）。此时「零命中」不等于「文档干净」。 */
        public boolean hasTextLayer() {
            return textChars > 0;
        }

        /** 存在不可见文本（Tr=3），通常是扫描件上盖的一层 OCR 结果。 */
        public boolean hasInvisibleText() {
            return invisibleChars > 0;
        }

        public int changeCount() {
            return result.changes().size();
        }
    }

    /**
     * 一处带位置的命中。
     *
     * <p>与 docx/xlsx 的 {@code Hit} 同构，字段含义也一致：
     * {@code part} 是「在哪儿」，{@code locator} 是「在哪儿里的哪一块」，
     * {@code unit} 是归组用的单元标识。
     *
     * <p><b>为什么 PDF 以前没有这一层</b>：命中在 PDF 里被摊平成一串 {@code Change}，
     * 内容流的单元 id 与块号在摊平那一步丢了。丢的不只是「给人看的位置」——
     * 还丢了「两处命中是不是在同一个文本块里」这个判断依据，
     * 于是 PDF 既给不出位置，也无法参与归组（见 {@link HitGrouping} 的说明）。
     *
     * @param part        内容流标签：{@code 第 1 页} 或 {@code 第 1 页 / 表单 widget1}
     * @param locator     块内位置：{@code 文本块 3}
     * @param original    命中的原值
     * @param token       <b>PDF 里实际写入的 ASCII 令牌</b>（不是引擎发出来的中文令牌）
     * @param engineToken 引擎发出来的中文令牌。两个都记：映射文件里两个都写，
     *                     免得有人拿着中文令牌去 PDF 里找那个根本不存在的串
     * @param ruleKey     命中的规则键
     * @param start       命中在<b>文本块</b>内的起点
     * @param end         命中在文本块内的终点（不含）
     * @param unit        归组用的单元标识（内容流 + 文本块）
     * @param occurrence  同一 {@code (规则键, 原值)} 在整篇文档里的第几次出现，0 基；
     *                    {@link com.litemask.core.Match#UNNUMBERED} 表示这一轮没有编号。
     *                    界面上「只保留第 N 处」用的就是这个数，序号由引擎分配。
     */
    public record Hit(String part, String locator, String original, String token, String engineToken,
                       String ruleKey, int start, int end, String unit, int occurrence) {
    }

    /** 与其他格式对齐的最小结果视图。 */
    public record RedactionResultView(String sanitizedText, List<Change> changes,
                                      Map<String, String> mapping) {
        public boolean isChanged() {
            return !changes.isEmpty();
        }
    }

    private PdfRedactor() {
    }

    // ================================================================ 对外入口

    public static Report redact(Path input, Path output, List<Rule> rules) throws IOException {
        return redact(input, output, rules, new Options());
    }

    public static Report redact(Path input, Path output, List<Rule> rules, Options opt)
            throws IOException {
        return redact(input, output, rules, opt, Exclusions.NONE);
    }

    /** 带排除项的脱敏；排除项在会话级生效，贯穿整份 PDF。 */
    public static Report redact(Path input, Path output, List<Rule> rules, Options opt,
                                Exclusions exclusions) throws IOException {
        return redact(input, output, rules, opt, exclusions, List.of());
    }

    /**
     * 带非正则来源（NER 之类）的脱敏；来源与规则共用同一个会话。
     *
     * <p>PDF 上这一条尤其要紧：页文本是按槽拼出来的，来源要作用在<b>拼好之后</b>的串上，
     * 而不是每个槽上——「王小明」若被拆到两个槽里，逐槽识别必然漏。
     * {@link SlotTextMapper} 已经把槽映射回整段文本，走它就是拼好之后再脱。
     */
    public static Report redact(Path input, Path output, List<Rule> rules, Options opt,
                                Exclusions exclusions, List<EntitySource> sources) throws IOException {
        try (PDDocument doc = open(input)) {
            List<Unit> units = collectUnits(doc);
            // 跨单元续接（PDF 侧）：分组 = **一个内容流一组**，组内单元 = 该内容流的各个文本块。
            //
            // 【为什么 PDF 侧的适用范围与 OOXML 不同】
            //   OOXML 的相邻单元是**顺序**相邻（上一句接着下一句），任何规则都能参与续接；
            //   PDF 的相邻块是**空间**相邻——同一页两栏也是「相邻」，
            //   两栏里各一个不相干的数字串拼起来也可能凑成一个合法手机号。
            //   → PDF 侧只允许**有真校验位**的规则参与（见 ContinuationScope）。
            //
            // 【为什么 PDF 侧现在能开这个开关了】
            //   块 13 说「PDF 的单元是文本块，与全文的分段口径对不上」——
            //   那是真的：当时全文是按**内容流**拼的，遍历却是按**块**。
            //   现在两边都用 splitBlocks 这一个口径（见 unitGroups）。
            RedactionSession session = new RedactionSession(collectAllText(units), rules, exclusions, sources,
                    unitGroups(units), ContinuationScope.CHECK_DIGIT_ONLY);
            List<Change> all = new ArrayList<>();
            Map<String, String> mapping = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            Outcome outcome = process(doc, units, opt, true, warnings, (slots, joined) -> {
                SlotTextMapper.Mapped mapped = SlotTextMapper.map(slots, session);
                for (Change c : mapped.changes()) {
                    mapping.putIfAbsent(PdfTokens.asciiToken(c), c.original());
                }
                return mapped.changes();
            });
            all.addAll(session.changes());
            // 不变式：位置清单与改动清单必须**一一对应且顺序一致**。
            // 界面上的「第 N 次出现」是按这份顺序数的；两者一旦错位，
            // 用户勾「第 2 次」勾到的就不是那一处，而界面上看不出任何异常。
            // 这条不是「防御性编程」：它守的是本类自己刚建立起来的契约。
            if (outcome.hits().size() != all.size()) {
                throw new IllegalStateException("PDF 的命中位置数(" + outcome.hits().size()
                        + ")与改动数(" + all.size() + ")对不上："
                        + "位置清单与「第 N 次出现」的编号会错位，拒绝出件。");
            }
            // 不变式（顺序）：**数量相等推不出顺序相等**——两个等长的序列完全可以顺序不同。
            // 而顺序错了的代价与数量错了一样：用户勾「第 2 次」勾到的不是那一处，
            // 界面上同样看不出异常。所以顺序要单独守，不能靠「数量对上了」顺带认为没问题。
            if (!sameOrder(outcome.hits(), all)) {
                throw new IllegalStateException("PDF 的命中位置顺序与改动顺序对不上："
                        + "位置清单与「第 N 次出现」的编号会错位，拒绝出件。");
            }
            List<String> removed = opt.scrubMetadata
                    // 元数据：改了内容但不进命中清单，所以走 redactHidden 不占出现序号
                    ? PdfMetadataScrubber.scrub(doc, t -> session.redactHidden(t).sanitizedText())
                    : List.of();
            save(doc, output);
            return report(all, mapping, warnings, removed, outcome.stats(), outcome.hits());
        }
    }

    /**
     * 还原：把 PDF 里的 ASCII 令牌换回原文。
     *
     * <p>换回时用**该算子原本的字体**编码（{@link PdfStreamRewriter.Render#NATIVE}），
     * 而不是令牌字体——否则中文人名在 Helvetica 里根本没有字形。原件本来就是这个字体写的，
     * 换回去之后宽度也自然回到原值，所以不需要任何位移补偿。
     */
    public static Report restore(Path input, Path output, Map<String, String> mapping)
            throws IOException {
        try (PDDocument doc = open(input)) {
            List<String> warnings = new ArrayList<>();
            List<Change> all = new ArrayList<>();
            Pattern pattern = tokenPattern(mapping);
            // 命中的令牌必须逐条记账：只统计数量的话，用户会看到「还原 0 处」，
            // 而文件里其实已经换回去了——报告与事实不符，用户没法判断该不该信它。
            Outcome outcome = process(doc, collectUnits(doc), new Options(), false, warnings, (slots, joined) -> {
                List<Change> found = findTokens(joined, pattern, mapping);
                all.addAll(found);
                return found;
            });
            List<String> removed =
                    PdfMetadataScrubber.scrub(doc, t -> restoreText(t, mapping, pattern));
            save(doc, output);
            // 还原的命中不报位置：还原件里的令牌位置与原件的原文位置没有对应关系，
            // 报一个「第 N 页」会让人以为原件的敏感信息也在那一页。
            return report(all, Map.copyOf(mapping), warnings, removed, outcome.stats(), List.of());
        }
    }

    /** 打开文档；需要口令的要说人话，而不是把 PDFBox 的英文异常直接甩给用户。 */
    private static PDDocument open(Path input) throws IOException {
        try {
            return Loader.loadPDF(input.toFile());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new IOException("PDF 需要口令才能打开，本工具不处理加密文档："
                    + "无法确认内容流可读，继续下去可能既没脱干净、又被报告成已脱敏。", e);
        }
    }

    // ================================================================ 主流程

    /** 在一条内容流的各文本槽（= 各显示算子）上寻找要替换的区间。 */
    private interface EditFinder {
        List<Change> find(List<String> slots, String joined);
    }

    /**
     * 一条内容流的处理结果。
     *
     * @param stats 改写前统计出来的文档规模
     * @param hits  带位置的命中；<b>只在脱敏时有值</b>（还原的命中不报位置）
     */
    private record Outcome(Stats stats, List<Hit> hits) {
    }

    private static Outcome process(PDDocument doc, List<Unit> units, Options opt, boolean isRedact,
                                   List<String> warnings, EditFinder findEdits)
            throws IOException {
        // 空用户口令的「加密」PDF（比如带 DRM 的商业文件）能正常解开，就正常处理，
        // 但输出时要把加密去掉——脱敏结果不该再带着权限限制。
        // 需要真口令而打不开的，PDFBox 在这里就抛异常，捕获后转成明确的人话。
        if (doc.isEncrypted()) {
            doc.setAllSecurityToBeRemoved(true);
        }
        PDType1Font tokenFont = new PDType1Font(opt.tokenFont);
        Map<PDResources, String> registered = new IdentityHashMap<>();
        List<Hit> hits = new ArrayList<>();

        for (Unit unit : units) {
            TaskContext.current().checkCancelled();
            // 只在同一行内拼接：跨行硬拼会造出原文里不存在的字符串，把真敏感信息顶掉
            Map<Integer, List<PdfStreamRewriter.OpEdit>> allEdits = new LinkedHashMap<>();
            int blockIndex = 0;
            for (TextBlock block : splitBlocks(unit)) {
                TaskContext.current().checkCancelled();
                // findEdits 只能调一次：它会驱动引擎分配令牌（副作用），
                // 调两次就会凭空多出一批令牌与改动。
                List<Change> blockChanges = findEdits.find(block.slots(), block.joined());
                if (isRedact) {
                    // 位置记在这一层：**块内偏移**才是「同一次出现」的坐标。
                    // 用全文偏移的话，两个不同文本块里的命中会算出「看起来紧挨着」的假象，
                    // 归组就会把两段不相干的内容拼成一个不存在的标识符。
                    for (Change c : blockChanges) {
                        hits.add(new Hit(unit.id(), blockLocator(blockIndex), c.original(),
                                PdfTokens.asciiToken(c), c.token(), c.ruleKey(),
                                c.start(), c.end(), blockUnit(unit.id(), blockIndex),
                                c.occurrence()));
                    }
                }
                for (var e : toOpEdits(unit.slots(), blockChanges, block.from(), isRedact).entrySet()) {
                    allEdits.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
                }
                blockIndex++;
            }
            if (allEdits.isEmpty()) {
                continue;
            }
            String fontName = isRedact ? registerTokenFont(unit.resources(), tokenFont, registered)
                    : null;
            PdfStreamRewriter.Result rewritten = PdfStreamRewriter.rewrite(
                    unit.content(), ContentScanner.scan(unit.content()), unit.ops(), allEdits,
                    isRedact ? PdfStreamRewriter.Render.TOKEN : PdfStreamRewriter.Render.NATIVE,
                    tokenFont, fontName, name -> unit.resources().getFont(COSName.getPDFName(name)),
                    warnings);
            // 整条内容流只回写一次。逐块回写会出事：后一块拿到的是**原始**字节，
            // 重建完会把前一块的成果整个冲掉，而且不报任何错。
            checkLayout(unit, rewritten, warnings);
            unit.write(rewritten.content());
        }
        return new Outcome(measure(doc, units), List.copyOf(hits));
    }

    /**
     * 文本块的人话位置。
     *
     * <p>叫「文本块」而不是「行」：块的边界是<b>定位算子</b>
     * （Tm、Td、TD、T*、BT、ET），而排版工具在一行之内也可能重新定位。
     * 绝大多数情况下两者重合，但说成「行」就是在骗用户——
     * 用户按「第 3 行」去找，找不到第 3 行时会以为是自己数错了。
     */
    private static String blockLocator(int blockIndex) {
        return "文本块 " + (blockIndex + 1);
    }

    /** 归组用的单元标识：内容流 + 文本块，两者合起来才唯一。 */
    private static String blockUnit(String unitId, int blockIndex) {
        return HitGrouping.unitId(unitId, "B" + (blockIndex + 1));
    }

    /**
     * 位置清单与改动清单是否**逐条同序**。
     *
     * <p>比的是「规则键 + 原值」这一对：两边的偏移**不可比**——
     * 位置清单里的偏移是<b>块内</b>偏移，跨块时本来就没有共同基准，
     * 能比的只有「这一条对应哪一条」。
     *
     * <p><b>为什么这一条会成立</b>：位置与改动来自<b>同一次</b> redact 调用——
     * {@code SlotTextMapper.map} 返回的是改动列表的原顺序（它排序的那份副本
     * 只用来倒着应用编辑），会话再按调用先后累积改动。于是两者天然同序。
     * 本方法把这个事实<b>钉住</b>：将来谁改了 map 的排序、或改了会话的累积方式，
     * 这里会立刻炸，而不是等到用户勾错位置才发现。
     */
    private static boolean sameOrder(List<Hit> hits, List<Change> changes) {
        if (hits.size() != changes.size()) {
            return false;
        }
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            Change c = changes.get(i);
            if (!h.ruleKey().equals(c.ruleKey()) || !h.original().equals(c.original())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 回写后的版面自检：改写前后的每段末尾位移必须一致。
     *
     * <p>这是一条**产品级**校验，不是测试脚手架：位移补偿算错时，PDF 照样能打开、
     * 文字照样抽得出来，肉眼在缩略图上也未必看得出来——但版面已经歪了。
     * 与 {@link ContentInterpreter} 一样，这里用独立按规范重写的宽度公式
     * （{@link TextAdvanceSimulator}）而不是回写器自己的算式：共用一个函数的话，
     * 补偿错了两边会一起错，这条校验就变成永远为真的空转。
     *
     * <p>只报不抛：版面歪了不等于泄密，把它升级成硬失败会让一份脱敏干净的文档
     * 直接产不出来。但也**不能吞**——自检自己跑不起来（字体解析不了、内容流畸形）
     * 同样要说出来，否则「没告警」会被误读成「查过了没问题」。
     */
    private static void checkLayout(Unit unit, PdfStreamRewriter.Result rewritten,
                                    List<String> warnings) {
        List<String> problems;
        try {
            ContentInterpreter.FontResolver resolver =
                    name -> unit.resources().getFont(COSName.getPDFName(name));
            problems = TextAdvanceSimulator.compareDisplacement(
                    TextAdvanceSimulator.simulate(unit.content(), resolver),
                    TextAdvanceSimulator.simulate(rewritten.content(), resolver),
                    LAYOUT_TOLERANCE_PT);
        } catch (IOException | RuntimeException e) {
            warnings.add("版面自检未能完成（" + unit.id() + "）：" + e
                    + "；无法确认版面是否保持原样");
            return;
        }
        if (!problems.isEmpty()) {
            warnings.add("版面位移与原文不一致（" + unit.id() + "，共 " + problems.size()
                    + " 处）：" + String.join("；", problems));
        }
    }

    /**
     * 版面比较的容差，单位 pt。
     *
     * <p>模拟器用 float 累加，几十个算子之后末位会有舍入；容差取 0.01pt 约等于
     * 300dpi 下 1/9 像素，肉眼与打印都不可见，同时远小于任何真实的漏补/错补
     * （令牌字号下差一个字符就是数个 pt）。
     */
    private static final double LAYOUT_TOLERANCE_PT = 0.01;

    private record Stats(int pageCount, int unitCount, int textChars, int invisibleChars) {
    }

    /** 在改写之前统计：此时统计到的才是原文的字符量，统计令牌没有意义。 */
    private static Stats measure(PDDocument doc, List<Unit> units) {
        int chars = 0;
        int invisible = 0;
        for (Unit u : units) {
            for (int i = 0; i < u.ops().size(); i++) {
                int n = u.slots().get(i).length();
                chars += n;
                if (u.ops().get(i).invisible()) {
                    invisible += n;
                }
            }
        }
        return new Stats(doc.getNumberOfPages(), units.size(), chars, invisible);
    }

    private static Report report(List<Change> all, Map<String, String> mapping,
                                 List<String> warnings, List<String> removed, Stats stats,
                                 List<Hit> hits) {
        RedactionResultView view = new RedactionResultView("", List.copyOf(all), Map.copyOf(mapping));
        return new Report(view, Map.copyOf(mapping), List.copyOf(warnings), List.copyOf(removed),
                stats.pageCount(), stats.unitCount(), stats.textChars(), stats.invisibleChars(),
                List.copyOf(hits));
    }

    private static void save(PDDocument doc, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // 整体重写而不是增量保存：增量保存会把旧版本原封不动留在文件里，
        // 那些旧版本里可能就有未脱敏的原文。
        doc.save(output.toFile());
    }

    // ================================================================ 令牌与改动

    private static Pattern tokenPattern(Map<String, String> mapping) {
        String alternation = mapping.keySet().stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
        return Pattern.compile("(?:" + alternation + ")");
    }

    private static List<Change> findTokens(String text, Pattern pattern, Map<String, String> mapping) {
        List<Change> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            TaskContext.current().checkCancelled();
            String token = m.group();
            String original = mapping.get(token);
            TaskContext.current().reserveRestoreGrowth(
                    Math.max(0L, (long) original.length() - token.length()));
            out.add(new Change(original, token, m.start(), m.end(), "restore"));
        }
        return out;
    }

    private static String restoreText(String text, Map<String, String> mapping, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            TaskContext.current().checkCancelled();
            String token = m.group();
            String original = mapping.get(token);
            TaskContext.current().reserveRestoreGrowth(
                    Math.max(0L, (long) original.length() - token.length()));
            sb.append(text, last, m.start()).append(original);
            last = m.end();
        }
        return sb.append(text.substring(last)).toString();
    }

    /**
     * 把「拼接坐标」上的命中摊到各显示算子上。
     *
     * <p>跨算子的命中是常态：一个手机号可能被排版工具切成 {@code (1380013)} 与 {@code (8000)} 两段。
     * 令牌只写进**第一个**算子，后续算子只删字符——否则同一个令牌会画两遍。
     */
    private static Map<Integer, List<PdfStreamRewriter.OpEdit>> toOpEdits(
            List<String> slots, List<Change> changes, int slotBase, boolean isRedact) {
        if (slots.isEmpty() || changes.isEmpty()) {
            return Map.of();
        }
        // 改动坐标是「块内拼接」的坐标，这里换成全文坐标再摊到算子上
        // base[i] 是槽 i 在块内拼接串中的起点；**哨兵 base[slots.size()] 也必须算出来**，
        // 少算一位会让「恰好落在块末尾」的命中的右边界变成 0，从而被当成空区间丢掉——
        // 也就是静默漏脱，而且一次都不报错。
        int[] base = new int[slots.size() + 1];
        base[slotBase] = 0;
        for (int i = slotBase + 1; i <= slots.size(); i++) {
            base[i] = base[i - 1] + slots.get(i - 1).length();
        }
        int[] offs = base;
        Map<Integer, List<PdfStreamRewriter.OpEdit>> out = new LinkedHashMap<>();
        for (Change c : changes) {
            int start = c.start();
            int end = c.end();
            // 脱敏写令牌，还原写原文——写错就是「什么都没改」且不报错
            String replacement = isRedact ? PdfTokens.asciiToken(c) : c.original();
            int firstOp = slotBase;
            while (firstOp < slots.size() && offs[firstOp + 1] <= start) {
                firstOp++;
            }
            for (int i = firstOp; i < slots.size() && offs[i] < end; i++) {
                int s = Math.max(start, offs[i]);
                int t = Math.min(end, offs[i + 1]);
                if (s >= t) {
                    continue;
                }
                out.computeIfAbsent(i, k -> new ArrayList<>())
                        .add(new PdfStreamRewriter.OpEdit(s - offs[i], t - offs[i],
                                i == firstOp ? replacement : null));
            }
        }
        return out;
    }

    private static String collectAllText(List<Unit> units) {
        StringBuilder sb = new StringBuilder();
        for (Unit u : units) {
            sb.append(String.join("", u.slots())).append('\n');
        }
        return sb.toString();
    }

    /** 把令牌字体登记进资源字典（每个字典只登记一次），返回资源名。 */
    private static String registerTokenFont(PDResources resources, PDType1Font tokenFont,
                                            Map<PDResources, String> registered) throws IOException {
        String cached = registered.get(resources);
        if (cached != null) {
            return cached;
        }
        for (COSName n : resources.getFontNames()) {
            if (TOKEN_FONT_NAME.equals(n.getName())) {
                registered.put(resources, n.getName());
                return n.getName();
            }
        }
        COSName name = resources.add(tokenFont);
        registered.put(resources, name.getName());
        return name.getName();
    }

    // ================================================================ 内容流收集

    private interface StreamTarget {
        void write(byte[] data) throws IOException;
    }

    /** 一条待处理的内容流：页面自身，或页面引用的某个表单 XObject。 */
    private record Unit(String id, byte[] content, List<ShowTextOp> ops, List<String> slots,
                        boolean[] repositionBefore, PDResources resources, StreamTarget target) {
        void write(byte[] data) throws IOException {
            target.write(data);
        }
    }

    /**
     * 一个文本块：<b>同一行内、没有重新定位</b>的一串文本算子。
     *
     * <p>块的边界来自「有没有定位算子」：{@code Tm / Td / TD / T* / BT / ET} 都会
     * 划开一块。多数 PDF 一个换行就是一次 {@code Td}，所以<b>块大体等于行</b>。
     *
     * @param from  块内第一个槽在该内容流全部槽里的下标
     * @param to    块内最后一个槽的下标（不含）
     * @param slots 块内的文本槽
     */
    private record TextBlock(int from, int to, List<String> slots) {
        String joined() {
            return String.join("", slots);
        }
    }

    /**
     * 把一个内容流切成文本块。
     *
     * <p><b>这是块口径的唯一定义处</b>：脱敏遍历（{@code process}）与
     * 跨单元续接的单元分组（{@link #unitGroups}）都必须用它——
     * 两边口径不一致时，续接的对齐检查会判红并把续接整个关掉
     * （症状与「续接机制坏了」一模一样，极难定位）。
     */
    private static List<TextBlock> splitBlocks(Unit unit) {
        List<TextBlock> out = new ArrayList<>();
        int n = unit.slots().size();
        int from = 0;
        while (from < n) {
            int to = from + 1;
            while (to < n && !unit.repositionBefore()[to]) {
                to++;
            }
            out.add(new TextBlock(from, to, List.copyOf(unit.slots().subList(from, to))));
            from = to;
        }
        return out;
    }

    /**
     * 跨单元续接的<b>单元分组</b>：一个内容流一组，组内是它的各个文本块。
     *
     * <p><b>为什么按内容流分组</b>：续接的前提是「两段本来就该是连着的」。
     * PDF 里相邻的两个块可能压根不相关——同一页左栏的末尾与右栏的开头也是「相邻」，
     * 不同页、不同表单 XObject 之间更是毫无关系。分组把「可以连着」的边界说出来：
     * <b>组内可以续接，组间一律不续接</b>。
     */
    private static List<List<String>> unitGroups(List<Unit> units) {
        List<List<String>> groups = new ArrayList<>();
        for (Unit unit : units) {
            List<String> texts = new ArrayList<>();
            for (TextBlock block : splitBlocks(unit)) {
                texts.add(block.joined());
            }
            groups.add(texts);
        }
        return groups;
    }

    private static List<Unit> collectUnits(PDDocument doc) throws IOException {
        List<Unit> units = new ArrayList<>();
        Set<Object> seenForms = Collections.newSetFromMap(new IdentityHashMap<>());
        int pageIndex = 0;
        for (PDPage page : doc.getPages()) {
            byte[] content = concatPageContent(page);
            if (content.length > 0) {
                String label = "第 " + (pageIndex + 1) + " 页";
                units.add(makeUnit(label, content, page.getResources(),
                        data -> page.setContents(new PDStream(doc, new ByteArrayInputStream(data)))));
                collectForms(page.getResources(), "第 " + (pageIndex + 1) + " 页", 1, units, seenForms);
            }
            pageIndex++;
        }
        return units;
    }

    private static void collectForms(PDResources resources, String pageLabel, int depth,
                                     List<Unit> units, Set<Object> seenForms) throws IOException {
        if (resources == null || depth > MAX_FORM_DEPTH) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject;
            try {
                xobject = resources.getXObject(name);
            } catch (IOException e) {
                continue;
            }
            if (!(xobject instanceof PDFormXObject form) || !seenForms.add(form.getCOSObject())) {
                continue;
            }
            PDStream contentStream = form.getContentStream();
            if (contentStream != null) {
                byte[] content = contentStream.toByteArray();
                if (content.length > 0) {
                    units.add(makeUnit(pageLabel + " / 表单 " + name.getName(), content,
                            form.getResources(), data -> {
                                try (OutputStream os = form.getContentStream().createOutputStream()) {
                                    os.write(data);
                                }
                            }));
                }
            }
            collectForms(form.getResources(), pageLabel, depth + 1, units, seenForms);
        }
    }

    private static Unit makeUnit(String id, byte[] content, PDResources resources,
                                 StreamTarget target) throws IOException {
        if (resources == null) {
            throw new IOException("内容 " + id + " 没有资源字典，无法解析字体，拒绝处理");
        }
        ContentInterpreter.Interpretation parsed = ContentInterpreter.interpretWithBreaks(
                ContentScanner.scan(content),
                name -> resources.getFont(COSName.getPDFName(name)));
        List<ShowTextOp> ops = parsed.ops();
        List<String> slots = new ArrayList<>(ops.size());
        for (ShowTextOp op : ops) {
            String text;
            try {
                text = op.decodedText();
            } catch (IOException e) {
                throw new IOException("内容 " + id + " 的字体解码失败", e);
            }
            if (text.indexOf(ShowTextOp.UNDECODABLE) >= 0) {
                throw new IOException("内容 " + id + " 里的字符无法解码为文本"
                        + "（字体缺少 ToUnicode 映射或编码异常）。继续处理等于"
                        + "「看着处理了、其实没处理」，因此拒绝。"
                        + "请先把该 PDF 转存为带完整文本层的版本再试。");
            }
            slots.add(text);
        }
        return new Unit(id, content, ops, List.copyOf(slots), parsed.repositionBefore(),
                resources, target);
    }

    private static byte[] concatPageContent(PDPage page) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        java.util.Iterator<PDStream> it = page.getContentStreams();
        while (it.hasNext()) {
            PDStream s = it.next();
            if (s != null) {
                sink.write(s.toByteArray());
                sink.write('\n');
            }
        }
        return sink.toByteArray();
    }
}
