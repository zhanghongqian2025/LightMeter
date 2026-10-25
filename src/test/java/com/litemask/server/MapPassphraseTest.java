package com.litemask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.litemask.core.SecretMap;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面上给映射件设口令：这条链路的安全性质。
 *
 * <p><b>为什么单列一个类</b>：口令这条链有<b>两个方向</b>的失败方式，
 * 而它们在别的测试里都看不见：
 * <ul>
 *   <li><b>该封的没封</b>——用户填了口令，文件里却还是明文原值。
 *       界面上写着「已加密」，用户据此以为这份文件可以随手传出去。</li>
 *   <li><b>不该泄的泄了</b>——口令出现在响应体里、落进了任务对象、
 *       写进了磁盘上的某个文件。这三种泄法在「映射件本身是封好的」面前全是隐形的：
 *       文件确实是密文，可口令就写在同一条 HTTP 响应的另一个字段里。</li>
 * </ul>
 * 别的测试（映射件能还原、权限是 0600）在这两种失败面前<b>照样全绿</b>。
 *
 * <p><b>标尺独立于被测实现</b>：这里看的是<b>落到盘上的字节</b>与
 * <b>真正发出去的报文</b>，不是「服务端自己说自己封好了」。
 * 「文件里有没有原值」用原文/base64/hex 三种形式查——只看原文的话，
 * 「把整张映射表 base64 一遍塞进 ciphertext」这种假加密能蒙混过关。
 *
 * <p><b>走真 HTTP</b>：请求体解析、令牌校验、下载响应这三段只有真跑一遍才算验过。
 */
class MapPassphraseTest {

    private static final String PHONE = "13800138000";
    private static final String OTHER_PHONE = "13900139000";
    private static final String PASS = "口令-abc-123";

    private static final String TXT =
            "甲方联系人 张三 电话 " + PHONE + "\n"
                    + "乙方联系人 李四 电话 " + OTHER_PHONE + "\n";

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
        ApiHandlers.register(server, workspace, ApiHandlers.defaultRules());
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

    private static LocalServerApiTest.Raw.Res 上传(byte[] content, String fileName) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/octet-stream");
        h.put(ApiHandlers.FILENAME_HEADER, fileName);
        h.putAll(tokenHeader);
        return LocalServerApiTest.Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token, content, h);
    }

    /** 应用。{@code passphrase} 为 {@code null} 表示报文里没有这个字段。 */
    private static LocalServerApiTest.Raw.Res 应用(String jobId, String passphrase) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("excluded", List.of());
        if (passphrase != null) {
            body.put("mapPassphrase", passphrase);
        }
        byte[] raw = Json.mapper().writeValueAsBytes(body);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.putAll(tokenHeader);
        return LocalServerApiTest.Raw.req(port, "POST", "/api/apply",
                "127.0.0.1:" + port, null, token, raw, h);
    }

    private static LocalServerApiTest.Raw.Res 应用任务(String jobId, String passphrase) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("excluded", List.of());
        if (passphrase != null) {
            body.put("mapPassphrase", passphrase);
        }
        byte[] raw = Json.mapper().writeValueAsBytes(body);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.putAll(tokenHeader);
        return LocalServerApiTest.Raw.req(port, "POST", "/api/task-apply",
                "127.0.0.1:" + port, null, token, raw, h);
    }

    /** 轮询任务到终态，返回任务结果报文。 */
    private static JsonNode 等任务完成(String taskId) throws IOException, InterruptedException {
        for (int i = 0; i < 200; i++) {
            LocalServerApiTest.Raw.Res res = LocalServerApiTest.Raw.req(port, "GET",
                    "/api/task?taskId=" + taskId, "127.0.0.1:" + port, null, token, new byte[0]);
            assertEquals(200, res.status(), res.body());
            JsonNode j = Json.mapper().readTree(res.body());
            // 线上的状态是小写的（Status.wire()）："done" / "failed" / "canceled" / "timed_out"。
            String status = j.path("status").asText("");
            if ("done".equals(status) || "failed".equals(status)
                    || "canceled".equals(status) || "timed_out".equals(status)) {
                return j;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("任务没有在 5 秒内到终态：" + taskId);
    }

    private static byte[] 下载(String path) throws IOException {
        return LocalServerApiTest.Raw.bodyBytes(port, "GET", path,
                "127.0.0.1:" + port, null, token, Map.of());
    }

    private static JsonNode json(String body) throws IOException {
        return Json.mapper().readTree(body);
    }

    /** 上传 → 应用，返回 jobId。 */
    private static String 走一遍(String passphrase, String fileName) throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(TXT.getBytes(StandardCharsets.UTF_8), fileName);
        assertEquals(200, scan.status(), scan.body());
        String jobId = json(scan.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res apply = 应用(jobId, passphrase);
        assertEquals(200, apply.status(), apply.body());
        return jobId;
    }

    /** 工作区里现在有哪些普通文件（整个根目录，不只是会话目录）。 */
    private static List<Path> 工作区文件() throws IOException {
        return 工作区文件(workspace);
    }

    private static List<Path> 工作区文件(Workspace ws) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var s = Files.walk(ws.root())) {
            s.filter(Files::isRegularFile).forEach(out::add);
        }
        return out;
    }

    /**
     * 读一个工作区文件；读不出来（被锁住）时返回 {@code null}。
     *
     * <p><b>【2026-10-25 真 Windows 实测踩到的】</b>：
     * 工作区里有一把**锁文件**（{@code locks\*.lock}），Workspace 正用 {@link java.nio.channels.FileLock}
     * 持着它。POSIX 的锁是**建议性**的 → 读它照样读得到；
     * Windows 的锁是**强制性**的 → 读被锁住的那一段直接抛
     * {@code IOException: 另一个程序已锁定文件的一部分}。
     * 整条断言因此在 Windows 上抛异常，而不是给出结论。
     *
     * <p><b>为什么只放过锁文件</b>：锁文件是 Workspace 自己建的、
     * 名字固定、内容与用户数据无关；而**别的**文件读不出来 =
     * 断言根本没生效（那才是真正要红的情况）。
     * → 所以：读不了且**不是**锁文件 → 直接红。
     */
    private static byte[] 读得出来(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            boolean 是锁文件 = p.getFileName().toString().endsWith(".lock");
            assertTrue(是锁文件,
                    "工作区文件读不出来（不是锁文件）：" + p + " —— " + e);
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 明文（向后兼容）

    @Test
    @DisplayName("不给口令时映射件仍是明文：老客户端与旧脚本的行为不能被改掉")
    void 不给口令仍是明文() throws IOException {
        String jobId = 走一遍(null, "明文.txt");

        byte[] raw = 下载("/api/mapping?jobId=" + jobId);
        assertFalse(SecretMap.isSealed(raw), "没给口令就不该是封装件");
        JsonNode doc = json(new String(raw, StandardCharsets.UTF_8));
        assertNotNull(doc.get("mapping"), "明文映射件顶层要有 mapping 段");
        assertTrue(doc.get("mapping").size() > 0, "映射不能是空的");
    }

    @Test
    @DisplayName("不给口令时响应里 mappingSealed 是 false（界面据此决定要不要提示）")
    void 不给口令时mappingSealed为假() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(TXT.getBytes(StandardCharsets.UTF_8), "标志.txt");
        String jobId = json(scan.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res apply = 应用(jobId, null);
        assertEquals(200, apply.status(), apply.body());
        assertTrue(apply.body().contains("\"mappingSealed\":false"),
                "没给口令必须是 false: " + apply.body());
    }

    // ------------------------------------------------------------ 封装

    @Test
    @DisplayName("给了口令：落盘的映射件是封装件，文件里找不到原值")
    void 给口令则封装且无原值() throws IOException {
        String jobId = 走一遍(PASS, "封装.txt");

        byte[] raw = 下载("/api/mapping?jobId=" + jobId);
        assertTrue(SecretMap.isSealed(raw),
                "给了口令就该是封装件（litemask-sealed-map），实际开头："
                        + new String(raw, StandardCharsets.UTF_8).substring(0, Math.min(120, raw.length)));

        // 「文件里没有原值」要查三种形式：原文、base64、hex。
        // 只查原文的话，「把整张表 base64 一遍塞进 ciphertext」这种假加密能蒙混过关。
        String text = new String(raw, StandardCharsets.UTF_8);
        for (String v : List.of(PHONE, OTHER_PHONE)) {
            assertFalse(text.contains(v), "文件里不该有原值: " + v);
            assertFalse(text.contains(Base64.getEncoder()
                    .encodeToString(v.getBytes(StandardCharsets.UTF_8))), "文件里不该有原值的 base64: " + v);
            assertFalse(text.contains(hex(v.getBytes(StandardCharsets.UTF_8))), "文件里不该有原值的 hex: " + v);
        }
        // 「只是换了个编码」也不算加密：明文特征键一个都不许有。
        for (String key : List.of("\"mapping\"", "\"changes\"", "\"original\"")) {
            assertFalse(text.contains(key), "封装件里不该出现明文特征键: " + key);
        }
    }

    @Test
    @DisplayName("给了口令：同一个口令能打开封装件，拿到的是完整可用的映射")
    void 同一口令能打开且映射完整() throws Exception {
        String jobId = 走一遍(PASS, "打开.txt");
        byte[] raw = 下载("/api/mapping?jobId=" + jobId);

        byte[] 明文 = SecretMap.open(raw, PASS.toCharArray());
        JsonNode doc = json(new String(明文, StandardCharsets.UTF_8));
        assertNotNull(doc.get("mapping"), "拆封后顶层要有 mapping 段");
        assertTrue(doc.get("mapping").size() > 0, "拆封后映射不能是空的");

        boolean 有原值 = false;
        var 字段 = doc.get("mapping").fields();
        while (字段.hasNext()) {
            if (PHONE.equals(字段.next().getValue().asText())) {
                有原值 = true;
            }
        }
        assertTrue(有原值, "拆封后的映射里要有原值，否则还原不回来");
    }

    @Test
    @DisplayName("错口令打不开；密文被改一个字节也打不开")
    void 错口令与密文被改都打不开() throws IOException {
        String jobId = 走一遍(PASS, "错口令.txt");
        byte[] raw = 下载("/api/mapping?jobId=" + jobId);

        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(raw, "不是这个口令".toCharArray()),
                "错口令必须打不开");

        // 改一个字节：GCM 的认证标签必须把它挡住。
        byte[] 改过 = raw.clone();
        改过[改过.length - 20] = (byte) (改过[改过.length - 20] ^ 0x01);
        assertThrows(SecretMap.SecretMapException.class,
                () -> SecretMap.open(改过, PASS.toCharArray()),
                "密文被改一个字节必须打不开");
    }

    @Test
    @DisplayName("封装件的权限仍是 0600（封装不该把权限放松）")
    void 封装件权限不放松() throws Exception {
        走一遍(PASS, "权限.txt");
        boolean 找到 = false;
        for (Path p : 工作区文件()) {
            byte[] raw = 读得出来(p);
            if (raw == null) {
                continue;
            }
            if (!SecretMap.isSealed(raw)) {
                continue;
            }
            找到 = true;
            // 【2026-10-25】原来这里在非 POSIX 文件系统上直接 `continue`，
            //   也就是说「封装件的权限」在 Windows 上**从来没有被断言过**。
            //   → 改成跟平台走：POSIX 看权限位，Windows 看 ACL。
            //     断言强度没有降低，只是换了个平台对应的判据。
            com.litemask.core.OwnerOnlyAssert.assertOwnerOnlyFile(p);
        }
        assertTrue(找到, "工作区里没找到封装件");
    }

    // ------------------------------------------------------------ 还原不受影响

    @Test
    @DisplayName("服务端自带还原不受口令影响：界面上的「还原并下载」照样能还原出原文")
    void 服务端还原不受口令影响() throws IOException {
        String jobId = 走一遍(PASS, "还原.txt");
        byte[] 还原 = LocalServerApiTest.Raw.bodyBytes(port, "POST",
                "/api/restore?jobId=" + jobId, "127.0.0.1:" + port, null, token, Map.of());
        assertEquals(TXT, new String(还原, StandardCharsets.UTF_8),
                "给了口令之后，服务端还原仍应当与原文逐字一致");
    }

    // ------------------------------------------------------------ 口令不外泄

    @Test
    @DisplayName("口令不出现在任何响应体里（apply 响应、任务结果、映射下载）")
    void 口令不出现在响应体里() throws IOException, InterruptedException {
        // 同步接口
        LocalServerApiTest.Raw.Res scan = 上传(TXT.getBytes(StandardCharsets.UTF_8), "同步.txt");
        String jobId = json(scan.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res apply = 应用(jobId, PASS);
        assertEquals(200, apply.status(), apply.body());
        assertFalse(apply.body().contains(PASS), "apply 响应体里不该有口令: " + apply.body());

        // 任务接口：结果报文里也不该有
        LocalServerApiTest.Raw.Res scan2 = 上传(TXT.getBytes(StandardCharsets.UTF_8), "任务.txt");
        String jobId2 = json(scan2.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res submit = 应用任务(jobId2, PASS);
        assertEquals(202, submit.status(), submit.body());
        assertFalse(submit.body().contains(PASS), "task-apply 响应体里不该有口令: " + submit.body());
        JsonNode 任务结果 = 等任务完成(json(submit.body()).get("taskId").asText());
        assertEquals("done", 任务结果.path("status").asText(), 任务结果.toString());
        assertFalse(任务结果.toString().contains(PASS), "任务结果里不该有口令: " + 任务结果);

        // 下载回来的映射件里也不该有（它是密文，本来就没有；但要确认没有明文残留）
        byte[] 映射 = 下载("/api/mapping?jobId=" + jobId2);
        assertFalse(new String(映射, StandardCharsets.UTF_8).contains(PASS),
                "映射件里不该有口令");
    }

    @Test
    @DisplayName("口令不落盘：工作区里没有任何文件含有它")
    void 口令不落盘() throws IOException {
        走一遍(PASS, "落盘.txt");
        for (Path p : 工作区文件()) {
            byte[] raw = 读得出来(p);
            if (raw == null) {
                continue;
            }
            assertFalse(new String(raw, StandardCharsets.UTF_8).contains(PASS),
                    "文件里有口令: " + p);
            assertFalse(new String(raw, StandardCharsets.ISO_8859_1).contains(PASS),
                    "文件里有口令: " + p);
        }
    }

    @Test
    @DisplayName("口令不进任务对象：它只需要活到映射件写完那一刻")
    void 口令不进任务对象() throws Exception {
        // 【为什么要另起一个 JobStore】服务端那个 JobStore 是 register() 内部建的，
        // 外面拿不到它的任务表；与其反射去挖那张私有表，不如直接建一个 JobStore
        // 来验「口令不进 Job 对象」——这条不变式本来就是 JobStore 的性质。
        Workspace 独立工作区 = Workspace.open(dir.resolve("独立工作区"));
        try {
            JobStore 独立 = new JobStore(独立工作区, ApiHandlers.defaultRules());
            JobStore.Job job = 独立.scan("独立.txt", TXT.getBytes(StandardCharsets.UTF_8));
            独立.apply(job.jobId(), null, PASS.toCharArray());

            // 把 Job 的每个字段都读出来逐个看：
            // 只看已知的几个字段的话，新增一个字段存口令就会漏过去。
            boolean 看过字段 = false;
            for (java.lang.reflect.Field f : JobStore.Job.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object 值 = f.get(job);
                看过字段 = true;
                assertFalse(String.valueOf(值).contains(PASS),
                        "任务对象的字段 " + f.getName() + " 里含有口令");
            }
            assertTrue(看过字段, "一个字段都没扫到，判据就是空的");
        } finally {
            独立工作区.close();
        }
    }

    // ------------------------------------------------------------ 空口令

    @Test
    @DisplayName("报文里口令是空串 → 400，而且不产出任何映射件（不许悄悄退回明文）")
    void 空口令被拒绝且不产出映射() throws IOException {
        LocalServerApiTest.Raw.Res scan = 上传(TXT.getBytes(StandardCharsets.UTF_8), "空口令.txt");
        String jobId = json(scan.body()).get("jobId").asText();

        // 上传之后工作区里已有原件与预览件；被拒的 apply **不许**再加出任何文件。
        // 【为什么比「文件里没有原值」好】原件里当然有原值（那就是用户的文件），
        // 所以只能比「有没有多出文件」——悄悄退回明文的话，多出来的那个就是明文映射件。
        List<String> 之前 = 文件名列表();

        LocalServerApiTest.Raw.Res apply = 应用(jobId, "");
        assertEquals(400, apply.status(), "空口令必须被拒: " + apply.body());
        assertFalse(apply.body().contains("\"mapping\""),
                "被拒的请求不该回一份映射: " + apply.body());

        // 关键：被拒之后**不能**留下一个明文映射件。
        // 悄悄退回明文是 fail-open：用户以为加密了，文件里却全是原值。
        LocalServerApiTest.Raw.Res 映射 = LocalServerApiTest.Raw.req(port, "GET",
                "/api/mapping?jobId=" + jobId, "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(400, 映射.status(),
                "被拒的请求不该产出映射件: " + 映射.body());
        assertEquals(之前, 文件名列表(),
                "被拒的请求不该在工作区里留下任何新文件");
    }

    /** 工作区里所有普通文件的文件名（排序）。 */
    private static List<String> 文件名列表() throws IOException {
        return 文件名列表(workspace);
    }

    private static List<String> 文件名列表(Workspace ws) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : 工作区文件(ws)) {
            out.add(p.getFileName().toString());
        }
        java.util.Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------ 库里这一层

    @Test
    @DisplayName("库里给了空口令也必须失败，不许悄悄退回明文")
    void 库里空口令必须失败() throws Exception {
        // 【为什么要单独在库这一层验】接口层把空串拦掉了，
        // 所以「封装失败时会不会悄悄退回明文」这条在接口层永远验不到。
        // 而它恰恰是最该防的那种失败：用户以为加密了，文件里却全是原值，
        // 而且**没有任何异常**。
        Workspace 独立工作区 = Workspace.open(dir.resolve("空口令工作区"));
        try {
            JobStore 独立 = new JobStore(独立工作区, ApiHandlers.defaultRules());
            JobStore.Job job = 独立.scan("空.txt", TXT.getBytes(StandardCharsets.UTF_8));
            long 映射件数量 = 映射件数量(独立工作区);

            assertThrows(IOException.class,
                    () -> 独立.apply(job.jobId(), null, new char[0]),
                    "空口令必须失败，不许产出映射件");

            assertEquals(映射件数量, 映射件数量(独立工作区),
                    "失败的应用不该留下映射件（悄悄退回明文 = 用户以为加密了，文件里全是原值）");
        } finally {
            独立工作区.close();
        }
    }

    /** 工作区里有多少个映射件（后缀 .json 的那批）。 */
    private static long 映射件数量(Workspace ws) throws IOException {
        long n = 0;
        for (Path p : 工作区文件(ws)) {
            if (p.getFileName().toString().endsWith(".json")) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ 任务路径
    @Test
    @DisplayName("任务路径也支持口令：结果报文说封了，落盘的件也确实是封装件")
    void 任务路径也支持口令() throws IOException, InterruptedException {
        LocalServerApiTest.Raw.Res scan = 上传(TXT.getBytes(StandardCharsets.UTF_8), "任务封装.txt");
        String jobId = json(scan.body()).get("jobId").asText();
        LocalServerApiTest.Raw.Res submit = 应用任务(jobId, PASS);
        assertEquals(202, submit.status(), submit.body());
        JsonNode 结果 = 等任务完成(json(submit.body()).get("taskId").asText());
        assertEquals("done", 结果.path("status").asText(), 结果.toString());
        assertTrue(结果.path("result").path("mappingSealed").asBoolean(),
                "任务结果里 mappingSealed 应当为 true: " + 结果);

        byte[] raw = 下载("/api/mapping?jobId=" + jobId);
        assertTrue(SecretMap.isSealed(raw), "任务路径产出的映射件也应当是封装件");
        assertFalse(new String(raw, StandardCharsets.UTF_8).contains(PHONE),
                "封装件里不该有原值");
    }
}
