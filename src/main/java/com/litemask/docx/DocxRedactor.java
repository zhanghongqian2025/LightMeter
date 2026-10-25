package com.litemask.docx;

import com.litemask.core.ContinuationScope;
import com.litemask.core.EntitySource;
import com.litemask.core.Change;
import com.litemask.core.HitGrouping;
import com.litemask.core.RedactionEngine;
import com.litemask.core.RedactionSession;
import com.litemask.core.SlotTextMapper;
import com.litemask.core.TextPlan;
import com.litemask.ooxml.DocPropsScrubber;
import com.litemask.zipxml.ZipXmlPackage;
import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * docx 脱敏。
 *
 * <p>实现只依赖 JDK：docx 就是一个 pkg，里面是若干 XML。只改 {@code <w:t>} / {@code <w:delText>}
 * 的文本节点，其余 XML 原样保留——所以加粗、字号、颜色、字体、批注锚点等样式天然不受影响，
 * 不需要任何"重建富文本"的代码。
 *
 * <p><b>覆盖范围</b>：对每个 XML part 做一次深度优先遍历，收集全部 {@code <w:p>} 段落，
 * 于是 body、表格、页眉页脚、脚注尾注、批注、以及<b>文本框</b>（{@code w:txbxContent}）
 * 走的是同一条代码路径，不存在"某个位置忘了处理"的可能。
 * 这也是刻意不用 POI 的原因：POI 不把文本框里的段落建模成 {@code XWPFParagraph}，
 * 用它就得分两套遍历机制，漏掉一层就是静默漏脱。
 *
 * <p><b>刻意的取舍</b>：
 * <ul>
 *   <li>不处理 {@code <w:instrText>}（域代码）。它是域指令不是正文，改动会破坏域；
 *       但域目标 URL 里确实可能带身份证号——已记为 M1 待办，不在此处悄悄放过。</li>
 *   <li>没有改动的 part 按原始字节复制：把改动面压到最小。</li>
 *   <li>XML 解析失败一律抛错（fail-closed）。宁可报错让人来看，
 *       也不能"解析不了就原样放行"——那等于静默交付一份未脱敏的文档。</li>
 * </ul>
 */
public final class DocxRedactor {

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String XML_NS = XMLConstants.XML_NS_URI;

    private DocxRedactor() {
    }

    /**
     * 一次替换的定位信息，便于回溯与人工核对。
     *
     * @param start   命中在<b>本段文本</b>里的起点（段落内各文本节点按顺序拼起来的坐标）
     * @param end     命中在本段文本里的终点（不含）
     * @param unit    所属文本单元的标识。同一单元内的偏移才可以互相比较，
     *                界面据此判断「原文里紧挨着」而把切开的命中归成一条。
     * @param occurrence 同一 {@code (规则键, 原值)} 在整篇文档里的第几次出现，0 基；
     *                   {@link com.litemask.core.Match#UNNUMBERED} 表示这一轮没有编号。
     *                   界面上「只保留第 N 处」用的就是这个数，序号由引擎分配。
     */
    public record Hit(String part, int paragraphIndex, int slotIndex, String original, String token, String ruleKey,
                       int start, int end, String unit, int occurrence) {
    }

    public record Report(int paragraphsScanned, int partsRewritten, int metadataFieldsCleared,
                         List<Hit> hits, Map<String, String> mapping) {
    }

    /** redact 过程中的中间结果。 */
    private record Rewrite(Map<String, byte[]> changed, int paragraphs, int metadataCleared, List<Hit> hits) {
    }

    /**
     * 脱敏一个 docx。
     *
     * @param in    输入 docx
     * @param out   输出 docx（可与 in 同路径，内部先写临时文件再替换）
     * @param rules 规则集
     */
    public static Report redact(Path in, Path out, List<Rule> rules) throws IOException {
        return redact(in, out, rules, Exclusions.NONE);
    }

    /**
     * 带排除项的脱敏：{@code exclusions} 里的命中原样保留。
     *
     * <p>排除项在<b>会话级</b>生效并贯穿整篇文档——不是逐部件传的。
     * 逐部件传的话调用方得记得每处都传同一份，漏一处就变成「只脱了一半」。
     */
    public static Report redact(Path in, Path out, List<Rule> rules, Exclusions exclusions)
            throws IOException {
        return redact(in, out, rules, exclusions, List.of());
    }

    /**
     * 带非正则来源（NER 之类）的脱敏。
     *
     * <p>来源与规则汇进同一个 {@link RedactionSession}，因此编号、去重、位置级排除
     * 对两者是同一套行为。docx 的部件很多（正文段落、表格、页眉页脚、批注、文本框），
     * 但来源只在会话里放一份就够——**每个部件各自 new 一个来源是错的**，
     * 那样同一个机构名在不同部件会拿到不同编号，还原时对不上。
     */
    public static Report redact(Path in, Path out, List<Rule> rules, Exclusions exclusions,
                                 List<EntitySource> sources) throws IOException {
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(in)) {
            TextPlan plan = extractText(pkg);
            // 先扫全文再脱敏：分配器需要全文才能避开原文里本来就有的 <身份证3> 之类占位符。
            // 顺序反了，撞号问题会重新出现。
            //
            // 单元序列**显式声明**（见 TextPlan），不用「全文按 '\n' 切」那条路。
            // 续接靠「当前喂进去的文本 == 单元序列里的第 N 个」来对齐，对不上就整体关掉续接。
            // 旧写法由全文按 '\n' 切得出单元序列，而单元文本里若自带换行符
            // （生成器把多行文本直接写进 <w:t>），一个单元会被切成两段 →
            // 与实际喂进去的文本对不上 → 续接被静默关掉 → 整篇文档的碎片都漏。
            //
            // 单元**按 zip 部件分组**（块 15）：组内可以续接，组间一律不续接。
            // 页眉末尾的编号与正文第一句在文件里挨着，在语义上是两件事——
            // 合成一组时它们会被拼成一个校验位正确的身份证号，两段都被错标成身份证。
            RedactionSession session = new RedactionSession(plan.fullText(), rules, exclusions, sources,
                    plan.groups(), ContinuationScope.ANY);
            Rewrite rewrite = rewrite(pkg, session);
            pkg.write(out, rewrite.changed());
            return new Report(rewrite.paragraphs(), rewrite.changed().size(),
                    rewrite.metadataCleared(), rewrite.hits(), session.mapping());
        }
    }

    /**
     * 还原一个已脱敏的 docx。
     *
     * <p>逐槽替换即可，不需要再走段落拼接：占位符在写入时被完整放进某一个 {@code <w:t>} 里，
     * 不会横跨槽，所以单槽内做一次非级联替换就够。
     *
     * <p>还原后<b>不承诺</b>与原文件二进制一致——pkg 的压缩参数、XML 属性顺序、
     * 可能存在的增量修订痕迹都可能不同。承诺的是「抽取出的文本与原文相同」，测试断言的也是这一条。
     */
    public static int restore(Path in, Path out, Map<String, String> mapping) throws IOException {
        if (mapping == null || mapping.isEmpty()) {
            throw new IOException("还原映射为空");
        }
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(in)) {
            Map<String, byte[]> changed = new LinkedHashMap<>();
            int restored = 0;
            for (ZipEntry entry : pkg.entries()) {
                if (!isProcessablePart(entry.getName())) {
                    continue;
                }
                byte[] original = pkg.read(entry);
                Document doc = pkg.parse(original);
                boolean dirty = false;
                NodeList list = doc.getElementsByTagNameNS(W_NS, "p");
                for (int i = 0; i < list.getLength(); i++) {
                    for (Element slot : collectSlots((Element) list.item(i))) {
                        String text = slot.getTextContent();
                        String back = RedactionEngine.restore(text, mapping);
                        if (!back.equals(text)) {
                            setSlotText(slot, back);
                            dirty = true;
                            restored++;
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
     * 单元文本里若自带换行符（生成器把多行文本直接写进 {@code <w:t>}），
     * 一个单元会被切成两段 → 与实际喂进去的文本对不上 → 续接被静默关掉
     * → <b>整篇文档</b>的碎片都漏（110105 残留、19491231002X 被错标成手机号）。
     * 这与 xlsx 的那条回归同源，只是触发条件不同（元数据混入 vs 单元自带换行符）。
     *
     * <p><b>每组 = 一个 zip 部件</b>（块 15）：docx 的部件很多
     * （正文、页眉页脚、批注、脚注、文本框…），它们在 zip 里挨着，在语义上却互不相干。
     * 合成一组时，页眉末尾的 17 位数字与正文开头的字母 X 会被拼成
     * 一个校验位正确的身份证号——两段都被按身份证脱敏，类型与原文语义无关。
     * 分组之后只有「同一个部件里的相邻段落」才能续接，那才是真正被切断的同一个值。
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
            NodeList paragraphs = doc.getElementsByTagNameNS(W_NS, "p");
            List<String> partUnits = new ArrayList<>();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                // 单元文本 = 段落内各槽的文本拼起来（与 rewrite 喂给会话的完全一致）
                String text = String.join("", readSlotTexts(collectSlots((Element) paragraphs.item(i))));
                full.append(text).append('\n');
                partUnits.add(text);
            }
            groups.add(partUnits);
        }
        return new TextPlan(full.toString(), List.copyOf(groups));
    }

    private static Rewrite rewrite(ZipXmlPackage.Session pkg, RedactionSession session) throws IOException {
        Map<String, byte[]> changed = new LinkedHashMap<>();
        int paragraphs = 0;
        int metadataCleared = 0;
        List<Hit> hits = new ArrayList<>();

        for (ZipEntry entry : pkg.entries()) {
            String name = entry.getName();
            if (!isProcessablePart(name)) {
                continue;
            }
            byte[] original = pkg.read(entry);
            Document doc = pkg.parse(original);
            boolean dirty = false;

            NodeList list = doc.getElementsByTagNameNS(W_NS, "p");
            for (int i = 0; i < list.getLength(); i++) {
                List<Element> slots = collectSlots((Element) list.item(i));
                if (slots.isEmpty()) {
                    continue;
                }
                paragraphs++;
                List<String> before = readSlotTexts(slots);
                SlotTextMapper.Mapped mapped = SlotTextMapper.map(before, session);
                if (!mapped.changed()) {
                    continue;
                }
                dirty = true;
                for (int s = 0; s < slots.size(); s++) {
                    setSlotText(slots.get(s), mapped.texts().get(s));
                }
                recordHits(name, i, slots, before, mapped.changes(), hits);
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
        return new Rewrite(changed, paragraphs, metadataCleared, List.copyOf(hits));
    }

    /** 把段内偏移换算成"哪个槽"，让报告能直接指到具体文本节点。 */
    private static void recordHits(String part, int paragraphIndex, List<Element> slots,
                                   List<String> before, List<Change> changes, List<Hit> hits) {
        int[] offsets = new int[slots.size() + 1];
        for (int i = 0; i < slots.size(); i++) {
            offsets[i + 1] = offsets[i] + before.get(i).length();
        }
        for (Change change : changes) {
            int slot = 0;
            for (int i = 0; i < slots.size(); i++) {
                if (change.start() >= offsets[i] && change.start() < offsets[i + 1]) {
                    slot = i;
                    break;
                }
            }
            hits.add(new Hit(part, paragraphIndex, slot, change.original(), change.token(), change.ruleKey(),
                    change.start(), change.end(),
                    HitGrouping.unitId(part, "P" + paragraphIndex), change.occurrence()));
        }
    }

    // ------------------------------------------------------------------ 元数据

    // ------------------------------------------------------------------ XML 遍历

    /**
     * 深度优先收集段落内的文本槽。
     *
     * <p>遇到嵌套的 {@code <w:p>} 就停：文本框里的段落是独立段落，由外层循环自己处理。
     * 在此处继续下钻会让同一段文本被处理两次。
     */
    private static List<Element> collectSlots(Element paragraph) {
        List<Element> slots = new ArrayList<>();
        collectSlots(paragraph, slots);
        return slots;
    }

    private static void collectSlots(Element element, List<Element> out) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            if (!W_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            String local = child.getLocalName();
            if ("p".equals(local)) {
                continue;
            }
            if ("t".equals(local) || "delText".equals(local)) {
                out.add(child);
                continue;
            }
            collectSlots(child, out);
        }
    }

    private static List<String> readSlotTexts(List<Element> slots) {
        List<String> out = new ArrayList<>(slots.size());
        for (Element slot : slots) {
            out.add(slot.getTextContent());
        }
        return out;
    }

    private static void setSlotText(Element slot, String text) {
        ZipXmlPackage.setText(slot, text);
        boolean edgeSpace = !text.isEmpty()
                && (Character.isWhitespace(text.charAt(0))
                || Character.isWhitespace(text.charAt(text.length() - 1)));
        if (edgeSpace) {
            // 不声明 xml:space="preserve"，Word 重开文档会把首尾空格吃掉，
            // 脱敏后的文档内容就和原文对不上，还原必然失败。
            slot.setAttributeNS(XML_NS, "xml:space", "preserve");
        } else {
            slot.removeAttributeNS(XML_NS, "space");
        }
    }

    private static boolean isProcessablePart(String name) {
        return ZipXmlPackage.isXmlPart(name)
                && (name.startsWith("word/") || name.startsWith("docProps/"));
    }

    // ------------------------------------------------------------------ IO

}
