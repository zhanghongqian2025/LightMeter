package com.litemask.server;

import com.litemask.ner.ModelManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型 HTTP 导入在验签前也必须有压缩上传预算。 */
class ModelUploadLimitTest {

    @Test
    @DisplayName("模型上传在边界内逐块复制且字节不变")
    void 边界内逐块复制() throws Exception {
        byte[] input = new byte[128 * 1024 + 17];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (i * 31);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long copied = ApiHandlers.copyModelUpload(
                new ByteArrayInputStream(input), output, input.length);

        assertEquals(input.length, copied);
        assertArrayEquals(input, output.toByteArray(), "合法模型包上传不能被截断或改写");
    }

    @Test
    @DisplayName("模型上传真实字节超过声明预算时立即拒绝")
    void 实际字节超限() {
        byte[] input = new byte[65 * 1024];
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ApiHandlers.ModelUploadTooLargeException failure = assertThrows(
                ApiHandlers.ModelUploadTooLargeException.class,
                () -> ApiHandlers.copyModelUpload(
                        new ByteArrayInputStream(input), output, 64 * 1024));

        assertTrue(failure.getMessage().contains("上限"));
        assertTrue(output.size() <= 64 * 1024,
                "超限后不能继续把攻击者控制的正文写入临时文件");
    }

    @Test
    @DisplayName("模型上传限制拒绝负数配置")
    void 负数限制拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiHandlers.copyModelUpload(
                        new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), -1));
    }

    @Test
    @DisplayName("模型接口在创建临时上传前以 413 拒绝超大声明")
    void 声明超限返回413(@TempDir Path tmp) throws Exception {
        Workspace workspace = Workspace.open(tmp.resolve("workspace"));
        ModelManager manager = new ModelManager(
                tmp.resolve("store"), null, tmp.resolve("bundled"));
        LocalServer server = LocalServer.create(workspace, 0);
        ApiHandlers.register(server, workspace, ApiHandlers.defaultRules(), List.of(), manager, manager);
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5_000);
            long tooLarge = ApiHandlers.MAX_MODEL_UPLOAD_BYTES + 1;
            String request = "POST /api/model/import HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + server.port() + "\r\n"
                    + "X-Litemask-Token: " + server.policy().token() + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "X-Filename: too-large.zip\r\n"
                    + "Content-Length: " + tooLarge + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            // 声明的巨大正文故意不发送；关闭写半边，证明处理器会先看长度而不是等正文落盘。
            socket.shutdownOutput();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            in.transferTo(response);
            String wire = response.toString(StandardCharsets.UTF_8);
            assertTrue(wire.startsWith("HTTP/1.1 413 "), wire);
            assertTrue(wire.contains("上限"), wire);
        } finally {
            server.close();
            manager.close();
            workspace.close();
        }
    }
}
