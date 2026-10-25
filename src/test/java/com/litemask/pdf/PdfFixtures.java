package com.litemask.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * PDF 测试夹具。
 *
 * <p>内容流一律**手工拼字符串**，不用 {@code PDPageContentStream} 生成。理由与 OOXML 夹具一致：
 * 要测的场景（一个身份证被排版切成三段、TJ 数组里夹着负间距、字形横跨多个字符串算子）
 * 正常写法根本不会产生，而一旦用了高层库，夹具就会跟着被测实现的假设走。
 */
public final class PdfFixtures {

    private PdfFixtures() {
    }

    /** 用标准 14 字体 Helvetica 写一个页面，内容流原样写入。 */
    public static Path writeLatin(Path path, String content) throws IOException {
        return writeLatinPages(path, List.of(content));
    }

    /**
     * 用标准 14 字体写一份<b>多页</b> PDF：列表里每一项是一页的内容流。
     *
     * <p>位置信息报的是「第 N 页」，所以必须有能跨页的夹具。
     * 一页一份的 {@link #writeLatin} 造不出「第 2 页上的命中」这个场景，
     * 而那正是位置信息最容易错的地方——页码从 0 数还是从 1 数、
     * 页码跟内容流对不对得上，只有真有两页才验得了。
     */
    public static Path writeLatinPages(Path path, List<String> pageContents) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (String content : pageContents) {
                PDPage page = new PDPage();
                page.setResources(new PDResources());
                doc.addPage(page);
                page.getResources().add(new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                page.setContents(stream(doc, content.getBytes(StandardCharsets.ISO_8859_1)));
            }
            doc.save(path.toFile());
        }
        return path;
    }

    /**
     * 仓库自带的 CJK 字体子集在 classpath 里的位置。
     *
     * <p>刻意<b>不找系统字体</b>。原来的做法是挑一个系统里的中文字体，
     * 三个问题都是实测出来的：
     *
     * <ol>
     *   <li>Windows 上唯一的候选是 {@code C:/Windows/Fonts/msyh.ttc}，那是<b>字体集</b>，
     *       而 PDFBox 的普通加载路径读不了它——{@code PDType0Font.load} 直接抛
     *       {@code IOException: 'head' table is mandatory}（fontbox 3.0.3 实测）。
     *       要读 {@code .ttc} 必须走 {@code TrueTypeCollection.getFontByName(...)} 单独取出一个字体。
     *   <li>「本机没字体就跳过」让中文 PDF 这条路径长期<b>没有被执行</b>，
     *       而全量单测对此完全无感。
     *   <li>不同机器的字体度量不同，版面断言的数值随机器变，测试不可复现。
     * </ol>
     *
     * <p>子集是 {@code Noto Sans SC}（SIL OFL 1.1，可再分发）的裁剪版，
     * 由 {@code tools/make_test_font.py} 生成，放在 {@code src/test/resources}——
     * <b>不进生产 jar</b>，因此不影响 90 MiB 体积预算。
     */
    static final String CJK_FONT_RESOURCE = "/fonts/litemask-cjk-test.ttf";

    /**
     * 定位仓库自带的 CJK 字体子集。
     *
     * <p>找不到就<b>抛异常</b>，不跳过：字体是仓库的一部分，缺它是仓库坏了，
     * 不是本机环境问题。跳过的代价是这条路径静默地不再被测——那正是本方法要消灭的。
     */
    static Path cjkFontFile() {
        URL url = PdfFixtures.class.getResource(CJK_FONT_RESOURCE);
        if (url == null) {
            throw new IllegalStateException("测试字体缺失：classpath:" + CJK_FONT_RESOURCE
                    + "。用 python3 tools/make_test_font.py --source <NotoSansSC-VF.ttf> 生成。");
        }
        try {
            Path p = Path.of(url.toURI());
            if (Files.isRegularFile(p)) {
                return p;
            }
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // 资源被打进了 jar（不是文件 URL），走下面的拷贝分支
        }
        try (InputStream in = PdfFixtures.class.getResourceAsStream(CJK_FONT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("测试字体缺失：classpath:" + CJK_FONT_RESOURCE);
            }
            Path tmp = Files.createTempFile("litemask-test-cjk", ".ttf");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (IOException e) {
            throw new IllegalStateException("测试字体无法落地为临时文件：" + CJK_FONT_RESOURCE, e);
        }
    }

    /**
     * 加载一个中文字体。
     *
     * <p>两个坑，都是实测出来的：
     *
     * <ol>
     *   <li><b>字体集（{@code .ttc}）不能直接加载。</b>{@code PDType0Font.load} 会抛
     *       {@code IOException: 'head' table is mandatory}——那是 fontbox 在抱怨
     *       「你给了我一个集合而不是一个字体」。要读 {@code .ttc} 必须先用
     *       {@code TrueTypeCollection.getFontByName(...)} 取出其中一个字体。
     *   <li><b>必须整份嵌入（{@code embedSubset=false}）。</b>子集路径（{@code embedSubset=true}）
     *       在本夹具的用法下产出的是<b>坏文件</b>，三种写法并排实测：
     *       <pre>
     *       embedSubset=true  不 subset()  → 1.5 KB，**没有 FontFile2**，文本抽回来是乱码
     *       embedSubset=true  编码后 subset() → 2.0 KB，有 FontFile2，但 ToUnicode 无效，文本仍是乱码
     *       embedSubset=false              → 794 KB，有 FontFile2，文本原样抽回 ✅
     *       </pre>
     * </ol>
     *
     * <p>代价是产物里带完整字体（几百 KB）。测试文件写在临时目录里，这个代价可以接受；
     * 换来的是「夹具产出的 PDF 真的自带字体」，而不是靠阅读器去猜。
     */
    static PDType0Font loadCjkFont(PDDocument doc, Path fontFile) throws IOException {
        String name = fontFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ttc")) {
            throw new IllegalArgumentException("字体集（.ttc）不能直接加载：PDFBox 的 PDType0Font.load"
                    + " 会报 \"'head' table is mandatory\"。请改用单字体文件（.ttf/.otf）；"
                    + "确需字体集时先用 TrueTypeCollection.getFontByName(...) 取出其中一个字体再加载。文件："
                    + fontFile);
        }
        try (InputStream in = Files.newInputStream(fontFile)) {
            return PDType0Font.load(doc, in, false);
        }
    }

    /**
     * 逐字检查字体是否覆盖给定文本；缺字时把缺哪些字一次列全。
     *
     * <p>存在的判据是「{@code encode} 能不能编出这个字」——
     * <b>不是</b> {@code hasGlyph} 或 {@code codeToGID}：
     * 实测 PDFBox 3.0.3 对<b>所有 CJK 字符</b>的 {@code hasGlyph} 一律返回 false、
     * {@code codeToGID} 一律返回 0，连字体里明明有的「民」「一」也一样。
     * 用它们判存在性，等于永远判「不存在」。
     *
     * <p>而 {@code encode} 对缺失字形是<b>抛异常</b>（{@code No glyph for U+xxxx}），
     * 不是静默产出 .notdef——所以它能当判据。
     */
    static void assertCovers(PDType0Font font, String text) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            try {
                font.encode(String.valueOf(c));
            } catch (IllegalArgumentException e) {
                missing.add(String.format("U+%04X(%s)", (int) c, c));
            } catch (IOException e) {
                // 读字体真的失败了（文件坏了、内存不足）——那是别的问题，不能当成「缺字」
                throw new IllegalStateException("读取字体失败，无法判断字形是否存在：" + e, e);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("字体缺少以下字形：" + missing
                    + "。用 python3 tools/make_test_font.py --source <NotoSansSC-VF.ttf> 重新生成子集。");
        }
    }

    /** 用 CJK 子集字体写一个页面；{@code texts} 会被编码成 Type0 的 UTF-16BE 串。 */
    static Path writeCjk(Path path, Path fontFile, List<String> lines) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            doc.addPage(page);
            // 关键：用**本体**字体对象编码，不要用 getFont(name) 取回来的那份。
            // getFont() 是从 COS 字典反序列化出来的副本，它看不到还没写进描述符的嵌入字体，
            // 于是 PDFBox 会拿一个替代字体顶上（实测警告：
            // "Using fallback font LiberationSans for CID-keyed TrueType font ..."）。
            // 用本体对象编码，保存时 PDFBox 才会把真字体写进 PDF。
            PDType0Font font = loadCjkFont(doc, fontFile);
            COSName name = page.getResources().add(font);
            StringBuilder content = new StringBuilder("BT\n");
            int y = 760;
            for (String line : lines) {
                assertCovers(font, line);
                content.append('/').append(name.getName()).append(" 12 Tf\n");
                content.append("1 0 0 1 50 ").append(y).append(" Tm\n");
                content.append('<').append(hex(font.encode(line))).append("> Tj\n");
                y -= 20;
            }
            content.append("ET\n");
            page.setContents(stream(doc, content.toString().getBytes(StandardCharsets.ISO_8859_1)));
            doc.save(path.toFile());
        }
        return path;
    }

    private static PDStream stream(PDDocument doc, byte[] data) throws IOException {
        return new PDStream(doc, new ByteArrayInputStream(data));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 取证

    /** 独立于被测代码的文本抽取：PDFBox 自己的文本引擎，用来验「原文确实没了」。 */
    public static String extractText(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setShouldSeparateByBeads(true);
            return stripper.getText(doc);
        }
    }

    /**
     * 读出第一页的<b>内容流原文</b>（ISO-8859-1 解码）。
     *
     * <p>用来检查内容流本身的结构，而不是抽出来的文字：
     * 「算子后面有没有空白」这种毛病在抽取文本里完全看不出来——
     * 字符一个不少，只是画窄了。
     */
    public static String readFirstPageContent(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            try (InputStream in = doc.getPage(0).getContents()) {
                if (in == null) {
                    return "";
                }
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
    }

    /**
     * 找出内容流里<b>没有以空白或分隔符结束</b>的算子。
     *
     * <p>规范要求算子必须以空白或分隔符结束（PDF 32000-1 §7.8.2）。
     * 「算子 + 数字」粘在一起是最常见的粘连形态（本项目实测到的就是
     * 「68.2317 Tz100 Tz」——「Tz100」成了一个没人认识的算子）。
     *
     * <p><b>刻意不复用被测代码的内容流扫描器</b>：那个扫描器把「Tz100」
     * 读成**一个**算子名，用它来检查就等于用它自己的口径证明它自己——
     * 真正的畸形它根本看不见。这里按规范自己切一遍。
     */
    public static List<String> malformedOperators(String content) {
        List<String> bad = new ArrayList<>();
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '%') {
                while (i < n && content.charAt(i) != '\n' && content.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '(') {
                i = skipLiteralString(content, i);
                continue;
            }
            if (c == '<') {
                if (i + 1 < n && content.charAt(i + 1) == '<') {
                    i += 2;
                    continue;
                }
                i = skipHexString(content, i);
                continue;
            }
            if (c == '>') {
                i += (i + 1 < n && content.charAt(i + 1) == '>' ? 2 : 1);
                continue;
            }
            if (c == '/') {
                // 名字（/F1、/F2 …）整体跳过：名字里带数字是**正常的**
                // （「F1」「F2」是字体资源名），不能当成算子。
                i++;
                while (i < n && isRegular(content.charAt(i))) {
                    i++;
                }
                continue;
            }
            if (c == '[' || c == ']' || c == '{' || c == '}') {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isRegular(content.charAt(i))) {
                i++;
            }
            String run = content.substring(start, i);
            if (run.isEmpty() || isNumber(run)) {
                continue;   // 数字不是算子
            }
            // 算子名里混进了数字：规范里只有 d0 / d1 两个算子带数字（PDF 32000-1 表 52）。
            // 「Tz100」这种是**算子与它的操作数粘在了一起**——
            // 它被当成了一个没人认识的算子，读者会忽略它。
            if (run.matches(".*[0-9].*") && !"d0".equals(run) && !"d1".equals(run)) {
                bad.add("算子名「" + run + "」里混进了数字（多半是算子与操作数粘在了一起）");
                continue;
            }
            if (i < n && !Character.isWhitespace(content.charAt(i)) && !isDelimiter(content.charAt(i))) {
                bad.add("「" + run + "」后面紧跟 '" + content.charAt(i) + "'（算子必须以空白或分隔符结束）");
            }
        }
        return bad;
    }

    private static int skipLiteralString(String s, int i) {
        int depth = 1;
        i++;
        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }
        return i;
    }

    private static int skipHexString(String s, int i) {
        i++;
        while (i < s.length() && s.charAt(i) != '>') {
            i++;
        }
        return Math.min(i + 1, s.length());
    }

    private static boolean isNumber(String run) {
        return run.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)");
    }

    private static boolean isRegular(char c) {
        return !Character.isWhitespace(c) && !isDelimiter(c);
    }

    private static boolean isDelimiter(char c) {
        return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == '/' || c == '%';
    }

    record Glyph(String text, double x, double y) {
    }

    /**
     * 一个字形的横向占位 {@code [x, x+width)}。
     *
     * <p>宽度取 PDFBox 自己算的 {@code getWidthDirAdj()}，不按字体度量表自己估：
     * 估出来的值和真值差个零点几个点，而「有没有压上去」恰恰是这个量级的判定，
     * 拿不准的尺子量出来的结论不能用。
     */
    record Box(String text, double x, double width) {
        double end() {
            return x + width;
        }
    }

    /** 与 {@link #glyphs} 同一个引擎，只是额外给出字形宽度。 */
    static List<Box> glyphBoxes(Path pdf) throws IOException {
        List<Box> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper engine = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    String u = text.getUnicode();
                    if (u != null && !u.isEmpty()) {
                        out.add(new Box(u, text.getXDirAdj(), text.getWidthDirAdj()));
                    }
                }
            };
            engine.setSortByPosition(false);
            engine.setShouldSeparateByBeads(false);
            engine.getText(doc);
        }
        return out;
    }

    /** PDFBox 文本引擎逐字形算出的坐标。这是**外部标尺**：它完全不知道我们改了什么。 */
    static List<Glyph> glyphs(Path pdf) throws IOException {
        List<Glyph> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper engine = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    String u = text.getUnicode();
                    if (u != null && !u.isEmpty()) {
                        out.add(new Glyph(u, text.getXDirAdj(), text.getYDirAdj()));
                    }
                }
            };
            engine.setSortByPosition(false);
            engine.setShouldSeparateByBeads(false);
            engine.getText(doc);
        }
        return out;
    }

    /** 某段文字的首个字形坐标；找不到返回 null。 */
    static double[] firstGlyphPosition(Path pdf, String needle) throws IOException {
        List<Glyph> gs = glyphs(pdf);
        StringBuilder sb = new StringBuilder();
        for (Glyph g : gs) {
            sb.append(g.text());
        }
        int i = sb.indexOf(needle);
        return i < 0 ? null : new double[]{gs.get(i).x(), gs.get(i).y()};
    }

    /** 读出某个页面的解码后内容流字节。 */
    static byte[] pageContent(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Iterator<PDStream> it = doc.getPage(0).getContentStreams();
            while (it.hasNext()) {
                sink.write(it.next().toByteArray());
                sink.write('\n');
            }
            return sink.toByteArray();
        }
    }

    /** 内容流为空时 PDFBox 也会写出一个空流，这里判断「这条内容是不是原封不动」。 */
    static String stripTrailingNewline(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1).strip();
    }

    static Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("litemask-pdf-test");
        dir.toFile().deleteOnExit();
        return dir;
    }

    // ---------------------------------------------------------------- 带结构的夹具

    /** 页面引用一个表单 XObject，敏感信息藏在表单里而不是页面内容流里。 */
    static Path writeFormWithSensitiveForm(Path path) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            doc.addPage(page);
            COSName fontName = page.getResources().add(new PDType1Font(Standard14Fonts.FontName.HELVETICA));

            String formContent = "BT\n/" + fontName.getName() + " 12 Tf\n"
                    + "1 0 0 1 10 10 Tm\n(Tel 13800138000) Tj\nET\n";
            org.apache.pdfbox.cos.COSStream formStream = new org.apache.pdfbox.cos.COSStream();
            try (java.io.OutputStream os = formStream.createOutputStream()) {
                os.write(formContent.getBytes(StandardCharsets.ISO_8859_1));
            }
            formStream.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE,
                    org.apache.pdfbox.cos.COSName.FORM);
            formStream.setItem(org.apache.pdfbox.cos.COSName.BBOX,
                    new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 300, 40));
            org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form =
                    new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(formStream);
            form.setResources(new PDResources());
            form.getResources().add(page.getResources().getFont(fontName));

            COSName xobjName = page.getResources().add(form);
            String pageContent = "q 1 0 0 1 50 700 cm /" + xobjName.getName() + " Do Q\n";
            page.setContents(stream(doc, pageContent.getBytes(StandardCharsets.ISO_8859_1)));
            doc.save(path.toFile());
        }
        return path;
    }

    /** 元数据、批注、附件、表单、JavaScript 全都塞满，用来验证「看得见的字脱了，藏起来的也脱了」。 */
    static Path writeWithHiddenData(Path path) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            doc.addPage(page);
            COSName fontName = page.getResources().add(new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setContents(stream(doc, ("BT\n/" + fontName.getName() + " 12 Tf\n"
                    + "1 0 0 1 50 700 Tm\n(Public text.) Tj\nET\n")
                    .getBytes(StandardCharsets.ISO_8859_1)));

            var info = doc.getDocumentInformation();
            info.setAuthor("张三");
            info.setCreator("SomeOffice 9.9");
            info.setTitle("内部卷宗");
            info.setCustomMetadataValue("Company", "某某律师事务所");
            info.setCustomMetadataValue("SourceModified", "D:20240101120000");

            byte[] xmp = ("<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                    + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF "
                    + "xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                    + "<rdf:Description dc:creator='张三' "
                    + "xmlns:dc='http://purl.org/dc/elements/1.1/'/></rdf:RDF></x:xmpmeta>"
                    + "<?xpacket end='w'?>").getBytes(StandardCharsets.UTF_8);
            org.apache.pdfbox.cos.COSStream meta = new org.apache.pdfbox.cos.COSStream();
            try (java.io.OutputStream os = meta.createOutputStream()) {
                os.write(xmp);
            }
            doc.getDocumentCatalog().getCOSObject()
                    .setItem(org.apache.pdfbox.cos.COSName.METADATA, meta);

            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup note =
                    new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup();
            note.setContents("请拨打 " + "13800138000" + " 核实");
            note.getCOSObject().setString(org.apache.pdfbox.cos.COSName.T, "李四");
            note.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(50, 690, 100, 12));
            page.setAnnotations(List.of(note));

            org.apache.pdfbox.cos.COSDictionary embedded = new org.apache.pdfbox.cos.COSDictionary();
            embedded.setString(org.apache.pdfbox.cos.COSName.getPDFName("F"),
                    "原始未脱敏文档.docx");
            org.apache.pdfbox.cos.COSArray files = new org.apache.pdfbox.cos.COSArray();
            files.add(embedded);
            org.apache.pdfbox.cos.COSDictionary names = new org.apache.pdfbox.cos.COSDictionary();
            names.setItem(org.apache.pdfbox.cos.COSName.getPDFName("EmbeddedFiles"), files);
            doc.getDocumentCatalog().getCOSObject()
                    .setItem(org.apache.pdfbox.cos.COSName.NAMES, names);

            org.apache.pdfbox.cos.COSDictionary js = new org.apache.pdfbox.cos.COSDictionary();
            js.setItem(org.apache.pdfbox.cos.COSName.JS, new org.apache.pdfbox.cos.COSString("app.alert(1)"));
            org.apache.pdfbox.cos.COSDictionary jsNames = new org.apache.pdfbox.cos.COSDictionary();
            jsNames.setItem(org.apache.pdfbox.cos.COSName.getPDFName("JavaScript"), js);
            names.setItem(org.apache.pdfbox.cos.COSName.getPDFName("JavaScript"), jsNames);

            org.apache.pdfbox.cos.COSDictionary acro = new org.apache.pdfbox.cos.COSDictionary();
            acro.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Fields"), new org.apache.pdfbox.cos.COSArray());
            doc.getDocumentCatalog().getCOSObject()
                    .setItem(org.apache.pdfbox.cos.COSName.getPDFName("AcroForm"), acro);
            doc.getDocumentCatalog().setOpenAction(
                    new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript());

            doc.save(path.toFile());
        }
        return path;
    }
}
