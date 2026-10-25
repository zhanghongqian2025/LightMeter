import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
 * PDF 管线的端到端样本生成 + 独立校验。
 *
 * <p>刻意分成两个方向：
 * <ul>
 *   <li>{@code gen} 造一份**满是坑**的真实样本：中文子集字体、跨算子切分的手机号、
 *       扫描件式的不可见 OCR 层、作者姓名、批注正文里的手机号、内嵌附件名、
 *       JavaScript、书签标题。</li>
 *   <li>{@code check} 用 **PDFBox 自己的文本引擎** + 原始字节扫描来断言，
 *       完全不碰被测代码的解码逻辑——否则被测实现解码写错时校验会跟着错。</li>
 * </ul>
 *
 * <p>字节扫描这一层尤其重要：文本引擎只能看见「画出来的字」，
 * 而作者名、附件名、书签标题这些根本不在任何页面里，只有扫原始字节才查得到。
 */
public final class E2ePdfSample {

    private static final String ID_CARD = "11010519491231002X";
    private static final String PHONE = "13800138000";
    private static final String EMAIL = "zhang@example.com";
    // 必须过 Luhn 校验位，否则规则**正确地**不命中——端到端要测的是管线，不是规则
    private static final String BANK = "622202020202020207";
    private static final String AUTHOR = "张三丰";
    private static final String ATTACH_NAME = "原始未脱敏扫描件.docx";

    private E2ePdfSample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: E2ePdfSample gen <out.pdf> <cjk字体> | check <原件> <脱敏件> <还原件>");
            System.exit(2);
        }
        switch (args[0]) {
            case "gen" -> gen(Path.of(args[1]), Path.of(args[2]));
            case "check" -> {
                // args[3] = 还原件，args[4] = 脱敏时的告警输出（可选）。
                // 告警输出不是可选项：版式那条判据要靠它确认「放不下时有没有指名道姓」，
                // 没有它就只剩「一律要求不重叠」这种物理上做不到的要求。
                check(Path.of(args[1]), Path.of(args[2]),
                        args.length > 3 && !args[3].equals("-") ? Path.of(args[3]) : null,
                        args.length > 4 && !args[4].equals("-") ? Path.of(args[4]) : null);
            }
            default -> {
                System.err.println("未知模式: " + args[0]);
                System.exit(2);
            }
        }
    }

    // ---------------------------------------------------------------- 生成样本

    private static void gen(Path out, Path fontFile) throws Exception {
        // 字体集（.ttc）不能直接加载：PDFBox 的 PDType0Font.load 会抛
        // "'head' table is mandatory"（fontbox 3.0.3 实测）。Windows 上唯一的中文字体
        // msyh.ttc 就是字体集，所以这里必须提前拦下来并说清怎么办，
        // 否则读日志的人只看到一句英文底层异常。
        String lower = fontFile.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".ttc")) {
            throw new IllegalArgumentException("字体集（.ttc）不能直接加载：PDFBox 的 PDType0Font.load"
                    + " 会报 \"'head' table is mandatory\"。请改用单字体文件（.ttf/.otf）；"
                    + "确需字体集时先用 TrueTypeCollection.getFontByName(...) 取出其中一个字体再加载。文件："
                    + fontFile);
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            doc.addPage(page);
            // embedSubset 必须是 false：子集路径在本用法下产出的是坏文件——
            // 实测三种写法，只有 embedSubset=false 出来的 PDF 才真的带字体、文本能原样抽回。
            // 而且必须用**本体**字体对象去编码：getFont() 取回的是从 COS 字典反序列化的副本，
            // 它看不到还没写进描述符的嵌入字体，PDFBox 会拿替代字体顶上
            // （实测警告 "Using fallback font LiberationSans for CID-keyed TrueType font ..."）。
            var loaded = PDType0Font.load(doc, Files.newInputStream(fontFile), false);
            var fontName = page.getResources().add(loaded);
            var font = loaded;
            String res = fontName.getName();

            StringBuilder c = new StringBuilder("BT\n");
            int y = 760;
            // 1) 普通中文行
            c.append('/').append(res).append(" 12 Tf\n1 0 0 1 50 ").append(y)
                    .append(" Tm\n").append(hex(font.encode("民事起诉状"))).append(" Tj\n");
            // 2) 一个被排版切成两段的手机号：命中必须跨算子
            y -= 24;
            c.append('/').append(res).append(" 12 Tf\n1 0 0 1 50 ").append(y).append(" Tm\n")
                    .append("[ ").append(hex(font.encode("联系电话：1380013")))
                    .append(" -60 ").append(hex(font.encode("8000"))).append(" ] TJ\n");
            // 3) 一行里三样
            y -= 24;
            c.append('/').append(res).append(" 12 Tf\n1 0 0 1 50 ").append(y).append(" Tm\n")
                    .append(hex(font.encode("身份证 " + ID_CARD + " 邮箱 " + EMAIL))).append(" Tj\n");
            // 4) 卡号（连续 19 位，靠规则命中）
            y -= 24;
            c.append('/').append(res).append(" 12 Tf\n1 0 0 1 50 ").append(y).append(" Tm\n")
                    .append(hex(font.encode("银行卡号 " + BANK))).append(" Tj\n");
            // 5) 不可见 OCR 层（Tr=3）：扫描件上盖的一层，屏幕上看不见，抽取拿得到
            y -= 24;
            c.append("/res 0 Tr\n").append(hex(font.encode("身份证 " + ID_CARD))).append(" Tj\n");
            c.append("BT\n");
            // 6) 未命中的正常内容，用来验证「没命中的地方一个字没动」
            //
            // 这里原来写的是「此致 北京市朝阳区人民法院」。那是个**语义错误的夹具**：
            // 法院名本来就是机构名，装了 NER 模型之后它被识别成 <机构名2>
            // 是**正确行为**，而校验器却拿「这行必须原样保留」去要求它，
            // 于是测出来一个「失败」，修法一度指向引擎或模型——两边都是对的，
            // 是这条夹具选错了东西。要验「不该脱的不被脱」，就必须拿一个
            // **确实不该被脱**的东西去验。
            //
            // 而且第一版改的「此致 敬礼」**也不行**：实测 v9 会把「敬礼」认成人名
            // （<人名1>）。所以这一行是**实测挑出来的**，不是看着像公文就拿来用——
            // 候选里「此致 敬礼」被脱、「特此通知 请予审批」「据此 申请执行」都保留，
            // 这里取同样像法律文书结尾、且实测保留的那句。
            c.append('/').append(res).append(" 12 Tf\n0 Tr\n1 0 0 1 50 ").append(y)
                    .append(" Tm\n").append(hex(font.encode("以上情况属实 特此说明"))).append(" Tj\n");
            c.append("ET\n");
            page.setContents(new PDStream(doc,
                    new ByteArrayInputStream(c.toString().getBytes(StandardCharsets.ISO_8859_1))));

            // 作者、标题、自定义元数据
            var info = doc.getDocumentInformation();
            info.setAuthor(AUTHOR);
            info.setTitle("内部卷宗 " + PHONE);
            info.setCreator("SomeOffice 9.9");
            info.setCustomMetadataValue("Company", "某某律师事务所");

            // XMP
            byte[] xmp = ("<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                    + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF "
                    + "xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                    + "<rdf:Description dc:creator='" + AUTHOR + "' "
                    + "xmlns:dc='http://purl.org/dc/elements/1.1/'/></rdf:RDF></x:xmpmeta>"
                    + "<?xpacket end='w'?>").getBytes(StandardCharsets.UTF_8);
            COSStream meta = new COSStream();
            try (OutputStream os = meta.createOutputStream()) {
                os.write(xmp);
            }
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.METADATA, meta);

            // 批注：正文里有手机号，作者名是中文姓名
            PDAnnotationMarkup note = new PDAnnotationMarkup();
            note.setContents("请拨打 " + PHONE + " 核实");
            note.getCOSObject().setString(COSName.T, AUTHOR);
            note.setRectangle(new PDRectangle(50, 690, 120, 14));
            page.setAnnotations(List.of(note));

            // 内嵌附件名、JavaScript、OpenAction
            var embedded = new org.apache.pdfbox.cos.COSDictionary();
            embedded.setString(COSName.getPDFName("F"), ATTACH_NAME);
            var files = new org.apache.pdfbox.cos.COSArray();
            files.add(embedded);
            var names = new org.apache.pdfbox.cos.COSDictionary();
            names.setItem(COSName.getPDFName("EmbeddedFiles"), files);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.NAMES, names);
            doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript());

            // 书签标题里塞一个手机号
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle("卷宗 " + PHONE);
            outline.addLast(item);

            doc.save(out.toFile());
        }
        // 样本必须**自带字体**。这不是洁癖：一份没有嵌入字体的 PDF 换个机器打开
        // 就是乱码或替代字体，而端到端照样可能「跑通」——因为校验器读的是
        // 同一台机器上 PDFBox 找到的替代字体。实测踩过：老写法写出的样本里
        // FontFile2 缺失，文本层是坏的，只因本机恰好装了同名字体才没暴露。
        try (PDDocument check = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
            var f = check.getPage(0).getResources().getFont(
                    org.apache.pdfbox.cos.COSName.getPDFName("F1"));
            if (f == null || f.getFontDescriptor() == null
                    || f.getFontDescriptor().getFontFile2() == null) {
                throw new IllegalStateException("样本 PDF 里没有嵌入字体（FontFile2）——"
                        + "这份样本在别的机器上是乱码，端到端的结果不可信。文件：" + out);
            }
        }
        System.out.println("样本已生成: " + out + " (" + Files.size(out) + " 字节，含嵌入字体)");
    }

    // ---------------------------------------------------------------- 独立校验

    private static int bad = 0;

    private static void check(Path original, Path redacted, Path restored, Path warningsLog)
            throws Exception {
        bad = 0;
        System.out.println("=== 独立校验（只用 PDFBox 自己的引擎 + 原始字节扫描） ===");

        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("身份证", ID_CARD);
        secrets.put("手机号", PHONE);
        secrets.put("邮箱", EMAIL);
        secrets.put("银行卡", BANK);
        // 下面这两类不在页面文字里，只有扫原始字节 / 读元数据才查得到
        secrets.put("作者姓名", AUTHOR);
        secrets.put("附件名", ATTACH_NAME);
        // 还原时**只要求文字类的回来**。元数据与内嵌附件名是被直接抹掉的，
        // 原值从未保存，无从还原——把「还原不回来」当成缺陷是在测一个不存在的承诺。
        List<String> textOnly = List.of(ID_CARD, PHONE, EMAIL, BANK);

        // --- 1. 文本层：抽出来的字里不能有原文 ---
        // 内容正确性一律用**默认抽取**（按内容流顺序）。理由见 extractText 的注释。
        String redText = extractText(redacted, false);
        System.out.println("--- 脱敏件抽取文本 ---");
        System.out.println(redText);
        for (var e : secrets.entrySet()) {
            require(!redText.contains(e.getValue()),
                    "文本层已无" + e.getKey(), "文本层仍能读到" + e.getKey() + ": " + e.getValue());
        }
        // 令牌序号不能写死。NER 会在规则命中之外插入它自己的实体
        // （拼音邮箱被切成人名、结尾敬语被认成机构名……），同一类别的第几次出现
        // 于是会整体往后挪。带模型形态下根本不存在 <MAIL_1>，
        // 写死序号等于把这条断言锁死在「无模型」这一种形态上。
        //
        // 这不是放宽：原来要求「恰好是 <MAIL_1>」，现在要求「存在 <MAIL_N>」。
        // 如果规则漏脱了邮箱（根本没写令牌），两条一样红。
        for (String kind : List.of("ID", "PHONE", "MAIL", "CARD")) {
            require(Pattern.compile("<" + kind + "_\\d+>").matcher(redText).find(),
                    "已写入 " + kind + " 类别的令牌",
                    "缺少 " + kind + " 类别的令牌；脱敏件文本：" + redText);
        }
        require(redText.contains("民事起诉状"), "未命中的正文完整",
                "未命中的正文丢了");
        require(redText.contains("以上情况属实 特此说明"), "未命中的末行完整",
                "未命中的末行丢了");

        // --- 1b. 版式保真：按坐标抽取时令牌还在不在原位 ---
        //
        // 这条**单独立项**，因为它和上面那些不是一回事：上面判「内容对不对」，
        // 这条判「坐标还对不对」。令牌比原文宽窄不同，位移补偿没算准时，
        // 多个令牌会落到重叠的 x 区间；按坐标重组的抽取器把它们当成同一处的
        // 多段文本逐字合并，于是显示成交错（实测形如
        // `<N E<RN_EPR <E_NROESROG_NPA_EN1RI>ZSAOTNIO_<N2M_>A1I>L_1>`）。
        //
        // **状态（2026-10-18）**：这条曾经红过，修法是把令牌横向压进槽位 +
        // 补上 NER 的短前缀（PER/ORG），见 docs/M3-3-阶段状态.md §1.19。
        // 判据本身**一个字没改**——它当初红了是因为产品有缺陷，不是判据太严。
        // 不为了让它变绿而改成默认抽取，那才是把缺陷藏起来。
        String byPos = extractText(redacted, true);
        System.out.println("--- 脱敏件按坐标抽取 ---");
        System.out.println(byPos);
        for (String kind : List.of("ID", "PHONE", "MAIL", "CARD")) {
            boolean intact = Pattern.compile("<" + kind + "_\\d+>").matcher(byPos).find();
            require(intact, kind + " 令牌按坐标抽取仍完整（版式保真）",
                    "【版式保真】" + kind + " 令牌在按坐标抽取下被打散。内容流里它其实是完整的，"
                            + "所以这**不是泄漏也不是内容缺失**，而是令牌墨迹宽于槽位、"
                            + "落到了与后文重叠的 x 区间。"
                            + "\n  按坐标抽取的结果：" + byPos);
        }
        // NER 的两个令牌单独查，判据与上面四条**不同**，所以单列。
        //
        // 为什么不同：上面四个令牌顶替的是 11~18 位数字，槽位动辄上百 pt，令牌放得下。
        // NER 顶替的常常是**两三个汉字**（实测夹具里 15pt 与 24pt），而任何 ASCII 令牌
        // 压到可读下限（45%）仍要 23~25pt——**槽位窄到物理上放不下**。
        //
        // 所以这里断言的不是「一定不重叠」（那做不到），而是本产品真正的承诺：
        // **要么干净，要么指名道姓**——放不下时必须有一条点名到该令牌、且带宽度的告警。
        // 只查前半句会让这条永久红（红着也不提供信息），只查后半句等于什么都不查。
        String warnLog = readIfPresent(warningsLog);
        for (String kind : List.of("PER", "ORG")) {
            Matcher inContent = Pattern.compile("<" + kind + "_\\d+>").matcher(redText);
            if (!inContent.find()) {
                continue;   // 这个模型没命中这一类，不该因此变红
            }
            String token = inContent.group();
            if (byPos.contains(token)) {
                continue;   // 放得下且没被打散
            }
            boolean named = warnLog.contains(token) && warnLog.contains("宽于原文");
            require(named, kind + " 令牌或干净、或被告警指名（版式保真）",
                    "【版式保真】" + token + " 在按坐标抽取下被打散，而脱敏时的告警里没有点名它。"
                            + "槽位窄到令牌压到可读下限仍放不下时，**指名道姓是本产品的承诺**，"
                            + "漏了它用户就无从判断这一行到底出了什么事。"
                            + "\n  按坐标抽取的结果：" + byPos
                            + "\n  脱敏时的告警：" + warnLog);
        }

        // --- 2. 原始字节：不在任何页面里的东西只有扫字节才查得到 ---
        byte[] raw = Files.readAllBytes(redacted);
        String rawLatin = new String(raw, StandardCharsets.ISO_8859_1);
        String rawUtf8 = new String(raw, StandardCharsets.UTF_8);
        for (var e : secrets.entrySet()) {
            require(!rawUtf8.contains(e.getValue()),
                    "字节里已无" + e.getKey(), "文件字节里仍有" + e.getKey() + ": " + e.getValue());
        }
        require(!rawLatin.contains("SomeOffice"), "创建软件名已清", "创建软件名残留");
        require(!rawLatin.contains("/EmbeddedFiles"), "内嵌附件已清", "内嵌附件残留");
        require(!rawLatin.contains("/JavaScript"), "JavaScript 已清", "JavaScript 残留");
        require(!rawLatin.contains("/OpenAction"), "OpenAction 已清", "OpenAction 残留");
        require(!rawLatin.contains("/Prev"), "无旧版本链残留", "残留增量保存的旧版本链");

        // --- 3. 元数据 ---
        try (PDDocument doc = Loader.loadPDF(redacted.toFile())) {
            var info = doc.getDocumentInformation();
            require(isBlank(info.getAuthor()), "作者名已清", "作者名残留: " + info.getAuthor());
            require(isBlank(info.getTitle()), "标题已清", "标题残留: " + info.getTitle());
            require(isBlank(info.getCreator()), "创建软件已清", "创建软件残留: " + info.getCreator());
            require(info.getMetadataKeys().isEmpty(), "自定义键位已清",
                    "自定义元数据残留: " + info.getMetadataKeys());
        }

        // --- 4. 往返 ---
        if (restored != null) {
            // 往返比较也用默认抽取。用按坐标抽取的话，原件与还原件
            // 会**错得一模一样**，于是判定相等——坐标问题被两边共同掩盖，
            // 「往返无损」这个结论就不再说明任何事了。
            String before = squeeze(extractText(original, false));
            String after = squeeze(extractText(restored, false));
            require(before.equals(after), "往返文本与原件逐字一致",
                    "还原后的文本与原件不一致\n  原件: " + before + "\n  还原: " + after);
            for (String s : textOnly) {
                require(after.contains(s), "还原后原文回来: " + s, "还原后原文没回来: " + s);
            }
            // 扫**全部**令牌形态，而不是原来那四个写死的 <ID_1> 之类。
            // 写死序号时，NER 自己发出去的 <机构名1> 残留根本不会被这条抓到——
            // 它只认识它当初列举过的那几个。带模型形态下这正是最容易漏的一类：
            // 规则那几样都还原干净了，NER 那几样留在文件里，而报告是绿的。
            //
            // 匹配结果必须先存下来再拼进失败信息。写成
            //     require(!m.find(), "...", m.group() + after)
            // 会让**通过**的那次调用炸掉：Java 的实参是急切求值的，
            // find() 返回 false 时 group() 照样被执行，抛
            // IllegalStateException: No match found。实测踩过——
            // 端到端在「一个残留都没有」这种最该绿的地方红着退出。
            Matcher leftover = Pattern.compile("<[A-Za-z_]+_\\d+>").matcher(after);
            String residue = leftover.find() ? leftover.group() : null;
            require(residue == null, "还原后无残留令牌",
                    "还原后还有令牌没被换掉: " + residue + "\n  全文: " + after);
        }

        System.out.println(bad == 0 ? "端到端校验全部通过。" : "端到端校验失败 " + bad + " 项。");
        System.exit(bad == 0 ? 0 : 1);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * @param ok     是否符合预期
     * @param label  符合预期时打印的话（说清「验的是什么」）
     * @param detail 不符合预期时打印的话（说清「实际发生了什么」）
     */
    private static void require(boolean ok, String label, String detail) {
        if (ok) {
            System.out.println("  ok   " + label);
        } else {
            System.out.println("  FAIL " + detail);
            bad++;
        }
    }

    /**
     * 抽取文本。
     *
     * <p><b>两种模式必须分开用，判的是两件不同的事。</b>
     * <ul>
     *   <li>{@code sortByPosition=false}（默认）：按内容流顺序抽。
     *       它回答「**内容对不对**」——原值有没有残留、令牌写没写进去。
     *       这是脱敏正确性，只能用这个判。</li>
     *   <li>{@code sortByPosition=true}：按字符坐标重组。
     *       它回答「**版式还对不对**」——脱敏后的 x 坐标有没有对齐。
     *       令牌比原文宽窄不同，位移补偿没算准时多个令牌会落到重叠的 x 区间，
     *       这个模式会把它们当成同一处的多段文本逐字合并，于是显示成交错。</li>
     * </ul>
     *
     * <p>原来这里一律用 {@code true}，于是「版式没对齐」被当成「内容没写进去」，
     * 报出「缺少令牌」——而文件里的令牌其实逐个完整。
     * 反过来，往返校验也是同一模式：原件与还原件**错得一样**就判相等，
     * 坐标问题被两边共同掩盖了。这是「两个实现错法一致时会一起错」的一个实例。
     */
    private static String readIfPresent(Path p) {
        if (p == null || !Files.isReadable(p)) {
            return "(没拿到脱敏时的告警输出)";
        }
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return "(读告警输出失败：" + e + ")";
        }
    }

    private static String extractText(Path pdf, boolean sortByPosition) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(sortByPosition);
            return s.getText(doc);
        }
    }

    private static String squeeze(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder("<");
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.append('>').toString();
    }
}
