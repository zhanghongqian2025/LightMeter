package com.litemask.ner;

import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 模型包加载器：把「目录里的 manifest.json + 模型 + 词表」变成一个可用的 {@link NerRecognizer}。
 *
 * <h2>为什么方案 B 要有这一层</h2>
 * 核心包不含模型（模型 7MB、还要随版本更新），模型包是独立分发/导入的产物。
 * 于是模型包就是一个<b>不受信任的输入</b>——和「用户拖进来的那个 docx」同一级别。
 * 本类按这个前提写：清单里每一个字段都要被验证，任何一处对不上就<b>拒绝加载</b>，
 * 绝不「尽量兼容地放行」。
 *
 * <h2>七道闸，缺一道就出不了货</h2>
 * <ol>
 *   <li><b>格式版本</b>：不是 {@link #SUPPORTED_FORMAT_VERSION} 一律拒绝。
 *       不做「尽力解析」——跨版本的字段语义可能变，猜错就是静默错脱。</li>
 *   <li><b>路径必须是包内的普通文件名</b>：拒绝绝对路径、拒绝任何分隔符、拒绝 {@code ..}、
 *       拒绝符号链接。这与 zip 包的 zip-slip 是同一类入口：
 *       清单写 {@code "../../../../etc/passwd"} 就该在加载模型时被挡住，
 *       而不是等推理时去打开它。</li>
 *   <li><b>SHA-256</b>：模型与词表都要对上清单里的摘要。
 *       传输损坏、下载到一半、被替换，都会在这里露出来。</li>
 *   <li><b>词表严格解析</b>：每一行必须恰好是一个码点，不许空行、不许重复。</li>
 *   <li><b>模型自包含</b>：{@link OnnxSelfContained}，防止借加载之名读本机文件。</li>
 *   <li><b>嵌入行数对账</b>：{@code ONNX 嵌入行数 == 词表字数 + 1}。
 *       这是本项目<b>最安静的一个 bug</b> 的唯一防线，见 {@link OnnxTensorShapes}。</li>
 *   <li><b>窗口契约</b>：{@code overlap ≥ maxEntityChars}，由 {@link NerRecognizer} 强制。</li>
 * </ol>
 *
 * <h2>关于「模型与词表各自合法、合起来才不合法」</h2>
 * 逐个文件校验是不够的。本项目真实踩过：词表多出一个空行被读取端跳过，
 * 于是每个字符 id 从那行起错位一格——训练日志的 F1 是 0.8397，
 * 拿产物重新打分是 0.1209，<b>没有任何报错</b>。所以第 6 道闸必须是
 * 「把两个文件<b>合起来</b>对一次账」，而不是各查各的合法性。
 */
public final class NerModelPackage implements AutoCloseable {

    /** 本加载器能读的清单格式版本。新增字段时<b>必须</b>同时改这里与训练侧导出。 */
    public static final int SUPPORTED_FORMAT_VERSION = 2;

    /**
     * 导出时用来代替 {@code -inf} 的有限值。
     *
     * <p>ONNX 的 float 张量里有 {@code -inf}，而某些后端在计算图时会把它变成 NaN，
     * 所以训练侧导出时统一换成 {@code -1e4}。加载侧必须还原：
     * 不还原的话 {@link SequenceDecoder} 那条「全路径 -Infinity 就报错」的安全网
     * 会被绕过（全是有限的 {@code -1e4}，不触发 isInfinite），于是模型包配错时
     * 不报错、而是编出一条结构非法的路径——<b>静默错脱</b>。
     */
    public static final double EXPORT_MASK_VALUE = -1.0e4;

    /** 单个文件的大小上限。模型约 7MB，词表约 18KB；给到 512MB 已经很宽松。 */
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 清单里出现本版本不认识的字段 = 多半是拼错了字段名。
            // 宽松放行的话，一个拼错的 "maxEntityChar" 会静默取默认值 0，
            // 然后窗口契约以一条完全对不上原因的消息炸掉。
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 清单里指向一个包内文件的一条记录。 */
    public record FileRef(String path, String sha256) {
    }

    /** 张量名。不同导出器给的名字不一样，必须由清单指定。 */
    public record Io(String input, String emission, String transition) {
    }

    /** 滑窗参数。 */
    public record Window(int chars, int overlap) {
    }

    /**
     * 清单里的 {@code files} 段。两个文件各自带自己的摘要。
     *
     * <p>刻意不叫 {@code Files}：那会与 {@link java.nio.file.Files} 撞名，
     * 于是同一个类里 {@code Files f = …} 与 {@code Files.readAllBytes(…)}
     * 并存，编译期就得靠 import 顺序决定谁是谁——改一个 import 就换一个含义。
     */
    public record FileSet(FileRef model, FileRef vocab) {
    }

    /**
     * 模型包清单。字段全部为必填，缺一个都拒绝加载。
     *
     * <p>{@code arch} / {@code entityTypes} / {@code provenance} 是给人看的记录性字段，
     * 本类不据此改变行为，但<b>仍然要求它们存在</b>：
     * 打开严格模式（{@code FAIL_ON_UNKNOWN_PROPERTIES}）是为了让「拼错的字段名」当场暴露。
     * 放宽成忽略未知字段的话，{@code "maxEntityChar"} 这种少个 s 的拼写会被静默接受，
     * 然后以一条完全对不上原因的消息炸掉——错误信息会指向错误的地方。
     */
    public record Manifest(int formatVersion, String id, String arch,
                           List<String> tags, Map<String, String> tagTypes,
                           List<String> entityTypes,
                           int maxEntityChars, String scoreSpace,
                           Window window, Io io, String embeddingTensor,
                           FileSet files,
                           Map<String, Object> metrics, Map<String, Object> provenance) {
    }

    private final Path directory;
    private final Manifest manifest;
    private final CharVocab vocab;
    private final OnnxRunner runner;
    private final NerRecognizer recognizer;

    private NerModelPackage(Path directory, Manifest manifest, CharVocab vocab,
                            OnnxRunner runner, NerRecognizer recognizer) {
        this.directory = directory;
        this.manifest = manifest;
        this.vocab = vocab;
        this.runner = runner;
        this.recognizer = recognizer;
    }

    public Path directory() {
        return directory;
    }

    public Manifest manifest() {
        return manifest;
    }

    public CharVocab vocab() {
        return vocab;
    }

    public NerRecognizer recognizer() {
        return recognizer;
    }

    /** 适配成引擎认识的来源。 */
    public NerEntitySource entitySource() {
        return new NerEntitySource(recognizer);
    }

    /**
     * 底层推理会话。包内可见，供测试直接读转移矩阵。
     *
     * <p>刻意<b>不给 public</b>：外部拿到它就能绕过自包含闸门之外的一切约束去跑任意张量，
     * 而本工具对外只需要「给我识别结果」这一件事。
     */
    OnnxRunner recognizerRunner() {
        return runner;
    }

    // ------------------------------------------------------------------ 加载

    public static NerModelPackage load(Path directory) throws IOException, OrtException {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidModelPackage("模型包目录不存在或不是目录: " + directory);
        }
        Path manifestPath = resolveInside(directory, "manifest.json");
        Manifest manifest = readManifest(manifestPath);
        checkFormatVersion(manifest, manifestPath);
        FileSet files = manifest.files();
        if (files == null || files.model() == null || files.vocab() == null) {
            throw new InvalidModelPackage(
                    "清单的 files 段必须同时含 model 与 vocab: " + manifestPath
                            + "。两者缺一就说明这个包不是一整套——"
                            + "模型与词表必须来自同一次导出，混用会让每个字符的 id 错位。");
        }

        Path modelPath = resolveInside(directory,
                require(files.model().path(), "files.model.path", manifestPath));
        Path vocabPath = resolveInside(directory,
                require(files.vocab().path(), "files.vocab.path", manifestPath));

        // 摘要必须在读进内存之前对：读 7MB 坏文件再发现坏了是白读。
        verifyDigest(modelPath, require(files.model().sha256(), "files.model.sha256", manifestPath));
        verifyDigest(vocabPath, require(files.vocab().sha256(), "files.vocab.sha256", manifestPath));

        CharVocab vocab = CharVocab.ofListedChars(readVocab(vocabPath));
        if (manifest.tags() == null || manifest.tags().isEmpty()) {
            throw new InvalidModelPackage("清单缺少必填字段 tags: " + manifestPath);
        }
        TagScheme scheme = buildScheme(manifest.tags(), manifest.tagTypes());
        Window window = manifest.window();
        Io io = manifest.io();
        if (window == null) {
            throw new InvalidModelPackage("清单缺少 window（滑窗参数）: " + manifestPath);
        }
        if (io == null || isBlank(io.input()) || isBlank(io.emission())) {
            throw new InvalidModelPackage(
                    "清单缺少 io.input / io.emission（张量名由导出器决定，不能猜）: " + manifestPath);
        }

        byte[] onnx = readBounded(modelPath);

        // 第 6 道闸：合起来对账。这是唯一能抓住「词表与模型不是同一对」的检查。
        String embName = require(manifest.embeddingTensor(), "embeddingTensor", manifestPath);
        long rows = OnnxTensorShapes.rowsOf(onnx, embName);
        if (rows < 0) {
            throw new InvalidModelPackage("模型里没有名为 " + embName + " 的初始化张量，"
                    + "无法核对模型与词表是不是同一对产物；实际有的张量见下方异常里的说明");
        }
        if (rows != vocab.idCount()) {
            throw new InvalidModelPackage("模型与词表不是同一对产物："
                    + "嵌入矩阵 " + embName + " 有 " + rows + " 行，而词表是 "
                    + vocab.size() + " 个字 + 1 个 UNK = " + vocab.idCount() + " 行。"
                    + "两者对不上会让**每个字符的 id 错位**，表现是识别结果大面积离谱却不报错。"
                    + "请重新导出整个模型包，不要只换其中一个文件。");
        }

        // 第 5 道闸在这里生效：OnnxRunner.open 是唯一的加载路径，它内部先过自包含检查。
        OnnxRunner runner = OnnxRunner.open(onnx, io.input(), io.emission());

        NerRecognizer.ScoreSpace space = parseScoreSpace(manifest.scoreSpace());
        double[] transitions = null;
        if (!isBlank(io.transition())) {
            transitions = readTransitions(runner, io.transition(), scheme);
        }

        try {
            NerRecognizer recognizer = new NerRecognizer(vocab, runner, scheme, transitions, space,
                    window.chars(), window.overlap(), manifest.maxEntityChars());
            // 通道数当场对一次：标签方案与模型通道数不符时，推理期才报错的现场
            // 是「第 3 个窗口炸了」，看不出是模型包配错。
            checkChannels(runner, scheme);
            return new NerModelPackage(directory, manifest, vocab, runner, recognizer);
        } catch (RuntimeException e) {
            runner.close();
            throw e;
        }
    }

    private static void checkFormatVersion(Manifest m, Path manifestPath) {
        if (m.formatVersion() != SUPPORTED_FORMAT_VERSION) {
            throw new InvalidModelPackage("模型包清单格式版本是 " + m.formatVersion()
                    + "，本版本只认 " + SUPPORTED_FORMAT_VERSION + "：" + manifestPath
                    + "。不兼容的清单不做「尽力解析」——字段语义可能已经变了，"
                    + "猜着读出来的结果是会静默错脱而不是报错。");
        }
    }

    /**
     * 读一个包内文件的路径，并挡掉一切越出包目录的可能。
     *
     * <p>这一段是本类里唯一与「用户能控制的字符串」直接相关的检查。
     * 清单来自离线导入或下载，是一个不可信输入：
     * {@code "/etc/passwd"}、{@code "../../secret"}、{@code "sub/../../x"} 都必须在这里挡住。
     *
     * <p>符号链接也挡：包目录里放一个指向别处的软链，
     * {@code Files.isRegularFile} 默认<b>会跟随</b>它，于是路径检查形同虚设。
     */
    static Path resolveInside(Path directory, String name) {
        if (isBlank(name)) {
            throw new InvalidModelPackage("清单里的文件名为空");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new InvalidModelPackage("清单里的文件名不能包含路径分隔符: " + name);
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new InvalidModelPackage("清单里的文件名不能是 . 或 ..: " + name);
        }
        Path resolved = directory.resolve(name);
        // 再兜一次 normalize 后的父目录，防止有构造能绕过上面的字符检查。
        if (!resolved.normalize().getParent().equals(directory)) {
            throw new InvalidModelPackage("清单里的文件名越出了模型包目录: " + name);
        }
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidModelPackage("模型包里缺这个文件: " + name);
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new InvalidModelPackage("模型包里的文件不能是符号链接: " + name
                    + "（软链可以让「按路径打开」读到包外的文件）");
        }
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidModelPackage("模型包里这个条目不是普通文件: " + name);
        }
        return resolved;
    }

    private static byte[] readBounded(Path p) throws IOException {
        long size = Files.size(p);
        if (size > MAX_FILE_BYTES) {
            throw new InvalidModelPackage("文件过大: " + p.getFileName() + " 有 " + size
                    + " 字节，上限 " + MAX_FILE_BYTES + "。模型实际只有几 MB。");
        }
        return Files.readAllBytes(p);
    }

    private static void verifyDigest(Path p, String expected) throws IOException {
        String actual = sha256(p);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new InvalidModelPackage("文件摘要对不上: " + p.getFileName()
                    + "\n  清单里写的是 " + expected
                    + "\n  实际算出来是 " + actual
                    + "\n模型包可能下载不完整、被替换过，或清单与文件来自不同的导出。");
        }
    }

    static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE 里没有 SHA-256，这在标准 JDK 上不可能发生", e);
        }
    }

    /**
     * 严格读词表。
     *
     * <p><b>空行是错误，不是「跳过」</b>。本项目在这里丢过一个字符：
     * 词表末尾多出一个空行被静默跳过，4586 个字只加载回 4585 个，
     * 于是从那行起每个字符的 id 整体错位一格——
     * 训练日志 F1 0.8397，产物重打分 0.1209，没有任何报错。
     * 「跳过空行」这个动作本身就是在把错误藏起来。
     */
    static List<String> readVocab(Path p) throws IOException {
        String text = Files.readString(p, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        int lineNo = 0;
        int start = 0;
        // 按 '\n' 切而不是 String.split：后者会丢掉末尾空串，
        // 于是「文件末尾多一个换行」这种最常见的形态根本看不出来。
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                String raw = text.substring(start, i);
                lineNo++;
                start = i + 1;
                if (raw.endsWith("\r")) {
                    raw = raw.substring(0, raw.length() - 1);
                }
                if (raw.isEmpty()) {
                    if (lineNo == 1 && text.isEmpty()) {
                        break;
                    }
                    // 末尾那一个换行是合法的；中间或开头再有空行就是错的。
                    if (i < text.length()) {
                        throw new InvalidModelPackage("词表第 " + lineNo + " 行是空行："
                                + p.getFileName() + "。空行会让词表与模型不再是同一对产物"
                                + "（每个字符的 id 从这里起错位一格），必须拒绝而不是跳过。");
                    }
                    continue;
                }
                out.add(raw);
            }
        }
        if (out.isEmpty()) {
            throw new InvalidModelPackage("词表是空的: " + p.getFileName());
        }
        return out;
    }

    /**
     * 由清单的标签方案构造 {@link TagScheme}。
     *
     * <p><b>标签后缀与脱敏规则码不是同一套命名，必须由清单显式映射</b>：
     * 模型用的是数据集惯例（{@code B-PER} / {@code B-ORG}），
     * 而写进还原映射与排除键的是稳定码（{@code person} / {@code organization}）。
     * 早先这里直接拿后缀去查 {@link EntityType}，于是加载器对着真实模型包报
     * 「不认识 PER」——那说明模型包与代码各说各话，不能靠猜。
     *
     * <p>映射放在清单里而不是代码里，好处是「一个来路不明的模型包能变成哪条脱敏规则」
     * 必须经过 {@link EntityType#byCode} 这道白名单：清单可以声明 {@code PER → person}，
     * 但不能声明 {@code PER → 银行卡号}。
     */
    private static TagScheme buildScheme(List<String> tags, Map<String, String> tagTypes) {
        if (tags == null || tags.isEmpty()) {
            throw new InvalidModelPackage("清单里的 tags 为空");
        }
        if (tagTypes == null || tagTypes.isEmpty()) {
            throw new InvalidModelPackage("清单缺少 tagTypes（标签后缀 → 脱敏规则码的映射）。"
                    + "这一项必须显式声明：直接拿 B-PER 之类的后缀当规则码用，"
                    + "会让模型包绕过 EntityType 白名单自行决定变成哪条脱敏规则。");
        }
        EntityType[] typeOf = new EntityType[tags.size()];
        boolean[] isBegin = new boolean[tags.size()];
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            if (tag == null || tag.isEmpty()) {
                throw new InvalidModelPackage("清单里的第 " + (i + 1) + " 个标签为空");
            }
            if ("O".equals(tag)) {
                continue;
            }
            boolean begin;
            String code;
            if (tag.startsWith("B-")) {
                begin = true;
                code = tag.substring(2);
            } else if (tag.startsWith("I-")) {
                begin = false;
                code = tag.substring(2);
            } else {
                throw new InvalidModelPackage("看不懂的标签 " + tag
                        + "：只接受 O / B-类型 / I-类型");
            }
            String ruleCode = tagTypes.get(code);
            if (ruleCode == null) {
                throw new InvalidModelPackage("清单里没有声明标签后缀 " + code + " 对应哪条脱敏规则"
                        + "（tagTypes 里也没有它）。本工具只接受：" + knownCodes()
                        + "。宁可拒绝加载，也不能让一个来路不明的标签变成新的脱敏规则。");
            }
            EntityType type = EntityType.byCode(ruleCode);
            if (type == null) {
                throw new InvalidModelPackage("清单把标签 " + tag + " 映射到了规则码 " + ruleCode
                        + "，而本工具不认识这个码（已知：" + knownCodes() + "）。"
                        + "新增类型要改 EntityType 枚举。");
            }
            typeOf[i] = type;
            isBegin[i] = begin;
        }
        return new TagScheme(typeOf, isBegin);
    }

    private static String knownCodes() {
        StringBuilder sb = new StringBuilder();
        for (EntityType t : EntityType.values()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(t.code());
        }
        return sb.toString();
    }

    private static NerRecognizer.ScoreSpace parseScoreSpace(String s) {
        if ("LOGIT".equals(s)) {
            return NerRecognizer.ScoreSpace.LOGIT;
        }
        if ("PROBABILITY".equals(s)) {
            return NerRecognizer.ScoreSpace.PROBABILITY;
        }
        throw new InvalidModelPackage("scoreSpace 只能是 LOGIT 或 PROBABILITY，实际 " + s
                + "。这个字段错了模型能跑、解码结果全错，且不会有任何报错。");
    }

    /**
     * 读 CRF 转移矩阵，并把导出时的占位值还原成「不可能」。
     *
     * <p>两道处理，都必要：
     * <ol>
     *   <li>结构上非法的转移直接置为 {@link SequenceDecoder#IMPOSSIBLE}——
     *       这一条不依赖任何阈值，是精确的。</li>
     *   <li>分数 ≤ {@link #EXPORT_MASK_VALUE} 的也置为不可能——
     *       把训练侧的「-inf」语义还原回来，否则
     *       {@link SequenceDecoder} 那条「全路径 -Infinity 就报错」的安全网会被绕过。</li>
     * </ol>
     */
    static double[] readTransitions(OnnxRunner runner, String name, TagScheme scheme)
            throws OrtException {
        float[][] m;
        try {
            m = runner.readConstantOutput(name);
        } catch (OrtException e) {
            throw new InvalidModelPackage("清单说模型有转移矩阵输出 " + name
                    + "，但实际读不出来：" + e.getMessage());
        }
        int c = scheme.size();
        if (m.length != c || (m.length > 0 && m[0].length != c)) {
            throw new InvalidModelPackage("转移矩阵是 " + m.length + "×"
                    + (m.length > 0 ? m[0].length : 0) + "，而标签方案是 " + c
                    + " 个通道（应当是 " + c + "×" + c + "）。模型包与标签方案不是一套。");
        }
        double[] flat = new double[c * c];
        for (int from = 0; from < c; from++) {
            for (int to = 0; to < c; to++) {
                float v = m[from][to];
                if (!scheme.allows(from, to) || v <= EXPORT_MASK_VALUE) {
                    flat[from * c + to] = SequenceDecoder.IMPOSSIBLE;
                } else if (Float.isNaN(v)) {
                    throw new InvalidModelPackage("转移矩阵 [" + from + "][" + to + "] 是 NaN，"
                            + "模型里有未初始化的参数");
                } else {
                    flat[from * c + to] = v;
                }
            }
        }
        return flat;
    }

    /** 跑一次推理核对通道数；模型包与标签方案配错时立刻发现，而不是第 3 个窗口才炸。 */
    private static void checkChannels(OnnxRunner runner, TagScheme scheme) throws OrtException {
        float[][] out;
        try {
            out = runner.run(new long[]{0L, 1L, 0L, 1L, 0L, 1L, 0L, 1L});
        } catch (OrtException e) {
            throw new InvalidModelPackage("模型跑不起来：" + e.getMessage());
        }
        if (out.length == 0 || out[0].length != scheme.size()) {
            throw new InvalidModelPackage("模型输出通道数 " + (out.length == 0 ? 0 : out[0].length)
                    + " 与标签方案 " + scheme.size() + " 不一致；"
                    + "多半是换了模型包但标签方案没跟着换");
        }
    }

    private static Manifest readManifest(Path p) throws IOException {
        if (Files.size(p) > MAX_FILE_BYTES) {
            throw new InvalidModelPackage("清单文件过大: " + p);
        }
        Manifest m;
        try {
            m = MAPPER.readValue(Files.readAllBytes(p), Manifest.class);
        } catch (IOException e) {
            // 一律包成 InvalidModelPackage：调用方只需要分两类
            // 「模型包有问题」与「文件系统有问题」，解析失败属于前者。
            throw new InvalidModelPackage("清单解析失败: " + p + " —— " + e.getMessage());
        }
        if (m == null) {
            throw new InvalidModelPackage("清单内容为空: " + p);
        }
        return m;
    }

    private static String require(String value, String field, Path p) {
        if (isBlank(value)) {
            throw new InvalidModelPackage("清单缺少必填字段 " + field + ": " + p);
        }
        return value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public void close() {
        runner.close();
    }

    @Override
    public String toString() {
        return "NerModelPackage{id=" + manifest.id()
                + ", dir=" + directory
                + ", vocab=" + vocab.size()
                + ", tags=" + manifest.tags() + '}';
    }

    /** 模型包本身不合法。这不是 IOException，也不是 OrtException——是「这份包我们不收」。 */
    public static final class InvalidModelPackage extends IllegalArgumentException {
        public InvalidModelPackage(String message) {
            super(message);
        }
    }
}
