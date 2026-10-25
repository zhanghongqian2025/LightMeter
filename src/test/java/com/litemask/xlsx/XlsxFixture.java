package com.litemask.xlsx;

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

/**
 * 测试用的最小 xlsx 构造器。
 *
 * <p>和 docx 夹具同一套思路：手工拼 XML，读取侧用正则而不是复用被测代码的解析逻辑。
 */
public final class XlsxFixture {

    private static final Pattern TEXT = Pattern.compile("<t(?:\\s[^>]*)?>(.*?)</t>", Pattern.DOTALL);
    private static final Pattern VALUES = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);

    private final Map<String, byte[]> parts = new LinkedHashMap<>();

    /** 共享字符串；传入的每个元素是一条 {@code <si>}。 */
    XlsxFixture sharedStrings(String... siTexts) {
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\""
                        + NS + "\" count=\"" + siTexts.length + "\" uniqueCount=\"" + siTexts.length + "\">");
        for (String t : siTexts) {
            sb.append("<si>").append(t == null ? "" : "<t>" + t + "</t>").append("</si>");
        }
        sb.append("</sst>");
        return part("xl/sharedStrings.xml", sb.toString());
    }

    /** 一个工作表：{@code <row><c r="A1" t="s"><v>0</v></c></row>} 之类的片段直接传字符串数组。 */
    XlsxFixture sheet(String name, String sheetData) {
        return part("xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<worksheet xmlns=\"" + NS + "\"><sheetData>" + sheetData + "</sheetData></worksheet>")
                .part("xl/workbook.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<workbook xmlns=\"" + NS + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                                + "<sheets><sheet name=\"" + name + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                                + "</workbook>");
    }

    public XlsxFixture sheetData(String sheetData) {
        return part("xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<worksheet xmlns=\"" + NS + "\"><sheetData>" + sheetData + "</sheetData></worksheet>");
    }

    /**
     * 只改工作表名，单元格内容不动。
     *
     * <p>用于验证「工作表名走隐形路径」：表名会被脱敏，但不进命中清单、不占用出现序号。
     */
    public XlsxFixture sheetName(String name) {
        return part("xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<workbook xmlns=\"" + NS + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"" + name + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                        + "</workbook>");
    }

    XlsxFixture workbook(String xml) {
        return part("xl/workbook.xml", xml);
    }

    XlsxFixture part(String name, String content) {
        parts.put(name, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    XlsxFixture binary(String name, byte[] data) {
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

    static byte[] rawPart(Path xlsx, String name) throws IOException {
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IOException("缺少 part: " + name);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static String textPart(Path xlsx, String name) throws IOException {
        return new String(rawPart(xlsx, name), StandardCharsets.UTF_8);
    }

    /** 抽出所有 {@code <t>} 与 {@code <v>} 的文本，用来断言"原值还在不在"。 */
    public static String allText(Path xlsx) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                if (!entry.getName().startsWith("xl/") || !entry.getName().endsWith(".xml")) {
                    continue;
                }
                String xml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                for (Pattern p : new Pattern[]{TEXT, VALUES}) {
                    Matcher m = p.matcher(xml);
                    while (m.find()) {
                        sb.append(unescape(m.group(1))).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static String sharedCell(String ref, int index) {
        return "<c r=\"" + ref + "\" t=\"s\"><v>" + index + "</v></c>";
    }

    public static String inlineCell(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + text + "</t></is></c>";
    }

    static String numericCell(String ref, String literal) {
        return "<c r=\"" + ref + "\"><v>" + literal + "</v></c>";
    }

    static String formulaCell(String ref, String formula, String cached) {
        return "<c r=\"" + ref + "\" t=\"str\"><f>" + formula + "</f><v>" + cached + "</v></c>";
    }

    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
}
