package com.litemask.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 HTTP 服务。
 *
 * <p><b>绑回环不是可选项。</b>Word/WPS 加载项只能走 http(s)，所以本机必须有一个服务；
 * 但一旦绑成 {@code 0.0.0.0}，整个局域网都能连上来，而这个服务会替用户处理文件。
 * 因此这里把绑定地址写死成 {@code 127.0.0.1}，并在 {@link #create} 里做一次
 * 启动自检——真的只监听回环，不是「我们打算只监听回环」。
 *
 * <p>访问控制由 {@link SecurityPolicy} 负责：Host 头白名单防 DNS rebinding，
 * Origin 同源 + 会话令牌防外部网页发起写请求。本类只负责把它俩接到每一条路由上，
 * 并且**在读请求体之前**就判定——先读进来再判断，等于先让攻击者把数据送到本机。
 *
 * <p>刻意不做的事：
 * <ul>
 *   <li>不返回 {@code Access-Control-Allow-Origin}。跨源脚本连响应都读不到，令牌自然偷不走。
 *       加载项走本机进程调接口，不受 CORS 约束，不需要这个头。</li>
 *   <li>不做 {@code OPTIONS} 预检应答。不配 CORS 就不该有预检应答，
 *       否则等于把跨源读取的门打开一条缝。</li>
 *   <li>不解析 {@code multipart/form-data}。上传体是 {@code application/octet-stream}
 *       加一个 {@code X-Filename} 头，引一个 multipart 解析器就是多一份攻击面，而
 *       「拖一个文件进来」并不需要它。</li>
 * </ul>
 */
public final class LocalServer implements AutoCloseable {

    /** 绑定地址。写死，不从参数来。 */
    private static final String BIND_ADDRESS = "127.0.0.1";

    /** 固定大小线程池。用有界而不是默认的：默认实现会为每个连接开一个线程。 */
    private static final int THREADS = 4;

    private final HttpServer http;
    private final Workspace workspace;
    private final SecurityPolicy policy;
    private final ExecutorService executor;
    private final List<Route> routes = new ArrayList<>();
    /**
     * 随服务一起关闭的资源。
     *
     * <p>后台任务池有它自己的工作线程，不关的话进程退出会一直等它们跑完——
     * 而它们跑的是「脱敏用户文档」，没人愿意为一个已经退出的程序多等几秒。
     */
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private volatile Runnable shutdownRequest;

    private LocalServer(HttpServer http, Workspace workspace, SecurityPolicy policy, ExecutorService executor) {
        this.http = http;
        this.workspace = workspace;
        this.policy = policy;
        this.executor = executor;
    }

    /**
     * 绑定端口、建好策略，但<b>还没开始服务</b>。
     *
     * <p>拆成两步是因为 {@link SecurityPolicy} 需要真实端口（传 0 让系统分配时，
     * 拿到端口之前没法构造 Host 白名单）。
     */
    public static LocalServer create(Workspace workspace, int requestedPort) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        int port = normalizePort(requestedPort);
        HttpServer http;
        try {
            // 注意传的是字符串形式的回环地址，不是 new InetSocketAddress(port)——
            // 后者是通配地址，会绑到 0.0.0.0。
            http = HttpServer.create(new InetSocketAddress(BIND_ADDRESS, port), 32);
        } catch (BindException e) {
            throw new IOException("端口 " + port + " 已被占用，无法启动本地服务。"
                    + "换一个端口重试（serve --port 8090），或先关掉占用它的程序。", e);
        }
        InetSocketAddress bound = http.getAddress();
        if (bound == null || !SecurityPolicy.isLoopback(bound)) {
            // 到这里说明绑定行为与预期不符。这是启动失败，不是警告：
            // 一个绑在全网地址上的脱敏工具，比一个起不来的脱敏工具危险得多。
            http.stop(0);
            throw new IOException("启动自检失败：服务绑定的不是回环地址（实际 "
                    + (bound == null ? "未知" : bound) + "）。已中止启动。");
        }
        SecurityPolicy policy = new SecurityPolicy(SecurityPolicy.newToken(), bound.getPort());
        ExecutorService executor = Executors.newFixedThreadPool(THREADS, threadFactory());
        return new LocalServer(http, workspace, policy, executor);
    }

    private static int normalizePort(int requested) throws IOException {
        if (requested == 0) {
            return 0;   // 让系统分配
        }
        if (requested < 0 || requested > 65535) {
            throw new IOException("端口超出范围: " + requested);
        }
        return requested;
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "litemask-http-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 注册一条路由并开始服务。
     *
     * @param method 只接受这个方法，其它方法回 405
     * @param path   精确匹配的路径（不做前缀匹配，避免 {@code /api/rules/../../} 之类的歧义）
     * @param write  是否是写操作——决定要不要额外查 Origin 和令牌
     */
    public void route(String method, String path, boolean write, Handler handler) {
        routes.add(new Route(method, path, write, handler));
    }

    /** 开始服务。必须在所有 {@link #route} 之后调用。 */
    public void start() {
        http.createContext("/", this::dispatch);
        http.setExecutor(executor);
        http.start();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            // Host 先判。读请求体之前判：读进来就等于让外部数据落到本机内存里。
            if (!policy.isAllowedHost(exchange.getRequestHeaders().getFirst("Host"))) {
                deny(exchange);
                return;
            }
            Route matched = null;
            boolean pathKnown = false;
            for (Route r : routes) {
                if (r.path.equals(path)) {
                    pathKnown = true;
                    if (r.method.equals(method)) {
                        matched = r;
                        break;
                    }
                }
            }
            if (matched == null) {
                // 路径存在但方法不对，与路径根本不存在要分开报：
                // 前者是调用方写错了，后者是压根没有这个接口。
                Http.text(exchange, pathKnown ? 405 : 404,
                        pathKnown ? "方法不允许: " + method : "没有这个接口: " + path);
                return;
            }
            if (matched.write && !policy.isAllowedWrite(
                    exchange.getRequestHeaders().getFirst("Host"),
                    exchange.getRequestHeaders().getFirst("Origin"),
                    exchange.getRequestHeaders().getFirst("X-Litemask-Token"))) {
                deny(exchange);
                return;
            }
            matched.handler.handle(exchange);
        } catch (Exception e) {
            // 兜底：处理器抛到这里的任何异常都不能变成连接被重置——那会让调用方
            // 以为「请求失败」，而实际上服务端可能已经做了一部分动作。
            try {
                Http.json(exchange, 500, java.util.Map.of(
                        "ok", false,
                        "error", "服务内部错误: " + e.getClass().getSimpleName()));
            } catch (IOException ignored) {
                // 连错误都写不出去就算了
            }
        } finally {
            exchange.close();
        }
    }

    private void deny(HttpExchange exchange) throws IOException {
        Http.json(exchange, 403, java.util.Map.of("ok", false, "error", SecurityPolicy.DENIED));
    }

    public int port() {
        return policy.port();
    }

    /**
     * 实际绑定的地址，从 socket 上读，<b>不是</b> {@link #BIND_ADDRESS} 常量。
     *
     * <p>为什么要专门开这个方法：健康检查里要报「绑在哪」。
     * 如果那里直接写常量字符串，那么把 {@code BIND_ADDRESS} 改成 {@code 0.0.0.0} 之后，
     * 接口仍然会报「绑在 127.0.0.1」——<b>报告在骗人，而界面正是照着它显示的</b>。
     * 从 socket 读就没有第二份字符串可以走样。
     */
    public String boundAddress() {
        InetSocketAddress bound = http.getAddress();
        if (bound == null || bound.getAddress() == null) {
            return "未知";
        }
        return bound.getAddress().getHostAddress();
    }

    /** 启动自检是否通过了。 */
    public boolean loopbackVerified() {
        InetSocketAddress bound = http.getAddress();
        return bound != null && SecurityPolicy.isLoopback(bound);
    }

    public String url() {
        return "http://" + BIND_ADDRESS + ":" + policy.port() + "/";
    }

    public SecurityPolicy policy() {
        return policy;
    }

    public Workspace workspace() {
        return workspace;
    }

    /** 已注册的路由，供诊断与测试。 */
    public List<String> routeSummary() {
        List<String> out = new ArrayList<>();
        for (Route r : routes) {
            out.add(r.method + " " + r.path + (r.write ? " [写]" : " [读]"));
        }
        return out;
    }

    /**
     * 注册一个随服务一起关闭的资源。
     *
     * <p>服务关闭时按注册顺序的反序关闭。
     */
    public void onClose(AutoCloseable resource) {
        if (resource != null) {
            resources.add(resource);
        }
    }

    /**
     * 注入「请求整个桌面服务退出」的生命周期回调。
     *
     * <p>没有注入时就不应暴露 GUI 退出路由：测试内嵌服务、库调用者和
     * 命令行同步服务的生命周期都由它们自己管，网页不能擅自关掉。
     */
    public void onShutdownRequest(Runnable request) {
        if (request == null) {
            throw new IllegalArgumentException("停服回调不能为 null");
        }
        if (shutdownRequest != null) {
            throw new IllegalStateException("停服回调已注册");
        }
        shutdownRequest = request;
    }

    public boolean shutdownRequestEnabled() {
        return shutdownRequest != null;
    }

    /**
     * 响应已完整写出后，在独立守护线程上发出一次性停服信号。
     * 回调不在 HTTP 请求线程上执行，因此不会发生「正在写退出响应时把自己关掉」。
     */
    public void requestShutdownAfterResponse() {
        Runnable request = shutdownRequest;
        if (request == null || !shutdownRequested.compareAndSet(false, true)) {
            return;
        }
        Thread signal = new Thread(request, "litemask-shutdown-request");
        signal.setDaemon(true);
        signal.start();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        http.stop(0);
        executor.shutdownNow();
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception ignored) {
                // 关闭失败不该把服务关不掉——它已经在停了
            }
        }
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
                executor.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一条路由。 */
    public record Route(String method, String path, boolean write, Handler handler) {
    }

    /** 处理器。异常会被 {@link #dispatch} 接住并转成 500。 */
    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** 响应与请求体的小工具。 */
    public static final class Http {

        private Http() {
        }

        public static void json(HttpExchange exchange, int status, Object payload) throws IOException {
            send(exchange, status, "application/json; charset=utf-8", Json.toBytes(payload));
        }

        public static void text(HttpExchange exchange, int status, String body) throws IOException {
            send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        }

        public static void send(HttpExchange exchange, int status, String contentType, byte[] body)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            // 本机页面里可能带用户文件名，给下游一个明确的不外传声明
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'self'; style-src 'self'; "
                            + "img-src 'self' data:; connect-src 'self'; form-action 'none'; "
                            + "base-uri 'none'; frame-ancestors 'none'");
            boolean head = "HEAD".equals(exchange.getRequestMethod());
            exchange.sendResponseHeaders(status, head ? -1 : body.length);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }

        /**
         * 带上限地读请求体。
         *
         * <p>上限是必须的：本机工具同样会被外部网页喂任意大的请求体，
         * 不限就是一条现成的内存耗尽路径。超限时**直接把流读完丢弃**再报错，
         * 否则连接会被重置，调用方看到的是「网络错误」而不是「文件太大」——
         * 而本机浏览器是自己开的，用户会以为程序崩了。
         */
        public static byte[] readBody(HttpExchange exchange, long limit) throws IOException {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            long total = 0;
            boolean over = false;
            try (InputStream in = exchange.getRequestBody()) {
                int n;
                while ((n = in.read(chunk)) > 0) {
                    total += n;
                    if (total > limit) {
                        over = true;
                    } else if (buf.size() + n <= limit) {
                        buf.write(chunk, 0, n);
                    }
                    // 超限后继续读但不保存：只多占一个 chunk 的内存
                }
            }
            if (over) {
                throw new BodyTooLargeException(limit);
            }
            return buf.toByteArray();
        }
    }

    /** 请求体超过上限。 */
    public static final class BodyTooLargeException extends IOException {
        public BodyTooLargeException(long limit) {
            super("请求体超过上限（" + (limit / 1024 / 1024) + "MB）");
        }
    }
}
