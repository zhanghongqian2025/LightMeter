package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ModelPackageSignature}：模型包的 Ed25519 分离式签名。
 *
 * <p><b>标尺独立于被测实现</b>：验签这件事，测试里用 JDK 自己的
 * {@link Signature}（{@code Ed25519}）重算一遍，摘要用
 * {@link MessageDigest} 独立算一遍。**不复用** {@code ModelPackageSignature.verify}
 * 来证明 {@code ModelPackageSignature.verify} 是对的——
 * 那样只能证明它跟自己自洽。
 *
 * <p><b>为什么这条线值得单独测</b>：签名是「模型包是不是发布方发的」这一层。
 * 它一旦悄悄放过一个坏包，后果不是「工具报错」，
 * 而是<b>人名没被脱敏而工具照常说脱敏完成</b>——
 * 一份没脱干净的文档，比一次明明白白的报错危险得多。
 */
class ModelPackageSignatureTest {

    @TempDir
    Path tmp;

    private KeyPair keys;
    private KeyPair otherKeys;

    @BeforeEach
    void setUp() throws Exception {
        keys = ModelPackageSignature.generateKeyPair();
        otherKeys = ModelPackageSignature.generateKeyPair();
    }

    // ------------------------------------------------------------------ 签名

    @Test
    @DisplayName("签一个文件：JDK 自己的验签能过")
    void signsAndVerifiesWithJdkOracle() throws Exception {
        Path f = file("模型包.zip", "模型包内容");

        ModelPackageSignature.Envelope env =
                ModelPackageSignature.sign(f, keys.getPrivate(), ModelPackageSignature.keyIdOf(keys.getPublic()));

        // 独立标尺：JDK 的 Signature 直接验，验的是被测实现产出的那串字节。
        Signature oracle = Signature.getInstance("Ed25519");
        oracle.initVerify(keys.getPublic());
        oracle.update(sha256(f));
        assertTrue(oracle.verify(env.sig()),
                "JDK 自己应该能验过被测实现产出的签名");

        // 信封里写的摘要也要等于独立算出来的摘要。
        assertEquals(toHex(sha256(f)), env.sha256(), "信封里的摘要应等于文件的 SHA-256");
        assertEquals(ModelPackageSignature.ALG, env.alg(), "算法名应写死为 Ed25519");
    }

    @Test
    @DisplayName("文件被改一个字节：验签必须不过")
    void detectsTamperedFile() throws Exception {
        Path f = file("模型包.zip", "模型包内容");
        ModelPackageSignature.Envelope env =
                ModelPackageSignature.sign(f, keys.getPrivate(), ModelPackageSignature.keyIdOf(keys.getPublic()));

        Files.writeString(f, "模型包内客", StandardCharsets.UTF_8);

        assertFalse(ModelPackageSignature.verify(env, f, keys.getPublic()),
                "内容变了一个字节，验签必须不过");
    }

    @Test
    @DisplayName("用别人的公钥验：必须不过")
    void rejectsWrongKey() throws Exception {
        Path f = file("模型包.zip", "模型包内容");
        ModelPackageSignature.Envelope env =
                ModelPackageSignature.sign(f, keys.getPrivate(), ModelPackageSignature.keyIdOf(keys.getPublic()));

        assertFalse(ModelPackageSignature.verify(env, f, otherKeys.getPublic()),
                "换一把公钥验签必须不过——否则谁都能签一个包");
    }

    @Test
    @DisplayName("签名是确定性的：同一份文件同一把钥匙签两次，字节一样")
    void signatureIsDeterministic() throws Exception {
        Path f = file("模型包.zip", "模型包内容");
        ModelPackageSignature.Envelope a =
                ModelPackageSignature.sign(f, keys.getPrivate(), "k1");
        ModelPackageSignature.Envelope b =
                ModelPackageSignature.sign(f, keys.getPrivate(), "k1");

        assertArrayEquals(a.sig(), b.sig(),
                "Ed25519 是确定性签名：同一份输入签两次必须一样（否则无法复现与比对）");
    }

    @Test
    @DisplayName("信封里的摘要被改：验签必须不过")
    void rejectsEnvelopeWithWrongDigest() throws Exception {
        Path f = file("模型包.zip", "模型包内容");
        ModelPackageSignature.Envelope env =
                ModelPackageSignature.sign(f, keys.getPrivate(), "k1");

        ModelPackageSignature.Envelope tampered = new ModelPackageSignature.Envelope(
                env.alg(), env.keyId(), "0".repeat(64), env.sig());

        assertFalse(ModelPackageSignature.verify(tampered, f, keys.getPublic()),
                "信封里的摘要与实际文件对不上，必须验不过");
    }

    @Test
    @DisplayName("签名本体任意一个字节被改：都判「验签不过」，不许表现成两种样子")
    void tamperingAnySignatureByteAlwaysFails() throws Exception {
        Path f = file("模型包.zip", "模型包内容");
        ModelPackageSignature.Envelope env =
                ModelPackageSignature.sign(f, keys.getPrivate(), "k1");

        // 逐字节扫过去。
        //
        // 【为什么不能只测一个字节】实测：Ed25519 签名里前 32 字节是一段曲线点的编码。
        // 改坏它，JDK 有两种表现——改出来的点解不开就抛「Invalid point」，
        // 改出来的点解得开但对不上就正常返回 false。
        // 同一个「签名被改过」的事实，**会因改到哪个字节而表现成两种不同的结果**。
        //
        // 只测一个字节的话，这条测试的结果取决于那把随机密钥——
        // 这一轮红、下一轮绿，看起来像 flaky。逐字节扫过去就没有这个不确定性了，
        // 而且它顺带把「抛异常」这种表现也一并钉住。
        for (int i = 0; i < env.sig().length; i++) {
            byte[] sig = env.sig().clone();
            sig[i] = (byte) (sig[i] ^ 0xFF);
            ModelPackageSignature.Envelope tampered =
                    new ModelPackageSignature.Envelope(env.alg(), env.keyId(), env.sha256(), sig);
            assertFalse(ModelPackageSignature.verify(tampered, f, keys.getPublic()),
                    "第 " + i + " 个字节被改过，必须判验签不过（不许抛异常、也不许放过）");
        }
    }

    // ------------------------------------------------------------------ 密钥标识

    @Test
    @DisplayName("密钥标识由公钥派生：同一把公钥两次派生一致，不同公钥不同")
    void keyIdIsDerivedFromPublicKey() throws Exception {
        String id1 = ModelPackageSignature.keyIdOf(keys.getPublic());
        String id2 = ModelPackageSignature.keyIdOf(keys.getPublic());
        String other = ModelPackageSignature.keyIdOf(otherKeys.getPublic());

        assertEquals(id1, id2, "同一把公钥必须派生出同一个标识");
        assertNotEquals(id1, other, "不同的公钥必须派生出不同的标识");
        assertTrue(id1.startsWith("ed25519-"), "标识应带上前缀，标明算法，实际: " + id1);
    }

    @Test
    @DisplayName("密钥标识 = 公钥 DER 的 SHA-256 前 6 字节的十六进制（独立算法）")
    void keyIdMatchesIndependentDefinition() throws Exception {
        // 这里是**独立重写一遍**标识的算法，而不是调被测实现再比自己。
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(keys.getPublic().getEncoded());
        StringBuilder sb = new StringBuilder("ed25519-");
        for (int i = 0; i < 6; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        assertEquals(sb.toString(), ModelPackageSignature.keyIdOf(keys.getPublic()),
                "标识的算法必须固定成「公钥 DER 的 SHA-256 前 6 字节」，否则跨机器对不上");
    }

    // ------------------------------------------------------------------ PEM / Base64

    @Test
    @DisplayName("公钥 PEM 往返：写出去再读回来是同一把钥匙")
    void publicKeyPemRoundTrips() throws Exception {
        String pem = ModelPackageSignature.publicKeyToPem(keys.getPublic());

        assertTrue(pem.contains("-----BEGIN PUBLIC KEY-----"), "PEM 应有标准头，实际: " + pem);
        PublicKey back = ModelPackageSignature.publicKeyFromPem(pem);
        assertArrayEquals(keys.getPublic().getEncoded(), back.getEncoded(),
                "往返之后必须是同一把公钥");
        assertEquals(ModelPackageSignature.keyIdOf(keys.getPublic()),
                ModelPackageSignature.keyIdOf(back), "往返后标识也必须一样");
    }

    @Test
    @DisplayName("私钥 PEM 往返：写出去再读回来能签出一样的签名")
    void privateKeyPemRoundTrips() throws Exception {
        Path f = file("模型包.zip", "模型包内容");
        String pem = ModelPackageSignature.privateKeyToPem(keys.getPrivate());

        PrivateKey back = ModelPackageSignature.privateKeyFromPem(pem);
        ModelPackageSignature.Envelope a =
                ModelPackageSignature.sign(f, keys.getPrivate(), "k");
        ModelPackageSignature.Envelope b =
                ModelPackageSignature.sign(f, back, "k");
        assertArrayEquals(a.sig(), b.sig(), "往返后的私钥必须能签出一样的签名");
    }

    @Test
    @DisplayName("公钥 Base64 往返（信任锚文件里存的就是这个）")
    void publicKeyBase64RoundTrips() throws Exception {
        String b64 = ModelPackageSignature.publicKeyBase64(keys.getPublic());

        PublicKey back = ModelPackageSignature.publicKeyFromBase64(b64);
        assertArrayEquals(keys.getPublic().getEncoded(), back.getEncoded(),
                "往返之后必须是同一把公钥");
    }

    @Test
    @DisplayName("不是 PEM 的内容：必须明确报错，不许当成一把钥匙")
    void rejectsNonPem() {
        IOException e = assertThrows(IOException.class,
                () -> ModelPackageSignature.publicKeyFromPem("这不是 PEM，只是一段文字"),
                "缺 BEGIN/END 头尾的必须被拒");
        assertTrue(e.getMessage().contains("PEM"), "错误消息要说清是 PEM 的问题，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("PEM 头尾对但内容不是 Base64：必须报错")
    void rejectsPemWithGarbageBody() {
        String pem = "-----BEGIN PUBLIC KEY-----\n!!!!\n-----END PUBLIC KEY-----\n";
        assertThrows(IOException.class, () -> ModelPackageSignature.publicKeyFromPem(pem),
                "内容不是合法 Base64 的 PEM 必须被拒");
    }

    @Test
    @DisplayName("Base64 不是合法公钥：必须报错")
    void rejectsGarbageBase64() {
        assertThrows(IOException.class, () -> ModelPackageSignature.publicKeyFromBase64("这不是 Base64!!"),
                "不合法的 Base64 必须被拒");
    }

    // ------------------------------------------------------------------ 信封

    @Test
    @DisplayName("信封 JSON 往返：写出去再读回来，四个字段都对")
    void envelopeRoundTrips() throws Exception {
        Path f = file("模型包.zip", "模型包内容");
        ModelPackageSignature.Envelope env =
                ModelPackageSignature.sign(f, keys.getPrivate(), "ed25519-abcdef012345");

        byte[] json = ModelPackageSignature.writeEnvelope(env);
        ModelPackageSignature.Envelope back = ModelPackageSignature.parseEnvelope(json);

        assertEquals(env.alg(), back.alg(), "alg 应往返一致");
        assertEquals(env.keyId(), back.keyId(), "keyId 应往返一致");
        assertEquals(env.sha256(), back.sha256(), "sha256 应往返一致");
        assertArrayEquals(env.sig(), back.sig(), "签名本体应往返一致");
    }

    @Test
    @DisplayName("信封不是合法 JSON：必须报错")
    void envelopeRejectsGarbage() {
        IOException e = assertThrows(IOException.class,
                () -> ModelPackageSignature.parseEnvelope("这不是 JSON".getBytes(StandardCharsets.UTF_8)),
                "不是 JSON 的信封必须被拒");
        assertTrue(e.getMessage().contains("JSON"), "错误消息要说清是 JSON 的问题，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("信封不是对象：必须报错")
    void envelopeRejectsNonObject() {
        assertThrows(IOException.class,
                () -> ModelPackageSignature.parseEnvelope("[1,2,3]".getBytes(StandardCharsets.UTF_8)),
                "顶层不是对象的信封必须被拒");
    }

    @Test
    @DisplayName("信封缺字段：必须报错，并说清缺哪个")
    void envelopeRejectsMissingFields() {
        IOException e = assertThrows(IOException.class,
                () -> ModelPackageSignature.parseEnvelope(
                        "{\"alg\":\"Ed25519\",\"keyId\":\"k\"}".getBytes(StandardCharsets.UTF_8)),
                "缺 sha256 与 sig 的信封必须被拒");
        assertTrue(e.getMessage().contains("sha256") && e.getMessage().contains("sig"),
                "错误消息要列出缺哪些字段，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("信封里的 sig 不是合法 Base64：必须报错")
    void envelopeRejectsBadBase64Signature() {
        String json = "{\"alg\":\"Ed25519\",\"keyId\":\"k\",\"sha256\":\""
                + "0".repeat(64) + "\",\"sig\":\"!!!不是 Base64!!!\"}";
        assertThrows(IOException.class,
                () -> ModelPackageSignature.parseEnvelope(json.getBytes(StandardCharsets.UTF_8)),
                "sig 不是合法 Base64 的信封必须被拒");
    }

    @Test
    @DisplayName("信封里的签名长度不对：必须报错")
    void envelopeRejectsWrongSignatureLength() {
        String json = "{\"alg\":\"Ed25519\",\"keyId\":\"k\",\"sha256\":\""
                + "0".repeat(64) + "\",\"sig\":\""
                + Base64.getEncoder().encodeToString(new byte[32]) + "\"}";
        IOException e = assertThrows(IOException.class,
                () -> ModelPackageSignature.parseEnvelope(json.getBytes(StandardCharsets.UTF_8)),
                "Ed25519 签名是 64 字节，32 字节的必须被拒");
        assertTrue(e.getMessage().contains("64"), "错误消息要说清长度要求，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("信封里的 sha256 不是 64 位十六进制：必须报错")
    void envelopeRejectsBadDigest() {
        String json = "{\"alg\":\"Ed25519\",\"keyId\":\"k\",\"sha256\":\"不是十六进制\",\"sig\":\""
                + Base64.getEncoder().encodeToString(new byte[64]) + "\"}";
        assertThrows(IOException.class,
                () -> ModelPackageSignature.parseEnvelope(json.getBytes(StandardCharsets.UTF_8)),
                "摘要不是 64 位十六进制的信封必须被拒");
    }

    // ------------------------------------------------------------------ 辅助

    private Path file(String name, String content) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private static byte[] sha256(Path f) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
