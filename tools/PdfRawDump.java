import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 打印 PDF 每页内容流的**原始字节**里与画文本有关的片段。
 *
 * <p>存在的理由：e2e 日志里那行
 * {@code 身份证 <ID_1> <N E<RN_EPR <E_NROESROG_NPA_EN1RI>ZSAOTNIO_<N2M_>A1I>L_1>}
 * 是 PDFBox <b>抽取</b>出来的结果。抽取要过字体 CMap 反查，
 * 于是「抽取出来交错」与「文件里真的交错」是两件完全不同的事——
 * 前者可能只是字体编码显示问题，后者才是产物里的残片。
 * 不看原始字节就分不出来。
 *
 * <p>用法：java -cp <jar>:. PdfRawDump <某个.pdf>
 */
public final class PdfRawDump {

    public static void main(String[] args) throws Exception {
        // extract 模式：只用 PDFBox 的 PDFTextStripper 打抽取结果，
        // 用来和原始字节读出来的顺序对照。两个观察者不一致时，
        // 说明问题在抽取侧（排序/反查），不在产物侧。
        if (args.length > 1 && "extract".equals(args[1])) {
            try (PDDocument doc = Loader.loadPDF(Path.of(args[0]).toFile())) {
                var stripper = new org.apache.pdfbox.text.PDFTextStripper();
                System.out.println("===== PDFBox extractText 默认排序 =====");
                System.out.println(stripper.getText(doc));
                stripper.setSortByPosition(true);
                System.out.println("===== PDFBox extractText setSortByPosition(true) =====");
                System.out.println(stripper.getText(doc));
            }
            return;
        }
        try (PDDocument doc = Loader.loadPDF(Path.of(args[0]).toFile())) {
            System.out.println("页数 = " + doc.getNumberOfPages());
            int pageNo = 0;
            for (PDPage page : doc.getPages()) {
                pageNo++;
                try (InputStream in = page.getContents()) {
                    if (in == null) {
                        System.out.println("第 " + pageNo + " 页：无内容流");
                        continue;
                    }
                    String s = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    dump(pageNo, s);
                }
            }
        }
    }

    /** 打出一页里所有 Tj / TJ 附近的内容，带上它前面的 Tf，好知道用的哪个字体。 */
    private static void dump(int pageNo, String s) {
        Pattern op = Pattern.compile(
                "/([A-Za-z0-9#+.-]+)\\s+[\\d.]+\\s+Tf|"
                        + "<([0-9A-Fa-f\\s]*)>\\s*Tj|"
                        + "\\(((?:[^()\\\\]|\\\\.)*)\\)\\s*Tj|"
                        + "\\[(.*?)]\\s*TJ", Pattern.DOTALL);
        Matcher m = op.matcher(s);
        boolean any = false;
        while (m.find()) {
            any = true;
            if (m.group(1) != null) {
                System.out.println("  [p" + pageNo + "] Tf " + m.group(1));
            } else if (m.group(2) != null) {
                String h = m.group(2).replaceAll("\\s", "");
                if (h.length() % 2 == 1) {
                    h += "0";
                }
                System.out.println("  [p" + pageNo + "] TjHEX 字数=" + (h.length() / 2)
                        + "  " + toCid(h));
            } else if (m.group(3) != null) {
                System.out.println("  [p" + pageNo + "] TjLIT " + m.group(3));
            } else {
                System.out.println("  [p" + pageNo + "] TJARR " + m.group(4).trim());
            }
        }
        if (!any) {
            System.out.println("  [p" + pageNo + "] （本页没匹配到画文本算子）");
        }
    }

    /** 十六进制串按 2 字节一 CID 排开，方便人眼看出令牌字符是不是完整落在字边界上。 */
    private static String toCid(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 3 < hex.length(); i += 4) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(hex, i, i + 4);
        }
        return sb.toString();
    }
}
