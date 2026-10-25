package com.litemask.pptx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 测试用的最小 pptx 构造器。 */
public final class PptxFixture {

    private static final Pattern TEXT = Pattern.compile(
            "<(?:a|c):(?:t|v)(?:\\s[^>]*)?>(.*?)</(?:a|c):(?:t|v)>", Pattern.DOTALL);

    private final Map<String, byte[]> parts = new LinkedHashMap<>();

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String C = "http://schemas.openxmlformats.org/drawingml/2006/chart";

    /** 一个段落，内部由若干 run 组成。 */
    public static String paragraph(String... runTexts) {
        StringBuilder sb = new StringBuilder("<a:p>");
        for (String t : runTexts) {
            sb.append("<a:r><a:rPr lang=\"zh-CN\" dirty=\"0\"/><a:t>").append(t).append("</a:t></a:r>");
        }
        return sb.append("</a:p>").toString();
    }

    public static String shape(String paragraphs) {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"1\" name=\"文本框 1\"/></p:nvSpPr>"
                + "<p:txBody>" + paragraphs + "</p:txBody></p:sp>";
    }

    public PptxFixture slide(String shapes) {
        return part("ppt/slides/slide1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<p:sld xmlns:a=\"" + A + "\" xmlns:p=\"" + P + "\"><p:cSld><p:spTree>"
                        + shapes + "</p:spTree></p:cSld></p:sld>");
    }

    PptxFixture notes(String paragraphs) {
        return part("ppt/notesSlides/notesSlide1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<p:notes xmlns:a=\"" + A + "\" xmlns:p=\"" + P + "\"><p:cSld><p:spTree>"
                        + paragraphs + "</p:spTree></p:cSld></p:notes>");
    }

    /** 图表：字符串缓存与数值缓存各给一个。 */
    PptxFixture chart(String strCache, String numCache) {
        return part("ppt/charts/chart1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<c:chartSpace xmlns:c=\"" + C + "\" xmlns:a=\"" + A + "\"><c:chart><c:plotArea>"
                        + "<c:barChart><c:ser><c:cat><c:strRef><c:f>Sheet1!$A$1:$A$3</c:f>"
                        + "<c:strCache><c:ptCount val=\"1\"/><c:pt idx=\"0\"><c:v>" + strCache
                        + "</c:v></c:pt></c:strCache></c:strRef></c:cat>"
                        + "<c:val><c:numRef><c:f>Sheet1!$B$1:$B$1</c:f>"
                        + "<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"1\"/>"
                        + "<c:pt idx=\"0\"><c:v>" + numCache + "</c:v></c:pt></c:numCache></c:numRef></c:val>"
                        + "</c:ser></c:barChart></c:plotArea></c:chart></c:chartSpace>");
    }

    PptxFixture part(String name, String content) {
        parts.put(name, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    PptxFixture binary(String name, byte[] data) {
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

    static byte[] rawPart(Path pptx, String name) throws IOException {
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IOException("缺少 part: " + name);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static String textPart(Path pptx, String name) throws IOException {
        return new String(rawPart(pptx, name), StandardCharsets.UTF_8);
    }

    /** 抽出所有 {@code <a:t>} 与 {@code <c:v>} 的文本，用来断言原值还在不在。 */
    public static String allText(Path pptx) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                if (!entry.getName().startsWith("ppt/") || !entry.getName().endsWith(".xml")) {
                    continue;
                }
                String xml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = TEXT.matcher(xml);
                while (m.find()) {
                    sb.append(unescape(m.group(1))).append('\n');
                }
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
