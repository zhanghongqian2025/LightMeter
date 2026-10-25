package com.litemask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.litemask.docx.DocxFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台任务接口的<b>线协议</b>：提交、轮询、取消这三段怎么说话。
 *
 * <p>走真 HTTP：请求体解析、令牌校验、状态码、报文形状这四段，
 * 只有真的发一遍请求才算验过。用内部方法调一遍，验的就只是「我调的那个方法对不对」。
 *
 * <p><b>任务结果与同步接口同形状</b>：界面拿到任务结果之后直接就能用，
 * 不必再调一次 {@code /api/scan}。这条不变式由这里的断言守住——
 * 两边的形状一旦分叉，界面就会显示一份空清单，而界面上看不出任何异常。
 */
class TaskApiTest {

    private static final String PHONE = "13800138000";

    private static Workspace workspace;
    private static LocalServer server;
    private static String token;
    private static int port;
    private static Path 夹具目录;

    /**
     * 第二个服务：上传上限被调到 1KB。
     *
     * <p>为什么要有它：上限判定若只能在 API 层验，就得真传 256MB——
     * 那要么慢到不可接受，要么为了迁就测试把上限改小，
     * 而改小的上限就不是生产用的那个上限了，等于测了另一个东西。
     * 把上限做成工作区的实例参数之后，测试可以把它调到 1KB，
     * <b>测的是同一段代码、同一个判定</b>。
     */
    private static Workspace 小工作区;
    private static LocalServer 小服务;
    private static String 小令牌;
    private static int 小端口;

    @BeforeAll
    static void 起服务(@TempDir Path tmp) throws Exception {
        workspace = Workspace.open(tmp);
        server = LocalServer.create(workspace, 0);
        ApiHandlers.register(server, workspace, ApiHandlers.defaultRules());
        server.start();
        token = server.policy().token();
        port = server.port();
        夹具目录 = Files.createDirectories(tmp.resolve("夹具"));

        小工作区 = Workspace.open(tmp.resolve("小工作区"), 1024);
        小服务 = LocalServer.create(小工作区, 0);
        ApiHandlers.register(小服务, 小工作区, ApiHandlers.defaultRules());
        小服务.start();
        小令牌 = 小服务.policy().token();
        小端口 = 小服务.port();
    }

    @AfterAll
    static void 停服务() {
        if (server != null) {
            server.close();
        }
        if (workspace != null) {
            workspace.close();
        }
        if (小服务 != null) {
            小服务.close();
        }
        if (小工作区 != null) {
            小工作区.close();
        }
    }

    /** 会话目录里现在有几个普通文件。 */
    private static long 会话文件数() throws IOException {
        try (var s = Files.list(workspace.sessionDir())) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    // ------------------------------------------------------------ 工具

    private static byte[] docx(int 段数) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 段数; i++) {
            body.append(DocxFixture.paragraph(
                    DocxFixture.run(null, "第" + i + "条 联系电话 " + PHONE)));
        }
        Path p = DocxFixture.simple(body.toString())
                .writeTo(夹具目录, "样本-" + 段数 + "-" + System.nanoTime() + ".docx");
        return Files.readAllBytes(p);
    }

    private static Map<String, String> 上传头(String fileName) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/octet-stream");
        h.put(ApiHandlers.FILENAME_HEADER, fileName);
        h.put(ApiHandlers.TOKEN_HEADER, token);
        return h;
    }

    private static LocalServerApiTest.Raw.Res 提交扫描(byte[] body, String name) throws IOException {
        return LocalServerApiTest.Raw.req(port, "POST", "/api/task-scan",
                "127.0.0.1:" + port, null, token, body, 上传头(name));
    }

    private static LocalServerApiTest.Raw.Res 提交应用(String jobId, List<Map<String, String>> excluded)
            throws IOException {
        byte[] body = Json.mapper().writeValueAsString(
                Map.of("jobId", jobId, "excluded", excluded)).getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put(ApiHandlers.TOKEN_HEADER, token);
        return LocalServerApiTest.Raw.req(port, "POST", "/api/task-apply",
                "127.0.0.1:" + port, null, token, body, h);
    }

    private static LocalServerApiTest.Raw.Res 查任务(String taskId) throws IOException {
        return LocalServerApiTest.Raw.req(port, "GET", "/api/task?taskId=" + taskId,
                "127.0.0.1:" + port, null, token, new byte[0], Map.of());
    }

    private static LocalServerApiTest.Raw.Res 取消任务(String taskId) throws IOException {
        return LocalServerApiTest.Raw.req(port, "POST", "/api/task-cancel?taskId=" + taskId,
                "127.0.0.1:" + port, null, token, new byte[0], Map.of());
    }

    private static JsonNode json(LocalServerApiTest.Raw.Res res) throws IOException {
        return Json.mapper().readTree(res.body());
    }

    /**
     * 一直轮询到任务走到终态，把每次看到的进度都记下来。
     *
     * @return 终态那一次的报文
     */
    private static JsonNode 轮询到终态(String taskId, List<Integer> 进度样本) throws Exception {
        long 截止 = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < 截止) {
            LocalServerApiTest.Raw.Res res = 查任务(taskId);
            assertEquals(200, res.status(), "查任务应返回 200，实际 " + res.status() + "：" + res.body());
            JsonNode t = json(res);
            进度样本.add(t.path("done").asInt(-1));
            String status = t.path("status").asText("");
            if (!"running".equals(status)) {
                return t;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等了 30 秒，任务还没走到终态");
    }

    // ------------------------------------------------------------ 提交与轮询

    @Test
    @DisplayName("提交扫描任务：202 + 任务号 + running")
    void 提交扫描任务() throws Exception {
        LocalServerApiTest.Raw.Res res = 提交扫描(docx(3), "样本.docx");
        assertEquals(202, res.status(), "提交应当立刻受理，实际 " + res.status() + "：" + res.body());
        JsonNode j = json(res);
        assertTrue(j.path("ok").asBoolean(), "应带 ok=true");
        String taskId = j.path("taskId").asText("");
        assertFalse(taskId.isBlank(), "必须给出任务号，否则界面没法轮询");
        assertEquals("running", j.path("status").asText());
    }

    @Test
    @DisplayName("轮询到完成：带回命中清单与作业号")
    void 轮询到完成() throws Exception {
        LocalServerApiTest.Raw.Res res = 提交扫描(docx(3), "样本.docx");
        String taskId = json(res).path("taskId").asText();
        JsonNode 终 = 轮询到终态(taskId, new ArrayList<>());

        assertEquals("done", 终.path("status").asText(), "任务应完成，实际 " + 终);
        JsonNode result = 终.path("result");
        assertTrue(result.isObject(), "完成的任务必须带回 result");
        // 与同步接口同形状：界面拿到就能直接渲染
        assertTrue(result.path("hits").isArray() && result.path("hits").size() > 0,
                "结果里应有命中清单，实际 " + result.path("hits"));
        assertFalse(result.path("jobId").asText("").isBlank(), "结果里应有作业号");
        assertTrue(result.path("summary").path("occurrences").asInt() > 0, "结果里应有命中处数");
        assertEquals("scanned", result.path("stage").asText());
        assertNotNull(终.path("elapsedMs"));
    }

    /**
     * 进度只能往前，不能退。
     *
     * <p>进度倒退会让用户以为程序在原地打转——那种界面比没有进度条更糟。
     */
    @Test
    @DisplayName("轮询期间进度单调不减")
    void 进度单调不减() throws Exception {
        LocalServerApiTest.Raw.Res res = 提交扫描(docx(400), "大样本.docx");
        String taskId = json(res).path("taskId").asText();
        List<Integer> 样本 = new ArrayList<>();
        JsonNode 终 = 轮询到终态(taskId, 样本);

        int 上一 = Integer.MIN_VALUE;
        for (int 一次 : 样本) {
            assertTrue(一次 >= 上一, "进度倒退了：" + 上一 + " → " + 一次);
            上一 = 一次;
        }
        assertTrue(终.path("done").asInt() >= 1, "跑完的文档至少应报过一次进度");
        assertEquals(-1, 终.path("total").asInt(1), "总量事先算不出来，应报 -1");
    }

    // ------------------------------------------------------------ 应用

    @Test
    @DisplayName("提交应用任务：完成后带回映射件与还原件的名字")
    void 提交应用任务() throws Exception {
        // 先用同步接口扫一份，拿到 jobId
        LocalServerApiTest.Raw.Res 扫 = LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token, docx(3), 上传头("样本.docx"));
        assertEquals(200, 扫.status(), "扫描失败：" + 扫.body());
        String jobId = json(扫).path("jobId").asText();

        LocalServerApiTest.Raw.Res 提交 = 提交应用(jobId, List.of());
        assertEquals(202, 提交.status(), "应用任务应立刻受理：" + 提交.body());
        String taskId = json(提交).path("taskId").asText();

        JsonNode 终 = 轮询到终态(taskId, new ArrayList<>());
        assertEquals("done", 终.path("status").asText(), "应用任务应完成，实际 " + 终);
        JsonNode result = 终.path("result");
        assertFalse(result.path("mappingName").asText("").isBlank(),
                "应用完成后必须给出映射件名字——没有它那份文件就永久不可逆");
        assertFalse(result.path("restoreName").asText("").isBlank(), "应用完成后必须给出还原件名字");
        assertEquals("applied", result.path("stage").asText());
    }

    // ------------------------------------------------------------ 取消

    @Test
    @DisplayName("取消一个已结束的任务：不改变它的状态")
    void 取消已结束的任务() throws Exception {
        LocalServerApiTest.Raw.Res res = 提交扫描(docx(2), "样本.docx");
        String taskId = json(res).path("taskId").asText();
        轮询到终态(taskId, new ArrayList<>());

        LocalServerApiTest.Raw.Res 取消 = 取消任务(taskId);
        assertEquals(200, 取消.status(), "取消请求应被受理：" + 取消.body());
        assertEquals("canceling", json(取消).path("status").asText());

        // 关键：已跑完的任务不能被改成 canceled——磁盘上明明有一份成品。
        LocalServerApiTest.Raw.Res 再查 = 查任务(taskId);
        assertEquals(200, 再查.status());
        JsonNode 终 = json(再查);
        assertEquals("done", 终.path("status").asText(),
                "已跑完的任务不能变成 canceled：" + 终);
    }

    @Test
    @DisplayName("未知任务号：查与取消都返回 400")
    void 未知任务号() throws Exception {
        LocalServerApiTest.Raw.Res 查 = 查任务("根本没有这个任务号");
        assertEquals(400, 查.status(), "查不存在的任务应返回 400，实际 " + 查.status());
        assertFalse(json(查).path("ok").asBoolean(), "失败时 ok 必须是 false");

        LocalServerApiTest.Raw.Res 取消 = 取消任务("根本没有这个任务号");
        assertEquals(400, 取消.status(), "取消不存在的任务应返回 400，实际 " + 取消.status());
    }

    // ------------------------------------------------------------ 提交时就拒

    /**
     * 不支持的文件类型：<b>提交时就拒</b>。
     *
     * <p>以前是「先收下、任务跑完才发现不支持」：用户要等好几秒，
     * 而且界面上先显示「已受理」再变「失败」——
     * 明明上传那一刻就能判定的事。
     */
    @Test
    @DisplayName("不支持的后缀：提交时同步 400（不用等任务跑完才失败）")
    void 不支持的后缀提交时就拒() throws Exception {
        long 前 = 会话文件数();
        LocalServerApiTest.Raw.Res res = 提交扫描("随便什么".getBytes(StandardCharsets.UTF_8), "样本.exe");
        assertEquals(400, res.status(),
                "不支持的后缀应当在提交时就被拒，实际 " + res.status() + "：" + res.body());
        JsonNode j = json(res);
        assertFalse(j.path("ok").asBoolean(), "拒绝时 ok 必须是 false");
        assertTrue(j.path("error").asText("").contains("不支持"),
                "错误要说清楚为什么：" + j.path("error").asText());
        assertTrue(j.path("taskId").asText("").isBlank(),
                "被拒的提交不该给出任务号——没有任务，也就没有轮询的对象");
        assertEquals(前, 会话文件数(),
                "被拒的上传不该在磁盘上留下任何文件：用户以为没上传成功，原文档却还在");
    }

    /**
     * 超限：提交时同步 400。
     *
     * <p>用它自己的小上限服务来触发，不需要真造 256MB。
     */
    @Test
    @DisplayName("上传超限：提交时同步 400（不是 500，也不是先收下再失败）")
    void 超限提交时就拒() throws Exception {
        byte[] 太大 = new byte[4096];
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/octet-stream");
        h.put(ApiHandlers.FILENAME_HEADER, "大文件.docx");
        h.put(ApiHandlers.TOKEN_HEADER, 小令牌);
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(小端口, "POST", "/api/task-scan",
                "127.0.0.1:" + 小端口, null, 小令牌, 太大, h);
        assertEquals(400, res.status(),
                "超限应当是 400（用户发错了），实际 " + res.status() + "：" + res.body());
        assertTrue(json(res).path("error").asText("").contains("上限"),
                "错误要说清楚是超限：" + json(res).path("error").asText());
    }

    /**
     * 同步接口超限也返 400。
     *
     * <p><b>这条以前是 500</b>：同步路径把请求体读成一个 byte[]，
     * 超限时抛的是「请求体太大」这个 IOException，
     * 而分发层的兜底 catch 会把它接成 500「服务内部错误」。
     * 用户于是看到「服务坏了」——实际上服务好好的，
     * 是他把文件传大了。这两件事必须分开。
     */
    @Test
    @DisplayName("同步接口超限也返 400（以前是 500：把「你发错了」说成「服务坏了」）")
    void 同步接口超限返400() throws Exception {
        byte[] 太大 = new byte[4096];
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/octet-stream");
        h.put(ApiHandlers.FILENAME_HEADER, "大文件.docx");
        h.put(ApiHandlers.TOKEN_HEADER, 小令牌);
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(小端口, "POST", "/api/scan",
                "127.0.0.1:" + 小端口, null, 小令牌, 太大, h);
        assertEquals(400, res.status(),
                "用户把文件传大了是 400，不是 500；实际 " + res.status() + "：" + res.body());
        assertTrue(json(res).path("error").asText("").contains("上限"),
                "错误要说清楚是超限：" + json(res).path("error").asText());
    }

    // ------------------------------------------------------------ 鉴权与方法

    @Test
    @DisplayName("四个任务接口都要令牌（缺令牌一律 403）")
    void 四个接口都要令牌() throws Exception {
        byte[] body = docx(1);
        Map<String, String> 无令牌头 = new LinkedHashMap<>();
        无令牌头.put("Content-Type", "application/octet-stream");
        无令牌头.put(ApiHandlers.FILENAME_HEADER, "样本.docx");

        assertEquals(403, LocalServerApiTest.Raw.req(port, "POST", "/api/task-scan",
                        "127.0.0.1:" + port, null, null, body, 无令牌头).status(),
                "提交扫描任务缺令牌应 403");
        assertEquals(403, LocalServerApiTest.Raw.req(port, "POST", "/api/task-apply",
                        "127.0.0.1:" + port, null, null, new byte[0], Map.of()).status(),
                "提交应用任务缺令牌应 403");
        assertEquals(403, LocalServerApiTest.Raw.req(port, "GET", "/api/task?taskId=x",
                        "127.0.0.1:" + port, null, null, new byte[0], Map.of()).status(),
                "查任务缺令牌应 403");
        assertEquals(403, LocalServerApiTest.Raw.req(port, "POST", "/api/task-cancel?taskId=x",
                        "127.0.0.1:" + port, null, null, new byte[0], Map.of()).status(),
                "取消任务缺令牌应 403");
    }

    @Test
    @DisplayName("方法不对返回 405（路径存在但方法错）")
    void 方法不对返回405() throws Exception {
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST", "/api/task",
                "127.0.0.1:" + port, null, token, new byte[0], Map.of());
        assertEquals(405, res.status(), "对只读接口发 POST 应 405，实际 " + res.status());
    }

    @Test
    @DisplayName("健康检查报出在跑的任务数（界面卡住时可核对）")
    void 健康检查报任务数() throws Exception {
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "GET", "/api/health",
                "127.0.0.1:" + port, null, null, new byte[0], Map.of());
        assertEquals(200, res.status());
        JsonNode j = json(res);
        assertTrue(j.path("maxTasks").asInt() > 0, "应报出任务表上限");
        assertTrue(j.path("runningTasks").asInt(-1) >= 0, "应报出在跑的任务数");
    }
}
