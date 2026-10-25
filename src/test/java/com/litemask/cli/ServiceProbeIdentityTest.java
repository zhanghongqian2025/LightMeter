package com.litemask.cli;

import com.litemask.core.ServiceIdentity;
import com.litemask.server.ApiHandlers;
import com.litemask.server.LocalServer;
import com.litemask.server.Workspace;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「服务在不在」这个判据的**判别力**测试（块 34）。
 *
 * <p><b>这个类在钉什么</b>：探活的「在线」不能只看「HTTP 200」。
 * 端口上被<b>任何</b>服务占着、只要它回 200，旧实现就说「在线」——
 * 而 {@code autostart status} 恰恰是用户验收「重启后服务自动起来」的那条命令。
 * 它说「在线」而界面打不开，用户就永远查不出问题在哪。
 *
 * <p><b>为什么每一种形态都要真起一个服务</b>：这些判定必须走**真的 socket**
 * （真 HTTP、真超时、真连接被拒），用假实现只能证明「实现自洽」，
 * 证明不了「与服务端真的对得上」。
 *
 * <p><b>正反两面都要有</b>：只测「不是本工具 → 不在线」，
 * 一个「永远报不在线」的实现也能全绿；只测「真服务 → 在线」，
 * 一个「永远报在线」的实现也能全绿。所以两类都在。
 */
class ServiceProbeIdentityTest {

    /** 探活实现（真 HTTP）。 */
    private final ServiceProbe.Http probe = new ServiceProbe.Http();

    // ---------------------------------------------------------------- 真服务

    @Test
    @DisplayName("真服务在跑时：探活报「在线」，且判断依据说清了三项都核对过")
    void 真服务报在线(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            LocalServer server = LocalServer.create(ws, 0);
            ApiHandlers.register(server, ws, List.of());
            server.start();
            try {
                ServiceProbe.Result r = probe.probe(server.port());
                assertEquals(ServiceProbe.State.ONLINE, r.state(),
                        "真服务必须报在线：" + r.detail());
                assertTrue(r.online(), "online() 必须与档位一致");
                assertTrue(r.detail().contains("服务名"), "依据要说清核对了哪些：" + r.detail());
                assertTrue(r.detail().contains("协议版本"), "依据要说清核对了哪些：" + r.detail());
                assertTrue(r.detail().contains("挑战"), "依据要说清核对了哪些：" + r.detail());
            } finally {
                server.close();
            }
        }
    }

    @Test
    @DisplayName("健康检查仍然可以手工查（不带挑战也答 200，且报出身份与协议）")
    void 手工查仍然可用(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            LocalServer server = LocalServer.create(ws, 0);
            ApiHandlers.register(server, ws, List.of());
            server.start();
            try {
                // 【为什么这条要钉住】加了挑战握手之后，最容易出的岔子是
                // 「手工 curl 一下看看服务在不在」这条退路被堵死。
                //   → 挑战是**可选**的：不带它，服务照样答 200，只是没有回显字段。
                String body = get(server.port(), "/api/health", null);
                assertTrue(body.contains("\"ok\":true"), body);
                assertTrue(body.contains("\"" + ServiceIdentity.FIELD_SERVICE + "\":\""
                        + ServiceIdentity.NAME + "\""), body);
                assertTrue(body.contains("\"" + ServiceIdentity.FIELD_PROTOCOL + "\":"
                        + ServiceIdentity.PROTOCOL), body);
                assertTrue(!body.contains("\"" + ServiceIdentity.FIELD_CHALLENGE + "\""),
                        "没带挑战就不该有回显字段：" + body);
            } finally {
                server.close();
            }
        }
    }

    @Test
    @DisplayName("挑战是**每次新生成**的（不是常量）——常量就等于没有握手")
    void 挑战每次都不一样() throws Exception {
        List<String> 收到的挑战 = new java.util.ArrayList<>();
        HttpServer fake = startFake(0, challenge -> {
            收到的挑战.add(challenge);
            // 老实回显：只有老实回显，服务端才可能通过探活的身份核对。
            return "{\"" + ServiceIdentity.FIELD_SERVICE + "\":\"" + ServiceIdentity.NAME
                    + "\",\"" + ServiceIdentity.FIELD_PROTOCOL + "\":" + ServiceIdentity.PROTOCOL
                    + ",\"" + ServiceIdentity.FIELD_CHALLENGE + "\":\"" + challenge + "\"}";
        });
        try {
            ServiceProbe.Result r1 = probe.probe(fake.getAddress().getPort());
            ServiceProbe.Result r2 = probe.probe(fake.getAddress().getPort());
            assertEquals(ServiceProbe.State.ONLINE, r1.state(), r1.detail());
            assertEquals(ServiceProbe.State.ONLINE, r2.state(), r2.detail());
            assertEquals(2, 收到的挑战.size(), "两次探活必须各带一个挑战");
            assertNotEquals(收到的挑战.get(0), 收到的挑战.get(1),
                    "挑战必须是**每次新生成**的：常量等于没有握手");
        } finally {
            fake.stop(0);
        }
    }

    // ------------------------------------------------------------ 端口上不是它

    @Test
    @DisplayName("端口上是不相干的服务时：必须报「不是本工具的服务」，不能报在线")
    void 别的服务不能冒充在线() throws Exception {
        // 【为什么要老实回显挑战】
        //   这一场要验的是「服务名不对 → 被挡下」。
        //   若这个假服务**连挑战也不回显**，它会被**三道核对里的任何一道**
        //   挡住（代码是顺序判的，第一道不匹配就返回）——
        //   于是「拆掉服务名核对」这个变异**照样红**（红在别的用例上），
        //   而「服务名核对」这一道就<b>永远没被单独验过</b>。
        //   → 每个「不是本工具」的用例都必须**只**被它要打的那一道核对挡住。
        HttpServer fake = startFake(0, challenge -> "{\"ok\":true,\""
                + ServiceIdentity.FIELD_CHALLENGE + "\":\"" + challenge + "\"}");
        try {
            ServiceProbe.Result r = probe.probe(fake.getAddress().getPort());
            assertEquals(ServiceProbe.State.NOT_OURS, r.state(),
                    "端口上不是本工具的服务，绝不能报在线：" + r.detail());
            assertTrue(!r.online(), "online() 必须为假");
            assertTrue(r.detail().contains("不是本工具"), "原因要说清：" + r.detail());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    @DisplayName("自称**别的**服务名时：不能报在线（服务名核对单独把住这一道）")
    void 服务名不对不能算在线() throws Exception {
        // 【这一条是为了「隔离」服务名核对而存在的】
        //   服务名不对、协议版本对、挑战也老实回显 ——
        //   → 只有「服务名核对」这一道能挡住它。
        HttpServer fake = startFake(0, challenge -> "{\""
                + ServiceIdentity.FIELD_SERVICE + "\":\"some-other-tool\",\""
                + ServiceIdentity.FIELD_PROTOCOL + "\":" + ServiceIdentity.PROTOCOL
                + ",\"" + ServiceIdentity.FIELD_CHALLENGE + "\":\"" + challenge + "\"}");
        try {
            ServiceProbe.Result r = probe.probe(fake.getAddress().getPort());
            assertEquals(ServiceProbe.State.NOT_OURS, r.state(),
                    "服务名不对就不是本工具：" + r.detail());
            assertTrue(r.detail().contains("服务名"), "原因要说清是服务名：" + r.detail());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    @DisplayName("自称是本工具、但**不回显挑战**时：仍不能报在线（声明不等于握手）")
    void 只声明不握手不能算在线() throws Exception {
        // 【这一条是为了「隔离」挑战核对而存在的】
        //   服务名对、协议版本对，唯独**不回显挑战** ——
        //   → 只有「挑战回显核对」这一道能挡住它。
        HttpServer fake = startFake(0, challenge -> "{\""
                + ServiceIdentity.FIELD_SERVICE + "\":\"" + ServiceIdentity.NAME + "\",\""
                + ServiceIdentity.FIELD_PROTOCOL + "\":" + ServiceIdentity.PROTOCOL + "}");
        try {
            ServiceProbe.Result r = probe.probe(fake.getAddress().getPort());
            assertEquals(ServiceProbe.State.NOT_OURS, r.state(),
                    "没有按本工具的协议应答，不能算在线：" + r.detail());
            assertTrue(r.detail().contains("挑战"), "原因要说清是挑战没被回显：" + r.detail());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    @DisplayName("协议版本对不上时：不能报在线（那是「服务没重启」，不是「不是它」）")
    void 协议版本对不上不能算在线() throws Exception {
        // 【这一条是为了「隔离」协议版本核对而存在的】
        //   服务名对、**挑战也老实回显**，唯独协议版本不对 ——
        //   → 只有「协议版本核对」这一道能挡住它。
        HttpServer fake = startFake(0, challenge -> "{\""
                + ServiceIdentity.FIELD_SERVICE + "\":\"" + ServiceIdentity.NAME + "\",\""
                + ServiceIdentity.FIELD_PROTOCOL + "\":" + (ServiceIdentity.PROTOCOL + 1)
                + ",\"" + ServiceIdentity.FIELD_CHALLENGE + "\":\"" + challenge + "\"}");
        try {
            ServiceProbe.Result r = probe.probe(fake.getAddress().getPort());
            assertEquals(ServiceProbe.State.NOT_OURS, r.state(),
                    "版本对不上不能算在线：" + r.detail());
            assertTrue(r.detail().contains("协议版本"), "原因要说清是版本：" + r.detail());
        } finally {
            fake.stop(0);
        }
    }

    // -------------------------------------------------------------- 不健康/不在

    @Test
    @DisplayName("服务答非 200 时：是「在但不健康」，不是「没在监听」")
    void 非200是在但不健康() throws Exception {
        HttpServer fake = startFake(0, 500, challenge -> "{\"ok\":false}");
        try {
            ServiceProbe.Result r = probe.probe(fake.getAddress().getPort());
            assertEquals(ServiceProbe.State.UNHEALTHY, r.state(),
                    "服务在应答但答不上来，是「不健康」：" + r.detail());
            assertTrue(r.detail().contains("HTTP"), "原因要说清状态码：" + r.detail());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    @DisplayName("端口上没有服务在听时：是「没在监听」")
    void 没在监听() throws Exception {
        // 先占一个端口再放掉 —— 拿到一个**确定**没人听的端口。
        int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ServiceProbe.Result r = probe.probe(port);
        assertEquals(ServiceProbe.State.DOWN, r.state(),
                "没人听就是没在监听：" + r.detail());
        assertTrue(r.detail().contains("连不上"), "原因要说清：" + r.detail());
    }

    @Test
    @DisplayName("端口不知道时：不得猜一个端口去探（前提不成立要显式说）")
    void 端口不知道就说不知道() {
        ServiceProbe.Result r = probe.probe(0);
        assertEquals(ServiceProbe.State.UNKNOWN, r.state(), r.detail());
        assertTrue(!r.online(), "不知道不能算在线");
        assertTrue(r.detail().contains("不知道"), r.detail());
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 起一个假服务：{@code bodyByChallenge} 收到「这次探活发来的挑战」，返回要回的响应体。
     *
     * @param port 0 = 随机端口
     * @param bodyByChallenge 由挑战决定响应体的函数
     */
    private static HttpServer startFake(int port,
            java.util.function.Function<String, String> bodyByChallenge) {
        return startFake(port, 200, bodyByChallenge);
    }

    /**
     * 起一个假服务：{@code bodyByChallenge} 收到「这次探活发来的挑战」，返回要回的响应体。
     *
     * @param port 0 = 随机端口
     * @param status 要回的状态码
     * @param bodyByChallenge 由挑战决定响应体的函数
     */
    private static HttpServer startFake(int port, int status,
            java.util.function.Function<String, String> bodyByChallenge) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            s.createContext("/api/health", ex -> {
                String challenge = ex.getRequestHeaders().getFirst(ServiceIdentity.CHALLENGE_HEADER);
                byte[] out = bodyByChallenge.apply(challenge).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(status, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            });
            s.start();
            return s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("起假服务失败", e);
        }
    }

    /** 真的发一次 GET（不带挑战），拿响应体。 */
    private static String get(int port, String path, String challenge) throws Exception {
        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) java.net.URI
                        .create("http://127.0.0.1:" + port + path).toURL().openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        if (challenge != null) {
            c.setRequestProperty(ServiceIdentity.CHALLENGE_HEADER, challenge);
        }
        try (java.io.InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
