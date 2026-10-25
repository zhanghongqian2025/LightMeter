package com.litemask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.litemask.core.Rule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「补充敏感词」（人工补漏）的端到端测试。
 *
 * <p><b>这一条补的是哪块缺口</b>：本工具的内置规则只认**有固定形态**的东西
 * （身份证、银行卡、手机号…）。但卷宗里有大量**没有固定形态**的敏感信息 ——
 * 某个当事人名字、某个内部案号。规则认不出来 → 清单上零命中 →
 * 而那些字**明明白白就在文档里**。
 * 过去界面上只能「排除误报」，做不了「补上漏检」——
 * 人工复核这件事只剩了一半能力。
 *
 * <p><b>为什么必须走 HTTP</b>：判据要看**下载回来的那个文件**与**映射文件**，
 * 直接调 {@code JobStore} 的话，请求体解析与下载响应这两段就没人验了。
 *
 * <p><b>判据不看接口自己报的命中数</b>：接口说「脱了 1 处」与文件里真的没有原值，
 * 是两件事。两边都要看。
 */
class UserTermsApiTest {

    /** 一个规则**认不出来**的词：内部案号，没有固定形态。 */
    private static final String 案号 = "京A-2026-00137";
    /** 一个规则也认不出来的人名。 */
    private static final String 人名 = "欧阳云深";

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

    private static Map<String, String> 头(String fileName) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/octet-stream");
        h.put(ApiHandlers.FILENAME_HEADER, 编码(fileName));
        h.putAll(tokenHeader);
        return h;
    }

    private static Map<String, String> json头() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.putAll(tokenHeader);
        return h;
    }

    /** HTTP 头是 ISO-8859-1 的，中文文件名要百分号编码。 */
    private static String 编码(String name) {
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

    private static JsonNode json(String body) throws IOException {
        return Json.mapper().readTree(body);
    }

    private static LocalServerApiTest.Raw.Res 上传(String 文本, String 文件名) throws IOException {
        return LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token,
                文本.getBytes(StandardCharsets.UTF_8), 头(文件名));
    }

    private static LocalServerApiTest.Raw.Res 补词(String jobId, List<String> terms) throws IOException {
        String body = Json.mapper().writeValueAsString(Map.of("jobId", jobId, "terms", terms));
        return LocalServerApiTest.Raw.req(port, "POST", "/api/terms",
                "127.0.0.1:" + port, null, token,
                body.getBytes(StandardCharsets.UTF_8), json头());
    }

    // ------------------------------------------------------------ 判据

    @Test
    @DisplayName("规则认不出来的词：补进去之后出现在命中清单里，且真的被脱敏、能还原")
    void 补的词会进清单且被脱敏() throws IOException {
        String 原文 = "本案内部编号 " + 案号 + "，当事人 " + 人名 + "。身份证 11010519491231002X。";

        // 1. 先扫：规则只认得出身份证，认不出案号与人名
        LocalServerApiTest.Raw.Res scan = 上传(原文, "卷宗.txt");
        assertEquals(200, scan.status(), scan.body());
        JsonNode s = json(scan.body());
        String jobId = s.get("jobId").asText();
        assertTrue(s.get("summary").get("distinctHits").asInt() >= 1,
                "身份证那条应当被规则命中：" + scan.body());
        for (JsonNode h : s.get("hits")) {
            assertFalse(案号.equals(h.get("original").asText()),
                    "案号不该被内置规则命中（那是本测试的前提）");
            assertFalse(人名.equals(h.get("original").asText()),
                    "人名不该被内置规则命中（那是本测试的前提）");
        }

        // 2. 补词 → 重扫
        LocalServerApiTest.Raw.Res terms = 补词(jobId, List.of(案号, 人名));
        assertEquals(200, terms.status(), terms.body());
        JsonNode t = json(terms.body());
        assertEquals(jobId, t.get("jobId").asText(), "补词不该换任务");
        assertTrue(t.has("userTerms"), "响应里要回显用户补的词：" + terms.body());
        assertEquals(2, t.get("userTerms").size(), terms.body());

        // 补的词必须**出现在清单里** —— 不出现的话用户没法排除它，
        // 那就不是「人工复核」，是「用户被迫接受」。
        boolean 看到案号 = false;
        boolean 看到人名 = false;
        for (JsonNode h : t.get("hits")) {
            String 原值 = h.get("original").asText();
            if (案号.equals(原值)) {
                看到案号 = true;
                assertEquals("user_term", h.get("ruleKey").asText(),
                        "补的词要用自己的规则键（界面靠它区分来源）");
                assertTrue(h.get("token").asText().contains("补充"),
                        "补的词的令牌应当能看出是补的：" + h.get("token").asText());
            }
            if (人名.equals(原值)) {
                看到人名 = true;
            }
        }
        assertTrue(看到案号, "补的案号没出现在命中清单里：" + terms.body());
        assertTrue(看到人名, "补的人名没出现在命中清单里：" + terms.body());

        // 3. 应用 → 下载脱敏件：原值必须没了
        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                Json.mapper().writeValueAsString(Map.of("jobId", jobId, "excluded", List.of()))
                        .getBytes(StandardCharsets.UTF_8),
                json头());
        assertEquals(200, apply.status(), apply.body());

        LocalServerApiTest.Raw.Res dl = LocalServerApiTest.Raw.req(port, "GET",
                "/api/download?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(200, dl.status(), dl.body());
        String 脱敏件 = dl.body();
        assertFalse(脱敏件.contains(案号), "脱敏件里还留着用户补的词：" + 脱敏件);
        assertFalse(脱敏件.contains(人名), "脱敏件里还留着用户补的词：" + 脱敏件);
        assertTrue(脱敏件.contains("补充"), "脱敏件里应当有占位符：" + 脱敏件);

        // 4. 映射里有原值 → 能还原
        LocalServerApiTest.Raw.Res map = LocalServerApiTest.Raw.req(port, "GET",
                "/api/mapping?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(200, map.status(), map.body());
        String 映射 = map.body();
        assertTrue(映射.contains(案号), "映射里必须有补的词的原值（否则还原不回去）：" + 映射);
    }

    @Test
    @DisplayName("补的词能排除（误伤也能放行）——它接的是同一套排除机制")
    void 补的词可以排除() throws IOException {
        String 原文 = "引用条文里的 「" + 案号 + "」 与本案无关，但保险起见也脱了。";
        LocalServerApiTest.Raw.Res scan = 上传(原文, "引用.txt");
        JsonNode s = json(scan.body());
        String jobId = s.get("jobId").asText();

        LocalServerApiTest.Raw.Res terms = 补词(jobId, List.of(案号));
        assertEquals(200, terms.status(), terms.body());
        JsonNode t = json(terms.body());
        assertTrue(t.get("summary").get("distinctHits").asInt() >= 1, terms.body());

        // 排除这个补的词 → 它应当原样保留在成品里
        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                Json.mapper().writeValueAsString(Map.of(
                        "jobId", jobId,
                        "excluded", List.of(Map.of("ruleKey", "user_term", "original", 案号))))
                        .getBytes(StandardCharsets.UTF_8),
                json头());
        assertEquals(200, apply.status(), apply.body());
        assertEquals(1, json(apply.body()).get("summary").get("excluded").asInt(), apply.body());

        LocalServerApiTest.Raw.Res dl = LocalServerApiTest.Raw.req(port, "GET",
                "/api/download?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        String 脱敏件 = dl.body();
        assertTrue(脱敏件.contains(案号),
                "被排除的补词应当原样保留（排除机制对补的词一视同仁）：" + 脱敏件);
    }

    @Test
    @DisplayName("太短的词要拒（一个字会命中全文每一个字，那不是脱敏是毁文件）")
    void 太短的词被拒() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传("内容", "a.txt");
        String jobId = json(scan.body()).get("jobId").asText();

        LocalServerApiTest.Raw.Res bad = 补词(jobId, List.of("张"));
        assertEquals(400, bad.status(), bad.body());
        assertTrue(json(bad.body()).get("error").asText().contains("太短"),
                "错误消息要说清为什么：" + bad.body());
    }

    @Test
    @DisplayName("没给词、或词全是空白，都要报错（不能当成「什么都没加」就默默成功）")
    void 空词表被拒() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传("内容", "a.txt");
        String jobId = json(scan.body()).get("jobId").asText();

        LocalServerApiTest.Raw.Res empty = 补词(jobId, List.of());
        assertEquals(400, empty.status(), empty.body());

        LocalServerApiTest.Raw.Res blank = 补词(jobId, List.of("  ", ""));
        assertEquals(400, blank.status(), blank.body());
    }

    @Test
    @DisplayName("同一个词补两次要报错（静默成功会让用户以为补上了）")
    void 重复补被拒() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传("内容 " + 案号, "a.txt");
        String jobId = json(scan.body()).get("jobId").asText();

        assertEquals(200, 补词(jobId, List.of(案号)).status());
        LocalServerApiTest.Raw.Res again = 补词(jobId, List.of(案号));
        assertEquals(400, again.status(), again.body());
        assertTrue(json(again.body()).get("error").asText().contains("已经"),
                "错误消息要说清是「已经有了」：" + again.body());
    }

    @Test
    @DisplayName("已经应用过的任务不能再补词（成品已经发出去了）")
    void 应用后不能补() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传("内容 " + 案号, "a.txt");
        String jobId = json(scan.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res apply = LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token,
                Json.mapper().writeValueAsString(Map.of("jobId", jobId, "excluded", List.of()))
                        .getBytes(StandardCharsets.UTF_8),
                json头());
        assertEquals(200, apply.status(), apply.body());

        LocalServerApiTest.Raw.Res after = 补词(jobId, List.of(人名));
        assertEquals(400, after.status(), after.body());
        assertTrue(json(after.body()).get("error").asText().contains("已经"),
                "错误消息要说清：" + after.body());
    }

    @Test
    @DisplayName("补词接口也要令牌（请求体里是用户自己写的敏感词）")
    void 补词要令牌() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传("内容", "a.txt");
        String jobId = json(scan.body()).get("jobId").asText();
        String body = Json.mapper().writeValueAsString(Map.of("jobId", jobId, "terms", List.of(案号)));
        LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "POST", "/api/terms",
                "127.0.0.1:" + port, null, null,
                body.getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json"));
        assertEquals(403, res.status(), "没令牌必须被拒：" + res.body());
    }

    @Test
    @DisplayName("零命中要说清是哪一种零（没开人名识别 ≠ 这份文件干净）")
    void 零命中要说清() throws IOException {
        // 一份没有任何规则能命中的文件（只有一个内部编号）
        LocalServerApiTest.Raw.Res scan = 上传("只有内部编号 " + 案号 + "。", "空.txt");
        assertEquals(200, scan.status(), scan.body());
        JsonNode s = json(scan.body());
        assertEquals(0, s.get("summary").get("distinctHits").asInt(), scan.body());
        String notes = s.get("notes").toString();
        assertTrue(notes.contains("人名") || notes.contains("识别"),
                "零命中必须说清人名识别没开（否则会被读成「这份文件很干净」）：" + notes);
    }
}
