package com.litemask.cli;

import com.litemask.ner.ModelPackageSignature;
import com.litemask.ner.ModelPackageStore;
import com.litemask.ner.ModelTrust;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「从网上把模型包取回来」这一层的单元测试。
 *
 * <p><b>为什么在这里起一个真的 HTTP 服务</b>：这一层的行为有一半在「HTTP 的细节」里
 * ——状态码、重定向、Content-Length、连接中途断掉。这些用 mock 测等于把要测的东西
 * 换成了自己写的那份，于是测的永远是「我的 mock 对不对」。
 * 起一个真的回环服务，成本很低，而测到的是真的那套行为。
 *
 * <p><b>判据独立于被测实现</b>：签名用 JDK 原生 crypto（Ed25519）造，
 * 信任锚用产品自己的 {@code model trust add} 那条路径写，
 * 「装没装上」看的是模型包目录里到底有没有东西——不是看某个标志位。
 *
 * <p><b>模型包本身是假的</b>：单元测试里不加载真的 ONNX 模型
 * （那要 15MB 的 fixture，还要起 onnxruntime）。
 * 所以校验器换成一个只认「解压出来是个目录」的桩。
 * <b>真的模型包的安装路径由端到端脚本覆盖</b>（那里有真模型）。
 * 签名验签这一段用的**是真的** {@link ModelTrust#verifyPackage}——
 * 那是这一块最要紧的判定，不能拿桩替。
 */
class ModelDownloadTest {

    @TempDir
    Path tmp;

    /** 一次 HTTP 响应。 */
    private record Reply(int status, byte[] body, String location) {
        static Reply ok(byte[] body) {
            return new Reply(200, body, null);
        }

        static Reply status(int status) {
            return new Reply(status, new byte[0], null);
        }

        static Reply redirect(String location) {
            return new Reply(302, new byte[0], location);
        }
    }

    private final Map<String, Reply> routes = new LinkedHashMap<>();

    private HttpServer server;
    private URI base;
    private String savedAnchors;
    private String savedBuiltin;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        // 信任锚隔离到临时目录：测试绝不能往开发机真实的信任锚里写东西。
        // 内置锚指向一个**不存在**的文件 = 没有内置锚（合法状态）。
        savedAnchors = System.getProperty(ModelTrust.ANCHORS_PROPERTY);
        savedBuiltin = System.getProperty(ModelTrust.BUILTIN_PROPERTY);
        System.setProperty(ModelTrust.ANCHORS_PROPERTY, tmp.resolve("anchors.json").toString());
        System.setProperty(ModelTrust.BUILTIN_PROPERTY, tmp.resolve("没有这个内置锚.json").toString());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        restore(ModelTrust.ANCHORS_PROPERTY, savedAnchors);
        restore(ModelTrust.BUILTIN_PROPERTY, savedBuiltin);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    // ------------------------------------------------------------------ 夹具

    /** 造一个「像模型包」的 zip（内容是假的，验签是真的）。 */
    private byte[] fakePackageZip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"id\":\"ner-test\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("ner.onnx"));
            zos.write(new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 给一段字节签名，返回签名信封的字节。 */
    private byte[] sign(byte[] packageBytes, KeyPair keys) throws Exception {
        Path zip = tmp.resolve("待签名.zip");
        Files.write(zip, packageBytes);
        String keyId = ModelPackageSignature.keyIdOf(keys.getPublic());
        return ModelPackageSignature.writeEnvelope(
                ModelPackageSignature.sign(zip, keys.getPrivate(), keyId));
    }

    /** 把一把公钥加进（隔离的）用户信任锚。 */
    private void trust(KeyPair keys) throws IOException {
        ModelTrust.addAnchor(ModelPackageSignature.publicKeyBase64(keys.getPublic()));
    }

    /** 跑一次 download，把退出码与输出一起收回来。 */
    private record Run(int code, String out, String err) {
        String all() {
            return out + "\n" + err;
        }
    }

    private Run runDownload(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ModelCommand.download(args, "litemask",
                new java.io.PrintStream(out, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8),
                dir -> new ModelPackageStore(dir,
                        d -> "ner-test",                       // 校验器：只认目录，不加载真模型
                        java.nio.file.Files::move,
                        ModelTrust::verifyPackage));         // 验签：真的
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** 断言「一个模型包都没装上」。 */
    private void assertNothingInstalled(Path storeDir) throws IOException {
        ModelPackageStore store = new ModelPackageStore(storeDir,
                d -> "ner-test", java.nio.file.Files::move, ModelTrust::verifyPackage);
        assertTrue(store.list().isEmpty(),
                "断言「没装上」的前提：仓库里居然有版本 " + store.list());
    }

    private void handle(HttpExchange ex) throws IOException {
        Reply r = routes.getOrDefault(ex.getRequestURI().getPath(), Reply.status(404));
        if (r.location() != null) {
            ex.getResponseHeaders().add("Location", percentEncode(r.location()));
        }
        byte[] body = r.body();
        try (OutputStream os = ex.getResponseBody()) {
            if (body.length == 0) {
                ex.sendResponseHeaders(r.status(), -1);
            } else {
                ex.sendResponseHeaders(r.status(), body.length);
                os.write(body);
            }
        } finally {
            ex.close();
        }
    }

    /**
     * Location 头必须 percent 编码。
     *
     * <p>HTTP 头是 ISO-8859-1 的，真实服务器发中文路径时也是这么编码的。
     * 这里照做，是为了让被测代码面对的是**真实服务器会发的东西**——
     * 不编码的话，回环服务会直接把连接掐掉，测的就不是产品行为了。
     */
    private static String percentEncode(String location) {
        StringBuilder sb = new StringBuilder();
        for (byte b : location.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c < 0x80) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void 下载并装上成功() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] zip = fakePackageZip();
        routes.put("/model.zip", Reply.ok(zip));
        routes.put("/model.zip.sig", Reply.ok(sign(zip, keys)));

        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("model.zip").toString(), "--to", storeDir.toString());

        assertEquals(0, r.code(), "下载并安装本该成功，实际输出：\n" + r.all());
        assertTrue(r.out().contains("已安装模型包"), "输出里该说装上了：\n" + r.all());
        ModelPackageStore store = new ModelPackageStore(storeDir,
                d -> "ner-test", java.nio.file.Files::move, ModelTrust::verifyPackage);
        List<ModelPackageStore.Version> versions = store.list();
        assertEquals(1, versions.size(), "应当正好装上一份，实际：\n" + r.all());
        assertTrue(versions.get(0).active(), "装上的那一份应当是当前生效的");
    }

    @Test
    void 签名文件缺失时报错且不装() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] zip = fakePackageZip();
        routes.put("/model.zip", Reply.ok(zip));
        // 故意不放 .sig

        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("model.zip").toString(), "--to", storeDir.toString());

        assertEquals(1, r.code(), "缺签名必须失败，实际输出：\n" + r.all());
        assertTrue(r.err().contains("下载失败"), "失败原因该说在下载这一环：\n" + r.all());
        assertNothingInstalled(storeDir);
    }

    @Test
    void 包被改过时装不上且不装() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] signedZip = fakePackageZip();
        byte[] sig = sign(signedZip, keys);
        // 签名对应的是 signedZip，而发出去的是另一个内容
        byte[] tampered = fakePackageZip();
        tampered[tampered.length - 1] = (byte) (tampered[tampered.length - 1] ^ 0xFF);
        routes.put("/model.zip", Reply.ok(tampered));
        routes.put("/model.zip.sig", Reply.ok(sig));

        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("model.zip").toString(), "--to", storeDir.toString());

        assertEquals(1, r.code(), "被改过的包必须装不上，实际输出：\n" + r.all());
        assertTrue(r.err().contains("安装失败"), "失败原因该说在安装（验签）这一环：\n" + r.all());
        assertNothingInstalled(storeDir);
    }

    @Test
    void 钥匙不受信时装不上() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        // **不**把公钥加进信任锚
        byte[] zip = fakePackageZip();
        routes.put("/model.zip", Reply.ok(zip));
        routes.put("/model.zip.sig", Reply.ok(sign(zip, keys)));

        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("model.zip").toString(), "--to", storeDir.toString());

        assertEquals(1, r.code(), "不受信的钥匙必须装不上，实际输出：\n" + r.all());
        assertTrue(r.err().contains("不被信任"), "失败原因该点名这把钥匙：\n" + r.all());
        assertNothingInstalled(storeDir);
    }

    @Test
    void 服务器返回404时报错且不装() throws Exception {
        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("没有这个.zip").toString(), "--to", storeDir.toString());

        assertEquals(1, r.code(), "404 必须失败，实际输出：\n" + r.all());
        assertTrue(r.err().contains("404"), "失败原因该说清状态码：\n" + r.all());
        assertNothingInstalled(storeDir);
    }

    @Test
    void 非http协议被拒() throws Exception {
        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload("file:///etc/passwd", "--to", storeDir.toString());

        assertEquals(1, r.code(), "非 http/https 必须失败，实际输出：\n" + r.all());
        assertTrue(r.err().contains("只支持 http 或 https"), "失败原因该说清协议限制：\n" + r.all());
        assertNothingInstalled(storeDir);
    }

    @Test
    void 重定向跳太多次时报错() throws Exception {
        routes.put("/loop.zip", Reply.redirect("/loop.zip"));
        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("loop.zip").toString(), "--to", storeDir.toString());

        assertEquals(1, r.code(), "打转的重定向必须失败，实际输出：\n" + r.all());
        assertTrue(r.err().contains("重定向"), "失败原因该说是重定向：\n" + r.all());
        assertNothingInstalled(storeDir);
    }

    @Test
    void 重定向到别处能跟过去() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] zip = fakePackageZip();
        routes.put("/起点.zip", Reply.redirect("/终点.zip"));
        routes.put("/终点.zip", Reply.ok(zip));
        routes.put("/终点.zip.sig", Reply.ok(sign(zip, keys)));

        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("起点.zip").toString(), "--to", storeDir.toString());

        assertEquals(0, r.code(), "跟一次重定向应当成功，实际输出：\n" + r.all());
    }

    @Test
    void 体积超上限时报错且不装() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] zip = fakePackageZip();
        routes.put("/model.zip", Reply.ok(zip));
        routes.put("/model.zip.sig", Reply.ok(sign(zip, keys)));

        Path storeDir = tmp.resolve("模型目录");
        Path destDir = tmp.resolve("下载暂存");
        // 上限调到比包还小：判定逻辑是同一份，只是阈值小。
        IOException e = assertThrows(IOException.class, () -> ModelFetcher.fetch(
                base.resolve("model.zip").toString(),
                base.resolve("model.zip.sig").toString(),
                destDir, 16L, 1024L * 1024L));
        assertTrue(e.getMessage().contains("体积上限"), "失败原因该说是超了上限：" + e.getMessage());
        assertNothingInstalled(storeDir);
    }

    @Test
    void 生产默认上限是512MiB() {
        // 记着这个数：它是「不让磁盘被写爆」的那道闸门，数字不能被悄悄改小。
        assertEquals(512L * 1024L * 1024L, ModelFetcher.MAX_PACKAGE_BYTES);
    }

    @Test
    void 下载不完整时报错且不装() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] zip = fakePackageZip();
        routes.put("/model.zip", Reply.ok(zip));
        routes.put("/model.zip.sig", Reply.ok(sign(zip, keys)));

        // 造一个「声明 100 字节、只给 10 字节」的响应
        HttpServer liar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        liar.createContext("/model.zip", ex -> {
            ex.sendResponseHeaders(200, 100);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(new byte[10]);
            }
            ex.close();
        });
        liar.start();
        try {
            URI liarBase = URI.create("http://127.0.0.1:" + liar.getAddress().getPort() + "/");
            Path storeDir = tmp.resolve("模型目录");
            Run r = runDownload(liarBase.resolve("model.zip").toString(), "--to", storeDir.toString());

            assertEquals(1, r.code(), "不完整的内容必须失败，实际输出：\n" + r.all());
            assertTrue(r.err().contains("下载失败"), "失败原因该说在下载这一环：\n" + r.all());
            assertNothingInstalled(storeDir);
        } finally {
            liar.stop(0);
        }
    }

    @Test
    void 拒绝_allow_unsigned() throws Exception {
        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("model.zip").toString(),
                "--to", storeDir.toString(), "--allow-unsigned");

        assertEquals(2, r.code(), "download 不该接受 --allow-unsigned，实际输出：\n" + r.all());
        assertTrue(r.err().contains("--allow-unsigned"), "错误信息该点名这个参数：\n" + r.all());
    }

    @Test
    void 参数个数不对时报用法() {
        Run r = runDownload();
        assertEquals(2, r.code(), "少参数必须报用法，实际输出：\n" + r.all());
        assertTrue(r.err().contains("用法"), "错误信息该给用法：\n" + r.all());
    }

    @Test
    void 下载用的临时目录不留下残留() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] zip = fakePackageZip();
        routes.put("/model.zip", Reply.ok(zip));
        routes.put("/model.zip.sig", Reply.ok(sign(zip, keys)));

        Path tmpBase = Path.of(System.getProperty("java.io.tmpdir"));
        long before = countDownloadTempDirs(tmpBase);
        Run r = runDownload(base.resolve("model.zip").toString(), "--to", tmp.resolve("模型目录").toString());

        assertEquals(0, r.code(), "本该成功，实际输出：\n" + r.all());
        assertEquals(before, countDownloadTempDirs(tmpBase),
                "下载用的临时目录必须删掉——它装着刚下载的 zip，留着只是白占磁盘");
    }

    private static long countDownloadTempDirs(Path tmpBase) {
        try (var s = Files.list(tmpBase)) {
            return s.filter(p -> p.getFileName().toString().startsWith("litemask-model-download-")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    @Test
    void 路径带中文的地址能下下来() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] zip = fakePackageZip();
        routes.put("/中文人名模型-v9.zip", Reply.ok(zip));
        routes.put("/中文人名模型-v9.zip.sig", Reply.ok(sign(zip, keys)));

        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("中文人名模型-v9.zip").toString(), "--to", storeDir.toString());

        assertEquals(0, r.code(), "中文路径的地址必须能下下来，实际输出：\n" + r.all());
    }

    @Test
    void 中文路径会被percent编码且不二次编码() throws Exception {
        URI u = ModelFetcher.encodeNonAsciiPath(URI.create("http://x/中文模型.zip"));
        assertEquals("/%E4%B8%AD%E6%96%87%E6%A8%A1%E5%9E%8B.zip", u.getRawPath());
        // 已经编码过的那部分不许被再编码一遍（%25 会把 %E4 变成字面的 %E4）
        URI v = ModelFetcher.encodeNonAsciiPath(URI.create("http://x/%E4%B8%AD.zip"));
        assertEquals("/%E4%B8%AD.zip", v.getRawPath());
        // 纯 ASCII 的地址原样返回
        URI w = ModelFetcher.encodeNonAsciiPath(URI.create("http://x/model.zip?a=1"));
        assertEquals("http://x/model.zip?a=1", w.toString());
    }

    @Test
    void sig_url_可以另指定签名地址() throws Exception {
        KeyPair keys = ModelPackageSignature.generateKeyPair();
        trust(keys);
        byte[] zip = fakePackageZip();
        routes.put("/model.zip", Reply.ok(zip));
        routes.put("/签名在别处.sig", Reply.ok(sign(zip, keys)));

        Path storeDir = tmp.resolve("模型目录");
        Run r = runDownload(base.resolve("model.zip").toString(),
                "--to", storeDir.toString(),
                "--sig-url", base.resolve("签名在别处.sig").toString());

        assertEquals(0, r.code(), "指定了 --sig-url 就该成功，实际输出：\n" + r.all());
    }

    // ------------------------------------------------------------------ 纯函数

    @Test
    void 重定向不许从https降到http() {
        IOException e = assertThrows(IOException.class, () -> ModelFetcher.checkRedirect(
                URI.create("https://例子.测试/a.zip"), URI.create("http://例子.测试/a.zip")));
        assertTrue(e.getMessage().contains("降到 http"), "该说清是降级：" + e.getMessage());
    }

    @Test
    void 重定向可以升级到https() throws Exception {
        ModelFetcher.checkRedirect(URI.create("http://例子.测试/a.zip"),
                URI.create("https://例子.测试/a.zip"));
        ModelFetcher.checkRedirect(URI.create("https://例子.测试/a.zip"),
                URI.create("https://例子.测试/b.zip"));
        ModelFetcher.checkRedirect(URI.create("http://例子.测试/a.zip"),
                URI.create("http://例子.测试/b.zip"));
    }

    @Test
    void 重定向到别的协议被拒() {
        assertThrows(IOException.class, () -> ModelFetcher.checkRedirect(
                URI.create("https://例子.测试/a.zip"), URI.create("ftp://例子.测试/a.zip")));
    }

    @Test
    void 文件名从地址里取且不许带路径() {
        assertEquals("model.zip", ModelFetcher.fileNameFor(URI.create("http://x/models/model.zip")));
        assertEquals("model.zip", ModelFetcher.fileNameFor(URI.create("http://x/models/model.zip?a=1")));
        assertEquals("model.zip", ModelFetcher.fileNameFor(URI.create("http://x/")));
        assertEquals("model.zip", ModelFetcher.fileNameFor(URI.create("http://x")));
        // 路径穿越要被洗掉，不能让它决定写到哪儿去
        String n = ModelFetcher.fileNameFor(URI.create("http://x/../../etc/passwd"));
        assertFalse(n.contains("/"), "文件名里不许有斜杠：" + n);
        assertFalse(n.contains(".."), "文件名里不许有 ..：" + n);
    }

    @Test
    void 地址检查拒掉缺协议的与缺主机的() {
        assertThrows(IOException.class, () -> ModelFetcher.checkedUri("例子.测试/a.zip", "模型包地址"));
        assertThrows(IOException.class, () -> ModelFetcher.checkedUri("file:///etc/passwd", "模型包地址"));
        assertThrows(IOException.class, () -> ModelFetcher.checkedUri("http:///a.zip", "模型包地址"));
    }
}
