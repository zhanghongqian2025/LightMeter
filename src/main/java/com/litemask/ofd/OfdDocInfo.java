package com.litemask.ofd;

import com.litemask.core.Change;
import com.litemask.core.HitGrouping;
import com.litemask.core.RedactionEngine;
import com.litemask.core.RedactionResult;
import com.litemask.core.RedactionSession;
import com.litemask.zipxml.ZipXmlPackage;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OFD 文档属性（{@code <ofd:DocInfo>}）。
 *
 * <p>这不是「顺手清一清」——<b>属性里就躺着证件号，而且正文里一个都没有</b>。
 * 核对过一张真实增值税发票样本：{@code DocInfo/CustomDatas/CustomData} 里直接写着
 * 发票号码 {@code 24112000000048542163} 和两个纳税人识别号
 * （{@code 91110108690017781B}、{@code 91460300MAA92DJN95}）。
 * 只扫 {@code TextCode} 的实现会理直气壮地报告「已脱敏」，而这三个值原封不动躺在文件里，
 * 用任何工具打开属性面板就能看到。
 *
 * <p><b>清空还是脱敏？两者都要</b>：
 * <ul>
 *   <li>{@code Author} / {@code Creator} <b>清空</b>。它们装的是人名或工具名，
 *       任何正则都匹配不上，等 NER 到位之前只能清。与 PDF 的文档属性处理一致。</li>
 *   <li>其余字段<b>按规则脱敏</b>。像 {@code CustomData} 的值是货真价实的业务数据，
 *       规则命中就换成令牌，而且因为原值进了映射，<b>可以还原</b>——这跟 PDF 的
 *       {@code /Info} 不一样，PDF 那边原值从不保存。</li>
 * </ul>
 *
 * <p>{@code DocID} 跳过：它是文档标识符（哈希），不是人读的信息，
 * 而一串 18 位十六进制完全可能碰巧通过身份证校验位——那会造成一次莫名其妙的误脱，
 * 还会把文档标识改掉。
 */
final class OfdDocInfo {

    /** 直接清空、不尝试匹配的字段。 */
    private static final Set<String> CLEARED = Set.of("Author", "Creator");

    /** 跳过的字段：标识符，不是正文。 */
    private static final Set<String> SKIPPED = Set.of("DocID");

    private OfdDocInfo() {
    }

    record Result(boolean changed, int cleared, List<String> clearedFields) {
        static final Result NONE = new Result(false, 0, List.of());
    }

    static Result redact(Element docInfo, String part, RedactionSession session, List<OfdRedactor.Hit> hits,
                          OfdRedactor.UnitCollector collector) {
        int cleared = 0;
        boolean changed = false;
        List<String> clearedFields = new ArrayList<>();

        for (Element leaf : leaves(docInfo)) {
            String name = leaf.getLocalName();
            if (SKIPPED.contains(name)) {
                continue;
            }
            if (CLEARED.contains(name)) {
                String before = leaf.getTextContent();
                if (!before.isEmpty()) {
                    ZipXmlPackage.setText(leaf, "");
                    cleared++;
                    clearedFields.add(part + " :: DocInfo/" + name);
                    changed = true;
                }
                continue;
            }
            String before = leaf.getTextContent();
            // 收集模式：这也是一次真实的喂入，必须进单元序列（否则后面的单元会整体错位）。
            if (collector != null && !before.isEmpty()) {
                collector.collect(new OfdRedactor.Unit(before, OfdRedactor.Kind.DOC_INFO, null, part));
            }
            RedactionResult result = session.redact(before);
            if (result.isChanged()) {
                ZipXmlPackage.setText(leaf, result.sanitizedText());
                changed = true;
                for (Change c : result.changes()) {
                    hits.add(new OfdRedactor.Hit(part, "DocInfo/" + name, c.original(), c.token(), c.ruleKey(),
                            c.start(), c.end(), HitGrouping.unitId(part, "DocInfo/" + name),
                            c.occurrence()));
                }
            }
            if (redactAttributes(leaf, session, part, "DocInfo/" + name, hits, collector)) {
                changed = true;
            }
        }
        return new Result(changed, cleared, clearedFields);
    }

    /** 只取叶子。
     *
     * <p>{@code CustomDatas} 这类容器节点如果也去脱敏，{@code getTextContent()}
     * 会把所有子项拼成一大串再替换，然后 {@code setText} 把子节点全删掉——
     * 文档结构直接毁掉，而且不报错，只是内容没了。
     */
    private static List<Element> leaves(Element root) {
        List<Element> out = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (!hasElementChild(element)) {
                out.add(element);
            }
        }
        return out;
    }

    private static boolean redactAttributes(Element element, RedactionSession session, String part,
                                            String carrier, List<OfdRedactor.Hit> hits,
                                            OfdRedactor.UnitCollector collector) {
        boolean dirty = false;
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String before = attr.getNodeValue();
            if (collector != null && !before.isEmpty()) {
                collector.collect(new OfdRedactor.Unit(before, OfdRedactor.Kind.DOC_INFO, null, part));
            }
            RedactionResult result = session.redact(before);
            if (result.isChanged()) {
                attr.setNodeValue(result.sanitizedText());
                dirty = true;
                for (Change c : result.changes()) {
                    hits.add(new OfdRedactor.Hit(part, carrier + "@" + attr.getNodeName(),
                            c.original(), c.token(), c.ruleKey(),
                            c.start(), c.end(), HitGrouping.unitId(part, carrier + "@" + attr.getNodeName()),
                            c.occurrence()));
                }
            }
        }
        return dirty;
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
}
