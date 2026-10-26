package com.litemask.xlsx;

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
 * xlsx 脱敏。
 *
 * <p>同样只依赖 JDK：xlsx 就是个 pkg，文本分散在几个特定位置，全部列在 {@link #isProcessablePart}
 * 与下面的处理分支里。覆盖：
 *
 * <ul>
 *   <li><b>共享字符串</b> {@code xl/sharedStrings.xml}：最常见的存放位置。一个字符串被多个单元格
 *       引用时只存一份，改它等于改所有引用它的单元格——这正好符合"同值同符"。</li>
 *   <li><b>内联字符串</b> {@code t="inlineStr"}：直接写在 {@code <is>} 里。</li>
 *   <li><b>公式缓存结果</b> {@code t="str"}：{@code <v>} 里是上次计算的显示值。</li>
 *   <li><b>数值单元格</b>：银行卡号、手机号常被存成数字（Excel 会把 18 位证件号转成科学计数法，
 *       所以实践中多数是文本，但银行卡号确实常是数字）。匹配到就转成字符串并计数上报。</li>
 *   <li><b>工作表名</b>：{@code <sheet name="张三的账户">}。Excel 允许 {@code < >}，
 *       所以可以直接用占位符，不用另造映射。</li>
 *   <li><b>定义名称</b>、<b>批注</b>、<b>文档属性</b>。</li>
 * </ul>
 *
 * <p><b>刻意留下的缺口</b>：图表缓存值（{@code xl/charts/chart*.xml} 里的 {@code <c:pt><c:v>}）
 * 不处理。Excel 打开时会按源单元格重算缓存，所以显示层不会泄漏；但直接解压 xlsx 仍能看到旧缓存值。
 * 已列入 M1 待办，不在这里假装覆盖了。
 */
public final class XlsxRedactor {

    private static final String S_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String XML_NS = XMLConstants.XML_NS_URI;

    private XlsxRedactor() {
    }

    /** 一次替换的定位信息：{@code part} + {@code locator}，便于人工核对到具体单元格。 */
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

    public record Report(int locationsScanned, int partsRewritten, int numericCellsConverted,
                         int sheetNamesRedacted, int metadataFieldsCleared,
                         List<Hit> hits, Map<String, String> mapping) {
    }

    private record Rewrite(Map<String, byte[]> changed, int locations, int numericConverted,
                           int sheetNames, int metadataCleared, List<Hit> hits) {
    }

    public static Report redact(Path in, Path out, List<Rule> rules) throws IOException {
        return redact(in, out, rules, Exclusions.NONE);
    }

    /** 带排除项的脱敏；排除项在会话级生效，贯穿整篇工作簿。 */
    public static Report redact(Path in, Path out, List<Rule> rules, Exclusions exclusions)
            throws IOException {
        return redact(in, out, rules, exclusions, List.of());
    }

    /**
     * 带非正则来源（NER 之类）的脱敏；来源与规则共用同一个会话。
     *
     * <p>xlsx 的「位置」极多（每个单元格、每个共享字符串片段、每个定义名称、每条批注），
     * 全部走同一个 {@link RedactionSession}，所以来源只要在会话里放一份。
     */
    public static Report redact(Path in, Path out, List<Rule> rules, Exclusions exclusions,
                                 List<EntitySource> sources) throws IOException {
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(in)) {
            TextPlan plan = extractText(pkg);
            // 单元序列**显式声明**，不用「全文按 '\n' 切」那条路。
            //
            // 续接靠「当前喂进去的文本 == 单元序列里的第 N 个」来对齐，对不上就整体关掉续接。
            // 而 xlsx 的元数据（工作表名 / 定义名称 / 批注）走**隐藏路径**——它们不喂编号路径。
            // 旧写法把它们也放进了单元序列（因为全文里有），于是对齐结果取决于
            // **zip 里部件的先后**：workbook.xml 排在工作表前面时，单元序列第一个是工作表名，
            // 而第一个喂进去的是单元格文本 → 对不上 → 续接被静默关掉 →
            // 被切断的身份证碎片原样留在包里（块 14 的 xlsx 回归）。
            //
            // 现在单元序列只收编号路径真正喂的文本，元数据只进全文（分配器要扫它），
            // 两边互不干扰，且**与部件顺序无关**。
            //
            // 单元**按 zip 部件分组**（块 15）：组内可以续接，组间一律不续接。
            // sharedStrings 里的字符串与工作表里的单元格值在文件里挨着，在语义上却是两件事——
            // 合成一组时它们会被拼成一个校验位正确的身份证号，两段都被错标成身份证。
            RedactionSession session = new RedactionSession(plan.fullText(), rules, exclusions, sources,
                    plan.groups(), ContinuationScope.ANY);
            Rewrite rewrite = rewrite(pkg, session);
            pkg.write(out, rewrite.changed());
            return new Report(rewrite.locations(), rewrite.changed().size(), rewrite.numericConverted(),
                    rewrite.sheetNames(), rewrite.metadataCleared(), rewrite.hits(), session.mapping());
        }
    }

    /**
     * 还原。
     *
     * <p>数值单元格被转成字符串后无法还原回数字类型——Excel 里的数字与文本是两个东西，
     * 还原成文本会让求和失效。这一点在脱敏时就已计入报告，不在还原阶段偷偷处理。
     */
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
                NodeList textNodes = doc.getElementsByTagNameNS(S_NS, "t");
                for (int i = 0; i < textNodes.getLength(); i++) {
                    TaskContext.current().checkCancelled();
                    Element node = (Element) textNodes.item(i);
                    String back = RedactionEngine.restore(node.getTextContent(), mapping);
                    if (!back.equals(node.getTextContent())) {
                        ZipXmlPackage.setText(node, back);
                        dirty = true;
                        restored++;
                    }
                }
                NodeList values = doc.getElementsByTagNameNS(S_NS, "v");
                for (int i = 0; i < values.getLength(); i++) {
                    TaskContext.current().checkCancelled();
                    Element node = (Element) values.item(i);
                    String back = RedactionEngine.restore(node.getTextContent(), mapping);
                    if (!back.equals(node.getTextContent())) {
                        ZipXmlPackage.setText(node, back);
                        dirty = true;
                        restored++;
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
     * <p><b>为什么必须分成两样</b>：跨单元续接靠「当前喂进去的文本 == 单元序列里的第 N 个」
     * 来对齐，一旦对不上就整体关掉续接（宁可漏脱也不错配）。
     * 而 xlsx 的元数据（工作表名、定义名称、批注）走的是<b>隐藏路径</b>——它们<b>不喂</b>编号路径。
     * 若它们也进了单元序列，序号就整体错位。详见 {@link TextPlan}。
     *
     * <p><b>这个坑真实发生过（块 14 的 xlsx 回归）</b>：单元序列原来直接由全文按 {@code '\n'}
     * 切分得到，而全文里含工作表名。于是对齐结果取决于 <b>zip 里部件的先后</b>——
     * {@code xl/workbook.xml} 排在工作表前面时，单元序列第一个是工作表名，
     * 而第一个喂进去的却是单元格文本，对不上 → 续接被静默关掉 →
     * 被切断的身份证碎片原样留在包里。单测当时还是绿的，因为夹具把工作表名放在了最后
     * （部件顺序不同，结论就不同）——<b>这种「换个顺序结论就变」的依赖必须由显式声明消掉</b>。
     *
     * <p><b>每组 = 一个 zip 部件</b>（块 15）：共享字符串与工作表单元格是两种容器，
     * 合成一组时前者末尾的编号与后者开头的字母会被拼成一个校验位正确的身份证号，
     * 两段都被错标成身份证。分组之后只有「同一个容器里的相邻单元」才能续接。
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
            collect(doc, entry.getName(), full, partUnits);
            groups.add(partUnits);
        }
        return new TextPlan(full.toString(), List.copyOf(groups));
    }

    /**
     * 收集一个部件里的文本。
     *
     * @param full  整篇文本（分配器要扫它有没有既有占位符，元数据也得算进去）
     * @param units 这个部件的单元序列（<b>只收编号路径真正喂的文本</b>）
     */
    private static void collect(Document doc, String part, StringBuilder full, List<String> units) {
        // 共享字符串：一个 <si> 就是一个单元，内部富文本片段是它的槽
        NodeList sharedItems = doc.getElementsByTagNameNS(S_NS, "si");
        for (int i = 0; i < sharedItems.getLength(); i++) {
            String text = String.join("", textsOf((Element) sharedItems.item(i)));
            full.append(text).append('\n');
            units.add(text);
        }
        // 工作表里的单元格：不承载正文的不喂会话（共享字符串引用、布尔/错误/日期、空单元格）
        NodeList cells = doc.getElementsByTagNameNS(S_NS, "c");
        for (int i = 0; i < cells.getLength(); i++) {
            CellText ct = cellText((Element) cells.item(i));
            if (ct == null) {
                continue;
            }
            full.append(ct.text()).append('\n');
            units.add(ct.text());
        }
        // 元数据（工作表名 / 定义名称）：**只进全文，不进单元序列**。
        // 它们走隐藏路径（不进命中清单、不占出现序号），也不喂编号路径。
        NodeList sheets = doc.getElementsByTagNameNS(S_NS, "sheet");
        for (int i = 0; i < sheets.getLength(); i++) {
            full.append(((Element) sheets.item(i)).getAttribute("name")).append('\n');
        }
        NodeList defined = doc.getElementsByTagNameNS(S_NS, "definedName");
        for (int i = 0; i < defined.getLength(); i++) {
            full.append(defined.item(i).getTextContent()).append('\n');
        }
    }

    private static Rewrite rewrite(ZipXmlPackage.Session pkg, RedactionSession session) throws IOException {
        Map<String, byte[]> changed = new LinkedHashMap<>();
        int locations = 0;
        int numericConverted = 0;
        int sheetNames = 0;
        int metadataCleared = 0;
        List<Hit> hits = new ArrayList<>();

        for (ZipEntry entry : pkg.entries()) {
            String name = entry.getName();
            if (!isProcessablePart(name)) {
                continue;
            }
            Document doc = pkg.parse(pkg.read(entry));
            boolean dirty = false;

            // 共享字符串：一个 <si> 就是一个"段"，内部富文本片段是它的槽
            NodeList sharedItems = doc.getElementsByTagNameNS(S_NS, "si");
            for (int i = 0; i < sharedItems.getLength(); i++) {
                Element si = (Element) sharedItems.item(i);
                List<Element> slots = textNodesOf(si);
                locations++;
                if (redactSlots(si, slots, session, name, "si#" + i, hits)) {
                    dirty = true;
                }
            }

            // 工作表里的单元格
            NodeList cells = doc.getElementsByTagNameNS(S_NS, "c");
            for (int i = 0; i < cells.getLength(); i++) {
                Element cell = (Element) cells.item(i);
                locations++;
                CellOutcome outcome = redactCell(cell, session, name, hits);
                if (outcome != CellOutcome.UNCHANGED) {
                    dirty = true;
                    if (outcome == CellOutcome.NUMERIC_CONVERTED) {
                        numericConverted++;
                    }
                }
            }

            // 工作表名（属性，不是文本节点）
            NodeList sheets = doc.getElementsByTagNameNS(S_NS, "sheet");
            for (int i = 0; i < sheets.getLength(); i++) {
                Element sheet = (Element) sheets.item(i);
                String original = sheet.getAttribute("name");
                if (original.isEmpty()) {
                    continue;
                }
                // 工作表名：改了内容但不进命中清单，所以走 redactHidden 不占出现序号
                String redacted = sanitizeSheetName(session.redactHidden(original).sanitizedText());
                if (!redacted.equals(original)) {
                    sheet.setAttribute("name", redacted);
                    dirty = true;
                    sheetNames++;
                    locations++;
                }
            }

            // 定义名称
            NodeList defined = doc.getElementsByTagNameNS(S_NS, "definedName");
            for (int i = 0; i < defined.getLength(); i++) {
                Element node = (Element) defined.item(i);
                String original = node.getTextContent();
                // 定义名称：同上，不进命中清单，不占出现序号
                String redacted = session.redactHidden(original).sanitizedText();
                if (!redacted.equals(original)) {
                    ZipXmlPackage.setText(node, redacted);
                    dirty = true;
                    locations++;
                }
            }

            // 批注等剩余的 <t> 文本节点（共享字符串已处理过的不再重复）
            if (name.startsWith("xl/comments")) {
                NodeList texts = doc.getElementsByTagNameNS(S_NS, "t");
                for (int i = 0; i < texts.getLength(); i++) {
                    Element node = (Element) texts.item(i);
                    String original = node.getTextContent();
                    // 批注：同上，不进命中清单，不占出现序号
                    String redacted = session.redactHidden(original).sanitizedText();
                    if (!redacted.equals(original)) {
                        ZipXmlPackage.setText(node, redacted);
                        dirty = true;
                        locations++;
                    }
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
        return new Rewrite(changed, locations, numericConverted, sheetNames, metadataCleared, List.copyOf(hits));
    }

    private enum CellOutcome {
        UNCHANGED,
        /** 文本内容被替换，单元格类型不变 */
        REDACTED,
        /** 数值单元格被转成了内联字符串——求和会失效，必须上报 */
        NUMERIC_CONVERTED
    }

    private static boolean redactSlots(Element owner, List<Element> slots, RedactionSession session,
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

    private static CellOutcome redactCell(Element cell, RedactionSession session, String part, List<Hit> hits) {
        CellText ct = cellText(cell);
        // 不承载正文的单元格（共享字符串引用、布尔/错误/日期、空单元格）：跳过。
        // 「哪些单元格承载正文」只有 {@link #cellText} 一处定义——预扫与脱敏必须一致。
        if (ct == null) {
            return CellOutcome.UNCHANGED;
        }
        if (ct.inline() != null) {
            return redactSlots(ct.inline(), textNodesOf(ct.inline()), session, part,
                    "cell " + cell.getAttribute("r"), hits)
                    ? CellOutcome.REDACTED : CellOutcome.UNCHANGED;
        }
        Element value = ct.value();
        String original = ct.text();
        RedactionResult result = session.redact(original);
        if (!result.isChanged()) {
            return CellOutcome.UNCHANGED;
        }
        String redacted = result.sanitizedText();
        Element formula = firstChild(cell, "f");
        if (formula != null) {
            // 有公式：保留 <f>，把缓存结果改成字符串类型
            cell.setAttribute("t", "str");
            ZipXmlPackage.setText(value, redacted);
        } else {
            // 无公式的数值单元格：转成内联字符串，否则 Excel 会把它当数字
            cell.setAttribute("t", "inlineStr");
            cell.removeChild(value);
            Element is = cell.getOwnerDocument().createElementNS(S_NS, "is");
            Element t = cell.getOwnerDocument().createElementNS(S_NS, "t");
            ZipXmlPackage.setText(t, redacted);
            is.appendChild(t);
            cell.appendChild(is);
        }
        for (Change change : result.changes()) {
            String cellLocator = "cell " + cell.getAttribute("r");
            hits.add(new Hit(part, cellLocator, change.original(), change.token(), change.ruleKey(),
                    change.start(), change.end(), HitGrouping.unitId(part, cellLocator), change.occurrence()));
        }
        return formula != null ? CellOutcome.REDACTED : CellOutcome.NUMERIC_CONVERTED;
    }

    /**
     * Excel 的工作表名非法字符是 {@code : \ / ? * [ ]}。
     * 占位符里的 {@code < >} 是合法的，可以直接用；这里仍做一次兜底替换，
     * 免得将来换了令牌格式就生成一个打不开的文件。
     */
    private static String sanitizeSheetName(String name) {
        String safe = name.replaceAll("[:\\\\/?*\\[\\]]", "_");
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    /** 收集容器内的文本节点（{@code <t>}），跳过 {@code <rPh>} 注音子树。 */
    private static List<Element> textNodesOf(Element container) {
        List<Element> out = new ArrayList<>();
        walk(container, out);
        return out;
    }

    private static void walk(Element element, List<Element> out) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            if (!S_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            String local = child.getLocalName();
            if ("rPh".equals(local)) {
                continue;
            }
            if ("t".equals(local)) {
                out.add(child);
                continue;
            }
            walk(child, out);
        }
    }

    private static List<String> textsOf(Element container) {
        List<String> out = new ArrayList<>();
        for (Element e : textNodesOf(container)) {
            out.add(e.getTextContent());
        }
        return out;
    }

    /**
     * 一个单元格到底装了什么正文。
     *
     * @param text   单元格里带的正文；{@code null} = 这个单元格不承载正文（不喂会话）
     * @param inline 内联字符串节点（非空时正文在 {@code <is>} 里）
     * @param value  数值/缓存值节点（非空时正文在 {@code <v>} 里）
     */
    private record CellText(String text, Element inline, Element value) {
    }

    /**
     * 单元格里的正文；{@code null} 表示这个单元格不承载正文，不该喂给会话。
     *
     * <p>不承载正文的四类：{@code t="s"}（共享字符串引用，正文在 sharedStrings.xml）、
     * {@code t="b"/"e"/"d"}（布尔/错误/日期）、以及没有 {@code <is>} 也没有 {@code <v>} 的空单元格。
     *
     * <p><b>为什么必须是单独一处</b>：预扫（收集单元序列）与脱敏（真正喂给会话）
     * 都要知道同一个答案。两处各写一遍，迟早会漂；漂了的后果不是「某处漏脱」，
     * 而是<b>单元序列与实际喂进去的文本对不上 → 续接被静默关掉</b>
     * （块 14 的 xlsx 回归就是这么来的）。
     */
    private static CellText cellText(Element cell) {
        String type = cell.getAttribute("t");
        if ("s".equals(type) || "b".equals(type) || "e".equals(type) || "d".equals(type)) {
            return null;
        }
        Element inline = firstChild(cell, "is");
        if (inline != null) {
            return new CellText(String.join("", textsOf(inline)), inline, null);
        }
        Element value = firstChild(cell, "v");
        return value == null ? null : new CellText(value.getTextContent(), null, value);
    }

    private static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && S_NS.equals(node.getNamespaceURI())
                    && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static boolean isProcessablePart(String name) {
        if (!ZipXmlPackage.isXmlPart(name)) {
            return false;
        }
        return name.startsWith("xl/") || name.startsWith("docProps/");
    }

    /**
     * 写入文本并处理 {@code xml:space}。
     *
     * <p>xlsx 与 docx 是同一个坑：{@code <t>} 的首尾空格不加 {@code xml:space="preserve"}
     * 就会被 Excel 丢掉，于是"身份证 " 变成"身份证"，还原对不上。
     */
    static void setTextPreservingSpace(Element element, String text) {
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
}
