package com.litemask.ofd;

import org.ofdrw.reader.ContentExtractor;
import org.ofdrw.reader.OFDReader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * OFD 测试夹具。
 *
 * <p><b>刻意手工拼 XML，不用 ofdrw 生成</b>。用库生成夹具，测试就只能覆盖「那家库怎么写」，
 * 覆盖不到真实世界里千奇百怪的生产方；而本项目要处理的就是这些别人的文件。
 * 需要构造的场景——跨 TextCode 切分、没有 TextObject 的孤儿 TextCode、
 * 模板里不显示的字——正常编辑根本产生不出来，只能手写。
 *
 * <p><b>读取侧用 ofdrw，而不是本项目的解析代码</b>：被测实现自己写、自己读，
 * 用自己验证自己等于没验证。「我们改完的文件别人还读得动吗」这个问题，
 * 只能由一个不是我们写的读取器来回答。
 */
public final class OfdFixtures {

    public static final String PHONE = "13800138000";
    static final String PHONE_SPLIT = "13900139000";
    public static final String ID = "11010519491231002X";
    static final String ID_TEMPLATE = "11010519491231002X";
    /** 一个身份证被切成两半时的前半（单独看匹配不上任何规则）。 */
    public static final String ID_HEAD = "110105";
    /** 一个身份证被切成两半时的后半（单独看会被手机号规则错标）。 */
    public static final String ID_TAIL = "19491231002X";
    static final String EMAIL = "zhang@example.com";
    static final String CARD = "622202020202020207";
    static final String USCC = "91110108690017781B";
    static final String DEFAULT_DOC_ID = "0123456789abcdef0123456789abcdef";

    /** 一条正文里正常出现的敏感数据：手机、身份证、邮箱、银行卡。 */
    static final String BODY_LINE_1 = "联系电话：" + PHONE;
    static final String BODY_LINE_2 = "身份证：" + ID;
    static final String BODY_LINE_3 = "邮箱：" + EMAIL;
    static final String BODY_LINE_4 = "卡号：" + CARD;

    private OfdFixtures() {
    }

    /** 生成一个「处处是坑」的标准 OFD：正文、跨槽切分、孤儿 TextCode、模板、批注、属性、附件。 */
    public static Path standard(Path dir, String name) throws IOException {
        Map<String, String> parts = baseParts(DEFAULT_DOC_ID);
        parts.put("Doc_0/Attachs/" + PHONE + "_申请书.pdf", "fake-pdf-bytes");
        parts.put("Doc_0/Attachments.xml", attachmentXml());
        write(dir.resolve(name), parts);
        return dir.resolve(name);
    }

    /**
     * 生成一份<b>同一行文字在 N 个 TextObject 里各出现一次</b>的 OFD。
     *
     * <p>专供位置级排除验证：需要「同一个值出现多次、位置各不相同」这种输入，
     * 标准夹具做不到（它每个值只出现一次）。
     *
     * @param times 出现次数，至少 1
     */
    public static Path repeated(Path dir, String name, String line, int times) throws IOException {
        if (times < 1) {
            throw new IllegalArgumentException("times 至少为 1，收到: " + times);
        }
        StringBuilder layer = new StringBuilder();
        for (int i = 0; i < times; i++) {
            layer.append(textObject(100 + i, line));
        }
        Map<String, String> parts = baseParts(DEFAULT_DOC_ID);
        parts.put("Doc_0/Pages/Page_0/Content.xml",
                xml("ofd:Page",
                        "<ofd:Content>",
                        "<ofd:Layer ID=\"1\">",
                        layer.toString(),
                        "</ofd:Layer>",
                        "</ofd:Content>", ""));
        write(dir.resolve(name), parts);
        return dir.resolve(name);
    }

    /** 换一个 {@code DocID} 的标准文档——用来验证「长得像身份证的标识符不参与脱敏」。 */
    static Path standardWithDocId(Path dir, String name, String docId) throws IOException {
        Map<String, String> parts = baseParts(docId);
        write(dir.resolve(name), parts);
        return dir.resolve(name);
    }

    /** 标准文档的全部部件，可就地改写后再打包。 */
    static Map<String, String> standardParts() {
        Map<String, String> parts = baseParts(DEFAULT_DOC_ID);
        parts.put("Doc_0/Attachs/" + PHONE + "_申请书.pdf", "fake-pdf-bytes");
        parts.put("Doc_0/Attachments.xml", attachmentXml());
        return parts;
    }

    static void writePackage(Path out, Map<String, String> parts) throws IOException {
        write(out, parts);
    }

    /**
     * 造一份「N 页、每页若干个指定几何位置的 TextObject」的 OFD。
     *
     * <p>专供续接分组相关用例：这一层的判据是<b>几何</b>（同不同行、紧不紧邻），
     * 夹具必须能精确控制坐标，否则测的就不是「几何」而是「夹具恰好怎么排」。
     *
     * @param pages 每页一个 TextObject 片段列表
     */
    static Path layout(Path dir, String name, List<List<String>> pages) throws IOException {
        Map<String, String> base = baseParts(DEFAULT_DOC_ID);
        Map<String, String> parts = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : base.entrySet()) {
            parts.put(entry.getKey(), entry.getValue());
            // 【页面必须紧挨着 —— 这是「跨页不续接」那条用例能不能有牙齿的关键】
            //   顺序是契约：若第 2 页被追加到包末尾，它与第 1 页之间就隔着
            //   模板页、批注页等别的部件 → 单元序列里两页的对象**本来就不相邻**
            //   →「跨页不续接」会变成一条恒真的断言（隔着别的东西当然也不会续接），
            //   于是「组不能跨部件」这条判据就没有任何用例能咬住它。
            //   （第一版就是踩了这个坑：变异「去掉部件检查」跑下来一条都没红。）
            if (entry.getKey().equals("Doc_0/Pages/Page_0/Content.xml")) {
                for (int i = 1; i < pages.size(); i++) {
                    parts.put("Doc_0/Pages/Page_" + i + "/Content.xml", pageContentXml(pages.get(i)));
                }
            }
        }
        parts.put("Doc_0/Pages/Page_0/Content.xml", pageContentXml(pages.get(0)));
        write(dir.resolve(name), parts);
        return dir.resolve(name);
    }

    private static String pageContentXml(List<String> objects) {
        StringBuilder layer = new StringBuilder();
        for (String object : objects) {
            layer.append(object);
        }
        return xml("ofd:Page",
                "<ofd:Content>",
                "<ofd:Layer ID=\"1\">",
                layer.toString(),
                "</ofd:Layer>",
                "</ofd:Content>", "");
    }

    /** 标准文档 + 签章（OFD 规范里签章在 {@code Doc_0/Signs/} 下，OFD.xml 用 {@code ofd:Signatures} 指向）。 */
    static Path signed(Path dir, String name) throws IOException {
        Map<String, String> parts = baseParts(DEFAULT_DOC_ID);
        parts.put("Doc_0/Signs/Signatures.xml",
                xml("ofd:Signatures",
                        "<ofd:Signature ID=\"1\" Type=\"Seal\" BaseLoc=\"/Doc_0/Signs/Sign_0/Signature.xml\"/>",
                        "<ofd:MaxSignId>2</ofd:MaxSignId>"));
        parts.put("Doc_0/Signs/Sign_0/Signature.xml",
                xml("ofd:Signature",
                        "<ofd:SignedValue>/Doc_0/Signs/Sign_0/SignedValue.dat</ofd:SignedValue>",
                        "<ofd:SignedInfo>",
                        "<ofd:SignatureDateTime>2026-01-01 00:00:00</ofd:SignatureDateTime>",
                        "</ofd:SignedInfo>"));
        parts.put("Doc_0/Signs/Sign_0/Seal.esl", "fake-seal");
        parts.put("Doc_0/Signs/Sign_0/SignedValue.dat", "fake-signed-value");
        write(dir.resolve(name), parts);
        return dir.resolve(name);
    }

    private static Map<String, String> baseParts(String docId) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("OFD.xml", ofdXml(docId));
        parts.put("Doc_0/Document.xml", documentXml());
        parts.put("Doc_0/PublicRes.xml", publicResXml());
        parts.put("Doc_0/Pages/Page_0/Content.xml", pageXml());
        parts.put("Doc_0/Tpls/Tpl_0/Content.xml", templateXml());
        parts.put("Doc_0/Annots/Annotations.xml",
                xml("ofd:Annotations",
                        "<ofd:Page PageID=\"1\"><ofd:FileLoc>Page_0/Annotation.xml</ofd:FileLoc></ofd:Page>"));
        parts.put("Doc_0/Annots/Page_0/Annotation.xml", annotationXml());
        return parts;
    }

    // ------------------------------------------------------------------ 各个部件

    private static String ofdXml(String docId) {
        return xml("ofd:OFD",
                "<ofd:DocBody>",
                "<ofd:DocInfo>",
                "<ofd:DocID>" + docId + "</ofd:DocID>",
                "<ofd:Author>张洪千</ofd:Author>",
                "<ofd:CreationDate>2026-01-01</ofd:CreationDate>",
                "<ofd:Creator>单机脱敏工具测试夹具</ofd:Creator>",
                "<ofd:CustomDatas>",
                "<ofd:CustomData Name=\"销售方纳税人识别号\">" + USCC + "</ofd:CustomData>",
                "</ofd:CustomDatas>",
                "</ofd:DocInfo>",
                "<ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot>",
                "</ofd:DocBody>");
    }

    private static String documentXml() {
        return xml("ofd:Document",
                "<ofd:CommonData>",
                "<ofd:PublicRes>PublicRes.xml</ofd:PublicRes>",
                "<ofd:PageArea>",
                "<ofd:PhysicalBox>0 0 210 297</ofd:PhysicalBox>",
                "<ofd:ApplicationBox>0 0 210 297</ofd:ApplicationBox>",
                "</ofd:PageArea>",
                "<ofd:MaxUnitID>20</ofd:MaxUnitID>",
                "</ofd:CommonData>",
                "<ofd:Pages>",
                "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>",
                "</ofd:Pages>",
                "<ofd:Annotations>Doc_0/Annots/Annotations.xml</ofd:Annotations>",
                "<ofd:Tpls>",
                "<ofd:Tpl ID=\"1\" BaseLoc=\"Tpls/Tpl_0/Content.xml\"/>",
                "</ofd:Tpls>");
    }

    private static String publicResXml() {
        return xml("ofd:Res",
                "<ofd:Fonts><ofd:Font FontName=\"宋体\" FamilyName=\"宋体\" ID=\"3\"/></ofd:Fonts>",
                "");
    }

    /**
     * 正文页。这里埋了三个坑：
     * <ol>
     *   <li>一个号码被拆进<b>同一个 TextObject 的两个 TextCode</b>（逐槽跑正则必然漏）；</li>
     *   <li>一个 TextCode <b>没有 TextObject 祖先</b>，直接挂在 Layer 上；</li>
     *   <li>一个 TextCode 带 {@code Text} 关键字属性。</li>
     * </ol>
     */
    private static String pageXml() {
        StringBuilder sb = new StringBuilder();
        sb.append(xml("ofd:Page",
                "<ofd:Content>",
                "<ofd:Layer ID=\"1\">",
                textObject(2, BODY_LINE_1),
                // 拆成两段：前半 + 后半
                "<ofd:TextObject ID=\"3\" Boundary=\"20 30 100 5\" Font=\"3\" Size=\"3.5\">"
                        + textCode("联系电话：" + PHONE_SPLIT.substring(0, 7), 0)
                        + textCode(PHONE_SPLIT.substring(7), 20)
                        + "</ofd:TextObject>",
                // 没有 TextObject 祖先的孤儿
                textCode("证件：" + ID, 40),
                // 带关键字属性的 TextCode
                "<ofd:TextObject ID=\"5\" Boundary=\"20 50 100 5\" Font=\"3\" Size=\"3.5\">"
                        + textCodeWithKeyword(BODY_LINE_2, BODY_LINE_3, 60)
                        + "</ofd:TextObject>",
                textObject(6, BODY_LINE_4),
                "</ofd:Layer>",
                "</ofd:Content>", ""));
        return sb.toString();
    }

    /** 模板页：屏幕上看不见，但字就在文件里。 */
    private static String templateXml() {
        return xml("ofd:TemplatePage",
                "<ofd:Content>",
                "<ofd:Layer ID=\"1\">",
                textObject(2, "此处不显示但含身份证：" + ID_TEMPLATE),
                "</ofd:Layer>",
                "</ofd:Content>", "");
    }

    private static String annotationXml() {
        return xml("ofd:PageAnnot",
                "<ofd:Annot Type=\"Watermark\" ID=\"7\">",
                "<ofd:Appearance Boundary=\"90 8 30 20\">",
                "<ofd:TextObject ID=\"8\" Boundary=\"0 0 30 20\" Font=\"3\" Size=\"3.5\">"
                        + textCode("制单人：" + EMAIL, 0)
                        + "</ofd:TextObject>",
                "</ofd:Appearance>",
                "</ofd:Annot>", "");
    }

    private static String attachmentXml() {
        return xml("ofd:Attachments",
                "<ofd:Attachment Name=\"" + PHONE + "的申请书\" ID=\"1\" Size=\"13\">",
                "<ofd:FileLoc>/Doc_0/Attachs/" + PHONE + "_申请书.pdf</ofd:FileLoc>",
                "</ofd:Attachment>", "");
    }

    // ------------------------------------------------------------------ XML 小工具

    /** 指定几何位置的 TextObject（{@code Boundary="x y w h"}，单位毫米）。 */
    static String textObjectAt(int id, int x, int y, int w, String text) {
        return "<ofd:TextObject ID=\"" + id + "\" Boundary=\"" + x + " " + y + " " + w
                + " 5\" Font=\"3\" Size=\"3.5\">" + textCode(text, 0) + "</ofd:TextObject>";
    }

    /** 没有 {@code Boundary} 的 TextObject：几何拿不到就不许与任何邻居续接。 */
    static String textObjectWithoutBoundary(int id, String text) {
        return "<ofd:TextObject ID=\"" + id + "\" Font=\"3\" Size=\"3.5\">"
                + textCode(text, 0) + "</ofd:TextObject>";
    }

    private static String textObject(int id, String text) {
        return "<ofd:TextObject ID=\"" + id + "\" Boundary=\"20 " + (id * 5) + " 100 5\" Font=\"3\" Size=\"3.5\">"
                + textCode(text, 0) + "</ofd:TextObject>";
    }

    private static String textCode(String text, int x) {
        return "<ofd:TextCode X=\"" + x + "\" Y=\"3\" DeltaX=\"" + deltaX(text.length()) + "\">"
                + text + "</ofd:TextCode>";
    }

    private static String textCodeWithKeyword(String text, String keyword, int x) {
        return "<ofd:TextCode X=\"" + x + "\" Y=\"3\" Text=\"" + keyword + "\" DeltaX=\""
                + deltaX(text.length()) + "\">" + text + "</ofd:TextCode>";
    }

    /** DeltaX 是逐字字距表，数量必须与字符数一致——真实文件就是这样。 */
    static String deltaX(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append("3.5");
        }
        return sb.toString();
    }

    private static String xml(String root, String... bodies) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append('<').append(root)
                .append(" xmlns:ofd=\"http://www.ofdspec.org/2016\">");
        // 各部件统一带 ofd 命名空间；TemplatePage 等根元素同样需要，避免夹具本身不合法
        for (String body : bodies) {
            sb.append(body);
        }
        sb.append("</").append(root).append('>');
        return sb.toString();
    }

    private static void write(Path out, Map<String, String> parts) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (OutputStream fos = Files.newOutputStream(out);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                ZipEntry entry = new ZipEntry(part.getKey());
                entry.setTime(1_700_000_000_000L);
                zos.putNextEntry(entry);
                zos.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------------ 读取侧

    /** 读出包里所有部件的原始文本。断言直接看字符串，不经过本项目的解析代码。 */
    public static Map<String, String> readTextParts(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entries = zip.stream().toList();
            for (ZipEntry entry : entries) {
                if (entry.isDirectory() || !entry.getName().endsWith(".xml")) {
                    continue;
                }
                try (var in = zip.getInputStream(entry)) {
                    out.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    /** 包内条目名（不含目录标记）。 */
    static List<String> entryNames(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                if (!entry.isDirectory()) {
                    out.add(entry.getName());
                }
            }
        }
        return out;
    }

    /**
     * 把 XML 实体解开，让断言能用自然文本比较。
     *
     * <p>只作用在<b>断言这一侧</b>：写回时 {@code <手机号1>} 里的尖括号必须转义成
     * {@code &lt;手机号1&gt;}（不转义产出的就不是合法 XML），断言却应该按人读的样子去比。
     * &amp; 必须最后解，否则 {@code &amp;lt;} 会被解两次。
     */
    public static String decode(String xml) {
        return xml.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** 独立读取器（ofdrw）抽出来的全文——不是我们写的代码。 */
    static String extractAll(Path file) throws IOException {
        try (OFDReader reader = new OFDReader(file)) {
            return String.join("\n", new ContentExtractor(reader).extractAll());
        }
    }
}
