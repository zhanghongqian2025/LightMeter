package com.litemask.server;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRestoreCancelBehaviorTest {

    @Test
    @Timeout(20)
    @DisplayName("独立还原取消会中止原上传并隔离迟到成功，结算后才能重试")
    void cancelAbortsOwnedUploadAndRejectsLateSuccess() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "本机没有 node，跳过浏览器脚本行为回归");
        Path harness = resourcePath("/web/restore-cancel-race-test.js");
        Path app = resourcePath("/web/app.js");
        Process process = new ProcessBuilder("node", harness.toString(), app.toString())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, "Node 行为回归超时：\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("restore-cancel-race-ok"), output);
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static Path resourcePath(String name) throws URISyntaxException {
        var url = WebRestoreCancelBehaviorTest.class.getResource(name);
        if (url == null) {
            throw new AssertionError("测试资源不存在: " + name);
        }
        return Path.of(url.toURI());
    }
}
