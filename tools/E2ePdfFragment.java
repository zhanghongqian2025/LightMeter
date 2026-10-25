import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF **碎片级**端到端样本生成 + 独立校验。
 *
 * <p><b>为什么要有它</b>：块 13 的实测里有一条PDF 侧的泄漏——
 * 身份证被文本块切成 14 + 3 时，PDF 侧是「0 处命中、整张身份证原样留在交付物里」。
 * 那不是标签错，是<b>泄漏</b>。块 14 把 PDF 侧的跨块续接打开（且限定在同一内容流内、
 * 只允许有真校验位的规则参与），这条端到端就是为了守住那个修复。
 *
 * <p><b>为什么校验器另起一个文件</b>：校验必须用**PDFBox 自己的文本引擎**，
 * 不碰被测代码的解码逻辑——否则被测实现解码写错时，校验会跟着错，测出来的东西是假的。
 * 另起一个文件也让「样本里放了什么」与「判据是什么」互不干扰：
 * 判据里的常量只有一处定义（下面的 FRAGMENTS / TOKENS），样本与校验共用它。
 */
public final class E2ePdfFragment {

    /** 一张校验位正确的身份证（GB 11643）。 */
    private static final String ID_CARD = "11010519491231002X";

    /** 切法一：14 + 3 —— 块 13 实测「0 处命中、整张留在交付物里」。 */
    private static final String FRAG_A = "11010519491231";
    private static final String FRAG_B = "002X";

    /** 切法二：6 + 12 —— 块 13 实测「命中 1 处，但被错标成手机号」。 */
    private static final String FRAG_C = "110105";
    private static final String FRAG_D = "19491231002X";

    /**
     * **不得出现在脱敏件里**的片段。
     *
     * <p>完整值也在列表里：完整值由片段拼成，片段都在时完整值自然也在——
     * 但反过来不成立（片段分开时完整值根本不会连续出现），
     * 所以必须**逐个片段**查，只查完整值是查不出泄漏的。
     */
    private static final List<String> FRAGMENTS = List.of(FRAG_A, FRAG_B, FRAG_C, FRAG_D, ID_CARD);

    /** 样本里的每一块（按出现顺序）。每个值独占一个文本块（一次 Tm 定位）。 */
    private static final List<String> BLOCKS = List.of(FRAG_A, FRAG_B, FRAG_C, FRAG_D, ID_CARD);

    /** 期望的身份证类令牌个数：5 块各出一个令牌。 */
    private static final int EXPECTED_ID_TOKENS = BLOCKS.size();

    private E2ePdfFragment() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: E2ePdfFragment gen <out.pdf> <cjk字体> | check <原件> <脱敏件> <还原件>");
            System.exit(2);
        }
        switch (args[0]) {
            case "gen" -> gen(Path.of(args[1]), Path.of(args[2]));
            case "check" -> {
                int bad = check(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
                System.exit(bad == 0 ? 0 : 1);
            }
            default -> {
                System.err.println("未知模式: " + args[0]);
                System.exit(2);
            }
        }
    }

    // ---------------------------------------------------------------- 生成样本

    private static void gen(Path out, Path fontFile) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            doc.addPage(page);

            // 字体用仓库自带的中文字体子集（与 tools/e2e-pdf.sh 同一份），
            // 保证样本**自带字体**：不嵌入字体的 PDF 换个机器打开就是乱码，
            // 而端到端照样可能「跑通」——因为校验器读的是同一台机器上 PDFBox
            // 找到的替代字体。实测踩过。
            var loaded = PDType0Font.load(doc, Files.newInputStream(fontFile), false);
            var fontName = page.getResources().add(loaded);
            var font = loaded;
            String res = fontName.getName();

            StringBuilder c = new StringBuilder("BT\n");
            int y = 760;
            for (String block : BLOCKS) {
                // 每次都重新定位 ⇒ 每个值独占一个文本块（这正是「被切断」的样子）
                c.append('/').append(res).append(" 12 Tf\n1 0 0 1 50 ").append(y)
                        .append(" Tm\n").append(hex(font.encode(block))).append(" Tj\n");
                y -= 24;
            }
            c.append("ET\n");
            page.setContents(new PDStream(doc,
                    new ByteArrayInputStream(c.toString().getBytes(StandardCharsets.ISO_8859_1))));
            doc.save(out.toFile());
        }
        // 自证：样本必须能被 PDFBox 的文本引擎抽回这 5 块。
        // 这一步不是走过场——样本若根本抽不出文本，后面的「碎片不得残留」会**恒真**，
        // 测出来的是「什么都没查到」，不是「没泄漏」。
        String back = extract(out);
        for (String block : BLOCKS) {
            if (!back.contains(block)) {
                throw new IllegalStateException("样本自检失败：块「" + block + "」抽不回来。抽回来的文本：\n" + back);
            }
        }
        System.out.println("样本已生成: " + out + " (" + Files.size(out) + " 字节，"
                + BLOCKS.size() + " 个文本块)");
    }

    // ---------------------------------------------------------------- 独立校验

    private static int bad = 0;

    private static int check(Path original, Path redacted, Path restored) throws Exception {
        bad = 0;
        System.out.println("=== PDF 碎片级独立校验（只用 PDFBox 自己的文本引擎） ===");

        String red = extract(redacted);
        System.out.println("--- 脱敏件抽取文本 ---");
        System.out.println(red);

        // --- 腿 1：碎片不得残留 ---
        // 逐个碎片查。只查完整值查不出泄漏：碎片分开时完整值根本不连续出现。
        for (String frag : FRAGMENTS) {
            require(!red.contains(frag),
                    "碎片/原值已不在交付物里: " + frag,
                    "碎片仍在交付物里: " + frag + "\n  脱敏件文本：" + red);
        }

        // --- 腿 2：令牌类型必须是身份证 ---
        // 「切法 6+12」在块 13 之前会被错标成手机号（19491231002 → <PHONE_1>），
        // 那是标签错、不泄漏，但用户按身份证去找就找不到——所以类型也要钉住。
        Matcher idm = Pattern.compile("<ID_\\d+>").matcher(red);
        int idCount = 0;
        while (idm.find()) {
            idCount++;
        }
        require(idCount == EXPECTED_ID_TOKENS,
                "身份证类令牌数正确（" + EXPECTED_ID_TOKENS + "）",
                "身份证类令牌数是 " + idCount + "，期望 " + EXPECTED_ID_TOKENS
                        + "；脱敏件文本：" + red);
        require(!red.contains("<PHONE_"),
                "碎片没有被错标成手机号",
                "碎片被错标成手机号了：" + red);

        // --- 腿 3：还原逐字一致 ---
        String before = squeeze(extract(original));
        String after = squeeze(extract(restored));
        require(before.equals(after),
                "还原后与原件逐字一致",
                "还原后与原件不一致\n  原件: " + before + "\n  还原: " + after);
        for (String frag : FRAGMENTS) {
            require(after.contains(frag), "还原后原值回来: " + frag, "还原后原值没回来: " + frag);
        }

        // --- 腿 4：还原后的版面（逐行文字宽度）与原件一致 ---
        // 抽出来的文本一样，**画出来的宽度**却可能不一样：
        // 令牌被横向压缩过（Tz ≠ 100）时，还原必须把缩放切回去，
        // 否则原文就按压缩后的宽度画回去——字符对得上，版面歪了。
        // 宽度用 PDFBox 自己的排版引擎量（TextPosition.getWidthDirAdj），
        // 不按字体度量表自己估：估出来的数差零点几个点，
        // 而「有没有被压窄」恰恰是这个量级的判定。
        Map<String, Double> wOrig = lineWidths(original);
        Map<String, Double> wRestored = lineWidths(restored);
        if (!wOrig.keySet().equals(wRestored.keySet())) {
            require(false, "还原后每一行都在",
                    "还原后行的集合与原件不同\n  原件: " + wOrig.keySet()
                            + "\n  还原: " + wRestored.keySet());
        } else {
            List<String> narrow = new ArrayList<>();
            for (String line : wOrig.keySet()) {
                double d = Math.abs(wOrig.get(line) - wRestored.get(line));
                if (d > 0.5) {
                    narrow.add(line + "（原件 " + fmt(wOrig.get(line))
                            + "pt → 还原 " + fmt(wRestored.get(line)) + "pt）");
                }
            }
            require(narrow.isEmpty(),
                    "还原后逐行文字宽度与原件一致",
                    "还原后有几行的宽度和原件对不上（版面被压窄或撑宽了）：" + narrow);
        }

        System.out.println();
        System.out.println(bad == 0 ? "PDF 碎片级校验：通过" : "PDF 碎片级校验：未通过（" + bad + " 条）");
        return bad;
    }

    /** 逐行量文字宽度：返回「行文本 → 该行总宽（pt）」。 */
    private static Map<String, Double> lineWidths(Path pdf) throws Exception {
        Map<String, Double> out = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition t) {
                    String key = t.getUnicode() + "@" + Math.round(t.getYDirAdj());
                    out.merge(key, (double) t.getWidthDirAdj(), Double::sum);
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 抽取文本层（默认顺序：按内容流顺序）。 */
    private static String extract(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    /** 把连续空白压成单个空格：抽取器的换行与缩进不该影响「逐字一致」这个判断。 */
    private static String squeeze(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("<");
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.append('>').toString();
    }

    private static void require(boolean ok, String okMsg, String failMsg) {
        if (ok) {
            System.out.println("PASS " + okMsg);
        } else {
            bad++;
            System.out.println("FAIL " + failMsg);
        }
    }
}
