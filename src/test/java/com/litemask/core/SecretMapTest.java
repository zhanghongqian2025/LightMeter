package com.litemask.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 映射件加密封装的单元测试。
 *
 * <p><b>这个类为什么必须有测试</b>：封装件是「唯一含原值的东西」的保护层。
 * 它要是坏了（恒真放行、认证标签不校验），后果是**用户以为加密了、实际没加密**，
 * 而界面上看不出任何异常——与本项目反复修的那类缺陷完全同型。
 *
 * <p>所以这里的判据大多是**否定式**的：错口令必须失败、密文被改必须失败、
 * 参数被改必须失败、封装件里不许出现原值。
 */
class SecretMapTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试里用的低轮数：只影响速度，不影响被测逻辑（轮数是参数，不是常量）。 */
    private static final int FAST = SecretMap.MIN_ITERATIONS;

    private static final byte[] PLAIN = ("{\"mapping\":{\"<身份证1>\":\"11010519491231002X\"},"
            + "\"changes\":[{\"original\":\"11010519491231002X\"}]}").getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("往返：封装再拆封，字节逐字相同")
    void roundTrip() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        byte[] opened = SecretMap.open(sealed, "口令".toCharArray());
        assertArrayEquals(PLAIN, opened);
    }

    @Test
    @DisplayName("往返：二进制与空内容也逐字相同")
    void roundTripBinary() throws Exception {
        byte[] weird = new byte[512];
        for (int i = 0; i < weird.length; i++) {
            weird[i] = (byte) (i * 7 % 251);
        }
        byte[] sealed = SecretMap.seal(weird, "pw".toCharArray(), FAST);
        assertArrayEquals(weird, SecretMap.open(sealed, "pw".toCharArray()));

        byte[] empty = new byte[0];
        assertArrayEquals(empty, SecretMap.open(SecretMap.seal(empty, "pw".toCharArray(), FAST),
                "pw".toCharArray()));
    }

    @Test
    @DisplayName("封装件里找不到原值（连 base64 形式也找不到）")
    void sealedFileHasNoPlaintext() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        String asText = new String(sealed, StandardCharsets.UTF_8);
        assertFalse(asText.contains("11010519491231002X"), "封装件里出现了身份证原值");
        assertFalse(asText.contains("身份证1"), "封装件里出现了令牌");
        assertFalse(asText.contains("mapping"), "封装件里出现了明文 JSON 的键名");
        // 「只是换了个编码」也要抓住：base64 / hex 形式同样不许出现。
        String b64 = Base64.getEncoder().encodeToString(PLAIN);
        assertFalse(asText.contains(b64), "封装件里出现了原内容的 base64");
        String hex = toHex(PLAIN);
        assertFalse(asText.contains(hex), "封装件里出现了原内容的 hex");
    }

    @Test
    @DisplayName("结构：字段齐全，KDF 参数随封装件走")
    void envelopeShape() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        JsonNode root = JSON.readTree(sealed);
        assertEquals(SecretMap.FORMAT, root.path("format").asText());
        assertEquals(SecretMap.VERSION, root.path("version").asInt());
        assertEquals("PBKDF2-HMAC-SHA256", root.path("kdf").path("algo").asText());
        assertEquals(FAST, root.path("kdf").path("iterations").asInt());
        assertEquals("AES-256-GCM", root.path("cipher").path("algo").asText());
        assertEquals(SecretMap.SALT_BYTES,
                Base64.getDecoder().decode(root.path("kdf").path("salt").asText()).length);
        assertEquals(SecretMap.NONCE_BYTES,
                Base64.getDecoder().decode(root.path("cipher").path("nonce").asText()).length);
        assertTrue(root.path("ciphertext").asText().length() > 0);
    }

    @Test
    @DisplayName("同明文两次封装，密文不同（盐与 nonce 必须随机）")
    void twoSealsDiffer() throws Exception {
        byte[] a = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        byte[] b = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        assertFalse(java.util.Arrays.equals(a, b), "两次封装结果相同 → 盐或 nonce 被写死了");
        // 但两边都还解得开
        assertArrayEquals(PLAIN, SecretMap.open(a, "口令".toCharArray()));
        assertArrayEquals(PLAIN, SecretMap.open(b, "口令".toCharArray()));
    }

    @Test
    @DisplayName("口令不对：失败，且失败信息里不出现原值")
    void wrongPassphraseFails() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        SecretMap.SecretMapException e = assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(sealed, "错口令".toCharArray()));
        assertFalse(e.getMessage().contains("11010519491231002X"), "失败信息里出现了原值");
    }

    @Test
    @DisplayName("错口令与密文被改：失败信息完全一样（不许做成「判断口令对不对」的旁路）")
    void wrongPassphraseAndTamperLookAlike() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        String wrongPw = assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(sealed, "错口令".toCharArray())).getMessage();
        String tampered = assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(tamperCiphertext(sealed), "口令".toCharArray())).getMessage();
        assertEquals(wrongPw, tampered, "两种失败被区分开了 → 成了判断口令对不对的旁路");
    }

    @Test
    @DisplayName("密文被改一个字节：失败")
    void tamperedCiphertextFails() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(tamperCiphertext(sealed), "口令".toCharArray()));
    }

    @Test
    @DisplayName("nonce 被改：失败")
    void tamperedNonceFails() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        byte[] broken = replaceField(sealed, "cipher", "nonce", "AAAAAAAAAAAAAAAA");
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(broken, "口令".toCharArray()));
    }

    @Test
    @DisplayName("盐被改：失败")
    void tamperedSaltFails() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        byte[] broken = replaceField(sealed, "kdf", "salt", "AAAAAAAAAAAAAAAAAAAAAA==");
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(broken, "口令".toCharArray()));
    }

    @Test
    @DisplayName("轮数被改小：拒绝（不能照着一个荒谬的轮数去算）")
    void tamperedIterationsRejected() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        byte[] broken = replaceField(sealed, "kdf", "iterations", "1");
        SecretMap.SecretMapException e = assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(broken, "口令".toCharArray()));
        assertTrue(e.getMessage().contains("轮数"), "失败信息应当说清是轮数的问题：" + e.getMessage());
    }

    @Test
    @DisplayName("轮数被改成另一个合法值：解不开（不能因为「参数合法」就放过）")
    void tamperedIterationsToAnotherValueFails() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        byte[] broken = replaceField(sealed, "kdf", "iterations", String.valueOf(FAST + 1));
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(broken, "口令".toCharArray()));
    }

    @Test
    @DisplayName("格式名或版本不认识：拒绝")
    void unknownFormatOrVersionRejected() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        byte[] badFormat = replaceRootField(sealed, "format", "something-else");
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(badFormat, "口令".toCharArray()));
        byte[] badVersion = replaceRootField(sealed, "version", "99");
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(badVersion, "口令".toCharArray()));
    }

    @Test
    @DisplayName("算法名不认识：拒绝")
    void unknownAlgorithmRejected() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        byte[] badKdf = replaceField(sealed, "kdf", "algo", "MD5");
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(badKdf, "口令".toCharArray()));
        byte[] badCipher = replaceField(sealed, "cipher", "algo", "AES-CBC");
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(badCipher, "口令".toCharArray()));
    }

    @Test
    @DisplayName("不是 JSON / 不是对象：拒绝")
    void notAnEnvelopeRejected() throws Exception {
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open("这不是 JSON".getBytes(StandardCharsets.UTF_8), "口令".toCharArray()));
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open("[1,2,3]".getBytes(StandardCharsets.UTF_8), "口令".toCharArray()));
    }

    @Test
    @DisplayName("空口令：封装与拆封都拒绝")
    void emptyPassphraseRejected() throws Exception {
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.seal(PLAIN, new char[0], FAST));
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.seal(PLAIN, null, FAST));
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray(), FAST);
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(sealed, new char[0]));
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(sealed, null));
    }

    @Test
    @DisplayName("轮数本身不合法：封装时就拒绝")
    void illegalIterationsRejectedOnSeal() {
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.seal(PLAIN, "口令".toCharArray(), SecretMap.MIN_ITERATIONS - 1));
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.seal(PLAIN, "口令".toCharArray(), SecretMap.MAX_ITERATIONS + 1));
    }

    @Test
    @DisplayName("isSealed：只认封装件，明文映射与服务端那份都判为「不是」")
    void isSealedDiscriminates() throws Exception {
        assertTrue(SecretMap.isSealed(SecretMap.seal(PLAIN, "口令".toCharArray(), FAST)));
        // 明文映射件：没有 format 字段
        assertFalse(SecretMap.isSealed(PLAIN));
        // 服务端产出的映射件：format 字段是**文档格式名**（DOCX / PDF …），不是封装格式名
        assertFalse(SecretMap.isSealed(("{\"format\":\"DOCX\",\"mapping\":{}}")
                .getBytes(StandardCharsets.UTF_8)));
        assertFalse(SecretMap.isSealed("垃圾".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SecretMap.isSealed(new byte[0]));
        assertFalse(SecretMap.isSealed(null));
    }

    @Test
    @DisplayName("默认轮数不许被悄悄调低（口令是人定的，不是高熵随机串）")
    void defaultIterationsFloor() {
        assertTrue(SecretMap.DEFAULT_ITERATIONS >= 100_000,
                "默认轮数只有 " + SecretMap.DEFAULT_ITERATIONS + "，对人定口令来说太低了");
    }

    @Test
    @DisplayName("默认轮数也真能跑通（生产路径不是只在测试里成立）")
    void defaultIterationsWorks() throws Exception {
        byte[] sealed = SecretMap.seal(PLAIN, "口令".toCharArray());
        assertArrayEquals(PLAIN, SecretMap.open(sealed, "口令".toCharArray()));
        assertEquals(SecretMap.DEFAULT_ITERATIONS,
                JSON.readTree(sealed).path("kdf").path("iterations").asInt());
    }

    // ---- 篡改辅助：直接改 JSON 里的字段，模拟「有人动了这个文件」----

    private static byte[] tamperCiphertext(byte[] sealed) throws Exception {
        JsonNode root = JSON.readTree(sealed);
        byte[] ct = Base64.getDecoder().decode(root.path("ciphertext").asText());
        ct[0] = (byte) (ct[0] ^ 0xFF);
        return rebuild(root, Base64.getEncoder().encodeToString(ct));
    }

    private static byte[] replaceField(byte[] sealed, String group, String field, String value) throws Exception {
        JsonNode root = JSON.readTree(sealed);
        com.fasterxml.jackson.databind.node.ObjectNode copy = root.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.get(group)).put(field, value);
        return JSON.writeValueAsBytes(copy);
    }

    private static byte[] replaceRootField(byte[] sealed, String field, String value) throws Exception {
        JsonNode root = JSON.readTree(sealed);
        com.fasterxml.jackson.databind.node.ObjectNode copy = root.deepCopy();
        if ("version".equals(field)) {
            copy.put("version", Integer.parseInt(value));
        } else {
            copy.put(field, value);
        }
        return JSON.writeValueAsBytes(copy);
    }

    private static byte[] rebuild(JsonNode root, String newCiphertext) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode copy = root.deepCopy();
        copy.put("ciphertext", newCiphertext);
        return JSON.writeValueAsBytes(copy);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
