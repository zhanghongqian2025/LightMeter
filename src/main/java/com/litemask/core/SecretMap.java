package com.litemask.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 映射件的加密封装。
 *
 * <p><b>它挡的是什么</b>：映射件是整个工具里**唯一含原文**的产物
 * （脱敏件里只有令牌，原值全在映射里）。映射件原本是**明文 JSON**，
 * 权限也是默认的 0644 —— 于是「把脱敏件发出去」这件事的安全前提，
 * 只差「别把映射件一起发出去」这一句话。而这两份文件通常躺在**同一个目录**里。
 *
 * <p><b>为什么是「可选」而不是「默认加密」</b>：默认加密会逼着每一次
 * {@code redact} 都先想口令，脚本化调用当场不可用。所以这里的取舍是：
 * <ul>
 *   <li>不给口令 → 行为与从前**完全一致**（明文），但落盘权限收紧到 0600；</li>
 *   <li>给了口令 → 映射件变成**封装件**，原值不出现在文件里。</li>
 * </ul>
 *
 * <p><b>格式</b>（一行一个字段，人可读，便于排障）：
 * <pre>
 * {
 *   "format": "litemask-sealed-map",
 *   "version": 1,
 *   "kdf":     {"algo": "PBKDF2-HMAC-SHA256", "iterations": 210000, "salt": "..."},
 *   "cipher":  {"algo": "AES-256-GCM", "nonce": "..."},
 *   "ciphertext": "..."
 * }
 * </pre>
 *
 * <p><b>为什么 KDF 参数随封装件走</b>：口令派生的轮数与盐必须能被将来的实现换掉，
 * 而旧封装件还得能解开。参数写进封装件里，换算法就是「读参数、按参数算」。
 *
 * <p><b>为什么用 AEAD（GCM）而不是「加密 + 单独一个校验和」</b>：
 * GCM 的认证标签把「密文被改过」和「口令不对」合成同一个失败信号
 * （{@link AEADBadTagException}），不需要自己设计 MAC，也不会出现
 * 「密文没被改但口令猜对了」的旁路。
 *
 * <p><b>失败信息里不许出现原文</b>：解密失败一律只说「口令不对，或文件已被改动」，
 * 不区分这两种情况 —— 区分开就成了一个「判断口令对不对」的旁路。
 *
 * <p>零新增第三方依赖：KDF 与分组密码都用 JDK 自带（JDK 21 的 JCE 无需额外 provider）。
 */
public final class SecretMap {

    /** 封装件的 {@code format} 字段值。 */
    public static final String FORMAT = "litemask-sealed-map";

    /** 当前封装格式的版本号。 */
    public static final int VERSION = 1;

    /**
     * 默认的 PBKDF2 轮数。
     *
     * <p>取值是按「口令由人自己定、不是高熵随机串」来定的 ——
     * 人定的口令必须靠轮数扛离线爆破。轮数写在封装件里，升级不影响旧件。
     */
    public static final int DEFAULT_ITERATIONS = 210_000;

    /** 轮数下限：低于这个值等于没做 KDF，直接拒收（而不是照着算）。 */
    public static final int MIN_ITERATIONS = 10_000;

    /** 轮数上限：防止封装件里的数字被人改成天文数字，把机器卡死。 */
    public static final int MAX_ITERATIONS = 5_000_000;

    /** 盐的字节数。 */
    public static final int SALT_BYTES = 16;

    /** GCM nonce 的字节数（96 位是 GCM 的标准长度）。 */
    public static final int NONCE_BYTES = 12;

    /** 派生密钥长度（位）。 */
    public static final int KEY_BITS = 256;

    /** GCM 认证标签长度（位）。 */
    public static final int TAG_BITS = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private SecretMap() {
    }

    /**
     * 用默认轮数封装。
     *
     * @param plaintext 待封装的字节（一般是映射件的 JSON）
     * @param passphrase 口令；空口令一律拒绝
     * @return 封装件字节（JSON）
     * @throws SecretMapException 口令为空、算法不可用等
     */
    public static byte[] seal(byte[] plaintext, char[] passphrase) throws SecretMapException {
        return seal(plaintext, passphrase, DEFAULT_ITERATIONS);
    }

    /**
     * 用指定轮数封装。
     *
     * <p>轮数参数只给测试用（否则每个用例都要花掉半秒做 KDF）。
     * 生产路径请用 {@link #seal(byte[], char[])}。
     *
     * @param plaintext 待封装的字节
     * @param passphrase 口令
     * @param iterations PBKDF2 轮数
     * @return 封装件字节（JSON）
     * @throws SecretMapException 参数不合法或算法不可用
     */
    public static byte[] seal(byte[] plaintext, char[] passphrase, int iterations) throws SecretMapException {
        if (plaintext == null) {
            throw new SecretMapException("待封装的内容为空");
        }
        if (passphrase == null || passphrase.length == 0) {
            throw new SecretMapException("口令为空，拒绝封装");
        }
        checkIterations(iterations);

        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            SecretKeySpec key = derive(passphrase, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);

            Map<String, Object> kdf = new LinkedHashMap<>();
            kdf.put("algo", "PBKDF2-HMAC-SHA256");
            kdf.put("iterations", iterations);
            kdf.put("salt", B64.encodeToString(salt));

            Map<String, Object> cipherNode = new LinkedHashMap<>();
            cipherNode.put("algo", "AES-256-GCM");
            cipherNode.put("nonce", B64.encodeToString(nonce));

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("format", FORMAT);
            envelope.put("version", VERSION);
            envelope.put("kdf", kdf);
            envelope.put("cipher", cipherNode);
            envelope.put("ciphertext", B64.encodeToString(ciphertext));
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope);
        } catch (GeneralSecurityException e) {
            throw new SecretMapException("封装失败：" + e.getMessage());
        } catch (java.io.IOException e) {
            throw new SecretMapException("封装件序列化失败：" + e.getMessage());
        }
    }

    /**
     * 拆封。
     *
     * @param envelope 封装件字节
     * @param passphrase 口令
     * @return 原始字节
     * @throws SecretMapException 不是封装件、版本不认识、口令不对、密文被改过
     */
    public static byte[] open(byte[] envelope, char[] passphrase) throws SecretMapException {
        if (envelope == null) {
            throw new SecretMapException("封装件为空");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(envelope);
        } catch (java.io.IOException e) {
            throw new SecretMapException("这个文件不是有效的封装件（不是 JSON）");
        }
        if (root == null || !root.isObject()) {
            throw new SecretMapException("这个文件不是有效的封装件");
        }
        String format = text(root, "format");
        if (!FORMAT.equals(format)) {
            throw new SecretMapException("这个文件的封装格式不认识：" + (format == null ? "(缺 format 字段)" : format));
        }
        int version = root.path("version").asInt(-1);
        if (version != VERSION) {
            throw new SecretMapException("封装版本不认识：" + version + "（本工具只认 " + VERSION + "）");
        }

        JsonNode kdf = root.path("kdf");
        JsonNode cipherNode = root.path("cipher");
        String kdfAlgo = text(kdf, "algo");
        if (!"PBKDF2-HMAC-SHA256".equals(kdfAlgo)) {
            throw new SecretMapException("封装里的密钥派生算法不认识：" + (kdfAlgo == null ? "(缺)" : kdfAlgo));
        }
        String cipherAlgo = text(cipherNode, "algo");
        if (!"AES-256-GCM".equals(cipherAlgo)) {
            throw new SecretMapException("封装里的加密算法不认识：" + (cipherAlgo == null ? "(缺)" : cipherAlgo));
        }

        int iterations = kdf.path("iterations").asInt(-1);
        checkIterations(iterations);

        byte[] salt = decodeB64(text(kdf, "salt"), "盐");
        byte[] nonce = decodeB64(text(cipherNode, "nonce"), "nonce");
        byte[] ciphertext = decodeB64(text(root, "ciphertext"), "密文");
        if (salt.length != SALT_BYTES) {
            throw new SecretMapException("封装里的盐长度不对：" + salt.length + "（应为 " + SALT_BYTES + "）");
        }
        if (nonce.length != NONCE_BYTES) {
            throw new SecretMapException("封装里的 nonce 长度不对：" + nonce.length + "（应为 " + NONCE_BYTES + "）");
        }
        if (ciphertext.length < TAG_BITS / 8) {
            throw new SecretMapException("封装里的密文长度不对（短于认证标签）");
        }
        if (passphrase == null || passphrase.length == 0) {
            throw new SecretMapException("口令为空");
        }

        try {
            SecretKeySpec key = derive(passphrase, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            // 【为什么不区分「口令不对」与「密文被改过」】
            //   区分开就成了一个「判断口令对不对」的免费旁路 —— 攻击者可以拿它
            //   穷举口令而不用担心自己碰到的是坏文件。
            //   两种情况的正确处置是同一个：让人重新确认口令 / 重新拿一份映射件。
            throw new SecretMapException("口令不对，或这个文件已被改动");
        } catch (GeneralSecurityException e) {
            throw new SecretMapException("解封失败：" + e.getMessage());
        }
    }

    /**
     * 这个字节串是不是封装件。
     *
     * <p>判据是「能不能解析成 JSON 且 {@code format} 等于 {@link #FORMAT}」，
     * 故意做得**宽松**：明文映射件、服务端产出的映射件（它的 {@code format}
     * 字段是文档格式名如 {@code DOCX}）都应被判为「不是封装件」。
     *
     * @param bytes 待判定的字节
     * @return 是封装件则 true
     */
    public static boolean isSealed(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(bytes);
            return root != null && root.isObject() && FORMAT.equals(text(root, "format"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** 封装件里明文载荷的字节数下界：GCM 的认证标签。 */
    public static int tagBytes() {
        return TAG_BITS / 8;
    }

    /** 供测试与排障用：封装件的格式名。 */
    public static String format() {
        return FORMAT;
    }

    /**
     * 擦掉口令数组里的字符。
     *
     * <p><b>为什么要有这个</b>：口令以 {@code char[]} 的形式在内存里传递，
     * 用完之后那份数组还在堆上——不擦掉的话，它的生命周期会长于「用完」这个时刻，
     * 而它是这份内存里唯一可以直接读出明文口令的东西。
     *
     * <p><b>擦不干净的部分要如实说明</b>：调用方若是从 {@link String} 拿口令
     * （HTTP 请求体里的 JSON 字段就是 String），那个 String 本身不可变、擦不掉，
     * 只能等它被回收。所以本方法只保证「{@code char[]} 这一份被擦掉」，
     * 不代表「内存里再没有这个口令」——那条限制写在阶段文档里，不靠这句话含糊过去。
     *
     * @param passphrase 要擦的数组；{@code null} 与空数组都直接返回
     */
    public static void wipe(char[] passphrase) {
        if (passphrase == null) {
            return;
        }
        java.util.Arrays.fill(passphrase, '\0');
    }

    private static void checkIterations(int iterations) throws SecretMapException {
        if (iterations < MIN_ITERATIONS) {
            throw new SecretMapException("密钥派生轮数不合法：" + iterations + "（下限 " + MIN_ITERATIONS + "）");
        }
        if (iterations > MAX_ITERATIONS) {
            throw new SecretMapException("密钥派生轮数不合法：" + iterations + "（上限 " + MAX_ITERATIONS + "）");
        }
    }

    private static SecretKeySpec derive(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    private static byte[] decodeB64(String raw, String what) throws SecretMapException {
        if (raw == null || raw.isEmpty()) {
            throw new SecretMapException("封装件里缺" + what);
        }
        try {
            return B64D.decode(raw);
        } catch (IllegalArgumentException e) {
            throw new SecretMapException("封装件里的" + what + "不是合法的 Base64");
        }
    }

    /** 封装/解封失败。异常消息里**永远不包含原文**。 */
    public static class SecretMapException extends Exception {
        public SecretMapException(String message) {
            super(message);
        }
    }
}
