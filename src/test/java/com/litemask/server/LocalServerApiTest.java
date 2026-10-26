package com.litemask.server;

import com.litemask.core.Rule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalServer} 的端到端测试：发真的 HTTP 包，走真的 socket。
 *
 * <p><b>为什么不用 HttpClient</b>：JDK 的 {@code HttpClient} 不允许设置 Host 头
 * （受限头，要靠 {@code allowRestrictedHeaders} 放开），而 Host 正是这里要测的东西。
 * 更重要的是，裸 socket 在<b>网络层</b>与浏览器发出的请求无法区分——
 * 服务端口看来的一定是「某个客户端连上来了」，而不是「某个受控的客户端」。
 *
 * <p>本测试覆盖的是服务端这一半。浏览器端的另一半（同源策略让跨源脚本<b>读不到</b>响应）
 * 服务端无法验证，只能靠「不返回 Access-Control-Allow-Origin」来实现——
 * 所以这里断言的是「响应里没有任何 CORS 头」，浏览器是否照此执行不在本测试的判定范围内。
 */
class LocalServerApiTest {

    private static Workspace workspace;
    private static LocalServer server;
    private static String token;
    private static int port;

    @BeforeAll
    static void 起服务(@TempDir Path tmp) throws Exception {
        workspace = Workspace.open(tmp);
        server = LocalServer.create(workspace, 0);
        List<Rule> rules = ApiHandlers.defaultRules();
        ApiHandlers.register(server, workspace, rules);
        server.start();
        token = server.policy().token();
        port = server.port();
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

    // ---------------------------------------------------------------- 读接口

    @Test
    void 健康检查放行本机请求() throws Exception {
        Raw.Res r = Raw.get(port, "/api/health");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("\"ok\":true"), r.body);
        assertTrue(r.body.contains("\"port\":" + port), r.body);
        assertTrue(r.body.contains("\"bindAddress\":\"127.0.0.1\""), r.body);
        assertTrue(r.body.contains("\"loopbackVerified\":true"), r.body);
        assertTrue(r.body.contains("\"corsHeaders\":false"), r.body);
    }

    @Test
    @DisplayName("页面把令牌注入 HTML，且令牌不出现在 URL 里")
    void 首页注入令牌() throws Exception {
        Raw.Res r = Raw.get(port, "/");
        assertEquals(200, r.status, r.body);
        assertFalse(r.body.contains("__LITEMASK_TOKEN__"), "占位符应被替换掉");
        assertTrue(r.body.contains(token), "令牌应注入 meta");
        assertTrue(r.headers.get("content-type").get(0).startsWith("text/html"));
    }

    @Test
    void 规则清单() throws Exception {
        Raw.Res r = Raw.get(port, "/api/rules");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("\"count\":18"), "规则数不符: " + r.body);
        assertTrue(r.body.contains("id_card"), r.body);
        assertTrue(r.body.contains("invoice_no"), "M1-f 加的发票号码规则应出现在接口里");
    }

    @Test
    void 静态资源同源可达() throws Exception {
        assertEquals(200, Raw.get(port, "/app.js").status);
        assertEquals(200, Raw.get(port, "/app.css").status);
    }

    @Test
    @DisplayName("未注入桌面生命周期回调时不暴露停服路由")
    void embeddedServerHasNoShutdownRoute() throws Exception {
        assertFalse(server.routeSummary().contains("POST /api/shutdown [写]"));
        assertEquals(404, Raw.req(port, "POST", "/api/shutdown",
                "127.0.0.1:" + port, null, token, new byte[0]).status());
    }

    @Test
    @DisplayName("注入生命周期后停服路由仍要同源+令牌，响应完整后才发信号")
    void shutdownRouteIsProtectedAndSignalsAfterResponse(@TempDir Path tmp) throws Exception {
        Workspace isolatedWorkspace = Workspace.open(tmp.resolve("shutdown-route"));
        LocalServer isolated = LocalServer.create(isolatedWorkspace, 0);
        AtomicInteger signals = new AtomicInteger();
        isolated.onShutdownRequest(signals::incrementAndGet);
        ApiHandlers.register(isolated, isolatedWorkspace, ApiHandlers.defaultRules());
        isolated.start();
        try {
            int isolatedPort = isolated.port();
            String isolatedToken = isolated.policy().token();
            Raw.Res crossOrigin = Raw.req(isolatedPort, "POST", "/api/shutdown",
                    "127.0.0.1:" + isolatedPort, "https://evil.example", isolatedToken, new byte[0]);
            assertEquals(403, crossOrigin.status(), crossOrigin.body());
            assertEquals(0, signals.get(), "被拒绝的跨源请求不得发停服信号");

            Raw.Res accepted = Raw.req(isolatedPort, "POST", "/api/shutdown",
                    "127.0.0.1:" + isolatedPort, null, isolatedToken, new byte[0]);
            assertEquals(200, accepted.status(), accepted.body());
            assertTrue(accepted.body().contains("\"stopping\":true"), accepted.body());
            for (int i = 0; i < 100 && signals.get() == 0; i++) {
                Thread.sleep(5);
            }
            assertEquals(1, signals.get(), "完整返回停服 JSON 后应只发一次生命周期信号");
            assertEquals(200, Raw.get(isolatedPort, "/api/health").status(),
                    "回调只是信号，HTTP 请求线程不得自己关服务");
        } finally {
            isolated.close();
            isolatedWorkspace.close();
        }
    }

    // ---------------------------------------------------------------- 主机绑定

    @Test
    @DisplayName("健康检查报的绑定地址来自 socket，不是绑定常量")
    void 绑定地址不是写死的() throws Exception {
        // 这条断言本身抓不住「写死常量」的实现——两者都返回 127.0.0.1，
        // 真正能抓住它的是对照实验 ⒎ 与上面那条「局域网连不上」。
        // 这条的作用是**钉住取值来源**：将来有人把 boundAddress() 改成
        // 返回一个随手写的字符串，这里会先发现格式/取值对不上。
        assertEquals("127.0.0.1", server.boundAddress());
        assertTrue(server.loopbackVerified());
        Raw.Res r = Raw.get(port, "/api/health");
        assertTrue(r.body.contains("\"boundAddress\":\"" + server.boundAddress() + "\"")
                        || r.body.contains("\"bindAddress\":\"" + server.boundAddress() + "\""),
                "接口报的地址应与 socket 实际绑定的一致: " + r.body);
    }

    @Test
    @DisplayName("确实只绑在回环：局域网地址连不上")
    void 局域网地址连不上() throws Exception {
        String lanIp = 本机局域网地址();
        if (lanIp == null) {
            // 纯离线/无网卡环境，跳过而不是伪造通过
            System.out.println("跳过：没有可用的局域网地址");
            return;
        }
        // 必须绕开系统 SOCKS 代理：new Socket() 会继承 JVM 的代理设置，
        // 代理进程可以先接住任意目标地址，让 connect() 假成功，实际并没有
        // 从局域网地址连到本服务。这里测的是内核绑定，不是代理可达性。
        try (Socket s = new Socket(Proxy.NO_PROXY)) {
            s.connect(new InetSocketAddress(lanIp, port), 1500);
            // 连上了就是问题本身。用 fail 让它红，而不是打个日志。
            org.junit.jupiter.api.Assertions.fail(
                    "服务在局域网地址 " + lanIp + ":" + port + " 上可连接，这等于对全网开放");
        } catch (IOException expected) {
            // 连不上才是对的
        }
    }

    // ---------------------------------------------------------------- Host 白名单

    @Test
    @DisplayName("伪造 Host（DNS rebinding）一律拒绝")
    void 伪造Host被拒() throws Exception {
        for (String evil : List.of("evil.com", "localhost.evil.com", "127.0.0.1.evil.com")) {
            Raw.Res r = Raw.req(port, "GET", "/api/health", evil + ":" + port, null, null, new byte[0]);
            assertEquals(403, r.status, "Host=" + evil + " 应当被拒，实际 " + r.status + " " + r.body);
        }
    }

    @Test
    @DisplayName("端口不符也拒绝——只校验主机名等于和回环上所有服务共享防线")
    void 端口不符被拒() throws Exception {
        Raw.Res r = Raw.req(port, "GET", "/api/health", "127.0.0.1:" + (port + 1), null, null, new byte[0]);
        assertEquals(403, r.status, r.body);
    }

    // ---------------------------------------------------------------- 写操作

    @Test
    @DisplayName("写操作：Host + Origin + 令牌三者齐备才走到处理器")
    void 写操作放行() throws Exception {
        // 处理器在 M2-b 已实现，这里期望的是 400（请求体类型不对），
        // 而不是 403（鉴权没过）或 404（路由没注册）——三者必须分得开。
        // M2-a 时这里是 501，因为接口只占位未实现。
        Raw.Res r = Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, token, new byte[0]);
        assertEquals(400, r.status, "鉴权应通过并抵达处理器: " + r.body);
        assertTrue(r.body.contains("application/octet-stream"),
                "应当是处理器抱怨请求体类型，而不是鉴权拦下: " + r.body);
    }

    @Test
    void 缺令牌被拒() throws Exception {
        Raw.Res r = Raw.req(port, "POST", "/api/scan",
                "127.0.0.1:" + port, null, null, new byte[0]);
        assertEquals(403, r.status, r.body);
    }

    @Test
    void 错令牌被拒() throws Exception {
        for (String bad : List.of("", "null", token + "x", token.substring(0, 47), "0".repeat(48))) {
            Raw.Res r = Raw.req(port, "POST", "/api/scan",
                    "127.0.0.1:" + port, null, bad, new byte[0]);
            assertEquals(403, r.status, "错令牌应被拒: " + bad + " → " + r.status);
        }
    }

    @Test
    @DisplayName("跨源写请求被拒——哪怕 Host 与令牌都对")
    void 跨源写被拒() throws Exception {
        for (String origin : List.of("https://evil.com", "http://evil.com",
                "http://127.0.0.1:1", "https://127.0.0.1:" + port, "null")) {
            Raw.Res r = Raw.req(port, "POST", "/api/scan",
                    "127.0.0.1:" + port, origin, token, new byte[0]);
            assertEquals(403, r.status, "跨源应被拒: " + origin + " → " + r.status);
        }
    }

    @Test
    @DisplayName("伪造 Host + 正确令牌，仍然被拒")
    void 伪造Host加真令牌被拒() throws Exception {
        Raw.Res r = Raw.req(port, "POST", "/api/scan",
                "evil.com:" + port, null, token, new byte[0]);
        assertEquals(403, r.status, r.body);
    }

    @Test
    @DisplayName("三种失败原因共用同一句措辞，不给外部页面试探信号")
    void 拒绝措辞一致() throws Exception {
        String a = Raw.req(port, "POST", "/api/scan", "evil.com:" + port, null, token, new byte[0]).body;
        String b = Raw.req(port, "POST", "/api/scan", "127.0.0.1:" + port, null, null, new byte[0]).body;
        String c = Raw.req(port, "POST", "/api/scan", "127.0.0.1:" + port,
                "https://evil.com", token, new byte[0]).body;
        assertEquals(a, b, "Host 失败与令牌失败的措辞应一致");
        assertEquals(b, c, "令牌失败与 Origin 失败的措辞应一致");
        assertTrue(a.contains(SecurityPolicy.DENIED), a);
    }

    // ---------------------------------------------------------------- CORS

    @Test
    @DisplayName("任何响应都不得带 CORS 头——这是外部页面读不到响应的唯一依据")
    void 不返回Cors头() throws Exception {
        List<Raw.Res> responses = List.of(
                Raw.get(port, "/api/health"),
                Raw.get(port, "/api/rules"),
                Raw.get(port, "/"),
                Raw.req(port, "POST", "/api/scan", "127.0.0.1:" + port, null, token, new byte[0]),
                Raw.req(port, "POST", "/api/scan", "evil.com:" + port, null, null, new byte[0]),
                Raw.get(port, "/nope"));
        for (Raw.Res r : responses) {
            for (String h : List.of("access-control-allow-origin",
                    "access-control-allow-credentials",
                    "access-control-allow-methods",
                    "access-control-allow-headers")) {
                assertFalse(r.headers.containsKey(h),
                        "响应里出现了 " + h + "（status=" + r.status + "）");
            }
        }
    }

    @Test
    @DisplayName("不做 OPTIONS 预检应答")
    void 不答预检() throws Exception {
        // 答了预检就等于告诉浏览器「跨源可以试试」，而我们恰恰不要这条门开着。
        Raw.Res r = Raw.req(port, "OPTIONS", "/api/scan", "127.0.0.1:" + port, null, null, new byte[0]);
        assertEquals(405, r.status, "预检不应被应答: " + r.status + " " + r.body);
    }

    // ---------------------------------------------------------------- 路由语义

    @Test
    void 未知路径404且已知路径方法不对405() throws Exception {
        Raw.Res nf = Raw.get(port, "/api/does-not-exist");
        assertEquals(404, nf.status, nf.body);

        Raw.Res wrongMethod = Raw.req(port, "DELETE", "/api/health",
                "127.0.0.1:" + port, null, null, new byte[0]);
        assertEquals(405, wrongMethod.status, wrongMethod.body);
    }

    @Test
    void 安全响应头齐备() throws Exception {
        Raw.Res r = Raw.get(port, "/");
        assertEquals("no-store", r.headers.get("cache-control").get(0));
        assertEquals("nosniff", r.headers.get("x-content-type-options").get(0));
        assertEquals("no-referrer", r.headers.get("referrer-policy").get(0));
        String csp = r.headers.get("content-security-policy").get(0);
        assertTrue(csp.contains("default-src 'none'"), csp);
        assertTrue(csp.contains("frame-ancestors 'none'"), csp);
    }

    @Test
    @DisplayName("路由清单把读写标出来，界面据此提示哪些操作需要令牌")
    void 路由清单可读() throws Exception {
        Raw.Res r = Raw.get(port, "/api/health");
        assertTrue(r.body.contains("POST /api/scan [写]"), r.body);
        assertTrue(r.body.contains("GET /api/health [读]"), r.body);
    }

    /**
     * 本机第一个 RFC 1918 站点本地 IPv4；没有就返回 null（由调用方跳过）。
     *
     * <p>不能只排除 127/8：macOS 可能同时持有 169.254/16 链路本地地址，
     * 该地址属于本机并走 LOCAL 路由，不能代表普通局域网客户端。
     */
    private static String 本机局域网地址() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet6Address || !a.isSiteLocalAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (ip != null) {
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {
            // 取不到就当作没有
        }
        return null;
    }

    /**
     * 极简裸 HTTP 客户端。
     *
     * <p>只做一件事：把请求原样写出去，把响应原样读回来。
     * 刻意不引 HTTP 客户端库——引进来的话「谁设置了 Host 头」就成了库的实现细节，
     * 而那正是本测试要控制的部分。
     */
    static final class Raw {

        record Res(int status, String body, Map<String, List<String>> headers) {
        }

        static Res get(int port, String path) throws IOException {
            return req(port, "GET", path, "127.0.0.1:" + port, null, null, new byte[0]);
        }

        static Res req(int port, String method, String path, String host,
                       String origin, String tokenHeader, byte[] body) throws IOException {
            return req(port, method, path, host, origin, tokenHeader, body, Map.of());
        }

        /** 带任意附加请求头（M2-b 的上传要用 {@code Content-Type} 与 {@code X-Filename}）。 */
        static Res req(int port, String method, String path, String host,
                       String origin, String tokenHeader, byte[] body,
                       Map<String, String> extraHeaders) throws IOException {
            ResBytes raw = exchange(port, method, path, host, origin, tokenHeader, extraHeaders, body);
            return new Res(raw.status(), new String(raw.body(), StandardCharsets.UTF_8), raw.headers());
        }

        /** 只取响应体原始字节——下载回来的文档是二进制，按 UTF-8 解码会毁掉它。 */
        static byte[] bodyBytes(int port, String method, String path, String host,
                                String origin, String tokenHeader, Map<String, String> extraHeaders)
                throws IOException {
            return exchange(port, method, path, host, origin, tokenHeader, extraHeaders, new byte[0]).body();
        }

        /** 带二进制请求体，并保留二进制响应体。 */
        static byte[] bodyBytes(int port, String method, String path, String host,
                                String origin, String tokenHeader, byte[] body,
                                Map<String, String> extraHeaders) throws IOException {
            ResBytes response = exchange(port, method, path, host, origin, tokenHeader,
                    extraHeaders, body);
            assertEquals(200, response.status(),
                    () -> new String(response.body(), StandardCharsets.UTF_8));
            return response.body();
        }

        private record ResBytes(int status, Map<String, List<String>> headers, byte[] body) {
        }

        private static ResBytes exchange(int port, String method, String path, String host,
                                         String origin, String tokenHeader,
                                         Map<String, String> extraHeaders, byte[] body) throws IOException {
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            if (origin != null) {
                req.append("Origin: ").append(origin).append("\r\n");
            }
            if (tokenHeader != null) {
                req.append("X-Litemask-Token: ").append(tokenHeader).append("\r\n");
            }
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            req.append("Content-Length: ").append(body.length).append("\r\n");
            req.append("Connection: close\r\n\r\n");

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                s.setSoTimeout(30000);
                OutputStream out = s.getOutputStream();
                out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush();

                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                try (InputStream in = s.getInputStream()) {
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        buf.write(chunk, 0, n);
                    }
                }
                return split(buf.toByteArray());
            }
        }

        /** 按字节切头部与体：二进制体不能先转字符串再切回来。 */
        private static ResBytes split(byte[] raw) {
            int marker = -1;
            for (int i = 0; i + 3 < raw.length; i++) {
                if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                    marker = i;
                    break;
                }
            }
            if (marker < 0) {
                throw new AssertionError("响应里找不到头部结束标记，长度 " + raw.length);
            }
            String head = new String(raw, 0, marker, StandardCharsets.ISO_8859_1);
            byte[] body = java.util.Arrays.copyOfRange(raw, marker + 4, raw.length);
            String[] lines = head.split("\r\n");
            String[] statusParts = lines[0].split(" ", 3);
            assertTrue(statusParts.length >= 2, "状态行异常: " + lines[0]);
            int status = Integer.parseInt(statusParts[1]);

            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                headers.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
            return new ResBytes(status, headers, body);
        }
    }
}
