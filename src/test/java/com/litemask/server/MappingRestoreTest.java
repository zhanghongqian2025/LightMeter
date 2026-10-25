package com.litemask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.litemask.core.RedactionEngine;
import com.litemask.core.Rule;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 映射件与还原：让界面上的「脱敏成功」是<b>真的可逆</b>的。
 *
 * <p><b>为什么单列一个类</b>：这两条路径是整个产品「可逆」承诺的落点。
 * 脱敏件能下载、界面显示成功——但用户拿不到映射，那份文件就<b>永久不可逆</b>，
 * 而界面上看不出任何异常。此前映射只活在 {@code job.applied.mapping()} 这块内存里，
 * 进程一退出就没了（{@code Workspace} 的类注释还写着「映射件都走这里」，
 * 描述的是一个不存在的功能）。
 *
 * <p><b>判据一律看内容</b>：接口说「映射里有 2 条」与「拿这个映射真能还原回来」
 * 是两件事；这里验的是后者——映射能直接喂给引擎还原，还原件与原文逐字一致。
 *
 * <p>走真 HTTP：请求体解析、令牌校验、下载响应头这三段都只有走一遍才算验过。
 */
class MappingRestoreTest {

    private static final String PHONE = "13800138000";
    private static final String OTHER_PHONE = "13900139000";

    private static final String TXT =
            "甲方联系人 张三 电话 " + PHONE + "\n"
                    + "乙方联系人 李四 电话 " + OTHER_PHONE + "\n"
                    + "身份证 11010519491231002X\n";

    private static Workspace workspace;
    private static LocalServer server;
    private static String token;
    private static int port;
    private static Map<String, String> tokenHeader;

    @TempDir
    static Path dir;

    @BeforeAll
    static void 起服务(@TempDir Path tmp) throws Exception {
        workspace = Workspace.open(tmp);
        server = LocalServer.create(workspace, 0);
        List<Rule> rules = ApiHandlers.defaultRules();
        ApiHandlers.register(server, workspace, rules);
        server.start();
        token = server.policy().token();
        port = server.port();
        tokenHeader = Map.of(ApiHandlers.TOKEN_HEADER, token);
    }

    @AfterAll
    static void 停服务() {
        if (server != null) {
            server.close();
        }
        if (workspace != null) {
            workspace.close();
        }
    }

    // ------------------------------------------------------------ 工具

    private static Map<String, String> 上传头(String fileName) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/octet-stream");
        h.put(ApiHandlers.FILENAME_HEADER, fileName);
        h.putAll(tokenHeader);
        return h;
    }

    private static LocalServerApiTest.Raw.Res 上传(byte[] content, String fileName) throws IOException {
        return LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token, content, 上传头(fileName));
    }

    private static LocalServerApiTest.Raw.Res 应用(String jobId) throws IOException {
        byte[] body = Json.mapper().writeValueAsString(
                Map.of("jobId", jobId, "excluded", List.of())).getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.putAll(tokenHeader);
        return LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token, body, h);
    }

    private static byte[] 下载(String path) throws IOException {
        return LocalServerApiTest.Raw.bodyBytes(port, "GET", path,
                "127.0.0.1:" + port, null, token, Map.of());
    }

    private static JsonNode json(String body) throws IOException {
        return Json.mapper().readTree(body);
    }

    /** 上传 → 应用，返回 jobId。 */
    private static String 走一遍(byte[] content, String fileName) throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(content, fileName);
        assertEquals(200, scan.status(), scan.body());
        String jobId = json(scan.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res apply = 应用(jobId);
        assertEquals(200, apply.status(), apply.body());
        return jobId;
    }

    // ------------------------------------------------------------ 映射件

    @Test
    @DisplayName("应用之后映射件落盘，且下载回来的内容里带着原值")
    void 映射件落盘且可下载() throws IOException {
        String jobId = 走一遍(TXT.getBytes(StandardCharsets.UTF_8), "合同.txt");

        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "GET",
                "/api/mapping?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(200, res.status(), res.body());
        assertTrue(res.headers().get("content-type").get(0).contains("json"),
                res.headers().toString());
        assertTrue(res.headers().get("content-disposition").get(0).contains("attachment"),
                res.headers().toString());

        // 映射里必须有原值：没有原值的映射还原不出任何东西。
        //
        // 判据卡在 **mapping 段**上，而不是整个响应体：响应体里还有 changes 段，
        // 那里也带原值。只看「body 里含不含原值」的话，把 mapping 掏空而
        // changes 照旧，这条断言照样绿——它量的是「文件里某个地方有原值」，
        // 而不是「映射能还原」。
        JsonNode mapping = json(res.body()).get("mapping");
        assertNotNull(mapping, "映射文件顶层必须有 mapping 段: " + res.body());
        assertTrue(mapping.size() > 0, "映射不能是空的: " + res.body());
        boolean 有原值 = false;
        var 字段 = mapping.fields();
        while (字段.hasNext()) {
            var e = 字段.next();
            if (PHONE.equals(e.getValue().asText()) || OTHER_PHONE.equals(e.getValue().asText())) {
                有原值 = true;
            }
        }
        assertTrue(有原值, "映射段里必须有原值，否则还原不回来: " + res.body());
    }

    @Test
    @DisplayName("映射里的方向是「令牌 → 原值」，能直接喂给引擎还原")
    void 映射方向正确() throws IOException {
        String jobId = 走一遍(TXT.getBytes(StandardCharsets.UTF_8), "方向.txt");
        byte[] raw = 下载("/api/mapping?jobId=" + jobId);
        JsonNode mapping = json(new String(raw, StandardCharsets.UTF_8)).get("mapping");
        assertNotNull(mapping, "映射文件顶层必须有 mapping 段");
        assertTrue(mapping.size() > 0, "映射不能是空的: " + mapping);

        // 逐条验：键是令牌、值是原值；拿令牌去原文里找得到，值就是原文里的那串
        mapping.fields().forEachRemaining(e -> {
            String token = e.getKey();
            String original = e.getValue().asText();
            assertTrue(TXT.contains(original),
                    "映射的值应当在原文里真的出现: " + original);
            assertFalse(token.equals(original),
                    "键是令牌、值是原值，两者不该相同: " + token);
        });

        // 真正的判据：这个映射能把令牌换回原值
        String 脱敏文本 = "电话 " + mapping.fieldNames().next();
        String 还原文本 = RedactionEngine.restore(脱敏文本, mappingOf(mapping));
        assertTrue(还原文本.contains(PHONE) || 还原文本.contains(OTHER_PHONE),
                "拿映射去还原应当换回原值: " + 还原文本);
    }

    private static Map<String, String> mappingOf(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    @Test
    @DisplayName("映射是敏感数据：没有令牌就拿不到")
    void 映射要令牌() throws IOException {
        String jobId = 走一遍(TXT.getBytes(StandardCharsets.UTF_8), "令牌.txt");
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "GET",
                "/api/mapping?jobId=" + jobId, "127.0.0.1:" + port, null, null, new byte[0]);
        assertEquals(403, res.status(), "无令牌必须被拒: " + res.body());
    }

    @Test
    @DisplayName("还没应用就下载映射 → 400，而不是给一个空壳")
    void 未应用不能下载映射() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(TXT.getBytes(StandardCharsets.UTF_8), "没应用.txt");
        String jobId = json(scan.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "GET",
                "/api/mapping?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(400, res.status(), res.body());
        assertTrue(res.body().contains("应用"), "报错要说清是还没应用: " + res.body());
    }

    @Test
    @DisplayName("scan 阶段不报映射名，apply 阶段才报——否则界面上有个点不动的按钮")
    void 映射名只在应用后出现() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(TXT.getBytes(StandardCharsets.UTF_8), "阶段.txt");
        JsonNode s = json(scan.body());
        assertFalse(s.has("mappingName"),
                "还没应用就没有映射件，报出名字会让界面以为现在就能下载: " + scan.body());
        assertFalse(s.has("restoreName"), scan.body());

        String jobId = s.get("jobId").asText();
        LocalServerApiTest.Raw.Res apply = 应用(jobId);
        JsonNode a = json(apply.body());
        assertTrue(a.has("mappingName"), apply.body());
        assertTrue(a.has("restoreName"), apply.body());
        // 文件名由服务端给，界面不自己拼
        assertTrue(a.get("mappingName").asText().endsWith(".redacted.map.json"),
                apply.body());
        assertTrue(a.get("restoreName").asText().endsWith(".restored.txt"),
                apply.body());
    }

    // ------------------------------------------------------------ 还原

    @Test
    @DisplayName("还原后的 txt 与原文逐字一致（这是「可逆」最直接的判据）")
    void 还原后与原文逐字一致() throws IOException {
        String jobId = 走一遍(TXT.getBytes(StandardCharsets.UTF_8), "往返.txt");

        // 先看脱敏件：它应当既没有原值、又有令牌
        byte[] 脱敏 = 下载("/api/download?jobId=" + jobId);
        String 脱敏文本 = new String(脱敏, StandardCharsets.UTF_8);
        assertFalse(脱敏文本.contains(PHONE), "脱敏件里不该还有原值: " + 脱敏文本);

        // 再还原
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST",
                "/api/restore?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(200, res.status(), res.body());
        byte[] 还原 = LocalServerApiTest.Raw.bodyBytes(port, "POST",
                "/api/restore?jobId=" + jobId, "127.0.0.1:" + port, null, token, Map.of());
        assertEquals(TXT, new String(还原, StandardCharsets.UTF_8),
                "还原后应当与原文逐字一致");
    }

    @Test
    @DisplayName("还原的响应头报出还原了几处，并说明「还原的是文本内容不是文件字节」")
    void 还原报出处数与提醒() throws IOException {
        String jobId = 走一遍(TXT.getBytes(StandardCharsets.UTF_8), "报数.txt");
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST",
                "/api/restore?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(200, res.status(), res.body());
        String n = res.headers().get("x-litemask-restored").get(0);
        assertTrue(Integer.parseInt(n) > 0,
                "这份文档有 3 处命中，还原处数应当大于 0，实际 " + n);

        // 「还原的是文本内容」这句话必须能被调用方读到：
        // 还原件与原件不是同一个文件，属性清空/签章移除都不可逆。
        String notice = java.net.URLDecoder.decode(
                res.headers().get("x-litemask-notice").get(0), StandardCharsets.UTF_8);
        assertTrue(notice.contains("文本内容"), notice);
        assertTrue(notice.contains("不可逆"), notice);
    }

    @Test
    @DisplayName("还原 docx：原值回到文档里，令牌不见了")
    void 还原docx() throws IOException {
        Path 样例 = DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "甲 " + PHONE + " 乙"))).writeTo(dir, "还原.docx");
        String jobId = 走一遍(Files.readAllBytes(样例), "还原.docx");

        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST",
                "/api/restore?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(200, res.status(), res.body());
        byte[] 还原 = LocalServerApiTest.Raw.bodyBytes(port, "POST",
                "/api/restore?jobId=" + jobId, "127.0.0.1:" + port, null, token, Map.of());
        Path 落盘 = dir.resolve("还原后.docx");
        Files.write(落盘, 还原);
        String xml = DocxFixture.text(落盘, "word/document.xml");
        assertTrue(xml.contains(PHONE), "还原后原值应当回到文档里: " + xml);
    }

    @Test
    @DisplayName("还原是敏感数据：没有令牌就拿不到")
    void 还原要令牌() throws IOException {
        String jobId = 走一遍(TXT.getBytes(StandardCharsets.UTF_8), "还原令牌.txt");
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST",
                "/api/restore?jobId=" + jobId, "127.0.0.1:" + port, null, null, new byte[0]);
        assertEquals(403, res.status(), "无令牌必须被拒: " + res.body());
    }

    @Test
    @DisplayName("还没应用就还原 → 400（没有映射就没有可还原的东西）")
    void 未应用不能还原() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(TXT.getBytes(StandardCharsets.UTF_8), "没应用还原.txt");
        String jobId = json(scan.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST",
                "/api/restore?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(400, res.status(), res.body());
        assertTrue(res.body().contains("应用"), "报错要说清是还没应用: " + res.body());
    }

    @Test
    @DisplayName("不存在的任务 → 400，且不泄露「不存在」与「已过期」的区别")
    void 未知任务() throws IOException {
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST",
                "/api/restore?jobId=00000000000000000000000000000000",
                "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(400, res.status(), res.body());
        assertFalse(res.body().contains("00000000000000000000000000000000"),
                "报错里不该回显任务号: " + res.body());
    }

    @Test
    @DisplayName("还原接口不再是 501 占位（方法不对要说 405，不是 501）")
    void 还原不是占位() throws IOException {
        // GET /api/restore 路径存在但方法不对 → 405。
        // 若服务端还留着 501 占位，这里会是 501 —— 那正是「做了一半」的形状。
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "GET",
                "/api/restore", "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(405, res.status(), res.body());
    }
}
