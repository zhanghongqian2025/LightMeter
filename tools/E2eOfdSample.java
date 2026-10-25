import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 生成一份「处处是坑」的 OFD 样本，用于命令行端到端验证。
 *
 * <p>只依赖 JDK：端到端要验的是<b>命令行 + 打包后的 jar</b>，样本生成器如果依赖测试夹具类，
 * 就等于把「测试代码本身对不对」混进了「成品对不对」。
 *
 * <p>坑的分布对着真实文件核对过（参考样本是一张增值税发票与一份电子签章文档）：
 * <ul>
 *   <li>正文手机号 / 身份证 / 邮箱 / 银行卡；</li>
 *   <li>一个手机号被拆进<b>同一个对象的两个 TextCode</b>；</li>
 *   <li>模板页里的身份证——屏幕上看不见，但字在文件里；</li>
 *   <li>批注里的邮箱；</li>
 *   <li>{@code TextCode/@Text} 关键字属性里的邮箱；</li>
 *   <li>{@code DocInfo/CustomData} 里的纳税人识别号；{@code DocInfo/Author} 里的姓名；</li>
 *   <li>zip 条目名里的手机号：{@code Attachs/13800138000_申请书.pdf}；</li>
 *   <li>签章目录 {@code Doc_0/Signs/} 与 {@code OFD.xml} 里的 {@code ofd:Signatures} 引用。</li>
 * </ul>
 */
public final class E2eOfdSample {

    public static final String PHONE = "13800138000";
    public static final String PHONE_SPLIT = "13900139000";
    public static final String ID = "11010519491231002X";
    public static final String EMAIL = "zhang@example.com";
    public static final String CARD = "622202020202020207";
    public static final String USCC = "91110108690017781B";
    public static final String AUTHOR = "张洪千";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("OFD.xml", ofdXml());
        parts.put("Doc_0/Document.xml", documentXml());
        parts.put("Doc_0/PublicRes.xml", xml("ofd:Res",
                "<ofd:Fonts><ofd:Font FontName=\"宋体\" FamilyName=\"宋体\" ID=\"3\"/></ofd:Fonts>"));
        parts.put("Doc_0/Pages/Page_0/Content.xml", pageXml());
        parts.put("Doc_0/Tpls/Tpl_0/Content.xml", xml("ofd:TemplatePage",
                "<ofd:Content><ofd:Layer ID=\"1\">"
                        + object(2, "此处不显示但含身份证：" + ID)
                        + "</ofd:Layer></ofd:Content>"));
        parts.put("Doc_0/Annots/Annotations.xml", xml("ofd:Annotations",
                "<ofd:Page PageID=\"1\"><ofd:FileLoc>Page_0/Annotation.xml</ofd:FileLoc></ofd:Page>"));
        parts.put("Doc_0/Annots/Page_0/Annotation.xml", xml("ofd:PageAnnot",
                "<ofd:Annot Type=\"Watermark\" ID=\"7\"><ofd:Appearance Boundary=\"90 8 30 20\">"
                        + object(8, "制单人：" + EMAIL)
                        + "</ofd:Appearance></ofd:Annot>"));
        parts.put("Doc_0/Attachs/" + PHONE + "_申请书.pdf", "fake-pdf-bytes");
        parts.put("Doc_0/Attachments.xml", xml("ofd:Attachments",
                "<ofd:Attachment Name=\"" + PHONE + "的申请书\" ID=\"1\" Size=\"13\">"
                        + "<ofd:FileLoc>/Doc_0/Attachs/" + PHONE + "_申请书.pdf</ofd:FileLoc>"
                        + "</ofd:Attachment>"));
        parts.put("Doc_0/Signs/Signatures.xml", xml("ofd:Signatures",
                "<ofd:Signature ID=\"1\" Type=\"Seal\" BaseLoc=\"/Doc_0/Signs/Sign_0/Signature.xml\"/>",
                "<ofd:MaxSignId>2</ofd:MaxSignId>"));
        parts.put("Doc_0/Signs/Sign_0/Signature.xml", xml("ofd:Signature",
                "<ofd:SignedValue>/Doc_0/Signs/Sign_0/SignedValue.dat</ofd:SignedValue>",
                "<ofd:SignedInfo>",
                "<ofd:SignatureDateTime>2026-01-01 00:00:00</ofd:SignatureDateTime>",
                "</ofd:SignedInfo>"));
        parts.put("Doc_0/Signs/Sign_0/Seal.esl", "fake-seal");
        parts.put("Doc_0/Signs/Sign_0/SignedValue.dat", "fake-signed-value");

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
        System.out.println("已生成样本: " + out.toAbsolutePath());
    }

    private static String ofdXml() {
        return xml("ofd:OFD",
                "<ofd:DocBody>",
                "<ofd:DocInfo>",
                "<ofd:DocID>0123456789abcdef0123456789abcdef</ofd:DocID>",
                "<ofd:Author>" + AUTHOR + "</ofd:Author>",
                "<ofd:CreationDate>2026-01-01</ofd:CreationDate>",
                "<ofd:Creator>单机脱敏工具端到端样本</ofd:Creator>",
                "<ofd:CustomDatas>",
                "<ofd:CustomData Name=\"销售方纳税人识别号\">" + USCC + "</ofd:CustomData>",
                "</ofd:CustomDatas>",
                "</ofd:DocInfo>",
                "<ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot>",
                "<ofd:Signatures>/Doc_0/Signs/Signatures.xml</ofd:Signatures>",
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
                "<ofd:Pages><ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/></ofd:Pages>",
                "<ofd:Annotations>Doc_0/Annots/Annotations.xml</ofd:Annotations>",
                "<ofd:Tpls><ofd:Tpl ID=\"1\" BaseLoc=\"Tpls/Tpl_0/Content.xml\"/></ofd:Tpls>",
                "<ofd:Attachments>Doc_0/Attachments.xml</ofd:Attachments>");
    }

    private static String pageXml() {
        return xml("ofd:Page",
                "<ofd:Content><ofd:Layer ID=\"1\">",
                object(2, "联系电话：" + PHONE),
                "<ofd:TextObject ID=\"3\" Boundary=\"20 15 100 5\" Font=\"3\" Size=\"3.5\">"
                        + code("联系电话：" + PHONE_SPLIT.substring(0, 7), 0)
                        + code(PHONE_SPLIT.substring(7), 20)
                        + "</ofd:TextObject>",
                "<ofd:TextCode X=\"40\" Y=\"3\" DeltaX=\"" + deltaX(("证件：" + ID).length()) + "\">"
                        + "证件：" + ID + "</ofd:TextCode>",
                "<ofd:TextObject ID=\"5\" Boundary=\"20 25 100 5\" Font=\"3\" Size=\"3.5\">"
                        + "<ofd:TextCode X=\"0\" Y=\"3\" Text=\"邮箱：" + EMAIL + "\" DeltaX=\""
                        + deltaX(("邮箱：" + EMAIL).length()) + "\">邮箱：" + EMAIL + "</ofd:TextCode>"
                        + "</ofd:TextObject>",
                object(6, "卡号：" + CARD),
                "</ofd:Layer></ofd:Content>");
    }

    private static String object(int id, String text) {
        return "<ofd:TextObject ID=\"" + id + "\" Boundary=\"20 " + (id * 5) + " 100 5\" Font=\"3\" Size=\"3.5\">"
                + code(text, 0) + "</ofd:TextObject>";
    }

    private static String code(String text, int x) {
        return "<ofd:TextCode X=\"" + x + "\" Y=\"3\" DeltaX=\"" + deltaX(text.length()) + "\">"
                + text + "</ofd:TextCode>";
    }

    private static String deltaX(int count) {
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
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append('<').append(root).append(" xmlns:ofd=\"http://www.ofdspec.org/2016\">");
        for (String body : bodies) {
            sb.append(body);
        }
        return sb.append("</").append(root).append('>').toString();
    }

    private E2eOfdSample() {
    }
}
