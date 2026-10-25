package com.litemask.ner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型包的<b>信任锚</b>：哪些公钥算「发布方」。
 *
 * <p><b>为什么要有这一层</b>：签名只回答「这份包是不是用某把私钥签的」，
 * 不回答「那把私钥是不是我们的」。后者要靠<b>信任锚</b>——
 * 一份「我们认可这些公钥」的清单。没有它，任何人生成一对密钥、
 * 自己签一个包，签名验得过，但那包是我们不认的。
 *
 * <p>信任锚从两处来：
 * <ul>
 *   <li><b>内置</b>：{@code model-trust-anchors.json}，随程序发布。
 *       信任链的根是程序本身（程序将来要签名、要公证）。
 *   <li><b>用户级</b>：{@code ~/.litemask/trust/anchors.json}，
 *       用户自己用 {@code model trust add} 加进来的公钥。
 * </ul>
 * 两处都读；一份签名只要有<b>任意一把</b>被信任的密钥验得过就放行。
 *
 * <p><b>为什么不做证书链</b>：证书链要 CA、要吊销列表、要有效期管理，
 * 而这个场景只有「发布方自己签自己的模型包」这一种关系。
 * Ed25519 + 一份「我们认可这些公钥」的清单，够用且没有能过期的东西。
 */
public final class ModelTrust {

    /**
     * 信任锚文件的系统属性覆盖。
     *
     * <p>存在的意义是<b>可测</b>：端到端要能在临时目录里放一把自己的钥匙，
     * 而不污染用户真实的信任锚。生产路径不设这个属性。
     */
    public static final String ANCHORS_PROPERTY = "litemask.trust.anchors";

    /**
     * <b>内置</b>信任锚文件的系统属性覆盖。
     *
     * <p>为什么要单独一个：内置锚是「随程序发布的那份」，
     * 正常路径下它就是打包进 jar 的资源文件。
     * 但有一类情况必须能测——「同一把钥匙**既在用户锚里、又在内置锚里**」。
     * 那一类的用户可见后果是「`model trust remove` 说已不再信任，
     * 可它其实还被内置锚认着」——**一句不实的陈述**，
     * 而它只在「两边都有同一把钥匙」时才发生，不给它一个测试缝就永远测不到。
     */
    public static final String BUILTIN_PROPERTY = "litemask.trust.builtin";

    /** 内置信任锚（随程序发布）。 */
    public static final String BUILTIN_RESOURCE = "/model-trust-anchors.json";

    /** 用户级信任锚相对 {@code user.home} 的位置。 */
    public static final String USER_ANCHORS_SUBPATH = ".litemask/trust/anchors.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelTrust() {
    }

    /**
     * 验签动作（可替换）。
     *
     * <p><b>为什么留这个缝</b>：单元测试里没有发布方的私钥，
     * 而「装一个包」这个动作在很多用例里只是顺带发生的事。
     * 做成可替换的动作后，测的是同一段代码、同一个判定，
     * 只是把「验签」这一步换成可控的。
     * 真实签名上的那条路径由端到端脚本覆盖（那里有真密钥）。
     */
    @FunctionalInterface
    public interface Checker {
        /**
         * 检查这个模型包文件是否可以安装。
         *
         * @param packageFile 模型包压缩文件
         * @throws IOException 不可信。消息直接给用户看。
         */
        void check(Path packageFile) throws IOException;
    }

    /**
     * 用户级信任锚文件的位置。
     *
     * @return 绝对路径
     */
    public static Path userAnchorsFile() {
        String override = System.getProperty(ANCHORS_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", ".")).resolve(USER_ANCHORS_SUBPATH);
    }

    /**
     * 一条信任锚，附带它<b>从哪来</b>。
     *
     * <p>为什么要带来源：用户级与内置级可能装着**同一把**公钥
     * （发布方把它内置了，用户又从官网拿到一份加了一遍）。
     * 这时「已信任几把公钥」如果按条数算就会**说多**，
     * 而「不再信任这把」如果只看用户级就会**说假**——
     * 它其实还被内置锚认着。两句话都是用户可见的，
     * 都不能说错，所以来源必须带出来。
     *
     * @param anchor  信任锚本身
     * @param source  来源（{@code 内置} / {@code 用户}）
     */
    public record Anchored(ModelPackageSignature.Anchor anchor, String source) {
    }

    /**
     * 读全部信任锚（内置 + 用户级），<b>按密钥去重</b>。
     *
     * <p>去重规则：同一把公钥在内置与用户级都出现时，<b>算一把</b>，
     * 来源标成「内置」（内置优先）。
     *
     * <p><b>为什么必须去重</b>：密钥标识是<b>从公钥派生</b>的，
     * 所以「标识相同」就意味着「是同一把公钥」。
     * 而「发布方内置了它 + 用户又加了一遍」是**很常见**的操作
     * （用户从官网拿到公钥，就加上了）——
     * 不去重的话 `model trust list` 会说「已信任 2 把公钥」，
     * 并把同一个标识打印两遍。
     *
     * @return 信任锚列表（已去重，带来源）
     */
    public static List<Anchored> anchored() throws IOException {
        List<Anchored> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ModelPackageSignature.Anchor a : builtinAnchors()) {
            if (seen.add(a.keyId())) {
                out.add(new Anchored(a, "内置"));
            }
        }
        Path user = userAnchorsFile();
        if (Files.isRegularFile(user)) {
            for (ModelPackageSignature.Anchor a : readAnchors(user, "用户信任锚")) {
                if (seen.add(a.keyId())) {
                    out.add(new Anchored(a, "用户"));
                }
            }
        }
        return out;
    }

    /**
     * 读全部信任锚（内置 + 用户级，已去重）。
     *
     * @return 信任锚列表
     */
    public static List<ModelPackageSignature.Anchor> anchors() throws IOException {
        List<ModelPackageSignature.Anchor> out = new ArrayList<>();
        for (Anchored a : anchored()) {
            out.add(a.anchor());
        }
        return out;
    }

    /**
     * 这把钥匙现在<b>受不受信任</b>。
     *
     * <p><b>为什么需要它</b>：{@code model trust remove} 只能删用户级那份。
     * 删完之后这把钥匙**可能还被内置锚认着**——
     * 那时候说「已不再信任」就是**一句不实的陈述**。
     * 要说真话，就得问一句「它现在还受不受信任」。
     *
     * @param keyId 密钥标识
     * @return 受信任就 true
     */
    public static boolean isTrusted(String keyId) throws IOException {
        for (ModelPackageSignature.Anchor a : anchors()) {
            if (a.keyId().equals(keyId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读内置信任锚。
     *
     * @return 信任锚列表
     */
    private static List<ModelPackageSignature.Anchor> builtinAnchors() throws IOException {
        String override = System.getProperty(BUILTIN_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p)) {
                // 覆盖指向的文件不存在 = 没有内置锚。这是合法状态，不是错误。
                return List.of();
            }
            return readAnchors(p, "内置信任锚");
        }
        try (var in = ModelTrust.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (in == null) {
                throw new IOException("程序里找不到内置信任锚（" + BUILTIN_RESOURCE + "）。");
            }
            return parseAnchors(in.readAllBytes(), "内置信任锚");
        }
    }

    /**
     * 读一份信任锚文件。
     *
     * @param file 文件
     * @param what 这份文件是什么（错误消息里用）
     * @return 信任锚列表
     */
    public static List<ModelPackageSignature.Anchor> readAnchors(Path file, String what) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return parseAnchors(bytes, what);
    }

    /**
     * 解析信任锚文件的内容。
     *
     * @param bytes 文件内容
     * @param what  这份文件是什么（错误消息里用）
     * @return 信任锚列表
     */
    public static List<ModelPackageSignature.Anchor> parseAnchors(byte[] bytes, String what) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new IOException(what + "读不出来（不是合法 JSON）：" + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException(what + "不是一个 JSON 对象。");
        }
        JsonNode arr = root.get("anchors");
        if (arr == null || !arr.isArray()) {
            throw new IOException(what + "里没有 anchors 数组。");
        }
        List<ModelPackageSignature.Anchor> out = new ArrayList<>();
        int i = 0;
        for (JsonNode n : arr) {
            i++;
            if (!n.isObject()) {
                throw new IOException(what + "里第 " + i + " 条不是对象。");
            }
            JsonNode id = n.get("keyId");
            JsonNode pk = n.get("publicKey");
            if (pk == null || !pk.isTextual()) {
                throw new IOException(what + "里第 " + i + " 条缺 publicKey。");
            }
            PublicKey key = ModelPackageSignature.publicKeyFromBase64(pk.textValue());
            // 标识**由公钥派生**，文件里写的那串只作核对。
            //
            // 为什么要派生而不是照抄文件里的：标识一旦是人写的，就一定会写错，
            // 而「标识对不上」在验签时表现为「不被信任」——用户看不出是自己写错了一个字。
            // 派生之后这个失败模式不存在：同一个公钥在任何机器上都是同一个标识。
            //
            // 那为什么文件里还留着 keyId 字段、而且对不上就报错？
            // 因为它让**手改过这个文件**这件事变得可见：标识是机器算的，
            // 算出来和写着的不一样，说明有人动过它——而动它的动机正是想改信任关系。
            // 静默忽略那个字段，等于把「这份信任锚被人动过」藏起来。
            String derived = ModelPackageSignature.keyIdOf(key);
            if (id != null && id.isTextual() && !id.textValue().isEmpty()
                    && !derived.equals(id.textValue())) {
                throw new IOException(what + "里第 " + i + " 条的 keyId 写的是 " + id.textValue()
                        + "，但按它的公钥算出来是 " + derived + "——这两个必须一致。");
            }
            out.add(new ModelPackageSignature.Anchor(derived, key));
        }
        return out;
    }

    /**
     * 校验一个模型包文件是否可以安装。
     *
     * <p><b>失败时必须说清卡在哪一环</b>：用户拿到一个「装不上」，
     * 最有用的信息是「签名文件没找到」还是「这把钥匙我们不认」——
     * 这两件事用户的下一步动作完全不同。
     *
     * @param packageFile 模型包 zip
     * @throws IOException 不可信。消息直接给用户看。
     */
    public static void verifyPackage(Path packageFile) throws IOException {
        Path sigFile = signatureFileFor(packageFile);
        if (!Files.isRegularFile(sigFile)) {
            throw new IOException("这个模型包没有签名文件：" + sigFile.getFileName()
                    + "。模型包必须由发布方签名才能装"
                    + "（先用 `model trust add` 加上发布方的公钥）。");
        }
        ModelPackageSignature.Envelope env;
        try {
            env = ModelPackageSignature.parseEnvelope(Files.readAllBytes(sigFile));
        } catch (IOException e) {
            throw new IOException("签名文件读不出来：" + e.getMessage(), e);
        }
        if (!ModelPackageSignature.ALG.equals(env.alg())) {
            throw new IOException("签名算法不是 " + ModelPackageSignature.ALG
                    + "（是 " + env.alg() + "）：不接受。算法能降级就不能拿来当信任依据。");
        }
        String actual = ModelPackageSignature.sha256Hex(packageFile);
        if (!actual.equalsIgnoreCase(env.sha256())) {
            throw new IOException("签名覆盖的是另一个包：签名里的 sha256 是 " + env.sha256()
                    + "，这个包的是 " + actual + "。拿错包了，或者包在签名之后被改过。");
        }
        List<ModelPackageSignature.Anchor> anchors = anchors();
        ModelPackageSignature.Anchor matched = null;
        for (ModelPackageSignature.Anchor a : anchors) {
            if (a.keyId().equals(env.keyId())) {
                matched = a;
                break;
            }
        }
        if (matched == null) {
            throw new IOException("这个签名用的密钥 " + env.keyId() + " 不被信任。"
                    + trustedHint(anchors));
        }
        boolean ok = ModelPackageSignature.verify(env, packageFile, matched.key());
        if (!ok) {
            throw new IOException("签名验不过：这份签名不是用已信任的密钥 " + env.keyId() + " 签的。");
        }
    }

    /**
     * 签名的旁挂文件名。
     *
     * @param packageFile 模型包 zip
     * @return {@code <包名>.sig}
     */
    public static Path signatureFileFor(Path packageFile) {
        Path p = packageFile.toAbsolutePath().normalize();
        Path parent = p.getParent();
        String name = p.getFileName().toString() + ".sig";
        return parent == null ? Path.of(name) : parent.resolve(name);
    }

    /**
     * 把一把公钥加进用户级信任锚。
     *
     * <p><b>标识由公钥派生</b>，不让用户手填——见
     * {@link ModelPackageSignature#keyIdOf(PublicKey)}。
     *
     * @param publicKeyBase64 公钥（SPKI DER 的 Base64）
     * @return 这把公钥的标识
     * @throws IOException 写不进去
     */
    public static String addAnchor(String publicKeyBase64) throws IOException {
        // 先确认这把公钥真的能用：不能把一个解析不了的公钥写进信任锚，
        // 写进去了以后的表现是「签名永远验不过」，而原因极难找。
        PublicKey key = ModelPackageSignature.publicKeyFromBase64(publicKeyBase64);
        String keyId = ModelPackageSignature.keyIdOf(key);
        Path file = userAnchorsFile();
        List<ModelPackageSignature.Anchor> existing = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            try {
                existing.addAll(readAnchors(file, "用户信任锚"));
            } catch (IOException e) {
                // 信任锚文件坏了的话，用户就**加不进**任何公钥了——
                // 这时只报「读不出来」是死路，必须告诉他下一步做什么。
                throw new IOException("用户信任锚文件读不出来（" + file + "）：" + e.getMessage()
                        + "。请先把它挪走或修好再试（挪走后这里会重新建一份）。", e);
            }
        }
        List<ModelPackageSignature.Anchor> merged = new ArrayList<>();
        boolean replaced = false;
        for (ModelPackageSignature.Anchor a : existing) {
            if (a.keyId().equals(keyId)) {
                // 同一把钥匙再来一次：就地替换，不产生第二条。
                // 标识是派生的，所以「标识相同」就意味着「公钥相同」——
                // 这里换上去的新公钥与旧的是同一把，换不换没有区别。
                merged.add(new ModelPackageSignature.Anchor(keyId, key));
                replaced = true;
            } else {
                merged.add(a);
            }
        }
        if (!replaced) {
            merged.add(new ModelPackageSignature.Anchor(keyId, key));
        }
        writeAnchors(file, merged);
        return keyId;
    }

    /**
     * 从用户级信任锚里删掉一把公钥。
     *
     * @param keyId 密钥标识
     * @return 删掉了没有
     * @throws IOException 写不进去
     */
    public static boolean removeAnchor(String keyId) throws IOException {
        Path file = userAnchorsFile();
        if (!Files.isRegularFile(file)) {
            return false;
        }
        List<ModelPackageSignature.Anchor> existing;
        try {
            existing = readAnchors(file, "用户信任锚");
        } catch (IOException e) {
            // 同 addAnchor：文件坏了的时候必须给出下一步，不能只说「读不出来」。
            throw new IOException("用户信任锚文件读不出来（" + file + "）：" + e.getMessage()
                    + "。请先把它挪走或修好再试。", e);
        }
        List<ModelPackageSignature.Anchor> kept = new ArrayList<>();
        boolean removed = false;
        for (ModelPackageSignature.Anchor a : existing) {
            if (a.keyId().equals(keyId)) {
                removed = true;
            } else {
                kept.add(a);
            }
        }
        if (removed) {
            writeAnchors(file, kept);
        }
        return removed;
    }

    private static void writeAnchors(Path file, List<ModelPackageSignature.Anchor> anchors) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("anchors");
        for (ModelPackageSignature.Anchor a : anchors) {
            ObjectNode n = arr.addObject();
            n.put("keyId", a.keyId());
            n.put("publicKey", ModelPackageSignature.publicKeyBase64(a.key()));
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    private static String trustedHint(List<ModelPackageSignature.Anchor> anchors) {
        if (anchors.isEmpty()) {
            // 标识由公钥派生，**不用手写**——所以这条提示里没有 --id。
            return "这台机器上还没有信任任何公钥。先用 `model trust add <公钥.pem>` 加一把。";
        }
        List<String> ids = new ArrayList<>();
        for (ModelPackageSignature.Anchor a : anchors) {
            ids.add(a.keyId());
        }
        return "已信任的密钥：" + String.join("、", ids) + "。";
    }
}
