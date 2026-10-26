package com.litemask.cli;

import com.litemask.server.Json;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServeShutdownProcessTest {

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

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java,
                "-Duser.home=" + home,
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "serve",
                "--no-browser", "--print-token", "--port", "0",
                "--workspace", workspace.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
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
