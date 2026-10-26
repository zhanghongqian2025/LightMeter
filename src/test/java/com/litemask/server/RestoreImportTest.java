package com.litemask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.litemask.core.Rule;
import com.litemask.core.SecretMap;
import com.litemask.ofd.OfdFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 重启后独立还原：用全新服务、工作区和令牌，不借用产生映射时的 job 内存。 */
class RestoreImportTest {

    private static final String PASSPHRASE = "fresh-process-口令";
    private static final String ORIGINAL = "模板原有 <手机号999999999>；联系人张三，电话 13800138000\n";

    private static byte[] product;
    private static byte[] mapping;
    private static byte[] zeroProduct;
    private static byte[] zeroMapping;
    private static Workspace workspace;
    private static LocalServer server;
    private static int port;
    private static String token;

    @BeforeAll
    static void 在旧服务产生并在新工作区还原(@TempDir Path tmp) throws Exception {
        // 第一个服务只负责产生下载物。取到字节后立即关闭，清掉任务表与会话目录。
        Workspace oldWorkspace = Workspace.open(tmp.resolve("old"));
        LocalServer oldServer = LocalServer.create(oldWorkspace, 0);
        ApiHandlers.register(oldServer, oldWorkspace, ApiHandlers.defaultRules());
        oldServer.start();
        String oldToken = oldServer.policy().token();
        int oldPort = oldServer.port();
        try {
            Map<String, String> uploadHeaders = new LinkedHashMap<>();
            uploadHeaders.put("Content-Type", "application/octet-stream");
            uploadHeaders.put(ApiHandlers.FILENAME_HEADER, "%E5%90%88%E5%90%8C.txt");
            LocalServerApiTest.Raw.Res scan = LocalServerApiTest.Raw.req(oldPort, "POST", "/api/scan",
                    "127.0.0.1:" + oldPort, null, oldToken,
                    ORIGINAL.getBytes(StandardCharsets.UTF_8), uploadHeaders);
            assertEquals(200, scan.status(), scan.body());
            String jobId = Json.mapper().readTree(scan.body()).get("jobId").asText();

            byte[] applyBody = Json.toBytes(Map.of(
                    "jobId", jobId, "excluded", List.of(), "mapPassphrase", PASSPHRASE));
            LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(oldPort, "POST", "/api/apply",
                    "127.0.0.1:" + oldPort, null, oldToken, applyBody,
                    Map.of("Content-Type", "application/json"));
            assertEquals(200, apply.status(), apply.body());

            product = LocalServerApiTest.Raw.bodyBytes(oldPort, "GET",
                    "/api/download?jobId=" + jobId, "127.0.0.1:" + oldPort,
                    null, oldToken, Map.of());
            // 分配器从原文的最大编号之后继续；合法新编号可能超过原文识别式的九位。
            assertTrue(new String(product, StandardCharsets.UTF_8).contains("<手机号1000000000>"));
            mapping = LocalServerApiTest.Raw.bodyBytes(oldPort, "GET",
                    "/api/mapping?jobId=" + jobId, "127.0.0.1:" + oldPort,
                    null, oldToken, Map.of());

            String clean = "这是一段没有结构化标识符的普通内容。\n";
            LocalServerApiTest.Raw.Res zeroScan = LocalServerApiTest.Raw.req(oldPort, "POST", "/api/scan",
                    "127.0.0.1:" + oldPort, null, oldToken,
                    clean.getBytes(StandardCharsets.UTF_8), uploadHeaders);
            assertEquals(200, zeroScan.status(), zeroScan.body());
            String zeroJob = Json.mapper().readTree(zeroScan.body()).get("jobId").asText();
            byte[] zeroApplyBody = Json.toBytes(Map.of(
                    "jobId", zeroJob, "excluded", List.of(), "mapPassphrase", PASSPHRASE));
            LocalServerApiTest.Raw.Res zeroApply = LocalServerApiTest.Raw.req(oldPort, "POST", "/api/apply",
                    "127.0.0.1:" + oldPort, null, oldToken, zeroApplyBody,
                    Map.of("Content-Type", "application/json"));
            assertEquals(200, zeroApply.status(), zeroApply.body());
            zeroProduct = LocalServerApiTest.Raw.bodyBytes(oldPort, "GET",
                    "/api/download?jobId=" + zeroJob, "127.0.0.1:" + oldPort,
                    null, oldToken, Map.of());
            zeroMapping = LocalServerApiTest.Raw.bodyBytes(oldPort, "GET",
                    "/api/mapping?jobId=" + zeroJob, "127.0.0.1:" + oldPort,
                    null, oldToken, Map.of());
        } finally {
            oldServer.close();
            oldWorkspace.close();
        }

        // 新工作区 + 新服务令牌：证明还原不依赖旧 jobId 或旧会话文件。
        workspace = Workspace.open(tmp.resolve("fresh"));
        server = LocalServer.create(workspace, 0);
        List<Rule> rules = ApiHandlers.defaultRules();
        ApiHandlers.register(server, workspace, rules);
        server.start();
        port = server.port();
        token = server.policy().token();
    }

    @AfterAll
    static void 停服务() {
        if (server != null) server.close();
        if (workspace != null) workspace.close();
    }

    @Test
    @DisplayName("新服务与工作区中用脱敏件+加密映射+口令还原成功")
    void 新服务与工作区成功() throws Exception {
        LocalServerApiTest.Raw.Res response = restore(product, mapping, PASSPHRASE, 1, token,
                "合同.redacted.txt", "合同.redacted.map.json");
        assertEquals(200, response.status(), response.body());
        assertEquals(ORIGINAL, response.body());
        assertTrue(Integer.parseInt(response.headers().get("x-litemask-restored").get(0)) > 0);
        assertFalse(response.headers().containsKey("access-control-allow-origin"));
    }

    @Test
    @DisplayName("合法的零命中加密映射可以 no-op 还原")
    void 零命中映射可还原() throws Exception {
        LocalServerApiTest.Raw.Res response = restore(zeroProduct, zeroMapping, PASSPHRASE, 1, token,
                "clean.redacted.txt", "clean.redacted.map.json");
        assertEquals(200, response.status(), response.body());
        assertEquals(new String(zeroProduct, StandardCharsets.UTF_8), response.body());
        assertEquals("0", response.headers().get("x-litemask-restored").get(0));
    }

    @Test
    @DisplayName("OFD 全角附件名令牌可跨新服务导入还原")
    void ofdAttachmentFilenameRestoresInFreshWorkspace(@TempDir Path tmp) throws Exception {
        Path source = OfdFixtures.standard(tmp, "attachment.ofd");
        byte[] redacted;
        byte[] encryptedMap;

        try (Workspace producerWorkspace = Workspace.open(tmp.resolve("ofd-producer"))) {
            LocalServer producer = LocalServer.create(producerWorkspace, 0);
            ApiHandlers.register(producer, producerWorkspace, ApiHandlers.defaultRules());
            producer.start();
            try {
                int producerPort = producer.port();
                String producerToken = producer.policy().token();
                LocalServerApiTest.Raw.Res scan = LocalServerApiTest.Raw.req(producerPort,
                        "POST", "/api/scan", "127.0.0.1:" + producerPort, null, producerToken,
                        Files.readAllBytes(source), Map.of(
                                "Content-Type", "application/octet-stream",
                                ApiHandlers.FILENAME_HEADER, "attachment.ofd"));
                assertEquals(200, scan.status(), scan.body());
                String jobId = Json.mapper().readTree(scan.body()).get("jobId").asText();

                LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(producerPort,
                        "POST", "/api/apply", "127.0.0.1:" + producerPort, null, producerToken,
                        Json.toBytes(Map.of("jobId", jobId, "excluded", List.of(),
                                "mapPassphrase", PASSPHRASE)),
                        Map.of("Content-Type", "application/json"));
                assertEquals(200, apply.status(), apply.body());
                redacted = LocalServerApiTest.Raw.bodyBytes(producerPort, "GET",
                        "/api/download?jobId=" + jobId, "127.0.0.1:" + producerPort,
                        null, producerToken, Map.of());
                encryptedMap = LocalServerApiTest.Raw.bodyBytes(producerPort, "GET",
                        "/api/mapping?jobId=" + jobId, "127.0.0.1:" + producerPort,
                        null, producerToken, Map.of());
            } finally {
                producer.close();
            }
        }

        Map<String, byte[]> redactedEntries = unzip(redacted);
        assertTrue(redactedEntries.keySet().stream().anyMatch(
                        name -> name.startsWith("Doc_0/Attachs/［") && name.endsWith("］_申请书.pdf")),
                "生产者没有写出 OFD 全角附件名令牌: " + redactedEntries.keySet());

        byte[] restored;
        try (Workspace consumerWorkspace = Workspace.open(tmp.resolve("ofd-consumer"))) {
            LocalServer consumer = LocalServer.create(consumerWorkspace, 0);
            ApiHandlers.register(consumer, consumerWorkspace, ApiHandlers.defaultRules());
            consumer.start();
            try {
                byte[] envelope = restoreRequest(redacted, encryptedMap, PASSPHRASE, 1,
                        "attachment.redacted.ofd", "attachment.ofd.redacted.map.json");
                restored = LocalServerApiTest.Raw.bodyBytes(consumer.port(), "POST",
                        "/api/restore-import", "127.0.0.1:" + consumer.port(), null,
                        consumer.policy().token(), envelope,
                        Map.of("Content-Type", "application/octet-stream"));
            } finally {
                consumer.close();
            }
        }

        Map<String, byte[]> restoredEntries = unzip(restored);
        String attachmentName = "Doc_0/Attachs/" + OfdFixtures.PHONE + "_申请书.pdf";
        assertTrue(restoredEntries.containsKey(attachmentName),
                "独立还原没有恢复附件名: " + restoredEntries.keySet());
        assertEquals("fake-pdf-bytes",
                new String(restoredEntries.get(attachmentName), StandardCharsets.UTF_8),
                "附件二进制内容不得丢失");
        assertTrue(new String(restoredEntries.get("Doc_0/Attachments.xml"), StandardCharsets.UTF_8)
                        .contains("/Doc_0/Attachs/" + OfdFixtures.PHONE + "_申请书.pdf"),
                "附件引用没有与条目名一起恢复");
    }

    @Test
    @DisplayName("旧版未绑定映射在独立入口明确拒绝，不产出文件")
    void 旧版未绑定映射被明确拒绝() throws Exception {
        byte[] legacy = rewriteMapping(map -> map.remove("binding"));
        LocalServerApiTest.Raw.Res response = restore(product, legacy, PASSPHRASE, 1, token,
                "a.txt", "legacy.map.json");
        assertEquals(400, response.status(), response.body());
        assertTrue(response.body().contains("旧版未绑定"), response.body());
        assertTrue(response.body().contains("当前完成页"), response.body());
        assertSessionEmpty();
    }

    @Test
    @DisplayName("解密后 null 与小数 bytes 绑定都是 400，不是 500")
    void 映射结构严格校验() throws Exception {
        byte[] nullPayload = SecretMap.seal("null".getBytes(StandardCharsets.UTF_8),
                PASSPHRASE.toCharArray());
        LocalServerApiTest.Raw.Res nullMap = restore(product, nullPayload, PASSPHRASE, 1, token,
                "a.txt", "null.map.json");
        assertEquals(400, nullMap.status(), nullMap.body());
        assertTrue(nullMap.body().contains("JSON 对象"), nullMap.body());

        byte[] fractional = rewriteMapping(map -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> binding = (Map<String, Object>) map.get("binding");
            binding.put("bytes", 1.5d);
        });
        LocalServerApiTest.Raw.Res badBytes = restore(product, fractional, PASSPHRASE, 1, token,
                "a.txt", "fraction.map.json");
        assertEquals(400, badBytes.status(), badBytes.body());
        assertTrue(badBytes.body().contains("成品绑定"), badBytes.body());
        assertSessionEmpty();
    }

    @Test
    @DisplayName("口令错误、映射被改、成品错配都拒绝")
    void 负例都拒绝() throws Exception {
        LocalServerApiTest.Raw.Res wrongPass = restore(product, mapping, "错误口令", 1, token,
                "a.txt", "a.map.json");
        assertEquals(400, wrongPass.status(), wrongPass.body());
        assertTrue(wrongPass.body().contains("映射文件打不开"), wrongPass.body());

        byte[] tampered = mapping.clone();
        tampered[tampered.length - 1] ^= 1;
        LocalServerApiTest.Raw.Res badMap = restore(product, tampered, PASSPHRASE, 1, token,
                "a.txt", "a.map.json");
        assertEquals(400, badMap.status(), badMap.body());

        byte[] wrongProduct = (new String(product, StandardCharsets.UTF_8) + "x")
                .getBytes(StandardCharsets.UTF_8);
        LocalServerApiTest.Raw.Res mismatch = restore(wrongProduct, mapping, PASSPHRASE, 1, token,
                "a.txt", "a.map.json");
        assertEquals(400, mismatch.status(), mismatch.body());
        assertTrue(mismatch.body().contains("SHA-256"), mismatch.body());
        assertSessionEmpty();
    }

    @Test
    @DisplayName("信封版本、文件路径和鉴权都 fail-closed")
    void 边界都拒绝() throws Exception {
        LocalServerApiTest.Raw.Res version = restore(product, mapping, PASSPHRASE, 2, token,
                "a.txt", "a.map.json");
        assertEquals(400, version.status(), version.body());
        assertTrue(version.body().contains("版本"), version.body());

        LocalServerApiTest.Raw.Res path = restore(product, mapping, PASSPHRASE, 1, token,
                "../a.txt", "a.map.json");
        assertEquals(400, path.status(), path.body());
        assertTrue(path.body().contains("文件名不合法"), path.body());

        LocalServerApiTest.Raw.Res noToken = restore(product, mapping, PASSPHRASE, 1, null,
                "a.txt", "a.map.json");
        assertEquals(403, noToken.status(), noToken.body());

        ByteBuffer oversized = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
                .putInt(0x4c4d5231).putInt(1).putInt(1)
                .putLong(workspace.maxUploadBytes() + 1).putLong(1);
        LocalServerApiTest.Raw.Res tooLarge = LocalServerApiTest.Raw.req(port, "POST",
                "/api/restore-import", "127.0.0.1:" + port, null, token,
                oversized.array(), Map.of("Content-Type", "application/octet-stream"));
        assertEquals(400, tooLarge.status(), tooLarge.body());
        assertTrue(tooLarge.body().contains("文件长度不合法"), tooLarge.body());

        LocalServerApiTest.Raw.Res nullMeta = restoreWithRawMeta(
                "null".getBytes(StandardCharsets.UTF_8), product, mapping, token);
        assertEquals(400, nullMeta.status(), nullMeta.body());
        assertTrue(nullMeta.body().contains("JSON 对象"), nullMeta.body());
        assertSessionEmpty();
    }

    @Test
    @DisplayName("导入映射的令牌和原值必须在有界 schema 内")
    void 映射条目严格校验() throws Exception {
        byte[] emptyToken = rewriteMapping(map -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) map.get("mapping");
            values.clear();
            values.put("", "x");
        });
        LocalServerApiTest.Raw.Res response = restore(product, emptyToken, PASSPHRASE, 1, token,
                "a.txt", "empty-token.map.json");
        assertEquals(400, response.status(), response.body());
        assertTrue(response.body().contains("映射令牌"), response.body());
        assertSessionEmpty();
    }

    @Test
    @DisplayName("单任务入场：第二份不读请求体就拒绝，健康检查与取消仍可响应")
    void 单任务入场与取消() throws Exception {
        Socket slow = openIncompleteRestore(port, token, 1024, 1);
        try {
            waitForSessionNonEmpty(workspace);
            // 只发第二份的 HTTP 头，不发它声明的 1MB 请求体。
            // 若服务在入场拒绝前读 body，这里会阻塞直到 socket 超时；
            // 能立即读到 409 就证明没分配/读取第二份文件。
            try (Socket second = new Socket()) {
                second.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                second.setSoTimeout(2_000);
                OutputStream out = second.getOutputStream();
                out.write(("POST /api/restore-import HTTP/1.1\r\n"
                        + "Host: 127.0.0.1:" + port + "\r\n"
                        + "X-Litemask-Token: " + token + "\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Length: 1048576\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                String statusLine = new BufferedReader(new InputStreamReader(
                        second.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
                assertTrue(statusLine != null && statusLine.contains(" 409 "), statusLine);
            }

            LocalServerApiTest.Raw.Res health = LocalServerApiTest.Raw.get(port, "/api/health");
            assertEquals(200, health.status(), health.body());

            LocalServerApiTest.Raw.Res canceled = waitUntilCancelable(port, token);
            assertEquals(200, canceled.status(), canceled.body());
            assertTrue(canceled.body().contains("\"canceled\":true"), canceled.body());
            assertFalse(canceled.headers().containsKey("content-disposition"),
                    "取消回应只能是 JSON，不能伪装成还原件");

            // 客户端故意保持未完整上传的 socket 开启；服务端必须自己打断 read、
            // 走完 finally 并释放入场，不能借助客户端 close。
            waitForSessionCleanup(workspace);
            LocalServerApiTest.Raw.Res next = restore(product, mapping, PASSPHRASE, 1, token,
                    "a.txt", "a.map.json");
            assertEquals(200, next.status(), next.body());
        } finally {
            slow.close();
        }
    }

    @Test
    @DisplayName("还原沿用可配时限：慢上传保持开启也会被断开并清理")
    void 超时不交付文件(@TempDir Path tmp) throws Exception {
        try (Workspace timedWorkspace = Workspace.open(tmp.resolve("timeout"))) {
            LocalServer timedServer = LocalServer.create(timedWorkspace, 0);
            ApiHandlers.register(timedServer, timedWorkspace, ApiHandlers.defaultRules(), List.of(), 250);
            timedServer.start();
            Socket slow = openIncompleteRestore(timedServer.port(), timedServer.policy().token(), 1024, 1);
            try {
                waitForSessionNonEmpty(timedWorkspace);
                waitForSessionCleanup(timedWorkspace);

                slow.setSoTimeout(2_000);
                try {
                    assertEquals(-1, slow.getInputStream().read(),
                            "超时应在任何附件响应头之前直接断开连接");
                } catch (SocketTimeoutException e) {
                    throw new AssertionError("服务端没有主动打断阻塞上传", e);
                }
                assertFalse(waitUntilCancelableFalse(timedServer.port(), timedServer.policy().token()),
                        "超时清理后不应还有活动任务");
            } finally {
                slow.close();
                timedServer.close();
            }
        }
    }

    @Test
    @DisplayName("服务关闭会等实际工作线程清理完，关闭后不再重建临时文件")
    void 关闭等清理完成(@TempDir Path tmp) throws Exception {
        try (Workspace closingWorkspace = Workspace.open(tmp.resolve("closing"))) {
            LocalServer closingServer = LocalServer.create(closingWorkspace, 0);
            ApiHandlers.register(closingServer, closingWorkspace, ApiHandlers.defaultRules());
            closingServer.start();
            Socket slow = openIncompleteRestore(closingServer.port(), closingServer.policy().token(), 1024, 1);
            try {
                waitForSessionNonEmpty(closingWorkspace);
                Thread closer = new Thread(closingServer::close, "restore-close-test");
                closer.start();
                closer.join(5_000);
                assertFalse(closer.isAlive(), "客户端不 close 时，服务关闭也应打断上传并等 finally 完成");
                waitForSessionCleanup(closingWorkspace);
                Thread.sleep(100);
                assertSessionEmpty(closingWorkspace);
            } finally {
                slow.close();
                closingServer.close();
            }
        }
    }

    private static LocalServerApiTest.Raw.Res restore(byte[] productBytes, byte[] mappingBytes,
                                                       String passphrase, int version, String auth,
                                                       String productName, String mappingName)
            throws IOException {
        return restoreAt(port, auth, productBytes, mappingBytes, passphrase, version,
                productName, mappingName);
    }

    private static LocalServerApiTest.Raw.Res restoreAt(int targetPort, String auth,
                                                         byte[] productBytes, byte[] mappingBytes,
                                                         String passphrase, int version,
                                                         String productName, String mappingName)
            throws IOException {
        byte[] request = restoreRequest(productBytes, mappingBytes, passphrase, version,
                productName, mappingName);
        return LocalServerApiTest.Raw.req(targetPort, "POST", "/api/restore-import",
                "127.0.0.1:" + targetPort, null, auth, request,
                Map.of("Content-Type", "application/octet-stream"));
    }

    private static byte[] restoreRequest(byte[] productBytes, byte[] mappingBytes,
                                         String passphrase, int version,
                                         String productName, String mappingName) throws IOException {
        byte[] meta = Json.toBytes(Map.of(
                "productName", productName,
                "mappingName", mappingName,
                "passphrase", passphrase));
        ByteBuffer request = ByteBuffer.allocate(28 + meta.length + productBytes.length + mappingBytes.length)
                .order(ByteOrder.BIG_ENDIAN);
        request.putInt(0x4c4d5231).putInt(version).putInt(meta.length)
                .putLong(productBytes.length).putLong(mappingBytes.length)
                .put(meta).put(productBytes).put(mappingBytes);
        return request.array();
    }

    private static LocalServerApiTest.Raw.Res restoreWithRawMeta(byte[] meta,
                                                                 byte[] productBytes,
                                                                 byte[] mappingBytes,
                                                                 String auth) throws IOException {
        ByteBuffer request = ByteBuffer.allocate(28 + meta.length + productBytes.length + mappingBytes.length)
                .order(ByteOrder.BIG_ENDIAN);
        request.putInt(0x4c4d5231).putInt(1).putInt(meta.length)
                .putLong(productBytes.length).putLong(mappingBytes.length)
                .put(meta).put(productBytes).put(mappingBytes);
        return LocalServerApiTest.Raw.req(port, "POST", "/api/restore-import",
                "127.0.0.1:" + port, null, auth, request.array(),
                Map.of("Content-Type", "application/octet-stream"));
    }

    private static Socket openIncompleteRestore(int targetPort, String auth,
                                                long productLength, long mappingLength)
            throws IOException {
        byte[] meta = Json.toBytes(Map.of(
                "productName", "slow.txt", "mappingName", "slow.map.json",
                "passphrase", PASSPHRASE));
        ByteBuffer header = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
                .putInt(0x4c4d5231).putInt(1).putInt(meta.length)
                .putLong(productLength).putLong(mappingLength);
        long contentLength = 28L + meta.length + productLength + mappingLength;
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", targetPort), 2_000);
        OutputStream out = socket.getOutputStream();
        out.write(("POST /api/restore-import HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + targetPort + "\r\n"
                + "X-Litemask-Token: " + auth + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + contentLength + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(header.array());
        out.write(meta);
        out.flush();
        return socket;
    }

    private static LocalServerApiTest.Raw.Res waitUntilCancelable(int targetPort, String auth)
            throws Exception {
        LocalServerApiTest.Raw.Res last = null;
        for (int i = 0; i < 40; i++) {
            last = LocalServerApiTest.Raw.req(targetPort, "POST", "/api/restore-import-cancel",
                    "127.0.0.1:" + targetPort, null, auth, new byte[0]);
            if (last.body().contains("\"canceled\":true")) return last;
            Thread.sleep(25);
        }
        throw new AssertionError("还原任务未在时限内进入可取消态: "
                + (last == null ? "无响应" : last.body()));
    }

    private static boolean waitUntilCancelableFalse(int targetPort, String auth) throws Exception {
        LocalServerApiTest.Raw.Res response = LocalServerApiTest.Raw.req(targetPort, "POST",
                "/api/restore-import-cancel", "127.0.0.1:" + targetPort,
                null, auth, new byte[0]);
        assertEquals(200, response.status(), response.body());
        return response.body().contains("\"canceled\":true");
    }

    @SuppressWarnings("unchecked")
    private static byte[] rewriteMapping(Consumer<Map<String, Object>> edit) throws Exception {
        byte[] opened = SecretMap.open(mapping, PASSPHRASE.toCharArray());
        Map<String, Object> payload = Json.read(opened, Map.class);
        edit.accept(payload);
        return SecretMap.seal(Json.toBytes(payload), PASSPHRASE.toCharArray());
    }

    private static void assertSessionEmpty() throws IOException {
        assertSessionEmpty(workspace);
    }

    private static void assertSessionEmpty(Workspace target) throws IOException {
        try (var files = Files.list(target.sessionDir())) {
            assertEquals(0, files.count(), "失败后不得留下上传或还原临时文件");
        }
    }

    private static void waitForSessionCleanup(Workspace target) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (var files = Files.list(target.sessionDir())) {
                if (files.findAny().isEmpty()) return;
            }
            Thread.sleep(20);
        }
        assertSessionEmpty(target);
    }

    private static void waitForSessionNonEmpty(Workspace target) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (var files = Files.list(target.sessionDir())) {
                if (files.findAny().isPresent()) return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("还原上传未进入服务端临时会话目录");
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return entries;
    }
}
