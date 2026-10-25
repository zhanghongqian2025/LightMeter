package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ModelTrust}：哪些公钥算「发布方」。
 *
 * <p><b>这一层为什么必须有</b>：签名只回答「这份包是不是用某把私钥签的」，
 * 不回答「那把私钥是不是我们的」。没有信任锚，任何人自己生成一对密钥、
 * 自己签一个包，签名都验得过——但那包是我们不认的。
 *
 * <p><b>测的重点是「拒绝理由要说清卡在哪一环」</b>：
 * 用户拿到一个「装不上」，最有用的信息是「签名文件没找到」还是「这把钥匙我们不认」——
 * 这两件事用户的下一步动作完全不同（一个是「去拿签名文件」，
 * 一个是「去核对公钥」）。把六种拒绝混成一句「签名有问题」，
 * 用户就只能猜。
 *
 * <p><b>测试不碰真实用户目录</b>：信任锚文件的位置由系统属性决定
 * （{@link ModelTrust#ANCHORS_PROPERTY}），这里指到临时目录，
 * 免得测试往用户真实的信任锚里写东西。
 */
class ModelTrustTest {

    @TempDir
    Path tmp;

    private String savedProperty;
    private String savedBuiltinProperty;
    private Path anchorsFile;
    private Path builtinAnchorsFile;
    private KeyPair keys;

    @BeforeEach
    void setUp() throws Exception {
        savedProperty = System.getProperty(ModelTrust.ANCHORS_PROPERTY);
        savedBuiltinProperty = System.getProperty(ModelTrust.BUILTIN_PROPERTY);
        anchorsFile = tmp.resolve("trust").resolve("anchors.json");
        System.setProperty(ModelTrust.ANCHORS_PROPERTY, anchorsFile.toString());
        // 【2026-10-25 发布改动】内置信任锚**不再留空**了 ——
        //   发布方把自己的公钥填进了 src/main/resources/model-trust-anchors.json。
        //   而这一整类测试原来把「内置为空」当成**前提**（清空属性 = 读真实那份，
        //   那时候真实那份恰好是空的）。
        //   → 现在**显式**把内置指到一份空的锚文件：
        //     前提由测试自己控制，判据不再依赖「发布件里恰好填没填」。
        //     （顺带把一件事变明显：这些测试以前其实是在**读发布件**，
        //       发布件一改，整类测试就红 —— 而红的原因与被测逻辑无关。）
        //   「发布件里必须已填」这件事由新加的那条 shippedBuiltinAnchorsAreRegistered 单独守。
        builtinAnchorsFile = tmp.resolve("builtin-anchors.json");
        Files.writeString(builtinAnchorsFile, "{\"anchors\":[]}", StandardCharsets.UTF_8);
        System.setProperty(ModelTrust.BUILTIN_PROPERTY, builtinAnchorsFile.toString());
        keys = ModelPackageSignature.generateKeyPair();
    }

    @AfterEach
    void tearDown() {
        if (savedProperty == null) {
            System.clearProperty(ModelTrust.ANCHORS_PROPERTY);
        } else {
            System.setProperty(ModelTrust.ANCHORS_PROPERTY, savedProperty);
        }
        if (savedBuiltinProperty == null) {
            System.clearProperty(ModelTrust.BUILTIN_PROPERTY);
        } else {
            System.setProperty(ModelTrust.BUILTIN_PROPERTY, savedBuiltinProperty);
        }
    }

    // ------------------------------------------------------------------ 验签

    @Test
    @DisplayName("签过且受信任：装得上（不抛）")
    void acceptsTrustedSignature() throws Exception {
        Path zip = signedZip("模型包.zip", "内容", keys);
        trust(keys);

        ModelTrust.verifyPackage(zip);
    }

    @Test
    @DisplayName("没有签名文件：拒绝，并说清是缺签名文件")
    void rejectsMissingSignatureFile() throws Exception {
        Path zip = file("模型包.zip", "内容");
        trust(keys);

        IOException e = assertThrows(IOException.class, () -> ModelTrust.verifyPackage(zip),
                "没有签名文件的包必须被拒");
        assertTrue(e.getMessage().contains("没有签名文件"),
                "错误消息要说清缺的是签名文件，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("签名文件不是 JSON：拒绝，并说清是签名文件坏了")
    void rejectsBrokenSignatureFile() throws Exception {
        Path zip = file("模型包.zip", "内容");
        trust(keys);
        Files.writeString(ModelTrust.signatureFileFor(zip), "这不是 JSON", StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class, () -> ModelTrust.verifyPackage(zip),
                "签名文件坏了必须被拒");
        assertTrue(e.getMessage().contains("签名文件"),
                "错误消息要指向签名文件，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("算法不是 Ed25519：拒绝，并说清算法不对")
    void rejectsWrongAlgorithm() throws Exception {
        Path zip = file("模型包.zip", "内容");
        trust(keys);
        ModelPackageSignature.Envelope good = ModelPackageSignature.sign(
                zip, keys.getPrivate(), ModelPackageSignature.keyIdOf(keys.getPublic()));
        // 换成别的算法名：算法能降级，就不能拿来当信任依据。
        writeEnvelope(zip, new ModelPackageSignature.Envelope(
                "HmacSHA256", good.keyId(), good.sha256(), good.sig()));

        IOException e = assertThrows(IOException.class, () -> ModelTrust.verifyPackage(zip),
                "算法不对的签名必须被拒");
        assertTrue(e.getMessage().contains("算法"),
                "错误消息要说清是算法不对，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("签名覆盖的是另一个包：拒绝，并说清拿错包了")
    void rejectsSignatureForAnotherPackage() throws Exception {
        Path zip = file("模型包.zip", "内容");
        Path other = file("另一个包.zip", "别的内容");
        trust(keys);
        // 把「另一个包」的签名放到这个包旁边。
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(
                other, keys.getPrivate(), ModelPackageSignature.keyIdOf(keys.getPublic()));
        writeEnvelope(zip, env);

        IOException e = assertThrows(IOException.class, () -> ModelTrust.verifyPackage(zip),
                "签名对不上包的必须被拒");
        assertTrue(e.getMessage().contains("另一个包"),
                "错误消息要说清签的是另一个包，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("签名用的钥匙不被信任：拒绝，并说清这把钥匙不认")
    void rejectsUntrustedKey() throws Exception {
        Path zip = signedZip("模型包.zip", "内容", keys);
        // 信任的是**另一把**钥匙。
        trust(otherKeys());

        IOException e = assertThrows(IOException.class, () -> ModelTrust.verifyPackage(zip),
                "不被信任的钥匙签的包必须被拒");
        assertTrue(e.getMessage().contains("不被信任"),
                "错误消息要说清是「钥匙不认」，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("签名本体被改过：拒绝，并说清验签不过")
    void rejectsTamperedSignature() throws Exception {
        Path zip = file("模型包.zip", "内容");
        trust(keys);
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(
                zip, keys.getPrivate(), ModelPackageSignature.keyIdOf(keys.getPublic()));
        byte[] sig = env.sig();
        sig[0] = (byte) (sig[0] ^ 0xFF);
        writeEnvelope(zip, new ModelPackageSignature.Envelope(
                env.alg(), env.keyId(), env.sha256(), sig));

        IOException e = assertThrows(IOException.class, () -> ModelTrust.verifyPackage(zip),
                "签名本体被改过的必须被拒");
        assertTrue(e.getMessage().contains("验不过"),
                "错误消息要说清是验签不过，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("一把钥匙都没信任：拒绝，并告诉用户怎么加")
    void tellsUserHowToTrustWhenNoAnchors() throws Exception {
        Path zip = signedZip("模型包.zip", "内容", keys);
        // 故意不信任任何钥匙。

        IOException e = assertThrows(IOException.class, () -> ModelTrust.verifyPackage(zip),
                "没信任任何钥匙时必须被拒");
        assertTrue(e.getMessage().contains("信任"),
                "错误消息要提到「信任」，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("model trust add"),
                "错误消息要告诉用户下一步怎么做，实际: " + e.getMessage());
    }

    // ------------------------------------------------------------------ 信任锚文件

    @Test
    @DisplayName("信任锚文件不是 JSON：读的时候必须报错，不许当成空清单")
    void rejectsBrokenAnchorsFile() throws Exception {
        Files.createDirectories(anchorsFile.getParent());
        Files.writeString(anchorsFile, "这不是 JSON", StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class, ModelTrust::anchors,
                "坏掉的信任锚文件必须报错");
        assertTrue(e.getMessage().contains("JSON"),
                "错误消息要说清是 JSON 的问题，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("信任锚文件里没有 anchors 数组：必须报错")
    void rejectsAnchorsFileWithoutArray() throws Exception {
        Files.createDirectories(anchorsFile.getParent());
        Files.writeString(anchorsFile, "{\"别的\":1}", StandardCharsets.UTF_8);

        assertThrows(IOException.class, ModelTrust::anchors,
                "没有 anchors 数组的文件必须被拒");
    }

    @Test
    @DisplayName("信任锚条目缺 publicKey：必须报错")
    void rejectsAnchorWithoutPublicKey() throws Exception {
        Files.createDirectories(anchorsFile.getParent());
        Files.writeString(anchorsFile, "{\"anchors\":[{\"keyId\":\"k\"}]}", StandardCharsets.UTF_8);

        assertThrows(IOException.class, ModelTrust::anchors,
                "缺 publicKey 的条目必须被拒");
    }

    @Test
    @DisplayName("信任锚里的 keyId 与公钥对不上：必须报错（标识是派生的，不许手写）")
    void rejectsAnchorWithMismatchedKeyId() throws Exception {
        Files.createDirectories(anchorsFile.getParent());
        String b64 = ModelPackageSignature.publicKeyBase64(keys.getPublic());
        Files.writeString(anchorsFile,
                "{\"anchors\":[{\"keyId\":\"ed25519-000000000000\",\"publicKey\":\"" + b64 + "\"}]}",
                StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class, ModelTrust::anchors,
                "手写错的 keyId 必须被报出来，而不是静默忽略");
        assertTrue(e.getMessage().contains("必须一致"),
                "错误消息要说清两个标识必须一致，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("信任锚条目只写 publicKey（不写 keyId）：按公钥派生，能用")
    void acceptsAnchorWithoutKeyId() throws Exception {
        Files.createDirectories(anchorsFile.getParent());
        String b64 = ModelPackageSignature.publicKeyBase64(keys.getPublic());
        Files.writeString(anchorsFile,
                "{\"anchors\":[{\"publicKey\":\"" + b64 + "\"}]}", StandardCharsets.UTF_8);

        List<ModelPackageSignature.Anchor> anchors = ModelTrust.anchors();
        assertEquals(1, anchors.size(), "应当读出一条信任锚");
        assertEquals(ModelPackageSignature.keyIdOf(keys.getPublic()), anchors.get(0).keyId(),
                "标识应由公钥派生");
    }

    // 【2026-10-25 发布改动：这条守卫改了】
    //   原来这里断言「内置信任锚是空的」（发行版不替发布方托管密钥）。
    //   而这条测试**自己的注释**就写明了将来该怎么改：
    //     「如果将来发布方把公钥填进了内置信任锚，请把这条改成断言具体内容，
    //       别让『内置了哪些钥匙』变成一个没人盯着的状态。」
    //   → 2026-10-25 的 Windows 发布把发布方公钥填了进去，
    //     所以「空」这条设计决定**不再成立**，改成断言**具体内容**：
    //     内置的必须是下面这一把（keyId + 公钥逐字对上）。
    //   断言具体内容（而不是「至少一把」）是刻意的：
    //   换一把钥匙必须同步改这里 —— 否则「内置了哪些钥匙」就成了没人盯着的状态。
    @Test
    @DisplayName("发行版内置的是发布方自己的那把公钥（换钥匙必须同步改这条）")
    void builtinAnchorsAreTheReleaseKey() throws Exception {
        byte[] builtin = ModelTrust.class
                .getResourceAsStream(ModelTrust.BUILTIN_RESOURCE).readAllBytes();
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(builtin);
        var arr = root.get("anchors");
        assertNotNull(arr, "内置信任锚文件里必须有 anchors 数组");
        assertEquals(1, arr.size(),
                "发行版应当只内置**一把**发布方公钥；多一把就意味着有人往里塞过东西");
        String keyId = arr.get(0).path("keyId").asText(null);
        String publicKey = arr.get(0).path("publicKey").asText(null);
        assertEquals("ed25519-1eae73de27c5", keyId,
                "内置的发布方公钥标识变了 —— 换钥匙必须同步改这条断言与发布清单");
        assertNotNull(publicKey, "必须带公钥本体");
        // 标识必须与公钥对得上（标识由公钥派生）—— 这一条 ModelTrust 运行时也会查，
        // 这里再查一次是为了让「文件被人手改过」在**单测里**就暴露，而不是等到用户装模型时。
        assertEquals(keyId, ModelPackageSignature.keyIdOf(
                        ModelPackageSignature.publicKeyFromBase64(publicKey)),
                "内置锚里的 keyId 与它的公钥对不上");
    }

    // ------------------------------------------------------------------ 增删

    @Test
    @DisplayName("加一把公钥：返回派生的标识，文件里就一条")
    void addAnchorWritesDerivedKeyId() throws Exception {
        String keyId = ModelTrust.addAnchor(ModelPackageSignature.publicKeyBase64(keys.getPublic()));

        assertEquals(ModelPackageSignature.keyIdOf(keys.getPublic()), keyId,
                "返回的标识应是由公钥派生的那个");
        List<ModelPackageSignature.Anchor> anchors = ModelTrust.readAnchors(anchorsFile, "用户信任锚");
        assertEquals(1, anchors.size(), "应当只有一条");
        assertEquals(keyId, anchors.get(0).keyId(), "文件里的标识应与返回的一致");
    }

    @Test
    @DisplayName("同一把公钥加两次：文件里仍然只有一条")
    void addAnchorIsIdempotent() throws Exception {
        String b64 = ModelPackageSignature.publicKeyBase64(keys.getPublic());
        ModelTrust.addAnchor(b64);
        ModelTrust.addAnchor(b64);

        List<ModelPackageSignature.Anchor> anchors = ModelTrust.readAnchors(anchorsFile, "用户信任锚");
        assertEquals(1, anchors.size(), "重复加同一把钥匙不应产生第二条，实际: " + anchors.size());
    }

    @Test
    @DisplayName("加一把解析不了的公钥：必须报错，不许把坏公钥写进信任锚")
    void rejectsUnparsablePublicKey() {
        assertThrows(IOException.class, () -> ModelTrust.addAnchor("这不是 Base64!!"),
                "解析不了的公钥必须被拒");
        assertFalse(Files.exists(anchorsFile),
                "被拒的公钥不该留下任何文件");
    }

    @Test
    @DisplayName("删掉一把信任的公钥：删掉了；删不存在的：说没有")
    void removeAnchorReportsOutcome() throws Exception {
        String keyId = ModelTrust.addAnchor(ModelPackageSignature.publicKeyBase64(keys.getPublic()));

        assertTrue(ModelTrust.removeAnchor(keyId), "删掉存在的应返回 true");
        assertEquals(0, ModelTrust.readAnchors(anchorsFile, "用户信任锚").size(),
                "删完应当一条不剩");
        assertFalse(ModelTrust.removeAnchor(keyId), "删不存在的应返回 false");
        assertFalse(ModelTrust.removeAnchor("ed25519-000000000000"),
                "删一个从来没加过的应返回 false");
    }

    @Test
    @DisplayName("信任锚文件还不存在时：读锚不报错（那是常态，不是错）")
    void missingUserAnchorsFileIsNotAnError() throws Exception {
        assertFalse(Files.exists(anchorsFile), "前提：这个文件还不存在");
        List<ModelPackageSignature.Anchor> anchors = ModelTrust.anchors();
        assertNotNull(anchors, "应当返回列表（可能是空的）");
        assertEquals(0, anchors.size(), "内置为空 + 用户没有 → 一条都没有");
    }

    /**
     * 发布件里的内置信任锚<b>必须已填</b>。
     *
     * <p><b>为什么这条要单独守</b>：上面这一整类测试都用
     * {@link ModelTrust#BUILTIN_PROPERTY} 把内置锚换成一份<b>空的</b>文件，
     * 好让前提由测试自己控制（不再依赖「发布件里恰好填没填」）。
     * 代价是：「发布件里忘了填」这件事<b>没有判据在看</b>了 ——
     * 而它一旦漏掉，交付出去的包<b>一件模型都装不上</b>
     * （{@code model install} 会以「没有可信的密钥」拒绝）。
     * → 所以这里直接读**发布件里那一份**（classpath 资源），单独守它。
     */
    @Test
    @DisplayName("发布件里的内置信任锚必须已填，且每条的 keyId 与公钥对得上")
    void shippedBuiltinAnchorsAreRegistered() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (var in = ModelTrustTest.class.getResourceAsStream(ModelTrust.BUILTIN_RESOURCE)) {
            assertNotNull(in, "发布件里必须有内置信任锚文件：" + ModelTrust.BUILTIN_RESOURCE);
            JsonNode root = mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JsonNode arr = root.get("anchors");
            assertNotNull(arr, "内置信任锚文件里必须有 anchors 数组");
            assertTrue(arr.size() > 0,
                    "内置信任锚是空的 —— 发布的模型包一件都装不上"
                            + "（model install 会以「没有可信的密钥」拒绝）");
            for (JsonNode n : arr) {
                String pk = n.path("publicKey").asText(null);
                assertNotNull(pk, "每条锚都要有 publicKey");
                PublicKey key = ModelPackageSignature.publicKeyFromBase64(pk);
                String derived = ModelPackageSignature.keyIdOf(key);
                String written = n.path("keyId").asText(null);
                assertEquals(derived, written,
                        "锚里的 keyId 与它的公钥对不上（标识由公钥派生，对不上说明这份文件被人手改过）");
            }
        }
    }

    // ---------------------------------------------------------------- 去重与来源

    @Test
    @DisplayName("同一把公钥既在内置又在用户级：只算一把，且标出来源")
    void dedupesSameKeyInBuiltinAndUser() throws Exception {
        // 前提：内置锚（用属性覆盖指到临时文件）里放这把公钥。
        Path builtin = tmp.resolve("builtin-anchors.json");
        Files.createDirectories(builtin.getParent());
        Files.writeString(builtin,
                "{\"anchors\":[{\"publicKey\":\""
                        + ModelPackageSignature.publicKeyBase64(keys.getPublic()) + "\"}]}",
                StandardCharsets.UTF_8);
        System.setProperty(ModelTrust.BUILTIN_PROPERTY, builtin.toString());
        // 用户级也放同一把。
        trust(keys);

        List<ModelTrust.Anchored> anchored = ModelTrust.anchored();

        assertEquals(1, anchored.size(),
                "同一把公钥只能算一把（标识是派生的，标识相同就是同一把），实际: " + anchored.size());
        assertEquals("内置", anchored.get(0).source(),
                "两处都有时来源应标成内置（内置优先）");
    }

    @Test
    @DisplayName("用户级那把被删掉之后：内置里还有 → 仍然受信")
    void stillTrustedWhenBuiltinHasTheSameKey() throws Exception {
        Path builtin = tmp.resolve("builtin-anchors2.json");
        Files.createDirectories(builtin.getParent());
        Files.writeString(builtin,
                "{\"anchors\":[{\"publicKey\":\""
                        + ModelPackageSignature.publicKeyBase64(keys.getPublic()) + "\"}]}",
                StandardCharsets.UTF_8);
        System.setProperty(ModelTrust.BUILTIN_PROPERTY, builtin.toString());
        trust(keys);
        String keyId = ModelPackageSignature.keyIdOf(keys.getPublic());

        assertTrue(ModelTrust.removeAnchor(keyId), "用户级那把应当删得掉");
        assertTrue(ModelTrust.isTrusted(keyId),
                "内置锚里还有同一把 → 仍然受信（说「已不再信任」就是一句不实的陈述）");
    }

    @Test
    @DisplayName("用户级那把被删掉之后：内置里没有 → 真的不再受信")
    void noLongerTrustedWhenOnlyUserHadIt() throws Exception {
        trust(keys);
        String keyId = ModelPackageSignature.keyIdOf(keys.getPublic());

        assertTrue(ModelTrust.removeAnchor(keyId), "应当删得掉");
        assertFalse(ModelTrust.isTrusted(keyId), "两处都没有了 → 真的不再受信");
    }

    @Test
    @DisplayName("用户级公钥的来源标成「用户」")
    void reportsUserSource() throws Exception {
        trust(keys);
        List<ModelTrust.Anchored> anchored = ModelTrust.anchored();
        assertEquals(1, anchored.size());
        assertEquals("用户", anchored.get(0).source(), "只有用户级有 → 来源是用户");
    }

    @Test
    @DisplayName("信任锚文件坏了要加公钥：必须告诉用户下一步，不能只说「读不出来」")
    void addAnchorGivesNextStepWhenFileIsBroken() throws Exception {
        Files.createDirectories(anchorsFile.getParent());
        Files.writeString(anchorsFile, "这不是 JSON", StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class,
                () -> ModelTrust.addAnchor(ModelPackageSignature.publicKeyBase64(keys.getPublic())),
                "坏掉的文件必须被报出来");
        assertTrue(e.getMessage().contains(anchorsFile.toString()),
                "错误消息要给出文件路径（用户得知道去动哪个文件），实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("挪走") || e.getMessage().contains("修好"),
                "错误消息要给出下一步动作，实际: " + e.getMessage());
    }

    // ------------------------------------------------------------------ 辅助

    private void trust(KeyPair kp) throws IOException {
        ModelTrust.addAnchor(ModelPackageSignature.publicKeyBase64(kp.getPublic()));
    }

    private KeyPair otherKeys() throws Exception {
        return ModelPackageSignature.generateKeyPair();
    }

    private Path file(String name, String content) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    /** 造一个签过名的包（签名文件就放在包旁边）。 */
    private Path signedZip(String name, String content, KeyPair kp) throws Exception {
        Path zip = file(name, content);
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(
                zip, kp.getPrivate(), ModelPackageSignature.keyIdOf(kp.getPublic()));
        writeEnvelope(zip, env);
        return zip;
    }

    private void writeEnvelope(Path zip, ModelPackageSignature.Envelope env) throws IOException {
        Files.write(ModelTrust.signatureFileFor(zip), ModelPackageSignature.writeEnvelope(env));
    }
}
