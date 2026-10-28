package com.litemask.ner;

import com.litemask.core.EntitySource;
import com.litemask.core.Match;
import com.litemask.server.ApiHandlers;
import com.litemask.server.LocalServer;
import com.litemask.server.Workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelManager} 的行为测试：状态机、原子换装、回退、以及「装不上时原版本还在」。
 *
 * <p><b>为什么这些必须被测</b>：这一层是「用户不碰终端也能用上模型」的兑现处。
 * 它接的是已有的验签/校验/换装代码（那些各自有测试），
 * 但<b>编排</b>是新的——而编排里的每一条判据都直接对应用户可见的后果：
 * 「可用」两个字对应「界面是不是真的能识别人名」，
 * 「更新失败」对应「用户原来那份还能不能用」。
 *
 * <p><b>本测试用的模型是玩具模型</b>（{@link NerModelFixtures}）：
 * 标尺必须独立于被测实现，而真模型的期望值依赖训练结果。
 * 签名用的是测试自己生成的密钥对，并通过
 * {@link ModelTrust#BUILTIN_PROPERTY} 换掉内置信任锚——
 * 验签这一步<b>是真的</b>，只是「发布方」换成了测试自己。
 */
class ModelManagerTest {

    @Test
    @DisplayName("启用状态缺少模型包清单时处理身份明确失败")
    void 缺清单不再降级成空模型身份() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ModelManager.requireManifestOf(null));
        assertTrue(failure.getMessage().contains("缺少有效清单"));
    }

    /** 一次性把内置信任锚换成测试自己的那把公钥。 */
    private static void 用测试信任锚(Path anchorsFile, KeyPair kp) throws IOException {
        String json = "{\"anchors\":[{\"keyId\":\""
                + ModelPackageSignature.keyIdOf(kp.getPublic())
                + "\",\"publicKey\":\"" + ModelPackageSignature.publicKeyBase64(kp.getPublic())
                + "\"}]}";
        Files.writeString(anchorsFile, json, StandardCharsets.UTF_8);
        System.setProperty(ModelTrust.BUILTIN_PROPERTY, anchorsFile.toString());
        System.setProperty(ModelTrust.ANCHORS_PROPERTY,
                anchorsFile.resolveSibling("user-anchors.json").toString());
    }

    /**
     * 造一个**带签名**的模型包（zip + 同名 .sig）。
     *
     * @return 模型包 zip 的路径
     */
    private static Path 造签名模型包(Path dir, KeyPair kp, String id) throws IOException {
        Files.createDirectories(dir);
        Path pkgDir = Files.createTempDirectory("包内容");
        NerModelFixtures.writePackage(pkgDir, NerModelFixtures.onnx(false),
                NerModelFixtures.vocabText(), false);
        // 换掉清单里的 id，好让「装了两版」这件事能被区分开
        Map<String, Object> manifest = NerModelFixtures.manifest(
                NerModelFixtures.onnx(false), NerModelFixtures.vocabText(), false);
        manifest.put("id", id);
        NerModelFixtures.writeManifest(pkgDir, manifest);

        Path zip = dir.resolve(id + ".zip");
        Files.createDirectories(dir);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String n : List.of("manifest.json", "ner.onnx", "vocab.txt")) {
            entries.put(n, Files.readAllBytes(pkgDir.resolve(n)));
        }
        Files.copy(NerModelFixtures.zipEntries(entries), zip,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(zip, kp.getPrivate(),
                ModelPackageSignature.keyIdOf(kp.getPublic()));
        Files.write(zip.resolveSibling(zip.getFileName() + ".sig"),
                ModelPackageSignature.writeEnvelope(env));
        return zip;
    }

    /** 造一个「离线包」：外层 zip 里装着模型包与它的签名（用户只需选这一个文件）。 */
    private static Path 造离线包(Path dir, Path modelZip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(modelZip.getFileName().toString(), Files.readAllBytes(modelZip));
        entries.put(modelZip.getFileName() + ".sig",
                Files.readAllBytes(modelZip.resolveSibling(modelZip.getFileName() + ".sig")));
        Path out = dir.resolve("LightMeter-离线模型包.zip");
        Files.copy(NerModelFixtures.zipEntries(entries), out,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    // ------------------------------------------------------------------ 状态

    @Test
    @DisplayName("没装模型时：状态是「未安装」，并且明说人名不会被脱敏")
    void 没装模型时说清楚(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);

        ModelManager m = new ModelManager(tmp.resolve("store"), null, tmp.resolve("bundle"));
        m.bootstrapSync();

        ModelManager.Status s = m.status();
        assertEquals(ModelManager.State.NOT_INSTALLED, s.state(), "没装就该是「未安装」");
        assertFalse(s.usable(), "没装就不该说可用");
        assertNotNull(s.message(), "必须有一句给用户看的话");
        assertTrue(s.message().contains("不会"),
                "这句话要点明「人名与机构名不会被脱敏」，实际：" + s.message());
        assertTrue(s.actions().contains("install"), "没装时要能点「安装」");
        assertTrue(s.actions().contains("import"), "还要能选离线模型包");
    }

    @Test
    @DisplayName("装上之后：状态是「可用」，并报出模型版本与人名/机构名能力")
    void 装上之后状态可用(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        造签名模型包(bundle, kp, "test-model-v1");

        ModelManager m = new ModelManager(tmp.resolve("store"), null, bundle);
        m.bootstrapSync();
        ModelManager.Status s = m.installBundled();

        assertEquals(ModelManager.State.READY, s.state(), "装上就该是「可用」，实际：" + s.state());
        assertTrue(s.usable(), "装上就该说可用");
        assertEquals("test-model-v1", s.modelId(), "要报出当前模型版本");
        assertTrue(s.signatureVerified(), "生产界面使用的模型必须能复验发布签名");
        assertNotNull(s.modelVersionKey(), "要给审批链一个内容寻址的模型版本键");
        assertNotNull(s.modelPackageSha256(), "要报出被签名发布包的摘要");
        assertEquals(ModelPackageSignature.keyIdOf(kp.getPublic()), s.modelSigningKeyId(),
                "要报出实际验签使用的密钥标识");
        com.litemask.core.ProcessingIdentity processing = m.processingIdentity();
        assertEquals(s.modelId(), processing.modelId());
        assertEquals(s.modelVersionKey(), processing.modelVersionKey());
        assertEquals(s.modelPackageSha256(), processing.modelPackageSha256());
        assertEquals(s.modelSigningKeyId(), processing.modelSigningKeyId());
        assertTrue(processing.signatureVerified());
        assertTrue(s.personName(), "要报出支持人名识别");
        assertTrue(s.organizationName(), "要报出支持机构名识别");
        assertTrue(s.bundledAvailable(), "要说清楚安装包里带了随附模型");
        assertFalse(s.canRollback(), "第一份装上时没有可回退的旧版本");

        // 真的装进了用户数据目录
        Path installed = tmp.resolve("store").resolve("ner.onnx");
        assertTrue(Files.isRegularFile(installed), "模型应当装到用户数据目录，实际没有：" + installed);
    }

    @Test
    @DisplayName("生产界面不直载无签名收据的展开目录；显式开发目录仍可用但标成未验签")
    void 生产界面要求可复验收据而显式目录保留(@TempDir Path tmp) throws IOException {
        Path expanded = tmp.resolve("store");
        NerModelFixtures.writePackage(expanded);

        ModelManager production = new ModelManager(expanded, null, tmp.resolve("bundle"));
        production.bootstrapSync();
        ModelManager.Status blocked = production.status();
        assertFalse(blocked.usable(), "仅有展开文件不能冒充已验签发布模型");
        assertEquals(ModelManager.State.BROKEN, blocked.state());
        assertFalse(blocked.signatureVerified());
        assertTrue(blocked.failure().contains("签名来源"), "应说清卡在来源复验：" + blocked.failure());

        ModelManager explicit = new ModelManager(tmp.resolve("unused"), expanded, tmp.resolve("bundle"));
        explicit.bootstrapSync();
        ModelManager.Status developer = explicit.status();
        assertTrue(developer.usable(), "显式 --ner-model 开发/排障入口应保持可用");
        assertEquals(ModelManager.State.READY, developer.state(), "显式目录加载成功也应报就绪");
        assertFalse(developer.signatureVerified(), "显式直载目录不能被标成已验签");
        com.litemask.core.ProcessingIdentity developerProcessing = explicit.processingIdentity();
        assertTrue(developerProcessing.usedModel());
        assertEquals(developer.modelId(), developerProcessing.modelId());
        assertFalse(developerProcessing.signatureVerified());
        assertFalse(developerProcessing.strictProductionEligible());
        explicit.close();
    }

    @Test
    @DisplayName("「可用」不是「文件在」：模型真的加载起来了，识别才真的参与")
    void 可用意味着真的能识别(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        造签名模型包(bundle, kp, "test-model-v1");

        ModelManager m = new ModelManager(tmp.resolve("store"), null, bundle);
        m.bootstrapSync();

        // 装之前：来源是「不工作」的 —— 这正是界面据此提示用户的那一条
        EntitySource src = m.source();
        assertFalse(src.isActive(), "模型没装好时来源必须说自己不工作");
        assertTrue(src.collect("张三丰在北京大学").isEmpty(), "没装模型时不该认出实体");

        m.installBundled();

        assertTrue(src.isActive(), "装好之后同一个来源必须立刻变成在工作");
        List<Match> found = src.collect("张三丰在北京大学");
        assertFalse(found.isEmpty(), "装好之后必须真的能认出实体（玩具模型认得「张三」「北京大学」）");
    }

    // ------------------------------------------------------------------ 失败

    @Test
    @DisplayName("签名不对：装不上，而且说清为什么、下一步做什么")
    void 签名不对装不上(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        KeyPair other = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        // 用**另一把**密钥签：验签必然不过
        Path zip = bundle.resolve("fake.zip");
        Files.createDirectories(bundle);
        Path pkgDir = Files.createTempDirectory("包内容");
        NerModelFixtures.writePackage(pkgDir);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String n : List.of("manifest.json", "ner.onnx", "vocab.txt")) {
            entries.put(n, Files.readAllBytes(pkgDir.resolve(n)));
        }
        Files.copy(NerModelFixtures.zipEntries(entries), zip,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(zip, other.getPrivate(),
                ModelPackageSignature.keyIdOf(other.getPublic()));
        Files.write(zip.resolveSibling("fake.zip.sig"), ModelPackageSignature.writeEnvelope(env));

        ModelManager m = new ModelManager(tmp.resolve("store"), null, bundle);
        m.bootstrapSync();
        ModelManager.Status s = m.installBundled();

        assertFalse(s.usable(), "验签不过就不该说可用");
        assertEquals(ModelManager.State.NOT_INSTALLED, s.state(), "没装成功就该是「未安装」");
        assertNotNull(s.failure(), "必须说清失败原因");
        assertNotNull(s.nextStep(), "必须说清用户下一步能做什么");
        assertFalse(s.failure().contains("--ner-model"), "给普通用户看的话里不许出现命令行");
        assertFalse(s.failure().contains("model trust"), "给普通用户看的话里不许出现命令");
    }

    @Test
    @DisplayName("更新失败：原来那份仍然可用（不能因为更新失败把用户降级）")
    void 更新失败原版仍可用(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        KeyPair other = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        造签名模型包(bundle, kp, "test-model-v1");

        ModelManager m = new ModelManager(tmp.resolve("store"), null, bundle);
        m.bootstrapSync();
        m.installBundled();
        assertTrue(m.isModelUsable(), "前置：先装上一版");

        // 放一个「签了名、但签名用的钥匙不受信」的模型包当新版本：
        // 签名文件在（所以它会被当成随附的新版本挑中），
        // 但那把钥匙不在信任锚里 → 验签必然失败 → **更新失败**。
        Path pkgDir = Files.createTempDirectory("包内容");
        NerModelFixtures.writePackage(pkgDir);
        Map<String, Object> manifest = NerModelFixtures.manifest(
                NerModelFixtures.onnx(false), NerModelFixtures.vocabText(), false);
        manifest.put("id", "test-model-v2");
        NerModelFixtures.writeManifest(pkgDir, manifest);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String n : List.of("manifest.json", "ner.onnx", "vocab.txt")) {
            entries.put(n, Files.readAllBytes(pkgDir.resolve(n)));
        }
        Path newer = bundle.resolve("test-model-v2.zip");
        Files.copy(NerModelFixtures.zipEntries(entries), newer,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(newer, other.getPrivate(),
                ModelPackageSignature.keyIdOf(other.getPublic()));
        Files.write(newer.resolveSibling("test-model-v2.zip.sig"),
                ModelPackageSignature.writeEnvelope(env));

        ModelManager.Status s = m.installBundled();

        assertEquals(ModelManager.State.UPDATE_FAILED, s.state(),
                "更新失败要如实报成「更新失败」，实际：" + s.state());
        assertTrue(s.usable(), "更新失败后原来那份仍然可用");
        assertTrue(m.isModelUsable(), "更新失败后引擎仍然能用得上模型");
    }

    @Test
    @DisplayName("损坏的模型包装不上（内容与清单对不上）")
    void 损坏的包装不上(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        Files.createDirectories(bundle);
        Path pkgDir = Files.createTempDirectory("包内容");
        NerModelFixtures.writePackage(pkgDir);
        // 模型文件中间翻一个字节 → 清单里的摘要对不上
        Path zip = NerModelFixtures.zipDirectoryWithCorruptedModel(pkgDir);
        Path target = bundle.resolve("corrupt.zip");
        Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(target, kp.getPrivate(),
                ModelPackageSignature.keyIdOf(kp.getPublic()));
        Files.write(target.resolveSibling("corrupt.zip.sig"),
                ModelPackageSignature.writeEnvelope(env));

        ModelManager m = new ModelManager(tmp.resolve("store"), null, bundle);
        m.bootstrapSync();
        ModelManager.Status s = m.installBundled();

        assertFalse(s.usable(), "损坏的包装不上就不该说可用");
        assertNotNull(s.failure(), "必须说清失败原因");
    }

    // ------------------------------------------------------------------ 离线包

    @Test
    @DisplayName("离线模型包：用户只选一个文件就能装上（不用分别找 .sha256/.sig）")
    void 离线包一个文件就能装(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path modelZip = 造签名模型包(tmp.resolve("models"), kp, "test-model-offline");
        Path bundleFile = 造离线包(tmp, modelZip);

        ModelManager m = new ModelManager(tmp.resolve("store"), null, tmp.resolve("bundle"));
        m.bootstrapSync();
        ModelManager.Status s = m.importOffline(bundleFile);

        assertEquals(ModelManager.State.READY, s.state(), "离线包装得上就该是「可用」，实际：" + s.state());
        assertTrue(s.usable(), "离线包装得上就该说可用");
        assertTrue(Files.isRegularFile(tmp.resolve("store").resolve("ner.onnx")),
                "离线包装完应当落在用户数据目录");
    }

    @Test
    @DisplayName("HTTP 导入合法签名离线包仍能安装")
    void HTTP导入合法签名包(@TempDir Path tmp) throws Exception {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path modelZip = 造签名模型包(tmp.resolve("models"), kp, "test-model-http");
        Path bundleFile = 造离线包(tmp, modelZip);

        ModelManager manager = new ModelManager(
                tmp.resolve("store"), null, tmp.resolve("bundled"));
        manager.bootstrapSync();
        Workspace workspace = Workspace.open(tmp.resolve("workspace"));
        LocalServer server = LocalServer.create(workspace, 0);
        ApiHandlers.register(server, workspace, ApiHandlers.defaultRules(), List.of(), manager, manager);
        server.start();
        try {
            byte[] body = Files.readAllBytes(bundleFile);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/api/model/import"))
                    .header("Content-Type", "application/octet-stream")
                    .header("X-Filename", "signed-offline-model.zip")
                    .header("X-Litemask-Token", server.policy().token())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"state\":\"READY\""), response.body());
            assertTrue(manager.isModelUsable(), "HTTP 返回成功时模型必须真的已加载");
        } finally {
            server.close();
            manager.close();
            workspace.close();
        }
    }

    @Test
    @DisplayName("离线包里没有签名 → 拒绝安装（不做「跳过验证」那条路）")
    void 离线包没签名不装(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path pkgDir = Files.createTempDirectory("包内容");
        NerModelFixtures.writePackage(pkgDir);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String n : List.of("manifest.json", "ner.onnx", "vocab.txt")) {
            entries.put(n, Files.readAllBytes(pkgDir.resolve(n)));
        }
        Path onlyZip = tmp.resolve("只有模型没有签名.zip");
        Files.copy(NerModelFixtures.zipEntries(entries), onlyZip,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        ModelManager m = new ModelManager(tmp.resolve("store"), null, tmp.resolve("bundle"));
        m.bootstrapSync();
        ModelManager.Status s = m.importOffline(onlyZip);

        assertFalse(s.usable(), "没有验证材料就不能装");
        assertNotNull(s.failure(), "必须说清失败原因");
        assertFalse(Files.exists(tmp.resolve("store").resolve("ner.onnx")),
                "没装上就不能在生效目录里留下模型");
    }

    // ------------------------------------------------------------------ 回退

    @Test
    @DisplayName("装了两版之后能回退到上一版")
    void 能回退到上一版(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        造签名模型包(bundle, kp, "test-model-v1");

        ModelManager m = new ModelManager(tmp.resolve("store"), null, bundle);
        m.bootstrapSync();
        m.installBundled();

        // 装第二版
        Files.delete(bundle.resolve("test-model-v1.zip"));
        Files.delete(bundle.resolve("test-model-v1.zip.sig"));
        造签名模型包(bundle, kp, "test-model-v2");
        ModelManager.Status after = m.installBundled();
        assertEquals("test-model-v2", after.modelId(), "前置：装上了第二版");
        assertTrue(m.canRollback(), "装过两版就该能回退");

        ModelManager.Status back = m.rollback();
        assertEquals(ModelManager.State.READY, back.state(), "回退后应当仍然可用");
        assertEquals("test-model-v1", back.modelId(), "回退后应当是上一版");
        assertTrue(m.isModelUsable(), "回退后引擎仍然能用得上模型");
    }

    @Test
    @DisplayName("没有历史版本时回退要说出「现在没什么可退回的」")
    void 没有历史时回退说清楚(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        ModelManager m = new ModelManager(tmp.resolve("store"), null, tmp.resolve("bundle"));
        m.bootstrapSync();
        ModelManager.Status s = m.rollback();
        assertFalse(s.usable(), "回退不成功就不该说可用");
        assertTrue(s.failure().contains("当前没有可用模型"), s.failure());
        assertTrue(s.nextStep().contains("安装随附模型或选择已签名离线包"), s.nextStep());
    }

    @Test
    @DisplayName("连续状态读取只读回退快照，不校验模型也不争抢仓库锁")
    void 状态轮询不触发历史复验或仓库锁(@TempDir Path tmp) throws Exception {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        Path v1 = 造签名模型包(bundle, kp, "test-model-v1");
        Path v2 = 造签名模型包(bundle, kp, "test-model-v2");
        Path storeDir = tmp.resolve("store");
        AtomicInteger validations = new AtomicInteger();
        ModelPackageStore observedStore = new ModelPackageStore(storeDir, dir -> {
            validations.incrementAndGet();
            return ModelPackageInstaller.verifyWithModelLoader(dir);
        }, Files::move);
        ModelManager manager = new ModelManager(storeDir, null, bundle, observedStore);
        manager.bootstrapSync();
        // bundle 同时有两份时发现逻辑会选最新文件；这里直接用仓库造出可信历史，再重启刷新快照。
        observedStore.install(v1);
        observedStore.install(v2);
        manager.bootstrapSync();
        assertTrue(manager.canRollback(), "启动边界应得到可信回退快照");
        int afterRefresh = validations.get();

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelPackageStore busyStore = new ModelPackageStore(storeDir, dir -> {
            lockHeld.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("测试：只占住仓库锁，不换装");
        }, Files::move);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> busy = pool.submit(() -> {
            assertThrows(IOException.class, () -> busyStore.install(v1));
        });
        try {
            assertTrue(lockHeld.await(5, TimeUnit.SECONDS), "前提：另一个仓库动作已拿到排他锁");
            for (int i = 0; i < 5; i++) {
                assertTrue(manager.canRollback());
                assertTrue(manager.status().canRollback());
            }
            assertEquals(afterRefresh, validations.get(),
                    "状态轮询不能再次解压或调用模型 validator");
        } finally {
            release.countDown();
            busy.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("只有坏历史时回退明确拒绝，并保持 active 与来源身份")
    void 坏历史不开放回退且不改变当前模型(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        造签名模型包(bundle, kp, "test-model-v1");
        Path store = tmp.resolve("store");
        ModelManager m = new ModelManager(store, null, bundle);
        m.bootstrapSync();
        m.installBundled();
        Files.writeString(store.resolve("vocab.txt"), "损坏", StandardCharsets.UTF_8);

        ModelManager.Status repaired = m.installBundled();
        assertTrue(repaired.usable(), "同一签名包必须能修复损坏 active");
        assertFalse(repaired.canRollback(), "换下来的坏 active 不能成为回退候选");
        assertTrue(repaired.signatureVerified(), "修复后的 active 必须仍有可复验来源");

        ModelManager.Status failed = m.rollback();

        assertTrue(failed.usable(), "拒绝坏历史后现有 active 仍应可推理");
        assertTrue(failed.failure().contains("没有可回退的已验证版本"), failed.failure());
        assertTrue(failed.nextStep().contains("现有模型可以继续使用"), failed.nextStep());
        assertEquals(repaired.modelVersionKey(), failed.modelVersionKey(), "失败回退不能改变 active 内容身份");
        assertEquals(repaired.modelPackageSha256(), failed.modelPackageSha256(), "失败回退不能改变签名包来源");
        assertEquals(repaired.modelSigningKeyId(), failed.modelSigningKeyId(), "失败回退不能改变签名身份");
    }

    @Test
    @DisplayName("BROKEN 状态有可信历史时显示回退操作，并能回到 READY")
    void 损坏当前模型仍可从可信历史恢复(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        Path v1 = 造签名模型包(bundle, kp, "test-model-v1");
        Path v2 = 造签名模型包(bundle, kp, "test-model-v2");
        Path store = tmp.resolve("store");
        ModelPackageStore repository = new ModelPackageStore(store);
        repository.install(v1);
        repository.install(v2);
        Files.writeString(store.resolve("vocab.txt"), "损坏", StandardCharsets.UTF_8);

        ModelManager manager = new ModelManager(store, null, bundle);
        manager.bootstrapSync();
        ModelManager.Status broken = manager.status();
        assertEquals(ModelManager.State.BROKEN, broken.state());
        assertTrue(broken.actions().contains("rollback"), "BROKEN 但有可信历史时界面必须给回退入口");

        ModelManager.Status recovered = manager.rollback();
        assertEquals(ModelManager.State.READY, recovered.state());
        assertTrue(recovered.usable());
        assertEquals("test-model-v1", recovered.modelId());
    }

    @Test
    @DisplayName("换装被中断（生效目录没了、但历史里还有）→ 启动时自动换回来")
    void 换装中断后能恢复(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        造签名模型包(bundle, kp, "test-model-v1");

        Path store = tmp.resolve("store");
        ModelManager m = new ModelManager(store, null, bundle);
        m.bootstrapSync();
        m.installBundled();
        assertTrue(m.isModelUsable(), "前置：先装上一版");

        // 模拟「换装死在两次改名之间」：
        // 生效目录被挪进了历史目录，新目录还没挪到位 —— 生效目录于是不存在。
        // 【布局要跟仓库一致】生效目录**就是** storeDir 本身，
        // 历史目录是它的**同级**兄弟（storeDir + ".versions"），不是它的子目录。
        Path active = store;
        Path versions = tmp.resolve("store.versions");
        assertTrue(Files.isDirectory(active), "前置：生效目录在");
        Path moved = versions.resolve("store-interrupted");
        Files.createDirectories(versions);
        Files.move(active, moved);
        assertFalse(Files.exists(active), "前置：生效目录确实没了");

        // 重新启动：应当把历史里那一版换回来，而不是报「未安装」
        ModelManager again = new ModelManager(store, null, bundle);
        again.bootstrapSync();
        assertTrue(again.isModelUsable(),
                "换装被中断之后，启动时应当自动把原来那份换回来（而不是让用户看到「未安装」）");
        assertTrue(Files.isRegularFile(store.resolve("ner.onnx")), "生效目录应当被恢复");
    }

    @Test
    @DisplayName("装到一半失败（模型装不上）：原来那份仍然可用")
    void 装到一半失败原版仍可用(@TempDir Path tmp) throws IOException {
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        用测试信任锚(tmp.resolve("anchors.json"), kp);
        Path bundle = tmp.resolve("bundle");
        造签名模型包(bundle, kp, "test-model-v1");

        ModelManager m = new ModelManager(tmp.resolve("store"), null, bundle);
        m.bootstrapSync();
        m.installBundled();
        assertTrue(m.isModelUsable(), "前置：先装上一版");

        // 造一个**签名过得去、但模型装不上**的包：
        // 验签通过 → 进入安装 → 校验时加载模型失败 → 安装失败。
        // 这一条覆盖的是「磁盘满/中断/文件损坏」那一类**发生在换装中途**的失败。
        Path pkgDir = Files.createTempDirectory("包内容");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("manifest.json"),
                NerModelFixtures.toJson(NerModelFixtures.manifest(
                        NerModelFixtures.onnx(false), NerModelFixtures.vocabText(), false)),
                StandardCharsets.UTF_8);
        // 模型文件是**垃圾**：清单里的摘要对得上，但真加载不了
        byte[] junk = new byte[64];
        Files.write(pkgDir.resolve("ner.onnx"), junk);
        Files.writeString(pkgDir.resolve("vocab.txt"), NerModelFixtures.vocabText(),
                StandardCharsets.UTF_8);
        Map<String, Object> mf = NerModelFixtures.manifest(junk, NerModelFixtures.vocabText(), false);
        mf.put("id", "test-model-broken");
        Files.writeString(pkgDir.resolve("manifest.json"), NerModelFixtures.toJson(mf),
                StandardCharsets.UTF_8);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String n : List.of("manifest.json", "ner.onnx", "vocab.txt")) {
            entries.put(n, Files.readAllBytes(pkgDir.resolve(n)));
        }
        Path broken = bundle.resolve("test-model-broken.zip");
        Files.copy(NerModelFixtures.zipEntries(entries), broken,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(broken, kp.getPrivate(),
                ModelPackageSignature.keyIdOf(kp.getPublic()));
        Files.write(broken.resolveSibling("test-model-broken.zip.sig"),
                ModelPackageSignature.writeEnvelope(env));

        ModelManager.Status s = m.installBundled();

        assertFalse(s.usable() && s.state() == ModelManager.State.READY,
                "装到一半失败不该报「可用」");
        assertEquals(ModelManager.State.UPDATE_FAILED, s.state(),
                "装到一半失败要如实报成「更新失败」，实际：" + s.state());
        assertTrue(m.isModelUsable(), "装到一半失败后，原来那份仍然可用（原子性）");
    }
}
