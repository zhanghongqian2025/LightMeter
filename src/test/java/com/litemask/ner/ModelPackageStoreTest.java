package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ModelPackageStore}：模型包的多版本仓库（装、列、回退、删）。
 *
 * <p><b>这一块为什么值得单独测</b>：版本共存不是为了「功能多」，
 * 是因为模型包一旦没了，<b>中文人名与机构名就不会被脱敏</b>——
 * 那是一份没脱干净的文档。所以「装新版本」必须可逆。
 * 可逆的代价是<b>多了一处状态</b>：生效的那一份 + 历史里的若干份。
 * 于是要盯住的是那些「状态错了但不会报错」的情形：
 * 旧版本没被留下、回退切错了份、删版本删到了别的东西上。
 *
 * <p><b>标尺独立于被测实现</b>：用的全是真玩具模型包 + 真加载闸门
 * （{@link NerModelFixtures}），期望值是手算出来的
 * （哪个 id 对应哪一版、哪个目录里该有什么），不读被测实现的内部状态。
 */
class ModelPackageStoreTest {

    @TempDir
    Path tmp;

    /** 生效目录。它旁边会有一个同名的 {@code .versions} 目录放历史版本。 */
    private Path active;

    /** 造包用的计数器：临时目录名不能由不受信任的 id 拼出来。 */
    private final AtomicInteger seq = new AtomicInteger();

    /** 用来给玩具模型包签名的密钥（这一组测试走**真**验签，不打桩）。 */
    private KeyPair signingKey;

    /** 信任锚文件的位置由系统属性决定；这里指到临时目录，别写用户真实的信任锚。 */
    private String savedTrustProperty;

    @BeforeEach
    void setUp() throws Exception {
        active = tmp.resolve("models").resolve("ner");
        signingKey = ModelPackageSignature.generateKeyPair();
        savedTrustProperty = System.getProperty(ModelTrust.ANCHORS_PROPERTY);
        Path anchors = tmp.resolve("trust").resolve("anchors.json");
        System.setProperty(ModelTrust.ANCHORS_PROPERTY, anchors.toString());
        ModelTrust.addAnchor(ModelPackageSignature.publicKeyBase64(signingKey.getPublic()));
    }

    @AfterEach
    void tearDown() {
        if (savedTrustProperty == null) {
            System.clearProperty(ModelTrust.ANCHORS_PROPERTY);
        } else {
            System.setProperty(ModelTrust.ANCHORS_PROPERTY, savedTrustProperty);
        }
    }

    // ------------------------------------------------------------------ 装

    @Test
    @DisplayName("装第一份：它就是当前生效的那一份，还没有历史")
    void installsFirstVersion() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);

        ModelPackageStore.InstallResult r = store.install(toyZip("模型-v1"));

        assertEquals("模型-v1", r.modelId(), "报出的模型 id 应来自清单");
        assertNull(r.previousKey(), "第一份之前没有旧版本可保留");
        assertTrue(Files.isRegularFile(active.resolve("ner.onnx")), "模型应装在生效目录里");
        assertTrue(Files.isRegularFile(active.resolve("manifest.json")), "清单应装在生效目录里");
        assertEquals(List.of("manifest.json", "ner.onnx", "vocab.txt"), r.files(),
                "装进去的文件应不多不少");

        List<ModelPackageStore.Version> versions = store.list();
        assertEquals(1, versions.size(), "只有一个版本");
        assertTrue(versions.get(0).active(), "它应该是当前生效的那一份");
        assertEquals("模型-v1", versions.get(0).modelId());
    }

    @Test
    @DisplayName("装第二份：旧版本被保留进历史，内容真的还在")
    void keepsPreviousVersionInHistory() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        ModelPackageStore.InstallResult first = store.install(toyZip("模型-v1"));
        ModelPackageStore.InstallResult second = store.install(toyZip("模型-v2"));

        assertNotEquals(first.key(), second.key(), "两版内容不同，版本键必须不同");
        assertEquals(first.key(), second.previousKey(), "旧版本的键要报出来，用户才知道怎么回退");

        List<ModelPackageStore.Version> versions = store.list();
        assertEquals(2, versions.size(), "当前一份 + 历史一份");
        assertTrue(versions.get(0).active(), "第一条应是当前生效的");
        assertEquals("模型-v2", versions.get(0).modelId());
        assertEquals("模型-v1", versions.get(1).modelId());

        Path kept = store.versionsDir().resolve(first.key());
        assertTrue(Files.isRegularFile(kept.resolve("ner.onnx")),
                "旧版本的内容必须真的还在（能回退的前提），实际目录: " + kept);
        assertEquals("模型-v1", ModelPackageStore.manifestId(kept),
                "历史目录里的清单要能读出 id");
    }

    @Test
    @DisplayName("重复装同一份：历史里不出现重复项")
    void reinstallSameContentDoesNotDuplicateHistory() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        ModelPackageStore.InstallResult first = store.install(toyZip("模型-v1"));
        store.install(toyZip("模型-v2"));
        store.install(toyZip("模型-v1"));

        List<ModelPackageStore.Version> versions = store.list();
        assertEquals(2, versions.size(), "当前一份 + 历史一份，重复装不该多出条目，实际: " + versions);
        assertTrue(versions.get(0).active(), "重复装的那一份变成当前生效的");
        assertEquals("模型-v1", versions.get(0).modelId());
        assertEquals(first.key(), versions.get(0).key(),
                "同一份内容 → 同一个键（内容一样 → 摘要一样）");
        assertEquals("模型-v2", versions.get(1).modelId(), "被换下来的那一版留在历史里");
    }

    @Test
    @DisplayName("版本键是内容寻址的：同样内容→同样键；改一个字节→不同的键")
    void versionKeyIsContentAddressed() throws Exception {
        Path a = toyPackageDir("同一份");
        Path b = toyPackageDir("同一份");
        assertEquals(ModelPackageStore.keyOf(a), ModelPackageStore.keyOf(b),
                "两份内容完全一样的目录必须得到同一个键");

        Files.writeString(a.resolve("vocab.txt"), "改了一个字节", StandardCharsets.UTF_8);
        assertNotEquals(ModelPackageStore.keyOf(a), ModelPackageStore.keyOf(b),
                "内容变了，键必须跟着变（否则两版会互相覆盖）");
    }

    @Test
    @DisplayName("不受信任的 id 洗成安全目录名：不含路径分隔符，也造不出 ..")
    void sanitizesUntrustedModelId() {
        assertEquals("model", ModelPackageStore.sanitize(null), "没有 id 时用占位名");
        assertEquals("model", ModelPackageStore.sanitize(".."), ".. 单独出现时要换掉");
        assertEquals("model", ModelPackageStore.sanitize("."), ". 单独出现时要换掉");
        String s = ModelPackageStore.sanitize("../../etc/passwd");
        assertFalse(s.contains("/"), "洗白后不能有路径分隔符，实际: " + s);
        assertFalse(s.contains("\\"), "洗白后不能有反斜杠，实际: " + s);
        // 「名字里含 ..」本身不危险——危险的只有「整个名字就是 ..」。
        // 判据是「拼出来的路径还在目标目录里」，不是「字符串里有没有 ..」。
        assertFalse(".".equals(s) || "..".equals(s), "不能整个名字就是 . 或 ..，实际: " + s);
        assertTrue(ModelPackageStore.sanitize("A-Za-z0-9._-").equals("A-Za-z0-9._-"),
                "本来就安全的字符不该被改");
    }

    @Test
    @DisplayName("id 里带路径穿越：建出来的目录仍在历史目录里")
    void untrustedIdCannotEscapeVersionsDir() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        ModelPackageStore.InstallResult r1 = store.install(toyZip("../../逃出去"));
        // 装第二份，把第一份换下来——历史目录里的名字才是由不受信任的 id 决定的。
        store.install(toyZip("模型-v2"));

        assertFalse(r1.key().contains("/"), "版本键里不能有路径分隔符，实际: " + r1.key());
        Path created = store.versionsDir().resolve(r1.key());
        assertTrue(created.normalize().startsWith(store.versionsDir()),
                "版本目录必须还在历史目录里，实际: " + created);
        assertTrue(Files.isDirectory(created), "版本目录应真的建在那儿，实际: " + created);
        assertTrue(Files.isDirectory(active), "生效目录位置不受 id 影响");
    }

    // -------------------------------------------------------------- 回退

    @Test
    @DisplayName("回退：换到旧版本，新的那版进历史")
    void rollbackSwapsVersions() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));
        store.install(toyZip("模型-v2"));

        ModelPackageStore.RollbackResult rb = store.rollback(null);

        assertEquals("模型-v1", rb.modelId(), "不带参数应回退到最近换下来的那一版");
        assertEquals("模型-v1", ModelPackageStore.manifestId(active),
                "生效目录里现在应该是 v1 的清单");

        List<ModelPackageStore.Version> versions = store.list();
        assertEquals(2, versions.size(), "回退后仍是两份（一份生效一份保留）");
        assertEquals("模型-v1", versions.get(0).modelId());
        assertEquals("模型-v2", versions.get(1).modelId());
    }

    @Test
    @DisplayName("回退（不带参数）回到最近换下来的那一版，不是最旧的那版")
    void rollbackWithoutArgumentGoesToMostRecentlyReplaced() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));
        store.install(toyZip("模型-v2"));
        store.install(toyZip("模型-v3"));

        ModelPackageStore.RollbackResult rb = store.rollback(null);

        assertEquals("模型-v2", rb.modelId(),
                "最近换下来的是 v2（装 v3 时把 v2 换了下来），不是最旧的 v1");
        List<ModelPackageStore.Version> versions = store.list();
        assertEquals("模型-v2", versions.get(0).modelId(), "当前生效的应是 v2");
        assertEquals("模型-v3", versions.get(1).modelId(), "被换下来的 v3 留在历史里");
        assertEquals("模型-v1", versions.get(2).modelId(), "v1 仍在历史里");
    }

    @Test
    @DisplayName("回退到指定版本：可以用摘要那一段来指认")
    void rollbackToNamedVersion() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        ModelPackageStore.InstallResult v1 = store.install(toyZip("模型-v1"));
        store.install(toyZip("模型-v2"));
        store.install(toyZip("模型-v3"));

        String digestOfV1 = v1.key().substring(v1.key().lastIndexOf('-') + 1);
        ModelPackageStore.RollbackResult rb = store.rollback(digestOfV1);

        assertEquals("模型-v1", rb.modelId(), "按摘要回退应切到 v1");
        assertEquals("模型-v1", ModelPackageStore.manifestId(active));
    }

    @Test
    @DisplayName("回退到不存在的版本：明确失败，而且当前版本一点没动")
    void rollbackToUnknownVersionFails() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));
        store.install(toyZip("模型-v2"));

        IOException e = assertThrows(IOException.class, () -> store.rollback("不存在的版本"));
        assertTrue(e.getMessage().contains("没有这个版本"),
                "错误消息要说清是「没有这个版本」，实际: " + e.getMessage());
        assertEquals("模型-v2", ModelPackageStore.manifestId(active),
                "失败之后当前生效的那份必须原封不动");
    }

    @Test
    @DisplayName("回退到当前已经生效的版本：说清「不用回退」，而不是「没有这个版本」")
    void rollbackToAlreadyActiveVersion() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));
        store.install(toyZip("模型-v2"));
        String activeKey = store.list().get(0).key();

        IOException e = assertThrows(IOException.class, () -> store.rollback(activeKey));
        assertTrue(e.getMessage().contains("已经是当前生效的版本"),
                "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("没有历史时回退：说清为什么不能回退")
    void rollbackWithoutHistoryExplains() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));

        IOException e = assertThrows(IOException.class, () -> store.rollback(null));
        assertTrue(e.getMessage().contains("没有可回退的历史版本"), "实际: " + e.getMessage());
    }

    // ---------------------------------------------------------------- 删

    @Test
    @DisplayName("删历史版本：删掉了，且当前生效的那份不受影响")
    void removesHistoryVersion() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        ModelPackageStore.InstallResult v1 = store.install(toyZip("模型-v1"));
        store.install(toyZip("模型-v2"));
        Path receipt = receiptZip(v1.key());
        assertTrue(Files.isRegularFile(receipt));
        assertTrue(Files.isRegularFile(ModelTrust.signatureFileFor(receipt)));

        store.remove(v1.key());

        List<ModelPackageStore.Version> versions = store.list();
        assertEquals(1, versions.size(), "历史里那份被删掉了，实际还剩: " + versions);
        assertTrue(versions.get(0).active());
        assertEquals("模型-v2", versions.get(0).modelId());
        assertTrue(Files.isRegularFile(active.resolve("ner.onnx")), "当前生效的那份不能被动");
        assertFalse(Files.exists(receipt), "删版本时也要清掉对应发布包收据");
        assertFalse(Files.exists(ModelTrust.signatureFileFor(receipt)), "删版本时也要清掉对应签名收据");
    }

    @Test
    @DisplayName("删当前生效的那一份：等于卸载模型（工具退回只做正则）")
    void removesActiveVersion() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        ModelPackageStore.InstallResult v1 = store.install(toyZip("模型-v1"));
        Path receipt = receiptZip(v1.key());

        store.remove(v1.key());

        assertFalse(Files.exists(active), "生效目录被删掉了（= 卸载了模型包）");
        assertEquals(0, store.list().size(), "一个版本都不剩");
        assertFalse(Files.exists(receipt), "卸载模型时也要清掉对应发布包收据");
        assertFalse(Files.exists(ModelTrust.signatureFileFor(receipt)), "卸载模型时也要清掉对应签名收据");
    }

    @Test
    @DisplayName("删不存在的版本：明确失败，不许静默成功")
    void removeUnknownVersionFails() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));

        IOException e = assertThrows(IOException.class, () -> store.remove("根本没有这个版本"));
        assertTrue(e.getMessage().contains("没有这个版本"), "实际: " + e.getMessage());
        assertTrue(Files.isDirectory(active), "失败之后当前生效的那份必须还在");
    }

    @Test
    @DisplayName("版本名里带路径分隔符：一律拒绝，不拿它拼路径")
    void rejectsPathTraversalInVersionName() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));
        Path canary = tmp.resolve("不能被删掉的东西");
        Files.writeString(canary, "还在", StandardCharsets.UTF_8);

        for (String bad : List.of("../../不能被删掉的东西", "..", "/etc", "a/b", "a\\b")) {
            IOException e = assertThrows(IOException.class, () -> store.remove(bad),
                    "版本名「" + bad + "」必须被拒");
            assertTrue(e.getMessage().contains("没有这个版本")
                            || e.getMessage().contains("路径分隔符"),
                    "错误消息要说清为什么，实际: " + e.getMessage());
        }
        assertTrue(Files.exists(canary), "任何东西都不能被这些输入删掉");
        assertTrue(Files.isDirectory(active), "当前生效的那份必须还在");
    }

    // ---------------------------------------------------------------- 信任

    @Test
    @DisplayName("没签名的模型包装不上，而且磁盘上什么都没多出来")
    void rejectsUnsignedPackage() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        Path unsigned = NerModelFixtures.zipDirectory(
                toyPackageDir("模型-v1"), "manifest.json", "ner.onnx", "vocab.txt");

        IOException e = assertThrows(IOException.class, () -> store.install(unsigned),
                "没签名的包必须装不上");
        assertTrue(e.getMessage().contains("没有签名文件"),
                "错误消息要说清缺的是签名文件，实际: " + e.getMessage());
        assertFalse(Files.exists(active), "装不上时生效目录不该被创建，实际: " + active);
        assertFalse(Files.exists(store.versionsDir()),
                "装不上时历史目录不该被创建，实际: " + store.versionsDir());
    }

    @Test
    @DisplayName("别的钥匙签的包装不上（签名过了，但不是我们信任的那把）")
    void rejectsPackageSignedByUntrustedKey() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        KeyPair stranger = ModelPackageSignature.generateKeyPair();
        Path zip = NerModelFixtures.zipDirectory(
                toyPackageDir("模型-v1"), "manifest.json", "ner.onnx", "vocab.txt");
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(
                zip, stranger.getPrivate(), ModelPackageSignature.keyIdOf(stranger.getPublic()));
        Files.write(ModelTrust.signatureFileFor(zip), ModelPackageSignature.writeEnvelope(env));

        IOException e = assertThrows(IOException.class, () -> store.install(zip),
                "不被信任的钥匙签的包必须装不上");
        assertTrue(e.getMessage().contains("不被信任"),
                "错误消息要说清是钥匙不认，实际: " + e.getMessage());
        assertFalse(Files.exists(active), "装不上时生效目录不该被创建");
    }

    @Test
    @DisplayName("签完名之后包被改过：装不上")
    void rejectsPackageModifiedAfterSigning() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        Path zip = toyZip("模型-v1");

        // 签完名再改包里的一个字节：签名覆盖的是改之前的那个包。
        byte[] bytes = Files.readAllBytes(zip);
        bytes[bytes.length / 2] = (byte) (bytes[bytes.length / 2] ^ 0xFF);
        Files.write(zip, bytes);

        IOException e = assertThrows(IOException.class, () -> store.install(zip),
                "签完名被改过的包必须装不上");
        assertTrue(e.getMessage().contains("另一个包"),
                "错误消息要说清签的是另一个包，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("验签不过时：当前生效的那一份没被动过")
    void trustFailureLeavesCurrentVersionUntouched() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));

        Path unsigned = NerModelFixtures.zipDirectory(
                toyPackageDir("模型-v2"), "manifest.json", "ner.onnx", "vocab.txt");
        assertThrows(IOException.class, () -> store.install(unsigned), "没签名的包必须装不上");

        assertEquals("模型-v1", ModelPackageStore.manifestId(active),
                "验签不过时当前生效的那一份必须原封不动");
        assertEquals(1, store.list().size(), "验签不过不该在历史里留下东西");
    }

    @Test
    @DisplayName("--allow-unsigned：能装上，但必须有一条说清「没验签」的提醒")
    void allowUnsignedInstallsButWarns() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        Path unsigned = NerModelFixtures.zipDirectory(
                toyPackageDir("模型-v1"), "manifest.json", "ner.onnx", "vocab.txt");

        ModelPackageStore.InstallResult r = store.install(unsigned, true);

        assertEquals("模型-v1", r.modelId(), "强装应当成功");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("没有验签")),
                "跳过验签必须明确提醒用户，实际提醒: " + r.warnings());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("漏脱敏")),
                "提醒里要说清漏脱敏的后果，实际提醒: " + r.warnings());
    }

    // -------------------------------------------------------- 失败不许毁东西

    @Test
    @DisplayName("装失败（校验不过）：当前版本没动，历史也没多出东西")
    void failedValidationChangesNothing() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));

        Path broken = toyPackageDir("模型-v2");
        Path badZip = NerModelFixtures.zipDirectoryWithCorruptedModel(broken);
        // 这个包是**故意坏的**（模型字节对不上清单里的摘要）。
        // 但它仍然要签名——不然它会在验签那一步就停住，
        // 根本走不到「摘要对不上」这一关，测试就变成了在测另一件事。
        signZip(badZip);

        IOException e = assertThrows(IOException.class, () -> store.install(badZip),
                "摘要对不上的包必须装不上");
        assertTrue(e.getMessage().contains("摘要"),
                "失败原因应当是摘要对不上，而不是验签，实际: " + e.getMessage());
        assertEquals("模型-v1", ModelPackageStore.manifestId(active),
                "装失败之后当前生效的那份必须原封不动");
        assertEquals(1, store.list().size(), "装失败不该在历史里留下东西");
    }

    @Test
    @DisplayName("换装失败（搬不动）：旧版本被放回原位，磁盘上不留新版本")
    void failedSwapRestoresPreviousVersion() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));

        // 只让「搬进生效目录」失败一次：换装失败后要把旧的放回去，那一次要成功。
        FailingMover mover = new FailingMover(active, false);
        ModelPackageStore store2 = new ModelPackageStore(active,
                ModelPackageInstaller::verifyWithModelLoader, mover);

        assertThrows(IOException.class, () -> store2.install(toyZip("模型-v2")),
                "搬不动必须明确失败");
        assertEquals("模型-v1", ModelPackageStore.manifestId(active),
                "换装失败后旧版本必须被放回原位");
        assertEquals(1, store.list().size(),
                "装失败不该在历史里留下新版本（装失败应等于「什么都没变」）");
    }

    @Test
    @DisplayName("连放回都失败：异常消息里必须写清旧版本现在在哪")
    void failedRestoreTellsWhereTheOldVersionIs() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));

        FailingMover mover = new FailingMover(active, true);
        ModelPackageStore store2 = new ModelPackageStore(active,
                ModelPackageInstaller::verifyWithModelLoader, mover);

        IOException e = assertThrows(IOException.class, () -> store2.install(toyZip("模型-v2")));
        assertTrue(e.getMessage().contains("没能放回原位"),
                "放不回原位是必须让用户知道的事，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains(store.versionsDir().toString()),
                "消息里要给出旧版本现在的位置，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("回退失败：当前版本被放回原位")
    void failedRollbackRestoresCurrentVersion() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        store.install(toyZip("模型-v1"));
        store.install(toyZip("模型-v2"));

        FailingMover mover = new FailingMover(active, false);
        ModelPackageStore store2 = new ModelPackageStore(active,
                ModelPackageInstaller::verifyWithModelLoader, mover);

        assertThrows(IOException.class, () -> store2.rollback(null),
                "搬不动必须明确失败");
        assertEquals("模型-v2", ModelPackageStore.manifestId(active),
                "回退失败后当前生效的那份必须原封不动");
    }

    // ---------------------------------------------------------------- 并发

    @Test
    @DisplayName("并发：第二个装模型包的动作明确失败，且没把第一个的弄坏")
    void secondConcurrentInstallFailsCleanly() throws Exception {
        CountDownLatch insideValidator = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelPackageInstaller.Validator slow = dir -> {
            insideValidator.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "慢模型";
        };
        ModelPackageStore first = new ModelPackageStore(active, slow, Files::move);
        ModelPackageStore second = new ModelPackageStore(active, slow, Files::move);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> f1 = pool.submit(() -> {
            first.install(toyZip("模型-v1"));
            return null;
        });
        try {
            assertTrue(insideValidator.await(20, TimeUnit.SECONDS),
                    "前提：第一个装模型包的动作应该已经拿到锁并进入校验");
            Future<?> f2 = pool.submit(() -> {
                second.install(toyZip("模型-v2"));
                return null;
            });
            ExecutionException ex = assertThrows(ExecutionException.class, () -> f2.get(20, TimeUnit.SECONDS));
            IOException cause = assertInstanceOf(IOException.class, ex.getCause(),
                    "第二个动作应该以 IOException 失败");
            assertTrue(cause.getMessage().contains("另一个"),
                    "错误消息要说清是「另一个动作在进行」，实际: " + cause.getMessage());
        } finally {
            // 必须等第一个动作**真的结束**：它还占着文件（和锁），
            // 而 JUnit 删临时目录是在方法返回之后——那时候文件还开着就删不掉。
            release.countDown();
            try {
                f1.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 收尾阶段的异常不该盖掉上面已经断言过的失败原因。
            }
            pool.shutdownNow();
            pool.awaitTermination(20, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------- 上限

    @Test
    @DisplayName("历史版本有上限：超出时删最旧的，并且说出来")
    void prunesHistoryBeyondLimit() throws Exception {
        ModelPackageStore store = new ModelPackageStore(active);
        ModelPackageStore.InstallResult last = null;
        for (int i = 0; i < ModelPackageStore.MAX_HISTORY + 2; i++) {
            last = store.install(toyZip("模型-v" + i));
        }
        assertNotNull(last);
        List<ModelPackageStore.Version> versions = store.list();
        assertEquals(ModelPackageStore.MAX_HISTORY + 1, versions.size(),
                "当前一份 + 上限份历史，实际: " + versions.size());
        assertTrue(last.warnings().stream().anyMatch(w -> w.contains("历史版本太多")),
                "删了东西就要说出来，实际提醒: " + last.warnings());
    }

    @Test
    @DisplayName("仓库的公开常量没变（挡住无意中的 API 漂移）")
    void publicApiIsStable() {
        assertEquals(8, ModelPackageStore.MAX_HISTORY, "历史版本上限");
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 造一个可加载的玩具模型包目录（清单里的 id 可指定）。
     */
    private Path toyPackageDir(String id) throws IOException {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> manifest = NerModelFixtures.manifest(model, vocab, false);
        manifest.put("id", id);
        Path dir = tmp.resolve("造包-" + seq.incrementAndGet());
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        NerModelFixtures.writeManifest(dir, manifest);
        return dir;
    }

    /**
     * 造一个可加载的玩具模型包并打成 zip，<b>顺便签上名</b>。
     *
     * <p><b>为什么签名不另写一条测试，而是每个包都签</b>：
     * 验签是装模型包的<b>第一道</b>关卡，不签名的包根本走不到校验那一步。
     * 若这里的包不签名，这一整组测试会在验签处就停住，
     * 后面关于「旧版本有没有留下」「回退切对没有」的那些断言就全成了摆设。
     * 所以这里让每个包都走一遍**真**验签（不注入假的 Checker），
     * 顺带保证「验签通过之后，别的断言才真的被执行过」。
     */
    private Path toyZip(String id) throws IOException {
        Path dir = toyPackageDir(id);
        Path zip = NerModelFixtures.zipDirectory(dir, "manifest.json", "ner.onnx", "vocab.txt");
        signZip(zip);
        return zip;
    }

    /** 用测试密钥给一个 zip 签名，签名文件写在包旁边。 */
    private void signZip(Path zip) throws IOException {
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(
                zip, signingKey.getPrivate(), ModelPackageSignature.keyIdOf(signingKey.getPublic()));
        Files.write(ModelTrust.signatureFileFor(zip), ModelPackageSignature.writeEnvelope(env));
    }

    private Path receiptZip(String key) {
        return active.resolveSibling(active.getFileName() + ".receipts").resolve(key + ".zip");
    }

    private static boolean same(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /**
     * 会失败的搬目录动作。
     *
     * <p><b>为什么需要它</b>：「换装失败要把旧的放回去」是这一块最要紧的一步，
     * 而它在正常机器上几乎不会发生——不注入失败点，就等于<b>从来没测过</b>。
     */
    private static final class FailingMover implements ModelPackageInstaller.Mover {
        private final Path failTarget;
        private final boolean alwaysFail;
        private int matched;

        FailingMover(Path failTarget, boolean alwaysFail) {
            this.failTarget = failTarget;
            this.alwaysFail = alwaysFail;
        }

        @Override
        public void move(Path from, Path to) throws IOException {
            if (same(to, failTarget)) {
                if (alwaysFail || matched == 0) {
                    matched++;
                    throw new IOException("模拟：搬不动 " + to);
                }
                matched++;
            }
            Files.move(from, to);
        }
    }
}
