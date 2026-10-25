package com.litemask.cli;

import com.litemask.ner.ModelPackageSignature;
import com.litemask.ner.ModelTrust;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code model} 子命令的输出里，建议给用户的命令必须用**这台机器上真的存在**的调用方式。
 *
 * <p><b>这里防的是一条真实存在的缺陷</b>：输出里写死了程序名 {@code litemask}，
 * 而可执行文件其实叫 {@code LightMeter}。用户照着输出敲，得到的是
 * {@code command not found}——工具指了一条走不通的路，比不说还糟。
 *
 * <p>另外还盖住 {@code model trust} 那组子命令的<b>用户可见行为</b>：
 * 加一把公钥要报出它的标识（标识是派生的，用户自己填不了），
 * 删一把不存在的钥匙要说「本来就没有」而不是报错。
 */
class ModelCommandOutputTest {

    private record Result(int code, String text) {
    }

    private static Result run(String prefix, String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int code = ModelCommand.run(args, prefix, ps, ps);
        return new Result(code, buf.toString(StandardCharsets.UTF_8));
    }

    /** 信任锚文件的位置由系统属性决定；这里指到临时目录，别写用户真实的信任锚。 */
    private String savedTrustProperty;
    private String savedBuiltinProperty;

    @BeforeEach
    void setUpTrustIsolation(@TempDir Path tmp) throws Exception {
        savedTrustProperty = System.getProperty(ModelTrust.ANCHORS_PROPERTY);
        savedBuiltinProperty = System.getProperty(ModelTrust.BUILTIN_PROPERTY);
        System.setProperty(ModelTrust.ANCHORS_PROPERTY,
                tmp.resolve("trust").resolve("anchors.json").toString());
        // 【2026-10-25 发布改动】内置信任锚**不再留空**了（发布方把自己的公钥填进去了）。
        //   这一类测试原来把「内置为空」当前提（清空属性 = 读真实那份，
        //   那时候真实那份恰好是空的）→ 发布件一填，「只有一把就要说一把」这类
        //   **按条数断言**的判据就红了，而红的原因与被测逻辑无关。
        //   → 显式把内置指到一份**空的**锚文件：前提由测试自己控制。
        //     「发布件里必须已填」由 ModelTrustTest.shippedBuiltinAnchorsAreRegistered 单独守。
        Path builtin = tmp.resolve("builtin-anchors.json");
        Files.writeString(builtin, "{\"anchors\":[]}", StandardCharsets.UTF_8);
        System.setProperty(ModelTrust.BUILTIN_PROPERTY, builtin.toString());
    }

    @AfterEach
    void tearDownTrustIsolation() {
        if (savedTrustProperty == null) {
            System.clearProperty(ModelTrust.ANCHORS_PROPERTY);
        } else {
            System.setProperty(ModelTrust.ANCHORS_PROPERTY, savedTrustProperty);
        }
        if (savedBuiltinProperty == null) {
            System.clearProperty(ModelTrust.BUILTIN_PROPERTY);
        } else {
            System.setProperty(ModelTrust.BUILTIN_PROPERTY, savedBuiltinProperty);
        }
    }

    @Test
    @DisplayName("建议装模型包时用的是实际调用前缀，不是写死的名字")
    void suggestionUsesActualPrefix(@TempDir Path tmp) {
        // 前提：那个目录**必须不存在**。
        // list() 看到目录存在就把它当成「已生效的模型包」，于是走不到「装一份」那一支——
        // 前提不成立时这条断言就是在测别的东西（第一版就踩了这个坑）。
        Path store = tmp.resolve("ner");
        Result r = run("LightMeter", "list", "--to", store.toString());
        assertEquals(0, r.code(), "输出：" + r.text());
        assertTrue(r.text().contains("还没有装任何模型包"),
                "前提不成立：那个目录应该不存在。实际输出：" + r.text());
        assertTrue(r.text().contains("LightMeter model install"),
                "建议里应当带上实际前缀。实际输出：" + r.text());
    }

    @Test
    @DisplayName("输出里不再出现那个这台机器上并不存在的程序名")
    void noHardcodedProgramName(@TempDir Path tmp) {
        Path store = tmp.resolve("ner");
        Result r = run("LightMeter", "list", "--to", store.toString());
        assertFalse(r.text().contains("litemask model"),
                "不该再出现写死的名字。实际输出：" + r.text());
    }

    @Test
    @DisplayName("取不到前缀时退回只写子命令：宁可少写，也不写一个不存在的名字")
    void fallsBackToSubcommandOnly(@TempDir Path tmp) {
        Path store = tmp.resolve("ner");
        Result r = run("", "list", "--to", store.toString());
        assertEquals(0, r.code(), "输出：" + r.text());
        assertTrue(r.text().contains("model install"),
                "至少还要给出子命令。实际输出：" + r.text());
        assertFalse(r.text().contains("litemask model"),
                "不该出现写死的名字。实际输出：" + r.text());
    }

    @Test
    @DisplayName("用法说明里只写子命令（不掺程序名），保持与主用法一致的写法")
    void usageUsesSubcommandOnly() {
        Result r = run("LightMeter", "verify");
        assertEquals(2, r.code(), "参数不对应当退出码 2。输出：" + r.text());
        assertTrue(r.text().contains("用法: model verify"),
                "用法说明应当是子命令形式。实际输出：" + r.text());
        assertFalse(r.text().contains("litemask"),
                "用法说明里不该出现写死的名字。实际输出：" + r.text());
    }

    // ---------------------------------------------------------------- 信任锚

    @Test
    @DisplayName("trust add：报出这把公钥的标识（标识是派生的，用户自己填不了）")
    void trustAddReportsDerivedKeyId(@TempDir Path tmp) throws Exception {
        Path pem = tmp.resolve("pub.pem");
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        Files.writeString(pem, ModelPackageSignature.publicKeyToPem(kp.getPublic()),
                StandardCharsets.UTF_8);
        String expected = ModelPackageSignature.keyIdOf(kp.getPublic());

        Result r = run("LightMeter", "trust", "add", pem.toString());

        assertEquals(0, r.code(), "输出：" + r.text());
        assertTrue(r.text().contains(expected),
                "应当报出派生的标识 " + expected + "。实际输出：" + r.text());
        assertTrue(r.text().contains("带外"),
                "加公钥是件要谨慎的事，输出应当提醒核对来源。实际输出：" + r.text());
    }

    @Test
    @DisplayName("trust list：列出刚加进去的那把公钥")
    void trustListShowsAddedKey(@TempDir Path tmp) throws Exception {
        Path pem = tmp.resolve("pub.pem");
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        Files.writeString(pem, ModelPackageSignature.publicKeyToPem(kp.getPublic()),
                StandardCharsets.UTF_8);
        run("LightMeter", "trust", "add", pem.toString());
        String expected = ModelPackageSignature.keyIdOf(kp.getPublic());

        Result r = run("LightMeter", "trust", "list");

        assertEquals(0, r.code(), "输出：" + r.text());
        assertTrue(r.text().contains(expected),
                "应当列出 " + expected + "。实际输出：" + r.text());
    }

    @Test
    @DisplayName("trust remove：删掉一把不存在的钥匙要说「本来就没有」，而不是报错")
    void trustRemoveUnknownKeyIsNotAnError(@TempDir Path tmp) {
        Result r = run("LightMeter", "trust", "remove", "ed25519-000000000000");

        assertEquals(0, r.code(), "删一个没有的不该是错误。输出：" + r.text());
        assertTrue(r.text().contains("本来就没有"),
                "应当说清本来就没有。实际输出：" + r.text());
    }

    @Test
    @DisplayName("trust list：报去重后的条数，并标出每一条的来源")
    void trustListReportsTrueCountAndSource(@TempDir Path tmp) throws Exception {
        Path pem = tmp.resolve("pub.pem");
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        Files.writeString(pem, ModelPackageSignature.publicKeyToPem(kp.getPublic()),
                StandardCharsets.UTF_8);
        run("LightMeter", "trust", "add", pem.toString());
        String expected = ModelPackageSignature.keyIdOf(kp.getPublic());

        Result r = run("LightMeter", "trust", "list");

        assertEquals(0, r.code(), "输出：" + r.text());
        assertTrue(r.text().contains("已信任 1 把公钥"),
                "只有一把就要说一把（说多了就是错的），实际输出：" + r.text());
        assertTrue(r.text().contains(expected + "（用户）"),
                "应当标出来源，实际输出：" + r.text());
    }

    @Test
    @DisplayName("trust remove：钥匙还在内置锚里时，不能说「已不再信任」")
    void trustRemoveDoesNotClaimFalseDistrust(@TempDir Path tmp) throws Exception {
        Path pem = tmp.resolve("pub.pem");
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        Files.writeString(pem, ModelPackageSignature.publicKeyToPem(kp.getPublic()),
                StandardCharsets.UTF_8);
        String expected = ModelPackageSignature.keyIdOf(kp.getPublic());

        // 内置锚里也放同一把（用属性覆盖指到临时文件）。
        Path builtin = tmp.resolve("builtin.json");
        Files.writeString(builtin,
                "{\"anchors\":[{\"publicKey\":\""
                        + ModelPackageSignature.publicKeyBase64(kp.getPublic()) + "\"}]}",
                StandardCharsets.UTF_8);
        System.setProperty(ModelTrust.BUILTIN_PROPERTY, builtin.toString());

        run("LightMeter", "trust", "add", pem.toString());
        Result r = run("LightMeter", "trust", "remove", expected);

        assertEquals(0, r.code(), "输出：" + r.text());
        assertFalse(r.text().contains("已不再信任"),
                "它还在内置锚里，说「已不再信任」是不实的。实际输出：" + r.text());
        assertTrue(r.text().contains("内置"),
                "必须说清它仍在内置信任锚里。实际输出：" + r.text());
    }

    @Test
    @DisplayName("--allow-unsigned 只有 install 认：别的子命令一律拒掉")
    void onlyInstallAcceptsAllowUnsigned(@TempDir Path tmp) {
        Path store = tmp.resolve("ner");
        for (String[] args : List.of(
                new String[]{"list", "--to", store.toString(), "--allow-unsigned"},
                new String[]{"rollback", "--to", store.toString(), "--allow-unsigned"},
                new String[]{"remove", "x", "--to", store.toString(), "--allow-unsigned"})) {
            Result r = run("LightMeter", args);
            assertEquals(2, r.code(), "参数：" + String.join(" ", args) + " 输出：" + r.text());
            assertTrue(r.text().contains("--allow-unsigned"),
                    "错误消息要说清是哪个参数不被接受。实际输出：" + r.text());
        }
    }

    @Test
    @DisplayName("主用法里要写清「模型包必须签名」与逃生门")
    void usageExplainsSignatureRequirement() {
        Result r = run("LightMeter", "trust", "--help");

        assertEquals(0, r.code(), "输出：" + r.text());
        assertTrue(r.text().contains("model trust add"),
                "用法里应当有 trust add。实际输出：" + r.text());
        assertTrue(r.text().contains("不用手写"),
                "应当说清密钥标识不用手写。实际输出：" + r.text());
    }
}
