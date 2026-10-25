package com.litemask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.litemask.core.EntitySource;
import com.litemask.core.Exclusions;
import com.litemask.core.Match;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「只保留第 N 处」（位置级排除）的端到端验证。
 *
 * <p>这一块最容易出的错不是「排除没生效」，而是<b>序号悄悄对不上</b>——
 * 用户勾了第 2 处，文件里改的却是第 3 处，而且程序看起来一切正常。
 * 所以这里的判据分两类：
 * <ol>
 *   <li><b>下发的序号必须与文件里的位置一一对应</b>：第 i 处位置说明带的就是第 i 次的序号。</li>
 *   <li><b>拿那个序号去排除，文件里留下的必须就是那一处</b>——判据看<b>下载回来的文件</b>，
 *       不看接口自己报的命中数。</li>
 * </ol>
 *
 * <p>还有一条负向判据不能少：<b>整条排除（值级）必须仍然覆盖所有出现处</b>。
 * 只断言「该保留的留下了」的话，一个「整篇都不脱」的错误实现照样能通过所有用例——
 * 那正好是最危险的方向。
 */
class PositionExclusionTest {

    private static final String PHONE = "13800138000";

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

    // ============================================================ 序号 ↔ 文件位置

    @Test
    @DisplayName("每处位置都带着自己的序号：第 i 条位置说明就是第 i 次出现")
    void 序号与位置一一对应() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(三段Docx("a.docx"), "a.docx");
        assertEquals(200, scan.status(), scan.body());
        JsonNode s = json(scan.body());

        JsonNode hit = 命中(s, PHONE);
        assertEquals(3, hit.get("occurrences").asInt(), scan.body());

        JsonNode locs = hit.get("locators");
        assertEquals(3, locs.size(), "三处出现都该有位置说明: " + locs);
        for (int i = 0; i < 3; i++) {
            assertEquals(i, 序号(locs.get(i)),
                    "第 " + i + " 处位置的序号必须就是 " + i + "，不能靠界面数出来: " + locs);
            assertTrue(locs.get(i).get("locator").asText().contains("第" + i + "段"),
                    "位置说明与序号必须对得上，实际: " + locs);
        }
    }

    @Test
    @DisplayName("勾「第 1 次」：文件里留下的就是第 1 段，前后两段照常脱敏")
    void 勾第N处只留那一处() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(三段Docx("b.docx"), "b.docx");
        assertEquals(200, scan.status(), scan.body());
        JsonNode s = json(scan.body());
        String jobId = s.get("jobId").asText();
        JsonNode hit = 命中(s, PHONE);

        // 序号直接从服务端下发的位置说明里取——界面上就是这么用的。
        int occurrence = 序号(hit.get("locators").get(1));
        assertEquals(1, occurrence, "前提：第 1 处的序号应当是 1");

        String body = Json.mapper().writeValueAsString(Map.of(
                "jobId", jobId,
                "excluded", List.of(Map.of(
                        "ruleKey", hit.get("ruleKey").asText(),
                        "original", hit.get("original").asText(),
                        "occurrence", occurrence))));
        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                body.getBytes(StandardCharsets.UTF_8), 应用体());
        assertEquals(200, apply.status(), apply.body());

        String xml = 下载正文(jobId);
        assertEquals(1, 出现次数(xml, PHONE),
                "只应保留被勾的那一处，实际: " + xml);
        assertTrue(xml.contains("乙 " + PHONE),
                "保留的应是第 1 段（乙），实际: " + xml);
        assertFalse(xml.contains("甲 " + PHONE), "第 0 段应当照常脱敏: " + xml);
        assertFalse(xml.contains("丙 " + PHONE), "第 2 段应当照常脱敏: " + xml);
    }

    @Test
    @DisplayName("负向判据：值级排除（不写序号）仍然覆盖全部出现处")
    void 不写序号仍然整条排除() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(三段Docx("c.docx"), "c.docx");
        JsonNode s = json(scan.body());
        String jobId = s.get("jobId").asText();
        JsonNode hit = 命中(s, PHONE);

        String body = Json.mapper().writeValueAsString(Map.of(
                "jobId", jobId,
                "excluded", List.of(Map.of(
                        "ruleKey", hit.get("ruleKey").asText(),
                        "original", hit.get("original").asText()))));
        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                body.getBytes(StandardCharsets.UTF_8), 应用体());
        assertEquals(200, apply.status(), apply.body());

        String xml = 下载正文(jobId);
        assertEquals(3, 出现次数(xml, PHONE),
                "不写序号＝整条不脱敏，三处都该留着: " + xml);
    }

    @Test
    @DisplayName("序号越界被拒（400），不会当成「整条排除」蒙混过关")
    void 序号越界被拒() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(三段Docx("d.docx"), "d.docx");
        JsonNode s = json(scan.body());
        String jobId = s.get("jobId").asText();
        JsonNode hit = 命中(s, PHONE);

        String body = Json.mapper().writeValueAsString(Map.of(
                "jobId", jobId,
                "excluded", List.of(Map.of(
                        "ruleKey", hit.get("ruleKey").asText(),
                        "original", hit.get("original").asText(),
                        "occurrence", 3))));
        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                body.getBytes(StandardCharsets.UTF_8), 应用体());
        assertEquals(400, apply.status(), apply.body());
        assertTrue(apply.body().contains("第 3 次出现"), apply.body());
    }

    // ============================================================ 归组行

    @Test
    @DisplayName("归组成员的逐处排除被拒：否则会留下「保留了一半的标识符」")
    void 归组成员的逐处排除被拒() throws Exception {
        // 假来源把 a.b 与 cn 认成人名，于是 "a.b@x.cn" 被切成三段紧挨着的命中，
        // 归组成一行（原文里连着的一段，是一个标识符）。
        try (Workspace ws = Workspace.open(dir.resolve("ws-merged"))) {
            JobStore jobs = new JobStore(ws, ApiHandlers.defaultRules(), List.of(假人名()));
            byte[] body = "备用 a.b@x.cn 联系人".getBytes(StandardCharsets.UTF_8);
            JobStore.Job job = jobs.scan("m.txt", body);

            DocumentService.HitItem merged = job.scan().hits().stream()
                    .filter(h -> h.valueCount() > 1)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "前提：应当有一个归组行，实际命中: " + job.scan().hits()));
            DocumentService.HitItem.Member member = merged.members().get(0);

            JobStore.BadRequestException e = assertThrows(JobStore.BadRequestException.class,
                    () -> jobs.apply(job.jobId(),
                            Exclusions.ofList(List.of(
                                    Exclusions.HitRef.nth(member.ruleKey(), member.original(), 0)))),
                    "归组成员的逐处排除必须被拒：引擎逐段脱敏，只放过一段会留下半个标识符");
            assertTrue(e.getMessage().contains("保留了一半的标识符"), e.getMessage());
        }
    }

    @Test
    @DisplayName("归组行整行保留仍然可用（这一轮不能把值级排除也堵上）")
    void 归组行整行保留仍然可用() throws Exception {
        try (Workspace ws = Workspace.open(dir.resolve("ws-merged-ok"))) {
            JobStore jobs = new JobStore(ws, ApiHandlers.defaultRules(), List.of(假人名()));
            byte[] body = "备用 a.b@x.cn 联系人".getBytes(StandardCharsets.UTF_8);
            JobStore.Job job = jobs.scan("m2.txt", body);

            DocumentService.HitItem merged = job.scan().hits().stream()
                    .filter(h -> h.valueCount() > 1)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("前提：应当有一个归组行"));

            List<Exclusions.HitRef> refs = new ArrayList<>();
            for (DocumentService.HitItem.Member m : merged.members()) {
                refs.add(new Exclusions.HitRef(m.ruleKey(), m.original()));
            }
            JobStore.Job applied = jobs.apply(job.jobId(), Exclusions.ofList(refs));

            String text = new String(Files.readAllBytes(ws.pathOf(applied.outputNameForTest())),
                    StandardCharsets.UTF_8);
            assertTrue(text.contains("a.b@x.cn"),
                    "整行保留后原文应当整体留着，实际: " + text);
        }
    }

    // ============================================================ 工具

    /** 同一个手机号出现在三段里。 */
    private static byte[] 三段Docx(String name) throws IOException {
        return Files.readAllBytes(DocxFixture.simple(
                DocxFixture.paragraph(DocxFixture.run(null, "甲 " + PHONE))
                        + DocxFixture.paragraph(DocxFixture.run(null, "乙 " + PHONE))
                        + DocxFixture.paragraph(DocxFixture.run(null, "丙 " + PHONE)))
                .writeTo(dir, name));
    }

    /**
     * 假的人名来源：把 {@code a.b} 与 {@code cn} 认成人名。
     *
     * <p>优先级 900 与真实 NER 同量级：它必须压过邮箱规则（120），
     * 邮箱那一整段才会被切开，切出来的片段才可能被当成人名。
     */
    private static EntitySource 假人名() {
        return new EntitySource() {
            @Override
            public String name() {
                return "test-fake-ner";
            }

            @Override
            public List<Match> collect(String text) {
                List<Match> out = new ArrayList<>();
                造一个(text, "a.b", out);
                造一个(text, "cn", out);
                return out;
            }

            private void 造一个(String text, String needle, List<Match> out) {
                int at = text.indexOf(needle);
                if (at < 0) {
                    return;
                }
                out.add(new Match(at, at + needle.length(), needle, "test_person", "人名", 900));
            }
        };
    }

    private static LocalServerApiTest.Raw.Res 上传(byte[] content, String fileName) throws IOException {
        return LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token,
                content, 上传头带令牌(fileName, "application/octet-stream"));
    }

    private static Map<String, String> 上传头带令牌(String fileName, String contentType) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", contentType);
        h.put(ApiHandlers.FILENAME_HEADER, encodeName(fileName));
        h.putAll(tokenHeader);
        return h;
    }

    /** 文件名走百分号编码：HTTP 头是 ISO-8859-1 的，中文直接发会变成问号。 */
    private static String encodeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
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

    private static Map<String, String> 应用体() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.putAll(tokenHeader);
        return h;
    }

    private static JsonNode json(String body) throws IOException {
        return com.litemask.server.Json.mapper().readTree(body);
    }

    /** 从扫描结果里找出某个原值对应的那一行。 */
    private static JsonNode 命中(JsonNode scan, String original) {
        for (JsonNode h : scan.get("hits")) {
            if (original.equals(h.get("original").asText())) {
                return h;
            }
        }
        throw new AssertionError("没扫到 " + original + "，命中清单: " + scan.get("hits"));
    }

    /**
     * 取位置说明里的出现序号。
     *
     * <p><b>为什么先断言字段存在，而不是直接 {@code get()} 之后 {@code .asInt()}</b>：
     * {@code ObjectNode.get()} 对<b>缺字段</b>返回 {@code null}，后面就是空指针。
     * 那样红灯会变成「抛异常」——对照实验据此判这条变异 INVALID（红灯原因不对），
     * 而真正的问题（序号字段整个没了）反而被一句 NPE 盖住，看不出是哪条判据炸的。
     * 断言失败才能说清「是序号字段没了」。
     */
    private static int 序号(JsonNode 位置说明) {
        assertTrue(位置说明.has("occurrence"),
                "位置说明必须带 occurrence 字段（界面靠它指出「第几次出现」），实际: " + 位置说明);
        return 位置说明.get("occurrence").asInt();
    }

    /** 下载成品件并读出正文。判据看文件内容，不看接口自己报的数。 */
    private static String 下载正文(String jobId) throws IOException {
        byte[] content = LocalServerApiTest.Raw.bodyBytes(port, "GET",
                "/api/download?jobId=" + jobId, "127.0.0.1:" + port, null, token, Map.of());
        Path downloaded = dir.resolve("downloaded-" + jobId + ".docx");
        Files.write(downloaded, content);
        return DocxFixture.text(downloaded, "word/document.xml");
    }

    private static int 出现次数(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
