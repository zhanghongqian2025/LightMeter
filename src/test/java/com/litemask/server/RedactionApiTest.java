package com.litemask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import com.litemask.docx.DocxFixture;
import com.litemask.ofd.OfdFixtures;
import com.litemask.pdf.PdfFixtures;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏 API 的端到端测试：真 socket、真文件、真下载。
 *
 * <p>和 {@link LocalServerApiTest} 的分工：那边验「服务挡不挡得住」，这边验
 * 「放进来之后东西对不对」。两边都必须走 HTTP——直接调 {@link DocumentService} 的话，
 * 请求体解析、文件名头、下载响应头这三段就没人验了，而它们恰恰是「文件传进来」时
 * 最容易出问题的地方。
 *
 * <p>判据一律看<b>下载回来的那个文件的内容</b>，不看接口自己报的命中数：
 * 接口说「脱了 2 处」和文件里真的没有原值，是两件事。
 */
class RedactionApiTest {

    private static final String KEEP_PHONE = "13800138000";
    private static final String REDACT_PHONE = "13900139000";

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

    /**
     * 上传头。<b>默认不含令牌</b>——需要令牌时由调用方显式加上。
     *
     * <p>这里踩过一次坑：早先这个方法把令牌一并塞进去，于是「无令牌应被拒」那条测试
     * 其实带着令牌通过，看起来像是令牌校验对新接口失效。真去追一遍才发现是测试自己骗了自己。
     * 把「无令牌」写成构造上就做不到，胜过写完再检查一遍。
     */
    private static Map<String, String> 上传头(String fileName, String contentType) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", contentType);
        h.put(ApiHandlers.FILENAME_HEADER, encodeName(fileName));
        return h;
    }

    private static Map<String, String> 上传头带令牌(String fileName, String contentType) {
        Map<String, String> h = new LinkedHashMap<>(上传头(fileName, contentType));
        h.putAll(tokenHeader);
        return h;
    }

    /** 文件名走百分号编码：HTTP 头是 ISO-8859-1 的，中文直接发会变成问号。 */
    private static String encodeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (byte b : name.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == '~' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static LocalServerApiTest.Raw.Res 上传(byte[] content, String fileName) throws IOException {
        return LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token,
                content, 上传头带令牌(fileName, "application/octet-stream"));
    }

    private static Map<String, String> 应用体() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.putAll(tokenHeader);
        return h;
    }

    private static JsonNode json(String body) throws IOException {
        return com.litemask.server.Json.mapper().readTree(body);
    }

    private static byte[] 样例Docx(String name) throws IOException {
        return Files.readAllBytes(DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "甲 " + KEEP_PHONE + " 乙 " + REDACT_PHONE))).writeTo(dir, name));
    }

    // ------------------------------------------------------------ 完整走一遍

    @Test
    @DisplayName("上传→排除→应用→下载：下载回来的文档里，被排除的值还在，另一个已经没了")
    void 完整流程() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(样例Docx("a.docx"), "a.docx");
        assertEquals(200, scan.status(), scan.body());
        JsonNode s = json(scan.body());

        assertEquals("DOCX", s.get("format").asText());
        assertEquals("scanned", s.get("stage").asText());
        assertEquals(2, s.get("summary").get("distinctHits").asInt(), scan.body());
        assertEquals(2, s.get("summary").get("occurrences").asInt(), scan.body());

        // 命中项必须带 (规则键, 原值) —— 这正是界面回传排除项时要用的键
        JsonNode hits = s.get("hits");
        assertTrue(hits.get(0).hasNonNull("ruleKey"), scan.body());
        assertTrue(hits.get(0).hasNonNull("original"), scan.body());
        assertEquals(1, hits.get(0).get("occurrences").asInt(),
                "occurrences 是这个值出现的次数，不是命中总数");
        assertTrue(hits.get(0).get("hasLocators").asBoolean(), "docx 应当有定位信息");

        String jobId = s.get("jobId").asText();

        // 排除第一个命中
        JsonNode first = hits.get(0);
        String applyBody = Json.mapper().writeValueAsString(Map.of(
                "jobId", jobId,
                "excluded", List.of(Map.of(
                        "ruleKey", first.get("ruleKey").asText(),
                        "original", first.get("original").asText()))));
        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                applyBody.getBytes(StandardCharsets.UTF_8),
                应用体());
        assertEquals(200, apply.status(), apply.body());

        JsonNode a = json(apply.body());
        assertEquals("applied", a.get("stage").asText());
        assertEquals(1, a.get("summary").get("excluded").asInt(), apply.body());
        assertEquals(1, a.get("summary").get("distinctHits").asInt(), apply.body());

        // 下载
        LocalServerApiTest.Raw.Res dl = LocalServerApiTest.Raw.req(port, "GET",
                "/api/download?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(200, dl.status(), dl.body());
        assertTrue(dl.headers().get("content-type").get(0).contains("wordprocessingml"),
                dl.headers().toString());
        assertTrue(dl.headers().get("content-disposition").get(0).contains("attachment"),
                dl.headers().toString());

        // **判据在文件内容上**：把下载回来的 docx 当 zip 打开看正文
        byte[] content = LocalServerApiTest.Raw.bodyBytes(port, "GET",
                "/api/download?jobId=" + jobId, "127.0.0.1:" + port, null, token, Map.of());
        Path downloaded = dir.resolve("downloaded.docx");
        Files.write(downloaded, content);

        String xml = DocxFixture.text(downloaded, "word/document.xml");
        assertTrue(xml.contains(first.get("original").asText()),
                "被排除的值应当原样保留: " + xml);
        JsonNode second = hits.get(1);
        assertFalse(xml.contains(second.get("original").asText()),
                "未排除的值应当已被脱敏: " + xml);
    }

    @Test
    @DisplayName("不排除任何项时下载到的就是预览件本身")
    void 零排除() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(样例Docx("b.docx"), "b.docx");
        JsonNode s = json(scan.body());
        String jobId = s.get("jobId").asText();

        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                Json.mapper().writeValueAsString(Map.of("jobId", jobId, "excluded", List.of()))
                        .getBytes(StandardCharsets.UTF_8),
                应用体());
        assertEquals(200, apply.status(), apply.body());
        assertEquals(0, json(apply.body()).get("summary").get("excluded").asInt(), apply.body());

        byte[] content = LocalServerApiTest.Raw.bodyBytes(port, "GET",
                "/api/download?jobId=" + jobId, "127.0.0.1:" + port, null, token, Map.of());
        Path downloaded = dir.resolve("downloaded-b.docx");
        Files.write(downloaded, content);
        String xml = DocxFixture.text(downloaded, "word/document.xml");
        assertFalse(xml.contains(KEEP_PHONE), "零排除时应当全部脱敏: " + xml);
        assertFalse(xml.contains(REDACT_PHONE), xml);
    }

    // ------------------------------------------------------------ 报告诚实性

    @Test
    @DisplayName("PDF 没有文本层时，notes 里必须明确说出来——0 命中不等于文档干净")
    void pdf无文本层必须报出来() throws IOException {
        // 画一个空页面：内容流里一个字都没有
        byte[] empty = Files.readAllBytes(PdfFixtures.writeLatin(dir.resolve("empty.pdf"),
                "BT /F1 12 Tf 50 700 Td () Tj ET"));

        LocalServerApiTest.Raw.Res scan = 上传(empty, "空.pdf");
        assertEquals(200, scan.status(), scan.body());
        JsonNode s = json(scan.body());

        assertEquals(0, s.get("summary").get("distinctHits").asInt(), scan.body());
        String notes = s.get("notes").toString();
        assertTrue(notes.contains("没有文本层"),
                "零命中必须区分「文档干净」与「没得脱」，notes 应说明后者: " + notes);
        assertTrue(notes.contains("OCR"), notes);
    }

    @Test
    @DisplayName("OFD 签章移除、数值单元格转文本等副作用必须在 notes 里报出")
    void ofd副作用报出() throws IOException {
        byte[] ofd = Files.readAllBytes(OfdFixtures.standard(dir, "s.ofd"));
        LocalServerApiTest.Raw.Res scan = 上传(ofd, "s.ofd");
        assertEquals(200, scan.status(), scan.body());
        JsonNode s = json(scan.body());
        assertEquals("OFD", s.get("format").asText());
        assertTrue(s.get("summary").get("occurrences").asInt() > 0, scan.body());
        // 各格式自己的计数要透传，不能被归一化吃掉
        assertTrue(s.get("stats").has("移除签章") || s.get("stats").has("文本对象"), scan.body());
    }

    // ------------------------------------------------------------ 请求校验

    @Test
    @DisplayName("Content-Type 不是 octet-stream 时拒绝——不能只靠令牌这一道")
    void contentType必须校验() throws IOException {
        byte[] docx = 样例Docx("c.docx");

        LocalServerApiTest.Raw.Res form = LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token, docx,
                上传头("c.docx", "application/x-www-form-urlencoded"));
        assertEquals(400, form.status(), "表单类型应被拒: " + form.body());
        assertTrue(form.body().contains("application/octet-stream"), form.body());

        LocalServerApiTest.Raw.Res json = LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token, docx,
                上传头("c.docx", "application/json"));
        assertEquals(400, json.status(), json.body());
    }

    @Test
    @DisplayName("apply 的 Content-Type 必须是 JSON")
    void apply要求JSON() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(样例Docx("d.docx"), "d.docx");
        String jobId = json(scan.body()).get("jobId").asText();

        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                ("{\"jobId\":\"" + jobId + "\"}").getBytes(StandardCharsets.UTF_8),
                上传头带令牌("x", "application/octet-stream"));
        assertEquals(400, res.status(), res.body());
    }

    @Test
    @DisplayName("不传令牌仍然被拒——M2-b 的新接口不能绕过 M2-a 的防线")
    void 新接口同样要令牌() throws IOException {
        byte[] docx = 样例Docx("e.docx");
        LocalServerApiTest.Raw.Res noToken = LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, null, docx, 上传头("e.docx", "application/octet-stream"));
        assertFalse(noToken.headers().toString().toLowerCase(java.util.Locale.ROOT)
                .contains("litemask-token"), "前提：这条请求确实没带令牌头");
        assertEquals(403, noToken.status(), "无令牌应 403: " + noToken.body());

        LocalServerApiTest.Raw.Res evilHost = LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "evil.com:" + port, null, token, docx, 上传头带令牌("e.docx", "application/octet-stream"));
        assertEquals(403, evilHost.status(), "伪造 Host 应 403: " + evilHost.body());

        LocalServerApiTest.Raw.Res evilOrigin = LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, "https://evil.com", token, docx,
                上传头带令牌("e.docx", "application/octet-stream"));
        assertEquals(403, evilOrigin.status(), "跨源应 403: " + evilOrigin.body());
    }

    // ------------------------------------------------------------ 参数与状态

    @Test
    @DisplayName("不支持的格式与无后缀都明确报错，不是静默忽略")
    void 不支持的格式() throws IOException {
        LocalServerApiTest.Raw.Res html = 上传("<h1>hi</h1>".getBytes(StandardCharsets.UTF_8), "a.html");
        assertEquals(400, html.status(), html.body());
        assertTrue(html.body().contains("不支持的文件类型"), html.body());

        LocalServerApiTest.Raw.Res none = 上传("x".getBytes(StandardCharsets.UTF_8), "noext");
        assertEquals(400, none.status(), none.body());
    }

    @Test
    @DisplayName("jobId 不存在时不泄露「存在但过期」还是「从来没有」")
    void 未知jobId() throws IOException {
        String a = json(上传(样例Docx("f.docx"), "f.docx").body()).get("jobId").asText();
        String b = json(上传(样例Docx("g.docx"), "g.docx").body()).get("jobId").asText();
        assertNotEquals(a, b, "每个任务应有独立的 id");

        LocalServerApiTest.Raw.Res unknown = LocalServerApiTest.Raw.req(port, "GET",
                "/api/download?jobId=" + "0".repeat(32),
                "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(400, unknown.status(), unknown.body());
        assertTrue(unknown.body().contains("不存在或已过期"), unknown.body());

        LocalServerApiTest.Raw.Res missing = LocalServerApiTest.Raw.req(port, "GET",
                "/api/download", "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(400, missing.status(), "缺 jobId 应报错: " + missing.body());
    }

    @Test
    @DisplayName("同一个任务不能反复应用——避免悄悄覆盖用户已经拿到的结果")
    void 不能重复应用() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(样例Docx("h.docx"), "h.docx");
        String jobId = json(scan.body()).get("jobId").asText();
        byte[] body = Json.mapper().writeValueAsString(
                Map.of("jobId", jobId, "excluded", List.of())).getBytes(StandardCharsets.UTF_8);

        assertEquals(200, LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token, body,
                Map.of("Content-Type", "application/json")).status());

        LocalServerApiTest.Raw.Res again = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token, body,
                Map.of("Content-Type", "application/json"));
        assertEquals(400, again.status(), again.body());
        assertTrue(again.body().contains("已经应用过"), again.body());
    }

    @Test
    @DisplayName("排除项对不上扫描结果时拒绝出件，而不是发一份没按要求处理的文档")
    void 无效排除项被拒() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(样例Docx("i.docx"), "i.docx");
        String jobId = json(scan.body()).get("jobId").asText();

        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                Json.mapper().writeValueAsString(Map.of("jobId", jobId, "excluded",
                        List.of(Map.of("ruleKey", "phone", "original", "13000000000"))))
                        .getBytes(StandardCharsets.UTF_8),
                应用体());

        assertEquals(400, apply.status(), apply.body());
        assertTrue(apply.body().contains("对不上"), apply.body());

        // 安全性质：既然请求被拒，就<b>不该</b>还能把这份文件下载走。
        // 「引擎照常脱敏」是引擎层的性质（ExclusionTest 已单独钉死），
        // 接口层选的是更严的一条——对不上就当场失败，不交出任何产物。
        LocalServerApiTest.Raw.Res dl = LocalServerApiTest.Raw.req(port, "GET",
                "/api/download?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(400, dl.status(), "被拒的请求不应留下可下载的产物: " + dl.body());
    }

    @Test
    @DisplayName("序号越界的排除项也被拒（界面拿的是旧清单时不会静默放过）")
    void 序号越界的排除项被拒() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(样例Docx("i2.docx"), "i2.docx");
        String jobId = json(scan.body()).get("jobId").asText();

        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                Json.mapper().writeValueAsString(Map.of("jobId", jobId, "excluded",
                        List.of(Map.of("ruleKey", "phone", "original", KEEP_PHONE, "occurrence", 7))))
                        .getBytes(StandardCharsets.UTF_8),
                应用体());

        assertEquals(400, apply.status(), apply.body());
        assertTrue(apply.body().contains("第 7 次出现"), apply.body());
    }

    @Test
    @DisplayName("报文里不写 occurrence 时按「整条不脱敏」处理，不悄悄变成「只排除第 0 次」")
    void 缺省序号等于整条排除() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(样例Docx("i3.docx"), "i3.docx");
        String jobId = json(scan.body()).get("jobId").asText();

        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                Json.mapper().writeValueAsString(Map.of("jobId", jobId, "excluded",
                        List.of(Map.of("ruleKey", "phone", "original", KEEP_PHONE))))
                        .getBytes(StandardCharsets.UTF_8),
                应用体());
        assertEquals(200, apply.status(), apply.body());

        byte[] content = LocalServerApiTest.Raw.bodyBytes(port, "GET",
                "/api/download?jobId=" + jobId, "127.0.0.1:" + port, null, token, Map.of());
        Path downloaded = dir.resolve("downloaded-i3.docx");
        Files.write(downloaded, content);
        String xml = DocxFixture.text(downloaded, "word/document.xml");
        assertTrue(xml.contains(KEEP_PHONE),
                "缺省应表示整条排除：Jackson 给缺失的 int 填 0 的话，这里会变成「只放过第 0 次」: " + xml);
    }

    @Test
    @DisplayName("残缺的排除项让请求失败，而不是悄悄少排除一条")
    void 残缺排除项被拒() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(样例Docx("j.docx"), "j.docx");
        String jobId = json(scan.body()).get("jobId").asText();

        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                ("{\"jobId\":\"" + jobId + "\",\"excluded\":[{\"ruleKey\":\"\",\"original\":\"x\"}]}")
                        .getBytes(StandardCharsets.UTF_8),
                应用体());
        assertEquals(400, apply.status(),
                "空规则键是请求本身不合法（400），不是服务内部错误（500）: " + apply.body());
        assertTrue(apply.body().contains("规则键"), apply.body());
    }

    @Test
    @DisplayName("上传的文件名只影响展示名与后缀，绝不参与落盘路径")
    void 上传文件名不参与路径() throws IOException {
        byte[] docx = 样例Docx("k.docx");
        // 先数一遍目录里已有多少：服务是 @BeforeAll 共享的，
        // 直接数总数会把它前面所有用例留下的文件也算进来（实测 19 个），那测的不是这件事。
        java.util.Set<String> before = 列出文件名();

        LocalServerApiTest.Raw.Res scan = 上传(docx, "../../../../../../etc/合同.docx");
        assertEquals(200, scan.status(), scan.body());
        JsonNode s = json(scan.body());
        assertEquals("合同.docx", s.get("fileName").asText(),
                "应只剩 basename（并且中文没被问号吃掉）: " + s.get("fileName").asText());
        assertEquals("合同.redacted.docx", s.get("downloadName").asText(), scan.body());

        java.util.Set<String> after = 列出文件名();
        java.util.Set<String> added = new java.util.LinkedHashSet<>(after);
        added.removeAll(before);
        assertEquals(2, added.size(), "一次扫描应只新增原件与预览件两份: " + added);
        for (String name : added) {
            assertTrue(name.matches("[0-9a-f]{32}\\.docx"),
                    "磁盘名必须由服务端生成，不能含客户端文件名成分: " + name);
        }
    }

    private static java.util.Set<String> 列出文件名() throws IOException {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        try (var kids = Files.list(workspace.sessionDir())) {
            kids.forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }
}
