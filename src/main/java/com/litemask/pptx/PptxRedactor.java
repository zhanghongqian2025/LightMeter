package com.litemask.pptx;

import com.litemask.core.ContinuationScope;
import com.litemask.core.EntitySource;
import com.litemask.core.HitGrouping;
import com.litemask.core.Change;
import com.litemask.core.RedactionEngine;
import com.litemask.core.RedactionResult;
import com.litemask.core.RedactionSession;
import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import com.litemask.core.TaskContext;
import com.litemask.core.SlotTextMapper;
import com.litemask.core.TextPlan;
import com.litemask.ooxml.DocPropsScrubber;
import com.litemask.zipxml.ZipXmlPackage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * pptx 脱敏。
 *
 * <p>同样只依赖 JDK。pptx 的骨架和 docx/xlsx 一样是 pkg + XML，文本位置：
 *
 * <ul>
 *   <li>{@code <a:t>}：DrawingML 的文本节点，出现在幻灯片、母版、版式、备注页、图表标题里。
 *       幻灯片上一句话同样会被拆进多个 {@code <a:r><a:t>}，所以照样要跨槽拼接。</li>
 *   <li>{@code <c:v>}：图表的数据缓存。pptx 的图表把数据内嵌在文件里，
 *       <b>不会</b>像 Excel 那样打开时重算——所以这里的值就是最终显示值，必须脱。</li>
 * </ul>
 *
 * <p><b>图表数值缓存的副作用</b>：把 {@code <c:numCache>} 里的数字换成占位符后，
 * PowerPoint 会提示"数据无效"直到用户刷新图表。这是脱敏必须付出的代价
 * （漏脱则是把身份证留在坐标轴标签上），因此计入报告并由 CLI 明确提示。
 */
public final class PptxRedactor {

    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String C_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart";
    private static final String XML_NS = XMLConstants.XML_NS_URI;

    private PptxRedactor() {
    }

    /**
     * 一次替换的定位信息。
     *
     * @param start   命中在<b>本单元文本</b>里的起点（单元内各文本节点按顺序拼起来的坐标）
     * @param end     命中在本单元文本里的终点（不含）
     * @param unit    所属文本单元的标识。同一单元内的偏移才可以互相比较，
     *                界面据此判断「原文里紧挨着」而把切开的命中归成一条。
     * @param occurrence 同一 {@code (规则键, 原值)} 在整篇文档里的第几次出现，0 基；
     *                   {@link com.litemask.core.Match#UNNUMBERED} 表示这一轮没有编号。
     *                   界面上「只保留第 N 处」用的就是这个数，序号由引擎分配。
     */
    public record Hit(String part, String locator, String original, String token, String ruleKey,
                       int start, int end, String unit, int occurrence) {
    }

    public record Report(int locationsScanned, int partsRewritten, int numericChartValuesRedacted,
                         int metadataFieldsCleared, List<Hit> hits, Map<String, String> mapping) {
    }

    private record Rewrite(Map<String, byte[]> changed, int locations, int numericCharts,
                           int metadataCleared, List<Hit> hits) {
    }

    public static Report redact(Path in, Path out, List<Rule> rules) throws IOException {
        return redact(in, out, rules, Exclusions.NONE);
    }

    /** 带排除项的脱敏；排除项在会话级生效，贯穿整份演示文稿。 */
    public static Report redact(Path in, Path out, List<Rule> rules, Exclusions exclusions)
            throws IOException {
        return redact(in, out, rules, exclusions, List.of());
    }

    /**
     * 带非正则来源（NER 之类）的脱敏；来源与规则共用同一个会话。
     *
     * <p>pptx 的备注页与图表数据缓存都会走来源：备注页是人写的，图表缓存里的分类标签
     * 常是人名或公司简称。前者漏了是「会议纪要里还留着参会人」，
     * 后者漏了是「图表打开后柱子上写着当事人」，两种都是文件正常生成、报告说成功、内容却还在。
     */
    public static Report redact(Path in, Path out, List<Rule> rules, Exclusions exclusions,
                                 List<EntitySource> sources) throws IOException {
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(in)) {
            TextPlan plan = extractText(pkg);
            // 单元序列**显式声明**并**按 zip 部件分组**（见 TextPlan）：
            // 组内可以续接，组间一律不续接。幻灯片正文与图表缓存、备注页
            // 在文件里挨着，在语义上却是两件事——合成一组时它们会被拼成
            // 一个校验位正确的身份证号，两段都被错标成身份证。
            RedactionSession session = new RedactionSession(plan.fullText(), rules, exclusions, sources,
                    plan.groups(), ContinuationScope.ANY);
            Rewrite rewrite = rewrite(pkg, session);
            pkg.write(out, rewrite.changed());
            return new Report(rewrite.locations(), rewrite.changed().size(), rewrite.numericCharts(),
                    rewrite.metadataCleared(), rewrite.hits(), session.mapping());
        }
    }

    public static int restore(Path in, Path out, Map<String, String> mapping) throws IOException {
        if (mapping == null || mapping.isEmpty()) {
            throw new IOException("还原映射为空");
        }
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(in)) {
            Map<String, byte[]> changed = new LinkedHashMap<>();
            int restored = 0;
            for (ZipEntry entry : pkg.entries()) {
                TaskContext.current().checkCancelled();
                if (!isProcessablePart(entry.getName())) {
                    continue;
                }
                Document doc = pkg.parse(pkg.read(entry));
                boolean dirty = false;
                for (String ns : new String[]{A_NS, C_NS}) {
                    for (String local : new String[]{"t", "v"}) {
                        NodeList nodes = doc.getElementsByTagNameNS(ns, local);
                        for (int i = 0; i < nodes.getLength(); i++) {
                            TaskContext.current().checkCancelled();
                            Element node = (Element) nodes.item(i);
                            String back = RedactionEngine.restore(node.getTextContent(), mapping);
                            if (!back.equals(node.getTextContent())) {
                                setTextPreservingSpace(node, back);
                                dirty = true;
                                restored++;
                            }
                        }
                    }
                }
                if (dirty) {
                    changed.put(entry.getName(), pkg.serialize(doc));
                }
            }
            pkg.write(out, changed);
            return restored;
        }
    }

    /**
     * 一次遍历产出两样东西：整篇文本与编号路径的单元分组。
     *
     * <p><b>单元序列必须显式声明</b>，不能由「全文按 {@code '\n'} 切」得到：
     * 单元文本里若自带换行符（生成器把多行文本直接写进 {@code <a:t>}），
     * 一个单元会被切成两段 → 与实际喂进去的文本对不上 → 续接被静默关掉
     * → <b>整篇演示文稿</b>的碎片都漏。这与 xlsx / docx 的那条回归同源。
     *
     * <p><b>分段粒度必须与 rewrite() 的单元粒度一致</b>：一个 {@code <a:p>} 是一个单元
     * （单元文本是它内部各 run 的 {@code <a:t>} 拼起来的），一个图表缓存
     * {@code <c:v>} 也是一个单元（rewrite 里逐个 {@code session.redact}）。
     * 早先这里按**单个 {@code <a:t>}** 追加，全文的分段与脱敏遍历的单元对不上，
     * 续接会被当场关掉——这不是续接机制的问题，是全文提取与脱敏遍历必须用同一套分段。
     *
     * <p><b>每组 = 一个 zip 部件</b>（块 15）：幻灯片、备注页、图表是三种容器，
     * 合成一组时幻灯片末尾的编号与图表缓存里的字符串会被拼成
     * 一个校验位正确的身份证号，两段都被错标成身份证。
     *
     * <p>详见 {@link TextPlan}。
     */
    private static TextPlan extractText(ZipXmlPackage.Session pkg) throws IOException {
        StringBuilder full = new StringBuilder();
        List<List<String>> groups = new ArrayList<>();
        for (ZipEntry entry : pkg.entries()) {
            if (!isProcessablePart(entry.getName())) {
                continue;
            }
            Document doc = pkg.parse(pkg.read(entry));
            List<String> partUnits = new ArrayList<>();
            // 段落：一个 <a:p> 是一个单元，单元文本是它内部各 run 的 <a:t> 拼起来的
            NodeList paragraphs = doc.getElementsByTagNameNS(A_NS, "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                List<Element> slots = collectTextRuns((Element) paragraphs.item(i));
                if (slots.isEmpty()) {
                    continue;
                }
                StringBuilder text = new StringBuilder();
                for (Element slot : slots) {
                    text.append(slot.getTextContent());
                }
                full.append(text).append('\n');
                partUnits.add(text.toString());
            }
            // 图表数据缓存：每个 <c:v> 也是编号路径真正喂给会话的一个单元
            // （变量名与 rewrite() 里那个刻意不同：CE 的锚点要唯一指向 rewrite 的那一处）
            NodeList chartCacheValues = doc.getElementsByTagNameNS(C_NS, "v");
            for (int i = 0; i < chartCacheValues.getLength(); i++) {
                String text = chartCacheValues.item(i).getTextContent();
                full.append(text).append('\n');
                partUnits.add(text);
            }
            groups.add(partUnits);
        }
        return new TextPlan(full.toString(), List.copyOf(groups));
    }

    private static Rewrite rewrite(ZipXmlPackage.Session pkg, RedactionSession session) throws IOException {
        Map<String, byte[]> changed = new LinkedHashMap<>();
        int locations = 0;
        int numericCharts = 0;
        int metadataCleared = 0;
        List<Hit> hits = new ArrayList<>();

        for (ZipEntry entry : pkg.entries()) {
            String name = entry.getName();
            if (!isProcessablePart(name)) {
                continue;
            }
            Document doc = pkg.parse(pkg.read(entry));
            boolean dirty = false;

            // 段落是"槽"的边界：<a:p> 内部的多个 <a:r><a:t> 要拼起来一起匹配
            NodeList paragraphs = doc.getElementsByTagNameNS(A_NS, "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                Element paragraph = (Element) paragraphs.item(i);
                List<Element> slots = collectTextRuns(paragraph);
                if (slots.isEmpty()) {
                    continue;
                }
                locations++;
                if (redactSlots(slots, session, name, "a:p#" + i, hits)) {
                    dirty = true;
                }
            }

            // 图表数据缓存：数值缓存被改会让 PowerPoint 提示需刷新，必须单独计数
            NodeList chartValues = doc.getElementsByTagNameNS(C_NS, "v");
            for (int i = 0; i < chartValues.getLength(); i++) {
                Element value = (Element) chartValues.item(i);
                locations++;
                String original = value.getTextContent();
                RedactionResult result = session.redact(original);
                if (!result.isChanged()) {
                    continue;
                }
                setTextPreservingSpace(value, result.sanitizedText());
                dirty = true;
                if (isNumericCache(value)) {
                    numericCharts++;
                }
                for (Change change : result.changes()) {
                    String cacheLocator = "c:v#" + i;
                    hits.add(new Hit(name, cacheLocator, change.original(), change.token(), change.ruleKey(),
                            change.start(), change.end(), HitGrouping.unitId(name, cacheLocator),
                            change.occurrence()));
                }
            }

            int cleared = DocPropsScrubber.scrub(doc, name);
            if (cleared > 0) {
                dirty = true;
                metadataCleared += cleared;
            }

            if (dirty) {
                changed.put(name, pkg.serialize(doc));
            }
        }
        return new Rewrite(changed, locations, numericCharts, metadataCleared, List.copyOf(hits));
    }

    /**
     * {@code <c:v>} 是否在数值缓存里。
     *
     * <p>层级是 {@code <c:numCache> → <c:pt> → <c:v>}，所以要看祖父而不是父节点。
     * 字符串缓存改了不影响图表渲染，数值缓存改了会让 PowerPoint 提示"数据无效"，
     * 两者必须分开计数。
     */
    private static boolean isNumericCache(Element value) {
        Node pt = value.getParentNode();
        if (pt == null || !C_NS.equals(pt.getNamespaceURI()) || !"pt".equals(pt.getLocalName())) {
            return false;
        }
        Node cache = pt.getParentNode();
        return cache != null && C_NS.equals(cache.getNamespaceURI())
                && "numCache".equals(cache.getLocalName());
    }

    private static boolean redactSlots(List<Element> slots, RedactionSession session,
                                       String part, String locator, List<Hit> hits) {
        List<String> before = new ArrayList<>(slots.size());
        for (Element slot : slots) {
            before.add(slot.getTextContent());
        }
        SlotTextMapper.Mapped mapped = SlotTextMapper.map(before, session);
        if (!mapped.changed()) {
            return false;
        }
        for (int i = 0; i < slots.size(); i++) {
            setTextPreservingSpace(slots.get(i), mapped.texts().get(i));
        }
        for (Change change : mapped.changes()) {
            hits.add(new Hit(part, locator, change.original(), change.token(), change.ruleKey(),
                    change.start(), change.end(), HitGrouping.unitId(part, locator), change.occurrence()));
        }
        return true;
    }

    /** 收集段落内的 {@code <a:t>}；遇到嵌套段落就停，交给外层循环处理。 */
    private static List<Element> collectTextRuns(Element paragraph) {
        List<Element> out = new ArrayList<>();
        collect(paragraph, out);
        return out;
    }

    private static void collect(Element element, List<Element> out) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            if (!A_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            String local = child.getLocalName();
            if ("p".equals(local)) {
                continue;
            }
            if ("t".equals(local)) {
                out.add(child);
                continue;
            }
            collect(child, out);
        }
    }

    private static void setTextPreservingSpace(Element element, String text) {
        ZipXmlPackage.setText(element, text);
        boolean edgeSpace = !text.isEmpty()
                && (Character.isWhitespace(text.charAt(0))
                || Character.isWhitespace(text.charAt(text.length() - 1)));
        if (edgeSpace) {
            element.setAttributeNS(XML_NS, "xml:space", "preserve");
        } else {
            element.removeAttributeNS(XML_NS, "space");
        }
    }

    private static boolean isProcessablePart(String name) {
        if (!ZipXmlPackage.isXmlPart(name)) {
            return false;
        }
        return name.startsWith("ppt/") || name.startsWith("docProps/");
    }
}
