package com.litemask.docx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 测试用的最小 docx 构造器。
 *
 * <p>刻意手工拼 XML 而不用 POI 之类的高层库生成：需要构造的场景（实体横跨三个 run、
 * 文本框嵌在 run 里、修订留痕的 delText、批注）都不是正常编辑会产生的样子，
 * 手写才能精确控制。
 *
 * <p>读取文本同样用正则而非 DOM——如果测试复用被测代码的解析逻辑，
 * 那个逻辑本身写错时测试会跟着错，测了个寂寞。
 */
public final class DocxFixture {

    private static final Pattern W_TEXT = Pattern.compile(
            "<w:(?:t|delText)(?:\\s[^>]*)?>(.*?)</w:(?:t|delText)>", Pattern.DOTALL);

    private final Map<String, byte[]> parts = new LinkedHashMap<>();

    DocxFixture document(String bodyXml) {
        parts.put("word/document.xml", xml(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                        + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<w:body>" + bodyXml + "</w:body></w:document>"));
        return this;
    }

    DocxFixture part(String name, String content) {
        parts.put(name, xml(content));
        return this;
    }

    DocxFixture binary(String name, byte[] data) {
        parts.put(name, data.clone());
        return this;
    }

    public Path writeTo(Path dir, String fileName) throws IOException {
        Path target = dir.resolve(fileName);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return target;
    }

    /** 读取某个 part 的原始字节。 */
    static byte[] rawPart(Path docx, String name) throws IOException {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IOException("缺少 part: " + name);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static String textPart(Path docx, String name) throws IOException {
        return new String(rawPart(docx, name), StandardCharsets.UTF_8);
    }

    static List<String> partNames(Path docx) throws IOException {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return zip.stream().map(ZipEntry::getName).toList();
        }
    }

    /** 抽取全文：把所有 w:t / w:delText 按出现顺序拼起来。 */
    public static String text(Path docx, String part) throws IOException {
        String xml = textPart(docx, part);
        StringBuilder sb = new StringBuilder();
        Matcher m = W_TEXT.matcher(xml);
        while (m.find()) {
            sb.append(unescape(m.group(1)));
        }
        return sb.toString();
    }

    /**
     * XML 文本节点里的尖括号必须写成实体。
     *
     * <p>所以生产输出里看到的是 {@code &lt;身份证1&gt;}——那是<b>正确</b>的 XML，
     * Word 读出来就是 {@code <身份证1>}。测试断言必须先反转义再比较，
     * 否则会把正确实现当成 bug。
     */
    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** 把若干段落包成带样式属性的 run，方便验证样式是否存活。 */
    public static String run(String rPr, String text) {
        return "<w:r>" + (rPr == null ? "" : "<w:rPr>" + rPr + "</w:rPr>") + "<w:t>" + text + "</w:t></w:r>";
    }

    public static String paragraph(String inner) {
        return "<w:p>" + inner + "</w:p>";
    }

    private static byte[] xml(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 供断言使用的最小 docx：正文一段、一个关系条目。 */
    public static DocxFixture simple(String bodyXml) {
        DocxFixture f = new DocxFixture();
        f.parts.put("[Content_Types].xml", xml(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "</Types>"));
        f.parts.put("_rels/.rels", xml(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>"));
        f.document(bodyXml);
        return f;
    }

    static List<String> lines(String... s) {
        return new ArrayList<>(List.of(s));
    }
}
