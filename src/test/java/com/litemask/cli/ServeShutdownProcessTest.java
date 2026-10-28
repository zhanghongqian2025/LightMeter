package com.litemask.cli;

import com.litemask.ner.ModelManager;
import com.litemask.server.Json;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServeShutdownProcessTest {

    @Test
    @Timeout(30)
    @DisplayName("并发 cleanup 的非 owner 必须等待 owner 完整清理后返回")
    void concurrentCleanupWaitsForOwner(@TempDir Path tmp) throws Exception {
        ModelManager models = new ModelManager(tmp.resolve("models"), null, tmp.resolve("bundle"));
        CountDownLatch ownerInside = new CountDownLatch(1);
        CountDownLatch allowOwnerFinish = new CountDownLatch(1);
        ServeCommand.ServiceLifecycle lifecycle = new ServeCommand.ServiceLifecycle(models, () -> {
            ownerInside.countDown();
            try {
                allowOwnerFinish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> owner = pool.submit(lifecycle::cleanup);
        try {
            assertTrue(ownerInside.await(5, TimeUnit.SECONDS), "owner 必须已进入资源清理临界区");
            CountDownLatch followerStarted = new CountDownLatch(1);
            Future<?> follower = pool.submit(() -> {
                followerStarted.countDown();
                lifecycle.cleanup();
            });
            assertTrue(followerStarted.await(5, TimeUnit.SECONDS), "非 owner 调用必须已经开始执行");
            assertThrows(TimeoutException.class, () -> follower.get(200, TimeUnit.MILLISECONDS),
                    "非 owner 不能在 owner 资源清理未完成时提前返回");

            allowOwnerFinish.countDown();
            owner.get(5, TimeUnit.SECONDS);
            follower.get(5, TimeUnit.SECONDS);
        } finally {
            allowOwnerFinish.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("Unix SIGTERM 在真实 JVM 被 reap 前删除精确会话目录与会话锁")
    void sigtermWaitsForCleanupBeforeJvmExit(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("windows"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Process process = startServe(home, workspace);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Thread drainer = new Thread(() -> drain(process.getInputStream(), output),
                "serve-sigterm-test-output");
        drainer.setDaemon(true);
        drainer.start();
        try {
            waitForLineValue(output, "本地脱敏服务已启动", process);
            Path ownSession = Path.of(waitForLineValue(output, "工作目录", process));
            Path ownLock = workspace.resolve("locks")
                    .resolve(ownSession.getFileName().toString() + ".lock");
            assertTrue(Files.isDirectory(ownSession), "前提：会话目录存在");
            assertTrue(Files.isRegularFile(ownLock), "前提：精确会话锁存在");

            process.destroy();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS),
                    "SIGTERM 后 JVM 未退出，PID=" + process.pid() + "\n" + output);
            assertFalse(ProcessHandle.of(process.pid()).map(ProcessHandle::isAlive).orElse(false),
                    "waitFor 返回后 PID 仍存活: " + process.pid());
            assertFalse(Files.exists(ownSession), "SIGTERM 正常退出不得留下空会话目录");
            assertFalse(Files.exists(ownLock), "SIGTERM 正常退出不得留下会话锁文件");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            drainer.join(2_000);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("GUI 退出先完整回 JSON，再退出新 JVM 并只清理自己会话")
    void shutdownRespondsThenExitsAndPreservesPersistentFiles(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Path adjacent = workspace.resolve("adjacent.keep");
        Path download = Files.createDirectories(home.resolve("Downloads")).resolve("saved-product.txt");
        Path modelState = Files.createDirectories(home.resolve(".litemask/models/ner"))
                .resolve("persistent-model-state.keep");
        Files.writeString(adjacent, "adjacent", StandardCharsets.UTF_8);
        Files.writeString(download, "download", StandardCharsets.UTF_8);
        Files.writeString(modelState, "model-state", StandardCharsets.UTF_8);

        Process process = startServe(home, workspace);
        long pid = process.pid();
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Thread drainer = new Thread(() -> drain(process.getInputStream(), output),
                "serve-shutdown-test-output");
        drainer.setDaemon(true);
        drainer.start();

        try {
            String token = waitForLineValue(output, "会话令牌:", process);
            String address = waitForLineValue(output, "地址", process);
            Path ownSession = Path.of(waitForLineValue(output, "工作目录", process));
            int port = URI.create(address).getPort();
            assertTrue(Files.isDirectory(ownSession), "启动输出的会话目录不存在: " + ownSession);

            Path adjacentSession = Files.createDirectories(
                    ownSession.getParent().resolve("adjacent-session-preserved"));
            Path adjacentSessionMarker = adjacentSession.resolve("keep.txt");
            Files.writeString(adjacentSessionMarker, "keep", StandardCharsets.UTF_8);

            HttpResult noToken = post(address + "api/shutdown", null, null);
            assertEquals(403, noToken.status(), noToken.body());
            assertTrue(process.isAlive(), "没有令牌的退出请求不得停服");

            HttpResult accepted = post(address + "api/shutdown", token, null);
            assertEquals(200, accepted.status(), accepted.body());
            @SuppressWarnings("unchecked")
            Map<String, Object> json = Json.read(
                    accepted.body().getBytes(StandardCharsets.UTF_8), Map.class);
            assertEquals(Boolean.TRUE, json.get("ok"), accepted.body());
            assertEquals(Boolean.TRUE, json.get("stopping"), accepted.body());
            assertTrue(String.valueOf(json.get("message")).contains("安全检查点"), accepted.body());

            assertTrue(process.waitFor(30, TimeUnit.SECONDS),
                    "已收到退出 JSON，但 PID " + pid + " 在 30 秒后仍未退出\n" + output);
            assertEquals(0, process.exitValue(), "服务退出码异常\n" + output);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "进程 PID 仍存活: " + pid);
            assertPortClosed(port);

            assertFalse(Files.exists(ownSession), "本次会话目录应在退出时删除");
            assertTrue(Files.isRegularFile(adjacent), "工作区相邻文件被误删");
            assertTrue(Files.isRegularFile(adjacentSessionMarker), "相邻会话目录被误删");
            assertTrue(Files.isRegularFile(download), "已下载成品被误删");
            assertTrue(Files.isRegularFile(modelState), "持久模型状态被误删");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            drainer.join(2_000);
        }
    }

    private static Process startServe(Path home, Path workspace) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java,
                "-Duser.home=" + home,
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "serve",
                "--no-browser", "--print-token", "--port", "0",
                "--workspace", workspace.toString());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static void drain(InputStream input, List<String> output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                output.add(line);
            }
        } catch (IOException e) {
            output.add("读取子进程输出失败: " + e.getMessage());
        }
    }

    private static String waitForLineValue(List<String> output, String prefix, Process process)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            synchronized (output) {
                for (String line : output) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(prefix)) {
                        return trimmed.substring(prefix.length()).trim();
                    }
                }
            }
            if (!process.isAlive()) {
                throw new AssertionError("服务在报出 " + prefix + " 前退出\n" + output);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待服务输出超时: " + prefix + "\n" + output);
    }

    private static HttpResult post(String endpoint, String token, String origin) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        connection.setConnectTimeout(3_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(0);
        if (token != null) {
            connection.setRequestProperty("X-Litemask-Token", token);
        }
        if (origin != null) {
            connection.setRequestProperty("Origin", origin);
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new HttpResult(status, body);
    }

    private static void assertPortClosed(int port) {
        assertThrows(IOException.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            }
        }, "进程退出后端口仍可连接: " + port);
    }

    private record HttpResult(int status, String body) {
    }
}
