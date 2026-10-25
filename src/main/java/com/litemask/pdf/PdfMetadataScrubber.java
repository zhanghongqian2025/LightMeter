package com.litemask.pdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 清理 PDF 里「不显示但能被读到」的东西。
 *
 * <p>脱敏工具最容易出的事故不是漏掉页面上看得见的字，而是**页面上看不见、但复制得出来**的东西：
 * 作者名、修订记录、内嵌的原始文件、Word 写下的私有数据、批注里的姓名。这些正是「零命中」
 * 却仍然泄露的典型来源，所以必须显式清理、显式记账。
 *
 * <p>对每类东西的处理口径不同，能改的就地改，改不干净的一律整项删除：
 * <ul>
 *   <li>文档信息字典（{@code /Info}）：整体换成空字典。自定义键位（Company、SourceModified…）
 *       之类谁也不知道塞了什么，整体换掉比逐个清白名单可靠。</li>
 *   <li>XMP 元数据（{@code /Metadata}）：整项删除。它常带作者、原文件名、DocumentID，
 *       还可能内嵌一张 base64 缩略图——那等于把整页原文又塞回去了一份。</li>
 *   <li>内嵌文件（{@code /EmbeddedFiles}）：整项删除。附件里常常就是**未脱敏的原文档**。</li>
 *   <li>JavaScript 与自动动作（{@code /OpenAction}、{@code /AA}、{@code /Names/JavaScript}）：
 *       整项删除。既是泄露面，也是安全隐患。</li>
 *   <li>表单（{@code /AcroForm}）：整项删除。字段值 {@code /V} 与外观流 {@code /AP} 里各存一份，
 *       只改一处不改另一处是最常见的「看着脱了其实没脱」。</li>
 *   <li>批注：{@code /T}（作者名）清空，{@code /Contents}（批注正文）过引擎脱敏。
 *       批注本身要留着——它是文档内容。</li>
 * </ul>
 */
final class PdfMetadataScrubber {

    private PdfMetadataScrubber() {
    }

    /**
     * @param session 共享会话，批注正文与书签标题要过它，保证同值同符
     * @return 被清理项的清单，直接进报告，让用户看见到底动了什么
     */
    static List<String> scrub(PDDocument doc, RedactionSessionView session) throws IOException {
        List<String> removed = new ArrayList<>();

        // 1) 文档信息字典：整体换空
        PDDocumentInformation info = doc.getDocumentInformation();
        if (!isEmptyInfo(info)) {
            doc.setDocumentInformation(new PDDocumentInformation());
            removed.add("文档信息字典 /Info（作者、标题、创建软件、创建/修改时间及全部自定义键位）");
        }

        COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();

        // 2) XMP 元数据
        if (catalog.getItem(COSName.METADATA) != null) {
            catalog.setItem(COSName.METADATA, null);
            removed.add("XMP 元数据 /Metadata（常含作者、原文件名与整页缩略图）");
        }

        // 3) 私有数据 /PieceInfo（Word 写入的 WordPrivateData 等）
        if (catalog.getItem(COSName.getPDFName("PieceInfo")) != null) {
            catalog.setItem(COSName.getPDFName("PieceInfo"), null);
            removed.add("私有数据 /PieceInfo");
        }

        // 4) 名字树里的内嵌文件与 JavaScript
        // 必须解引用：PDFBox 里 Names 常以 COSObject 形式挂在目录上，直接取会拿到壳
        COSDictionary names = asDict(catalog.getDictionaryObject(COSName.NAMES));
        if (names != null) {
            if (dereference(names.getItem(COSName.getPDFName("EmbeddedFiles"))) != null) {
                names.setItem(COSName.getPDFName("EmbeddedFiles"), null);
                removed.add("内嵌附件 /Names/EmbeddedFiles（常直接就是未脱敏的原文档）");
            }
            if (dereference(names.getItem(COSName.getPDFName("JavaScript"))) != null) {
                names.setItem(COSName.getPDFName("JavaScript"), null);
                removed.add("文档级 JavaScript /Names/JavaScript");
            }
        }

        // 5) 打开动作与附加动作
        if (catalog.getItem(COSName.OPEN_ACTION) != null) {
            catalog.setItem(COSName.OPEN_ACTION, null);
            removed.add("打开动作 /OpenAction");
        }
        if (catalog.getItem(COSName.getPDFName("AA")) != null) {
            catalog.setItem(COSName.getPDFName("AA"), null);
            removed.add("附加动作 /AA");
        }

        // 6) 表单：值与外观流两份，只改一处等于没改，因此整项删除
        if (catalog.getItem(COSName.getPDFName("AcroForm")) != null) {
            catalog.setItem(COSName.getPDFName("AcroForm"), null);
            removed.add("交互表单 /AcroForm（字段值与外观流各存一份，整项删除以免漏脱）");
        }

        // 7) 书签标题：不是私有的，但常含案件名、当事人名
        COSDictionary outlines = asDict(catalog.getItem(COSName.OUTLINES));
        if (outlines != null) {
            int redacted = redactOutlineTitles(outlines, session);
            if (redacted > 0) {
                removed.add("书签标题 " + redacted + " 处（按同一套规则脱敏）");
            }
        }

        // 8) 页面级：私有数据与批注
        int annotationContents = 0;
        int annotationAuthors = 0;
        for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
            COSDictionary pageDict = page.getCOSObject();
            if (pageDict.getItem(COSName.getPDFName("PieceInfo")) != null) {
                pageDict.setItem(COSName.getPDFName("PieceInfo"), null);
                removed.add("页面私有数据 /PieceInfo");
            }
            COSArray annots = asArray(pageDict.getDictionaryObject(COSName.getPDFName("Annots")));
            if (annots == null) {
                continue;
            }
            for (int i = 0; i < annots.size(); i++) {
                COSDictionary annot = asDict(dereference(annots.get(i)));
                if (annot == null) {
                    continue;
                }
                COSBase contents = dereference(annot.getItem(COSName.getPDFName("Contents")));
                if (contents instanceof COSString cs) {
                    String red = session.redact(cs.getString());
                    if (!red.equals(cs.getString())) {
                        annot.setItem(COSName.getPDFName("Contents"), new COSString(red));
                        annotationContents++;
                    }
                }
                if (annot.getItem(COSName.T) instanceof COSString author && !author.getString().isBlank()) {
                    annot.setItem(COSName.T, new COSString(""));
                    annotationAuthors++;
                }
            }
        }
        if (annotationContents > 0) {
            removed.add("批注正文 " + annotationContents + " 处命中并已脱敏");
        }
        if (annotationAuthors > 0) {
            removed.add("批注作者名 " + annotationAuthors + " 处已清空");
        }

        return removed;
    }

    private static boolean isEmptyInfo(PDDocumentInformation info) {
        COSDictionary d = info.getCOSObject();
        return d.keySet().stream().noneMatch(k ->
                d.getItem(k) instanceof COSString s && !s.getString().isBlank())
                && d.getItem(COSName.CREATION_DATE) == null
                && d.getItem(COSName.MOD_DATE) == null;
    }

    private static int redactOutlineTitles(COSDictionary outlines, RedactionSessionView session) {
        int count = 0;
        COSBase first = outlines.getItem(COSName.getPDFName("First"));
        java.util.Set<COSBase> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        while (dereference(first) instanceof COSDictionary node0 && seen.add(node0)) {
            COSDictionary node = node0;
            COSBase title = dereference(node.getItem(COSName.TITLE));
            if (title instanceof COSString ts) {
                String red = session.redact(ts.getString());
                if (!red.equals(ts.getString())) {
                    node.setItem(COSName.TITLE, new COSString(red));
                    count++;
                }
            }
            first = node.getItem(COSName.NEXT);
        }
        return count;
    }

    /** 间接对象要解引用再判断类型，否则全是 COSObject，一样都匹配不上，等于整段静默跳过。 */
    private static COSBase dereference(COSBase base) {
        return base instanceof org.apache.pdfbox.cos.COSObject o ? o.getObject() : base;
    }

    private static COSDictionary asDict(COSBase base) {
        COSBase b = dereference(base);
        return b instanceof COSDictionary d ? d : null;
    }

    private static COSArray asArray(COSBase base) {
        COSBase b = dereference(base);
        return b instanceof COSArray a ? a : null;
    }

    /** 供元数据清理复用的脱敏入口，用接口把 core 的会话类型隔开，避免这里出现实现细节。 */
    interface RedactionSessionView {
        String redact(String text);
    }

}
