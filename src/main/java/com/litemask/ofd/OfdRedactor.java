package com.litemask.ofd;

import com.litemask.core.EntitySource;
import com.litemask.core.ContinuationScope;
import com.litemask.core.HitGrouping;
import com.litemask.core.Change;
import com.litemask.core.RedactionEngine;
import com.litemask.core.RedactionResult;
import com.litemask.core.RedactionSession;
import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import com.litemask.core.SlotTextMapper;
import com.litemask.zipxml.ZipXmlPackage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * OFD 脱敏。
 *
 * <p>OFD 和 OOXML 是同一类东西：一个 pkg，里面若干 XML，文本藏在少数几种节点里。
 * 所以这里同样只用 JDK 的 pkg + DOM，不引任何 OFD 库——
 * 判据和当初放弃 POI 时一样：<b>先问「这个库覆盖不到的位置有哪些，漏了会不会静默失败」</b>。
 * OFD 的文本位置是可穷举的（下面 {@link #TEXT_CARRIERS} 全部列出来了），
 * 而引一个库换来的是「我大概都处理了」。把清单列出来本身就是覆盖率证明。
 *
 * <p><b>文本只藏在 {@code <ofd:TextCode>} 里吗？不是。</b> 这是 OFD 最容易漏的地方，
 * 真实文件（已核对过一张增值税发票样本）里至少有六处：
 * <ol>
 *   <li>{@code Pages/Page_N/Content.xml} 正文；</li>
 *   <li>{@code Tpls/Tpl_N/Content.xml} <b>模板</b>——不直接显示，但文字就在文件里；</li>
 *   <li>{@code Annots/...} 批注与水印里的 {@code TextCode}；</li>
 *   <li>{@code TextCode/@Text} 关键字属性（规范里的可选属性，用于检索）；</li>
 *   <li>{@code OFD.xml} 的 {@code DocInfo}——那张发票的 {@code CustomData} 里
 *       直接躺着<b>发票号码和两个纳税人识别号</b>，只看正文等于没脱；</li>
 *   <li>附件名——{@code Attachs/身份证扫描件_张三.pdf} 这种，
 *       而且它<b>连 pkg 条目名都是</b>，XML 引用改了、文件名留着一样漏。</li>
 * </ol>
 *
 * <p>因此实现是「<b>对包内每一个 XML 部件做一次深度优先遍历</b>」，
 * 靠元素名识别载体，而不是靠目录约定去找那几个文件。目录约定是规范里的<b>建议</b>，
 * 不是保证——一旦某个生产方把内容放到了别处，按目录找的实现就会安静地跳过它。
 *
 * <p><b>刻意不做的事</b>：
 * <ul>
 *   <li>不跨 {@code TextObject} 拼接。OFD 是绝对定位版式，跨对象合并只能靠几何启发式，
 *       而发票这类多栏文件里左右两栏的 y 完全相同——一合并就把两栏拼成一条不存在的字符串，
 *       轻则假脱敏，重则把版面改坏。宁可少认，也不在版式文件上赌。</li>
 *   <li>不改 {@code DeltaX}。改了就丢了逐字字距，还原时按新长度重新生成也回不到原值。
 *       留着它，脱敏后 DeltaX 的数量可能多于字符数（阅读器按字符数取用，多出来的忽略），
 *       换来的是<b>还原后与原文件逐字节一致</b>。</li>
 *   <li>不改 {@code Boundary} 坐标、不挪动后续对象。令牌比原值短就会留下空隙，
 *       但不会重叠、不会错位。真正要补版面得知道字体度量，那是另一件事（M2 再谈）。</li>
 * </ul>
 *
 * <p><b>一个必须说清楚的还原限制</b>：号码横跨多个 {@code TextCode} 时，
 * 令牌会整个写进<b>第一个</b>槽，后面的槽被清空。还原时按令牌替换回原文，
 * 文字内容与原文完全一致，但<b>段落的切分方式回不去了</b>——
 * 原来分散在三个 TextCode 里的那串数字，还原后会连成一个 TextCode。
 * 在 docx 里这看不出来（文字会自动回流），在 OFD 里位置会有肉眼可见的偏移。
 * 要真正复原，需要额外记录「原文是按什么切分写进每个槽的」，那是 M2 才值得做的数据结构。
 * 这里把它写成已知限制，而不是让用户以为还原是逐字节的。
 */
public final class OfdRedactor {

    /** OFD 规范命名空间。与本地名一起判断，避免把别的命名空间的同名元素当正文。 */
    static final String OFD_NS = "http://www.ofdspec.org/2016";

    /** 根部件名。这是「这确实是一个 OFD」的唯一硬证据。 */
    private static final String ROOT_ENTRY = "OFD.xml";

    /** 签章目录名（小写比较）。规范用 {@code Signs}，别的生产方有用 {@code Signatures} 的。 */
    private static final Set<String> SIGN_DIRS = Set.of("signs", "signatures");

    private OfdRedactor() {
    }

    /**
     * 一次替换的定位信息。
     *
     * @param start   命中在<b>本单元文本</b>里的起点（单元内各 TextCode 按顺序拼起来的坐标）
     * @param end     命中在本单元文本里的终点（不含）
     * @param unit    所属文本单元的标识。同一单元内的偏移才可以互相比较，
     *                界面据此判断「原文里紧挨着」而把切开的命中归成一条。
     *                <b>必须逐个元素唯一</b>：两个不同的 TextObject 若共用一个 unit，
     *                它们的偏移会被拿去互相比较，于是两段不相干的内容被拼成一个「标识符」。
     * @param occurrence 同一 {@code (规则键, 原值)} 在整篇文档里的第几次出现，0 基；
     *                   {@link com.litemask.core.Match#UNNUMBERED} 表示这一轮没有编号。
     *                   界面上「只保留第 N 处」用的就是这个数，序号由引擎分配。
     */
    public record Hit(String part, String carrier, String original, String token, String ruleKey,
                       int start, int end, String unit, int occurrence) {
    }

    public record Report(int textObjects, int textCodes, int partsRewritten, int fieldsCleared,
                         int entriesRenamed, int signatureEntriesDropped,
                         List<Hit> hits, Map<String, String> mapping, List<String> clearedFields) {
    }

    // ------------------------------------------------------------------ 续接的单元与分组

    /**
     * 一段<b>真的喂进引擎</b>的文本。
     *
     * <p><b>「喂进去」是唯一的事实来源</b>：续接的覆盖是按「单元序号」算的，
     * 而覆盖要落到哪个单元上，取决于遍历顺序与单元序列的顺序完全一致。
     * 两者只要有一处对不上，覆盖就会落到别的单元上——把 A 的碎片按 B 的类型脱掉。
     * 所以这里记录的是<b>实际调用引擎时传进去的那段文本</b>，
     * 而不是「按某种规则推导出来的、理论上应该一样的序列」。
     *
     * <p>（这条是块 14 在 xlsx 上踩出来的：那里单元序列是「全文按 '\\n' 切」推导的，
     * 而全文里含元数据，两边对不上，续接被静默关掉。）
     *
     * @param part 所属部件（pkg 条目名）。<b>组不能跨部件</b>：
     *              不同部件之间是不同页面／模板，坐标相近也不代表是同一段文字。
     */
    record Unit(String text, Kind kind, Rect bounds, String part) {
    }

    /** 单元的种类。不同种类之间<b>一律不续接</b>。 */
    enum Kind {
        /** 一个 {@code TextObject}（其内各 TextCode 拼接后的文本）。 */
        TEXT_OBJECT,
        /** 属性值（{@code TextCode/@Text}、{@code Outline/@Title}、{@code Attachment/@Name}）。 */
        ATTRIBUTE,
        /** {@code DocInfo} 里的文本或属性。 */
        DOC_INFO
    }

    /**
     * 一个 {@code TextObject} 的包围盒（{@code Boundary="x y w h"}），单位毫米。
     *
     * <p>解析不出来就是 {@code null} —— 那个对象**自成一组**，不与任何邻居续接。
     * 宁可漏脱也不错配。
     */
    record Rect(double x, double y, double w, double h) {
        double right() {
            return x + w;
        }

        /** 与另一个盒子在纵向上是否重叠（同一行的必要条件）。 */
        boolean sameLine(Rect other, double tolerance) {
            return y < other.y + other.h + tolerance && other.y < y + h + tolerance;
        }
    }

    /**
     * 记录「实际喂进引擎的每一段文本」。
     *
     * <p>收集模式与执行模式<b>跑的是同一段代码</b>，只是收集模式多记一笔。
     * 这样「单元序列的顺序」与「实际喂入的顺序」**不可能**对不上——
     * 它们本来就是同一次遍历的产物。
     */
    interface UnitCollector {
        void collect(Unit unit);
    }

    private record Rewrite(Map<String, byte[]> changed, Map<String, String> renames, Set<String> dropped,
                           int textObjects, int textCodes, int fieldsCleared,
                           int entriesRenamed, int signatureDropped,
                           List<Hit> hits, List<String> clearedFields) {
    }

    // ------------------------------------------------------------------ 脱敏

    public static Report redact(Path in, Path out, List<Rule> rules) throws IOException {
        return redact(in, out, rules, Exclusions.NONE);
    }

    /** 带排除项的脱敏；排除项在会话级生效，贯穿整份 OFD。 */
    public static Report redact(Path in, Path out, List<Rule> rules, Exclusions exclusions)
            throws IOException {
        return redact(in, out, rules, exclusions, List.of());
    }

    /**
     * 带非正则来源（NER 之类）的脱敏；来源与规则共用同一个会话。
     *
     * <p>OFD 的附件条目名走 {@code redactHidden}，第一版里隐藏路径完全不跑来源——
     * 而附件文件名里常常是「张三-授权委托书.pdf」这种人名，
     * 漏掉的后果是「卷宗附件清单上的当事人姓名原样留着」。
     * 这里补上：「不进清单」只该表示不消耗出现序号，不表示不脱敏。
     *
     * <p>（OFD 的文档属性走的是普通 {@code redact}，本来就带来源；
     * docx 的文档属性是<b>整体清空</b>，不走脱敏通路，因此与来源无关。）
     */
    public static Report redact(Path in, Path out, List<Rule> rules, Exclusions exclusions,
                                 List<EntitySource> sources) throws IOException {
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(in)) {
            List<ZipEntry> entries = pkg.entries();
            requireOfd(entries);
            // 先扫全文再脱敏：分配器需要全文才能避开原文里本来就有的 <身份证3> 之类占位符。
            String fullText = extractFullText(pkg, entries);

            // 【第一遍：收集「实际喂进引擎的每一段文本」】
            //   跑的是**同一段 rewrite 代码**，只是多记一笔。
            //   单元序列与喂入顺序因此不可能对不上——它们本来就是同一次遍历的产物。
            //   这一遍不写任何文件，DOM 的改动全部丢弃（第二遍会重新 parse 拿到原文）。
            List<Unit> units = new ArrayList<>();
            RedactionSession probing = new RedactionSession(fullText, rules, exclusions, sources);
            rewrite(pkg, entries, probing, units::add);

            // 【第二遍：带续接地脱敏】
            //   范围取 CHECK_DIGIT_ONLY：OFD 与 PDF 同属**空间定位**版式，
            //   相邻对象在空间上也可能挨着（两栏），只有带真校验位的规则参与才安全。
            RedactionSession session = new RedactionSession(fullText, rules, exclusions, sources,
                    groupUnits(units), ContinuationScope.CHECK_DIGIT_ONLY);
            Rewrite rw = rewrite(pkg, entries, session, null);
            pkg.write(out, rw.changed(),
                    new ZipXmlPackage.WriteOptions(rw.renames(), rw.dropped()));
            return new Report(rw.textObjects(), rw.textCodes(), rw.changed().size(), rw.fieldsCleared(),
                    rw.entriesRenamed(), rw.signatureDropped(),
                    List.copyOf(rw.hits()), withFileNameTokens(session.mapping(), rw.renames()),
                    List.copyOf(rw.clearedFields()));
        }
    }

    /**
     * 令牌形态：{@code <前缀N>}。
     *
     * <p>与 {@code com.litemask.core.TokenAllocator} 的形态一致 ——
     * 那边是「占位符长什么样」的唯一定义，这边只用来**认出**占位符。
     */
    private static final Pattern TOKEN_ANGLE = Pattern.compile("<([^<>\\s]{1,32}?)(\\d{1,9})>");

    /** 全角形态的令牌（文件名里用的那一支）。 */
    private static final Pattern TOKEN_FULL = Pattern.compile("［([^［］\\s]{1,32}?)(\\d{1,9})］");

    /**
     * 文件名安全化的令牌形态：把 {@code < >} 换成全角 {@code ［ ］}。
     *
     * <p><b>为什么必须换（2026-10-25 真 Windows 实测）</b>：
     * 引擎的令牌形态是 {@code <手机号1>}，而
     * <b>Windows 的文件名不允许出现 {@code <} 与 {@code >}</b>。
     * 附件名被脱敏成 {@code Attachs/<手机号1>_申请书.pdf} 之后，
     * 在 Windows 上<b>连解包都做不了</b>（{@code InvalidPathException}）——
     * 也就是说用户拿到的是一份「附件根本取不出来」的脱敏件。
     * macOS 上没这个问题（那两个字符在文件名里合法），所以这条只有真机能暴露。
     *
     * <p><b>为什么换成全角而不是别的</b>：全角括号在两个平台的文件名里都合法，
     * 肉眼又几乎一样，用户不会觉得「名字被换掉了」。
     *
     * <p><b>【已知的边界】</b>：附件名里**本来就有**字面量 {@code <} {@code >} 的情况
     * 不做改写 —— 那样的名字在 Windows 上本来就无法落盘，
     * 改了反而让还原对不上（还原是按映射逐字替换的）。
     * 这种输入在 macOS 上产生，在 Windows 上无法正常打开，属于**输入侧的限制**。
     */
    private static String toFileNameSafe(String text) {
        return TOKEN_ANGLE.matcher(text).replaceAll("［$1$2］");
    }

    /**
     * 把「全角形态的令牌」补进映射。
     *
     * <p><b>为什么必须补</b>：还原是<b>按映射逐字替换</b>的。
     * 附件名里写的是 {@code ［手机号1］}，而映射里原本只有 {@code <手机号1>} ——
     * 不补这一条，还原时找不到它，**附件名就换不回来**（附件内容还在、名字没了）。
     * 补上之后，映射里有两条：{@code <手机号1>}（正文里的）与
     * {@code ［手机号1］}（附件名里的），各自换回同一个原值。
     */
    private static Map<String, String> withFileNameTokens(Map<String, String> mapping,
                                                         Map<String, String> renames) {
        Map<String, String> out = new LinkedHashMap<>(mapping);
        for (String newName : renames.values()) {
            // 新名字里已经是**全角形态**的令牌了（planRenames 里做过安全化）。
            Matcher m = TOKEN_FULL.matcher(bare(newName));
            while (m.find()) {
                // 换回引擎的角括号形态，去原映射里找原值。
                String original = mapping.get("<" + m.group(1) + m.group(2) + ">");
                if (original != null) {
                    out.put(m.group(), original);
                }
            }
        }
        return out;
    }

    /**
     * 还原一个已脱敏的 OFD。
     *
     * <p>做法是<b>把令牌替换铺满全部叶子文本与全部属性值</b>，而不是照着脱敏时的载体清单再走一遍。
     * 脱敏必须逐类枚举载体（多改一处就是误伤），还原却可以只认一个不变量：
     * <b>令牌只会出现在我们写进去的地方</b>。照单枚举反而会漏——
     * 附件改名后同步替换进去的路径字符串就不属于任何一类载体，
     * 而漏掉它的后果是「文件改回来了、引用还指着新名字」，比多替换几处严重得多。
     *
     * <p>pkg 条目名也做同一次替换，所以文件名和指向它的引用必然同时变回原样。
     *
     * <p><b>换不回来的</b>：被清空的 {@code Author}/{@code Creator}、被移除的签章，
     * 以及<b>跨 TextCode 的段结构</b>（见类注释）。原值从来没有被保存过，
     * 这一点必须写进用户文档，不能让人以为还原是全量的。
     */
    public static int restore(Path in, Path out, Map<String, String> mapping) throws IOException {
        if (mapping == null || mapping.isEmpty()) {
            throw new IOException("还原映射为空");
        }
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(in)) {
            List<ZipEntry> entries = pkg.entries();
            Map<String, byte[]> changed = new LinkedHashMap<>();
            Map<String, String> renames = new LinkedHashMap<>();
            int restored = 0;

            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!entry.isDirectory()) {
                    // 条目名本身也可能含令牌：附件文件名脱敏时会被改名，这里改回去
                    String back = RedactionEngine.restore(name, mapping);
                    if (!back.equals(name)) {
                        renames.put(name, back);
                        restored++;
                    }
                }
                if (!ZipXmlPackage.isXmlPart(name)) {
                    continue;
                }
                Document doc = pkg.parse(pkg.read(entry));
                int partRestored = restoreElementTree(doc, mapping);
                restored += partRestored;
                if (partRestored > 0) {
                    changed.put(name, pkg.serialize(doc));
                }
            }
            pkg.write(out, changed,
                    new ZipXmlPackage.WriteOptions(renames, Set.of()));
            return restored;
        }
    }

    private static int restoreElementTree(Document doc, Map<String, String> mapping) {
        int restored = 0;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (!hasElementChild(element)) {
                String before = element.getTextContent();
                String after = RedactionEngine.restore(before, mapping);
                if (!after.equals(before)) {
                    ZipXmlPackage.setText(element, after);
                    restored++;
                }
            }
            NamedNodeMap attrs = element.getAttributes();
            for (int a = 0; a < attrs.getLength(); a++) {
                Node attr = attrs.item(a);
                String before = attr.getNodeValue();
                String after = RedactionEngine.restore(before, mapping);
                if (!after.equals(before)) {
                    attr.setNodeValue(after);
                    restored++;
                }
            }
        }
        return restored;
    }

    // ------------------------------------------------------------------ 主流程

    private static Rewrite rewrite(ZipXmlPackage.Session pkg, List<ZipEntry> entries, RedactionSession session,
                                   UnitCollector collector) throws IOException {
        Map<String, byte[]> changed = new LinkedHashMap<>();
        RenamePlan plan = planRenames(entries, session);
        Set<String> dropped = new LinkedHashSet<>();
        int textObjects = 0;
        int textCodes = 0;
        int cleared = 0;
        List<Hit> hits = new ArrayList<>();
        List<String> clearedFields = new ArrayList<>();

        for (ZipEntry entry : entries) {
            String name = entry.getName();
            if (isSignatureEntry(name)) {
                dropped.add(name);
                continue;
            }
            if (!ZipXmlPackage.isXmlPart(name)) {
                continue;
            }
            Document doc = pkg.parse(pkg.read(entry));
            boolean dirty = false;

            // A. 文本槽
            List<List<Element>> textCodeGroups = groupTextCodes(doc);
            for (int objIndex = 0; objIndex < textCodeGroups.size(); objIndex++) {
                List<Element> group = textCodeGroups.get(objIndex);
                textObjects++;
                textCodes += group.size();
                List<String> before = new ArrayList<>(group.size());
                for (Element slot : group) {
                    before.add(slot.getTextContent());
                }
                // 收集模式：记下「即将喂进引擎的那段文本」。
                // 拼接规则取自 SlotTextMapper.join —— 与 map 内部用的是同一份实现，
                // 这里若手写一份、那边改了拼接方式，两边就会错位。
                // 空文本不记：SlotTextMapper.map 对空文本不会调用引擎，
                // 而引擎对空文本也不推进单元游标，两边必须一致。
                if (collector != null) {
                    String full = SlotTextMapper.join(before);
                    if (!full.isEmpty()) {
                        collector.collect(new Unit(full, Kind.TEXT_OBJECT, boundsOf(group), name));
                    }
                }
                SlotTextMapper.Mapped mapped = SlotTextMapper.map(before, session);
                if (!mapped.changed()) {
                    continue;
                }
                dirty = true;
                for (int s = 0; s < group.size(); s++) {
                    ZipXmlPackage.setText(group.get(s), mapped.texts().get(s));
                }
                recordHits(name, objIndex, before, mapped.changes(), hits);
            }

            // B. 关键字属性
            NodeList codes = doc.getElementsByTagNameNS(OFD_NS, "TextCode");
            for (int i = 0; i < codes.getLength(); i++) {
                Element code = (Element) codes.item(i);
                if (redactTextAttribute(code, "Text", session, name, "TC" + i, hits, collector)) {
                    dirty = true;
                }
            }

            // C. 文档属性
            NodeList info = doc.getElementsByTagNameNS(OFD_NS, "DocInfo");
            for (int i = 0; i < info.getLength(); i++) {
                Element docInfo = (Element) info.item(i);
                OfdDocInfo.Result r = OfdDocInfo.redact(docInfo, name, session, hits, collector);
                if (r.changed()) {
                    dirty = true;
                    cleared += r.cleared();
                    clearedFields.addAll(r.clearedFields());
                }
            }

            // D. 附件名 / 书签标题的属性
            if (redactAttributeOn(doc, "Outline", "Title", session, name, hits, collector)
                    | redactAttributeOn(doc, "Attachment", "Name", session, name, hits, collector)) {
                dirty = true;
            }

            // E. 签章引用（签章部件已经丢掉了，引用必须一起清，否则读者找不到文件）
            if (removeSignatureReference(doc)) {
                dirty = true;
            }

            // F. 条目改名后，指向它的路径字符串必须同步改
            if (applyRenames(doc, plan)) {
                dirty = true;
            }

            if (dirty) {
                changed.put(name, pkg.serialize(doc));
            }
        }

        return new Rewrite(changed, plan.full(), dropped, textObjects, textCodes, cleared,
                plan.full().size(), dropped.size(), hits, clearedFields);
    }

    // ------------------------------------------------------------------ 文本槽分组

    /**
     * 把 TextCode 分成若干组，组内按文档顺序拼接。
     *
     * <p>组 = 同一个 {@code TextObject} 下的全部 {@code TextCode}。
     * {@code TextCode} 的 {@code X} 是对象内的横向偏移，所以一个对象里可能有多个 TextCode，
     * 一个号码被拆成「138 0013 8000」三段是真实存在的——逐槽跑正则必然漏。
     *
     * <p>没有 TextObject 祖先的 TextCode 自成一组。这不是洁癖：规范保证它们在对象里，
     * 但「保证」是规范的话，不是这份文件的话，而漏掉一个孤零零的 TextCode
     * 不会有任何报错，只会让敏感信息留在文件里。
     *
     * <p>见类注释：<b>不跨 TextObject 合并</b>，多栏版式上那会把两栏拼成一条假字符串。
     */
    static List<List<Element>> groupTextCodes(Document doc) {
        // 【顺序是契约 —— 这里原来用的是 IdentityHashMap，是个真缺陷】
        //   IdentityHashMap 的 values() 顺序是**哈希顺序**（identity hash = 对象内存地址），
        //   同一份文件**两次解析**出来的 Document 是不同对象 → 哈希值不同 → **顺序可能不同**。
        //   而这里返回的组序 = A 段遍历 TextObject 的顺序 = 「喂进引擎的单元顺序」，
        //   它必须与「收集单元序列时用的顺序」完全一致，否则续接的对齐检查会失败，
        //   续接被**静默关掉**（表现：改了代码，续接就是不生效，也没有任何报错）。
        //   → 改用保留插入顺序的 LinkedHashMap：DOM 遍历顺序本身就是文档顺序。
        //   （键仍是 identity 语义——Element 没重写 equals/hashCode，行为不变，只是顺序确定了。）
        Map<Element, List<Element>> byObject = new LinkedHashMap<>();
        Set<Element> claimed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        NodeList objects = doc.getElementsByTagNameNS(OFD_NS, "TextObject");
        for (int i = 0; i < objects.getLength(); i++) {
            Element object = (Element) objects.item(i);
            List<Element> slots = directTextCodes(object);
            if (!slots.isEmpty()) {
                byObject.put(object, slots);
                claimed.addAll(slots);
            }
        }
        List<List<Element>> groups = new ArrayList<>(byObject.values());
        NodeList all = doc.getElementsByTagNameNS(OFD_NS, "TextCode");
        for (int i = 0; i < all.getLength(); i++) {
            Element code = (Element) all.item(i);
            if (!claimed.contains(code)) {
                groups.add(List.of(code));
            }
        }
        return groups;
    }

    // ------------------------------------------------------------------ 续接分组（几何）

    /**
     * 同一行内两个 {@code TextObject} 之间的最大水平间隙（毫米）。
     *
     * <p>同一行被拆成两个对象时，前者的右边缘应当<b>紧挨着</b>后者的左边缘
     * （{@code Boundary} 是文本实际占位，字距也含在里面）。
     * 左右两栏之间的距离要大得多（排版上至少几毫米）。
     * 1 毫米是个保守值：远小于栏间距，又足以容下生成器给出的坐标误差。
     */
    static final double MAX_HORIZONTAL_GAP_MM = 1.0;

    /** 纵向重叠的容差（毫米）。 */
    static final double LINE_TOLERANCE_MM = 0.2;

    /** 允许的微小重叠（毫米）：两个对象的边界压在一起一点点不算「重叠」。 */
    static final double EPSILON_MM = 0.01;

    /**
     * 把单元序列切成「允许续接」的组。
     *
     * <p>组内相邻的单元之间才允许续接，组间一律不允许。切组依据有两条，缺一不可：
     * <ol>
     *   <li><b>种类</b>：只有两个都是 {@code TextObject} 才可能续接
     *       （属性值、DocInfo 与正文不是同一段文字）；</li>
     *   <li><b>几何</b>：两个 {@code TextObject} 必须在<b>同一行</b>且<b>水平紧邻</b>。
     *       这是 OFD 与 OOXML / PDF 的根本差别：OFD 是绝对定位版式，
     *       「顺序相邻」在空间上未必挨着——左右两栏就是同 y、x 相距很远，
     *       只看顺序会把两栏拼成一条不存在的字符串。</li>
     * </ol>
     *
     * <p><b>边界解析不出来就不合并</b>（{@code null} 边界 → 自成一组）：
     * 宁可漏脱，也不错配。
     */
    static List<List<String>> groupUnits(List<Unit> units) {
        List<List<String>> groups = new ArrayList<>();
        if (units == null || units.isEmpty()) {
            return groups;
        }
        List<String> current = new ArrayList<>();
        Unit previous = null;
        for (Unit unit : units) {
            if (!joinable(previous, unit)) {
                if (!current.isEmpty()) {
                    groups.add(List.copyOf(current));
                    current = new ArrayList<>();
                }
            }
            current.add(unit.text());
            previous = unit;
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return groups;
    }

    /** 两个单元能不能放进同一组（组内才允许续接）。 */
    private static boolean joinable(Unit previous, Unit current) {
        if (previous == null || current == null) {
            return false;
        }
        // 组不能跨部件：不同部件是不同页面／模板，坐标相近也不代表是同一段文字。
        // 不挡的话，两个相邻页面里位置相同的两个对象会被当成同一行——那就跨页续接了。
        if (!java.util.Objects.equals(previous.part(), current.part())) {
            return false;
        }
        if (previous.kind() != Kind.TEXT_OBJECT || current.kind() != Kind.TEXT_OBJECT) {
            return false;
        }
        Rect a = previous.bounds();
        Rect b = current.bounds();
        if (a == null || b == null) {
            return false;
        }
        if (!a.sameLine(b, LINE_TOLERANCE_MM)) {
            return false;
        }
        double gap = b.x() - a.right();
        return gap >= -EPSILON_MM && gap <= MAX_HORIZONTAL_GAP_MM;
    }

    /**
     * 取一组 TextCode 所在 {@code TextObject} 的包围盒。
     *
     * <p>没有 {@code TextObject} 祖先的孤儿 TextCode 没有 {@code Boundary}，
     * 返回 {@code null}——那种对象自成一组，不与任何邻居续接。
     */
    private static Rect boundsOf(List<Element> slots) {
        if (slots == null || slots.isEmpty()) {
            return null;
        }
        Node node = slots.get(0).getParentNode();
        while (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
            Element element = (Element) node;
            if (OFD_NS.equals(element.getNamespaceURI()) && "TextObject".equals(element.getLocalName())) {
                return parseRect(element.getAttribute("Boundary"));
            }
            node = node.getParentNode();
        }
        return null;
    }

    /**
     * 解析 {@code Boundary="x y w h"}。
     *
     * <p>格式不对、数值非法、宽高非正时一律返回 {@code null}：
     * 拿不到边界就不合并（宁可漏脱不错配）。
     */
    private static Rect parseRect(String boundary) {
        if (boundary == null || boundary.isEmpty()) {
            return null;
        }
        String[] parts = boundary.trim().split("\\s+");
        if (parts.length < 4) {
            return null;
        }
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                v[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (!Double.isFinite(v[i])) {
                return null;
            }
        }
        if (v[2] <= 0 || v[3] <= 0) {
            return null;
        }
        return new Rect(v[0], v[1], v[2], v[3]);
    }

    private static List<Element> directTextCodes(Element object) {
        List<Element> out = new ArrayList<>();
        NodeList children = object.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            if (OFD_NS.equals(child.getNamespaceURI()) && "TextCode".equals(child.getLocalName())) {
                out.add(child);
            }
        }
        return out;
    }

    private static void recordHits(String part, int objectIndex, List<String> before,
                                   List<Change> changes, List<Hit> hits) {
        int[] offsets = new int[before.size() + 1];
        for (int i = 0; i < before.size(); i++) {
            offsets[i + 1] = offsets[i] + before.get(i).length();
        }
        for (Change change : changes) {
            int slot = 0;
            for (int i = 0; i < before.size(); i++) {
                if (change.start() >= offsets[i] && change.start() < offsets[i + 1]) {
                    slot = i;
                    break;
                }
            }
            hits.add(new Hit(part, "TextObject#" + slot, change.original(), change.token(), change.ruleKey(),
                    change.start(), change.end(), HitGrouping.unitId(part, "TO" + objectIndex),
                    change.occurrence()));
        }
    }

    // ------------------------------------------------------------------ 属性里的文本

    private static boolean redactTextAttribute(Element element, String attr, RedactionSession session,
                                              String part, String unitLocal, List<Hit> hits,
                                              UnitCollector collector) {
        if (!element.hasAttribute(attr)) {
            return false;
        }
        String before = element.getAttribute(attr);
        // 收集模式：属性值也是一次真实的喂入，必须进单元序列。
        // 空值不进：引擎对空文本不推进单元游标（对齐检查里先判空再推进）。
        if (collector != null && !before.isEmpty()) {
            collector.collect(new Unit(before, Kind.ATTRIBUTE, null, part));
        }
        RedactionResult result = session.redact(before);
        if (!result.isChanged()) {
            return false;
        }
        element.setAttribute(attr, result.sanitizedText());
        for (Change change : result.changes()) {
            hits.add(new Hit(part, element.getLocalName() + "@" + attr,
                    change.original(), change.token(), change.ruleKey(),
                    change.start(), change.end(), HitGrouping.unitId(part, unitLocal),
                    change.occurrence()));
        }
        return true;
    }

    private static boolean redactAttributeOn(Document doc, String localName, String attr,
                                             RedactionSession session, String part, List<Hit> hits,
                                             UnitCollector collector) {
        boolean dirty = false;
        NodeList nodes = doc.getElementsByTagNameNS(OFD_NS, localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            dirty |= redactTextAttribute((Element) nodes.item(i), attr, session, part,
                    localName + "#" + i, hits, collector);
        }
        return dirty;
    }

    private static int restoreText(Element element, Map<String, String> mapping) {
        String before = element.getTextContent();
        String after = RedactionEngine.restore(before, mapping);
        if (after.equals(before)) {
            return 0;
        }
        ZipXmlPackage.setText(element, after);
        return 1;
    }

    // ------------------------------------------------------------------ 条目改名

    /**
     * 条目名里可能直接带个人信息（{@code Attachs/13800138000_申请书.pdf}），
     * XML 里改了引用、文件名还留着，等于没脱。
     *
     * <p><b>路径字符串一律不走引擎</b>：文件名和指向它的 {@code FileLoc} 必须得到<b>同一个</b>令牌，
     * 否则引用会指向一个不存在的条目。如果两边各自跑一次引擎，分配器按先到先给的规则
     * 会发出两个不同编号，结果就是「文件名改了、引用还指着旧名」。
     * 所以只认这两张表，XML 里的路径字符串也只由它们改。
     *
     * <p>分两张表是因为<b>裸文件名在包里可能重名</b>（{@code Res/a.pdf} 和 {@code Tpls/a.pdf}）。
     * 这时按裸名去替换，会把另一处指向同名文件的引用一起改坏。全路径永远安全；
     * 裸名只在全局唯一时才允许替换。
     */
    private static RenamePlan planRenames(List<ZipEntry> entries, RedactionSession session) {
        Map<String, Integer> bareCount = new HashMap<>();
        for (ZipEntry entry : entries) {
            if (!entry.isDirectory()) {
                bareCount.merge(bare(entry.getName()), 1, Integer::sum);
            }
        }
        Map<String, String> full = new LinkedHashMap<>();
        Map<String, String> bare = new LinkedHashMap<>();
        for (ZipEntry entry : entries) {
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            String leaf = bare(name);
            // 附件文件名：改了内容但不进命中清单，所以走 redactHidden 不占出现序号
            // 【2026-10-25】改完还要做一次**文件名安全化**：
            //   引擎的令牌形态是 <手机号1>，而 Windows 的文件名不允许 < 与 > ——
            //   不换的话，脱敏件在 Windows 上连解包都做不了。
            //   （对应的那条映射由 withFileNameTokens 补上，还原才换得回来。）
            String redacted = toFileNameSafe(session.redactHidden(leaf).sanitizedText());
            if (redacted.equals(leaf)) {
                continue;
            }
            full.put(name, dir(name) + redacted);
            if (bareCount.getOrDefault(leaf, 0) == 1) {
                bare.put(leaf, redacted);
            }
        }
        return new RenamePlan(full, bare);
    }

    /** 改名表：{@code full} 是全条目名映射，{@code bare} 只收全局唯一的裸文件名。 */
    private record RenamePlan(Map<String, String> full, Map<String, String> bare) {
        boolean isEmpty() {
            return full.isEmpty();
        }
    }

    /** 把改名表套到 XML 的叶子文本与属性值上。只动叶子，碰了有子元素的会毁掉结构。 */
    private static boolean applyRenames(Document doc, RenamePlan plan) {
        if (plan.isEmpty()) {
            return false;
        }
        boolean dirty = false;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (!hasElementChild(element)) {
                String before = element.getTextContent();
                String after = replaceAll(before, plan);
                if (!after.equals(before)) {
                    ZipXmlPackage.setText(element, after);
                    dirty = true;
                }
            }
            NamedNodeMap attrs = element.getAttributes();
            for (int a = 0; a < attrs.getLength(); a++) {
                Node attr = attrs.item(a);
                String before = attr.getNodeValue();
                String after = replaceAll(before, plan);
                if (!after.equals(before)) {
                    attr.setNodeValue(after);
                    dirty = true;
                }
            }
        }
        return dirty;
    }

    private static String replaceAll(String text, RenamePlan plan) {
        String out = text;
        for (Map.Entry<String, String> e : plan.full().entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : plan.bare().entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private static String bare(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    private static String dir(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? "" : entryName.substring(0, slash + 1);
    }

    private static boolean hasElementChild(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 签章

    /**
     * 签章必须移除，而且必须报出来。
     *
     * <p>脱敏改动了页面内容，原签名<b>必然</b>验不过。留着它，读者打开时会看到一个
     * 「签名无效」的提示——那是把一个必然的结果推给用户去发现。删掉并明确告知，
     * 才是不骗人的做法。
     */
    private static boolean isSignatureEntry(String name) {
        for (String segment : name.split("/")) {
            if (SIGN_DIRS.contains(segment.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 去掉 {@code <ofd:Signatures>} 引用：部件都丢了，引用留着只会让读者报错。 */
    private static boolean removeSignatureReference(Document doc) {
        NodeList refs = doc.getElementsByTagNameNS(OFD_NS, "Signatures");
        boolean dirty = false;
        List<Element> doomed = new ArrayList<>();
        for (int i = 0; i < refs.getLength(); i++) {
            doomed.add((Element) refs.item(i));
        }
        for (Element element : doomed) {
            Node parent = element.getParentNode();
            if (parent != null) {
                parent.removeChild(element);
                dirty = true;
            }
        }
        return dirty;
    }

    // ------------------------------------------------------------------ 全文与校验

    private static String extractFullText(ZipXmlPackage.Session pkg, List<ZipEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (ZipEntry entry : entries) {
            if (entry.isDirectory()) {
                int slash = entry.getName().lastIndexOf('/');
                sb.append(slash < 0 ? entry.getName() : entry.getName().substring(slash + 1)).append('\n');
                continue;
            }
            sb.append(entry.getName()).append('\n');
            if (!ZipXmlPackage.isXmlPart(entry.getName())) {
                continue;
            }
            Document doc = pkg.parse(pkg.read(entry));
            for (List<Element> group : groupTextCodes(doc)) {
                for (Element slot : group) {
                    sb.append(slot.getTextContent());
                }
                sb.append('\n');
            }
            NodeList info = doc.getElementsByTagNameNS(OFD_NS, "DocInfo");
            for (int i = 0; i < info.getLength(); i++) {
                sb.append(((Element) info.item(i)).getTextContent()).append('\n');
            }
        }
        return sb.toString();
    }

    private static void requireOfd(List<ZipEntry> entries) throws IOException {
        for (ZipEntry entry : entries) {
            if (ROOT_ENTRY.equals(entry.getName())) {
                return;
            }
        }
        throw new IOException("不是 OFD 文件：包根目录没有 " + ROOT_ENTRY + "（已中止，不做处理）");
    }
}
