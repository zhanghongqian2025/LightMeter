package com.litemask.ooxml;

import com.litemask.zipxml.ZipXmlPackage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.List;

/**
 * 清理 OOXML 的文档属性。
 *
 * <p>正文脱干净了、属性面板里还留着「张伟」，等于没脱：审阅者双击文件属性就能看到，
 * 而且这些字段<b>永远不会被任何正则命中</b>——它们不在正文里，解析阶段根本不会扫到。
 * 所以必须单独、显式地清。
 *
 * <p>docx 与 xlsx 的 docProps 结构一致，共用这一份。
 */
public final class DocPropsScrubber {

    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";
    private static final String CP_NS = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties";
    private static final String EP_NS = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties";
    private static final String CUSTOM_NS = "http://schemas.openxmlformats.org/officeDocument/2006/custom-properties";

    private DocPropsScrubber() {
    }

    /** @return 被清空的字段数；0 表示这个 part 不需要动 */
    public static int scrub(Document doc, String partName) {
        return switch (partName) {
            case "docProps/core.xml" -> clearNames(doc, DC_NS, "creator", "title", "subject", "description")
                    + clearNames(doc, CP_NS, "lastModifiedBy", "keywords", "category", "contentStatus", "lastPrinted");
            case "docProps/app.xml" -> clearNames(doc, EP_NS, "Company", "Manager");
            case "docProps/custom.xml" -> clearCustomProperties(doc);
            default -> 0;
        };
    }

    private static int clearNames(Document doc, String ns, String... localNames) {
        int cleared = 0;
        for (String name : localNames) {
            NodeList nodes = doc.getElementsByTagNameNS(ns, name);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                if (element.getFirstChild() != null) {
                    ZipXmlPackage.setText(element, "");
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /** 自定义属性的值完全由用户定义。保留结构、只清值：删掉属性本身可能让 Excel/Word 报错。 */
    private static int clearCustomProperties(Document doc) {
        NodeList nodes = doc.getElementsByTagNameNS(CUSTOM_NS, "property");
        int cleared = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element property = (Element) nodes.item(i);
            if (property.getFirstChild() != null) {
                ZipXmlPackage.setText(property, "");
                cleared++;
            }
        }
        return cleared;
    }

    /** 供报告展示：会清哪些字段，避免"为什么作者字段没动"这种疑问无处可查。 */
    public static List<String> coreFieldNames() {
        return List.of("dc:creator", "dc:title", "dc:subject", "dc:description",
                "cp:lastModifiedBy", "cp:keywords", "cp:category", "cp:contentStatus", "cp:lastPrinted");
    }
}
