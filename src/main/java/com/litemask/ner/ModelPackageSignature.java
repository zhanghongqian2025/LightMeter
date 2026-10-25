package com.litemask.ner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 模型包的<b>签名</b>：Ed25519 分离式签名，签的是模型包 zip 的 SHA-256 摘要。
 *
 * <p><b>为什么必须有它</b>：模型包是<b>独立分发</b>的产物，来源可能不可信。
 * 而模型包决定的是「中文人名与机构名会不会被脱敏」——
 * 一个「自洽的坏包」（清单合法、模型合法、但故意漏识别某些名字）
 * 能通过现有的**全部**校验：格式对、摘要对、模型能加载。
 * 装上去之后工具照常跑、照常说「脱敏完成」，而人名就留在输出文件里了。
 *
 * <p>旁挂的 sha256 挡不住这个：摘要和包是一起分发的，
 * 攻击者把两个一起换掉就过了。签名挡得住——它把「来源」和「包」绑在一起，
 * 而私钥不在分发链路上。
 *
 * <p><b>为什么签摘要而不是签整个文件</b>：zip 是确定性的，摘要就是它的稳定标识；
 * 签一个小摘要，验证时不必把整个包再读一遍进内存。
 *
 * <p><b>为什么用 Ed25519</b>：JDK 15+ 原生支持（{@code Signature.getInstance("Ed25519")}），
 * <b>不引入任何新依赖</b>（体积预算 90MiB 是硬指标，每加一个依赖都要给取舍）；
 * 签名只有 64 字节，不需要证书链。
 */
public final class ModelPackageSignature {

    /** 信封里写死的算法名。别的算法一律拒绝——算法可以降级，就不能拿来当信任依据。 */
    public static final String ALG = "Ed25519";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelPackageSignature() {
    }

    /**
     * 一份签名信封。
     *
     * @param alg    算法名，必须是 {@link #ALG}
     * @param keyId  签名用的密钥标识（对应信任锚里的 keyId）
     * @param sha256 被签的那个包的摘要（十六进制，小写）
     * @param sig    签名本体
     */
    public record Envelope(String alg, String keyId, String sha256, byte[] sig) {
    }

    /**
     * 一把信任锚：密钥标识 + 公钥。
     *
     * @param keyId 标识
     * @param key   公钥
     */
    public record Anchor(String keyId, PublicKey key) {
    }

    /**
     * 由公钥派生出密钥标识。
     *
     * <p><b>为什么不让人手填标识</b>：标识是人写的，就一定会写错——
     * 而「标识对不上」这件事在验签时表现为「不被信任」，
     * 用户根本看不出是自己标识打错了一个字。
     * 由公钥派生就没有这个失败模式：同一个公钥在任何机器上都是同一个标识。
     *
     * @param key 公钥
     * @return 形如 {@code ed25519-1a2b3c4d5e6f} 的标识
     */
    public static String keyIdOf(PublicKey key) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("这台机器上算不了 SHA-256：" + e.getMessage(), e);
        }
        byte[] digest = md.digest(key.getEncoded());
        StringBuilder sb = new StringBuilder("ed25519-");
        for (int i = 0; i < 6; i++) {
            sb.append(Character.forDigit((digest[i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(digest[i] & 0xf, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 摘要

    /**
     * 算一个文件的 SHA-256（十六进制，小写）。
     *
     * @param file 文件
     * @return 64 个十六进制字符
     */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IOException("这台机器上算不了 SHA-256：" + e.getMessage(), e);
        }
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    /**
     * 算一个文件的 SHA-256（原始字节，用于签名）。
     *
     * @param file 文件
     * @return 32 个字节
     */
    public static byte[] sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IOException("这台机器上算不了 SHA-256：" + e.getMessage(), e);
        }
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    // ------------------------------------------------------------------ 签名

    /**
     * 给一个文件签名。
     *
     * @param file      被签的文件（模型包 zip）
     * @param key       私钥
     * @param keyId     密钥标识
     * @return 信封
     */
    public static Envelope sign(Path file, PrivateKey key, String keyId) throws IOException {
        byte[] digest = sha256(file);
        Signature s = signature();
        try {
            s.initSign(key);
            s.update(digest);
            return new Envelope(ALG, keyId, hex(digest), s.sign());
        } catch (GeneralSecurityException e) {
            throw new IOException("签名失败：" + e.getMessage(), e);
        }
    }

    /**
     * 验一个签名。
     *
     * @param env  信封
     * @param file 被签的文件
     * @param key  公钥
     * @return 验得过就 true
     */
    public static boolean verify(Envelope env, Path file, PublicKey key) throws IOException {
        byte[] digest = sha256(file);
        if (!hex(digest).equalsIgnoreCase(env.sha256())) {
            // 摘要对不上时**先**报摘要：签的是另一个包，
            // 拿这份签名去验签当然也过不了，但用户真正要知道的是「你拿错了包」。
            return false;
        }
        Signature s = signature();
        try {
            s.initVerify(key);
        } catch (GeneralSecurityException e) {
            // 公钥本身用不了（不是「签名不对」，是「没法验」）。
            // 这两件事必须分开：前者是工具/信任锚配错了，后者是包不可信。
            throw new IOException("这把公钥用不了，没法验签：" + e.getMessage(), e);
        }
        try {
            s.update(digest);
        } catch (GeneralSecurityException e) {
            throw new IOException("验签过程中出错：" + e.getMessage(), e);
        }
        try {
            return s.verify(env.sig());
        } catch (SignatureException e) {
            // 签名本体坏了——被改过，而且改到了「曲线点的编码」那几字节上。
            //
            // 【为什么这里必须返回 false 而不是报错】实测：Ed25519 的签名里前 32 字节
            // 是一段曲线点的编码。把它改坏之后，JDK 有两种表现：
            // 改出来的点解不开 → 抛「Invalid point」；
            // 改出来的点解得开但对不上 → 正常返回 false。
            // 也就是说，**同一个「签名被改过」的事实，会因改到哪个字节而表现成两种结果**。
            //
            // 不统一的后果很实际：用户看到的错误信息会随机地一会儿像「签名不对」、
            // 一会儿像「工具坏了」。而从调用方的角度只有一个问题——
            // 「这份签名有效吗？」答案是明确的「无效」。
            return false;
        } catch (GeneralSecurityException e) {
            throw new IOException("验签过程中出错：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 信封

    /**
     * 解析签名信封（JSON）。
     *
     * @param bytes 信封文件内容
     * @return 信封
     * @throws IOException 信封读不出来或字段不对
     */
    public static Envelope parseEnvelope(byte[] bytes) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new IOException("签名文件不是合法的 JSON：" + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("签名文件不是一个 JSON 对象。");
        }
        String alg = text(root, "alg");
        String keyId = text(root, "keyId");
        String sha = text(root, "sha256");
        String sigB64 = text(root, "sig");
        if (alg == null || keyId == null || sha == null || sigB64 == null) {
            throw new IOException("签名文件缺少字段（alg / keyId / sha256 / sig 都要有）。");
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(sigB64);
        } catch (IllegalArgumentException e) {
            throw new IOException("签名文件里的 sig 不是合法的 Base64：" + e.getMessage(), e);
        }
        if (sig.length != 64) {
            throw new IOException("签名长度不对（Ed25519 签名是 64 字节，这里是 " + sig.length + " 字节）。");
        }
        if (sha.length() != 64 || !sha.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IOException("签名文件里的 sha256 不是 64 位十六进制。");
        }
        return new Envelope(alg, keyId, sha.toLowerCase(java.util.Locale.ROOT), sig);
    }

    /**
     * 把信封写成 JSON 字节。
     *
     * @param env 信封
     * @return JSON 字节
     */
    public static byte[] writeEnvelope(Envelope env) throws IOException {
        var node = MAPPER.createObjectNode();
        node.put("alg", env.alg());
        node.put("keyId", env.keyId());
        node.put("sha256", env.sha256());
        node.put("sig", Base64.getEncoder().encodeToString(env.sig()));
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
    }

    // ------------------------------------------------------------------ 密钥

    /**
     * 生成一对 Ed25519 密钥。
     *
     * @return 密钥对
     */
    public static KeyPair generateKeyPair() throws IOException {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("生成 Ed25519 密钥失败：" + e.getMessage(), e);
        }
    }

    /**
     * 从 PEM 文本读公钥（X.509 SubjectPublicKeyInfo）。
     *
     * @param pem PEM 文本
     * @return 公钥
     */
    public static PublicKey publicKeyFromPem(String pem) throws IOException {
        byte[] der = decodePem(pem, "PUBLIC KEY");
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IOException("这串 PEM 不是一把 Ed25519 公钥：" + e.getMessage(), e);
        }
    }

    /**
     * 从 PEM 文本读私钥（PKCS#8）。
     *
     * @param pem PEM 文本
     * @return 私钥
     */
    public static PrivateKey privateKeyFromPem(String pem) throws IOException {
        byte[] der = decodePem(pem, "PRIVATE KEY");
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IOException("这串 PEM 不是一把 Ed25519 私钥：" + e.getMessage(), e);
        }
    }

    /**
     * 把公钥编码成 PEM 文本。
     *
     * @param key 公钥
     * @return PEM 文本
     */
    public static String publicKeyToPem(PublicKey key) {
        return toPem("PUBLIC KEY", key.getEncoded());
    }

    /**
     * 把私钥编码成 PEM 文本。
     *
     * @param key 私钥
     * @return PEM 文本
     */
    public static String privateKeyToPem(PrivateKey key) {
        return toPem("PRIVATE KEY", key.getEncoded());
    }

    /**
     * 公钥的 Base64（SPKI DER）——信任锚文件里存的是这个，不是 PEM。
     *
     * @param key 公钥
     * @return Base64 文本
     */
    public static String publicKeyBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * 从 Base64（SPKI DER）读公钥。
     *
     * @param b64 Base64 文本
     * @return 公钥
     */
    public static PublicKey publicKeyFromBase64(String b64) throws IOException {
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("公钥不是合法的 Base64：" + e.getMessage(), e);
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IOException("这串 Base64 不是一把 Ed25519 公钥：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 内部

    private static Signature signature() throws IOException {
        try {
            return Signature.getInstance(ALG);
        } catch (GeneralSecurityException e) {
            throw new IOException("这台机器上不支持 " + ALG + "：" + e.getMessage(), e);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual()) {
            return null;
        }
        String s = n.textValue();
        return s == null || s.isEmpty() ? null : s;
    }

    private static byte[] decodePem(String pem, String label) throws IOException {
        if (pem == null) {
            throw new IOException("没有 PEM 内容。");
        }
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int beginIdx = pem.indexOf(begin);
        int endIdx = pem.indexOf(end);
        if (beginIdx < 0 || endIdx < 0 || endIdx < beginIdx) {
            throw new IOException("这不是一段 " + label + " 的 PEM（缺 BEGIN/END 头尾）。");
        }
        String body = pem.substring(beginIdx + begin.length(), endIdx).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IOException("PEM 里的内容不是合法的 Base64：" + e.getMessage(), e);
        }
    }

    private static String toPem(String label, byte[] der) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** 摘要的十六进制形式与字节形式之间换算（给外部用）。 */
    public static byte[] unhex(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 字节的十六进制形式（给外部用）。 */
    public static String toHex(byte[] bytes) {
        return hex(bytes);
    }

    /** 读一个文本文件（给外部用）。 */
    public static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
