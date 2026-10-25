package com.litemask.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LocalServer#url()} 的地址性质：<b>恒为回环</b>。
 *
 * <h2>为什么这条性质要有专门的守卫</h2>
 * <p>块 36 之前，「本工具只连本机」这件事**没有任何判据守着**：
 * <ul>
 *   <li>{@code LocalServer} 的绑定地址是常量 {@code "127.0.0.1"}，写在源码里；</li>
 *   <li>{@code BrowserLauncher.isLaunchable()} 只放行 http/https，
 *       <b>不检查是不是回环</b> —— 也就是说它<b>能</b>把一个外网地址交给浏览器；</li>
 *   <li>唯一挡住它的是「调用方恰好传了 {@code server.url()}」这件<b>事实</b>，
 *       而那件事实当时**没有任何东西在看</b>。</li>
 * </ul>
 * <p>所以这条测试的价值不在于「现在是对的」（现在当然是对的），
 * 而在于：一旦有人把绑定地址改成 {@code 0.0.0.0} 或主机名，
 * 或者让某个命令把外网地址交给浏览器，<b>这里立刻红</b>。
 *
 * <h2>为什么不改成在 {@code isLaunchable} 里查回环</h2>
 * <p>那会把语义改窄：{@code BrowserLauncherTest.onlyHttpIsLaunchable} 明确断言
 * {@code isLaunchable("https://example.com/a")} 为 true，
 * 而「用系统命令打开一个外部地址」本身是合理能力（不是漏洞）。
 * <p>真正的性质是「<b>本工具自己</b>交给浏览器的地址恒为回环」——
 * 那是<b>调用方</b>的责任，不是 <b>地址校验</b>的责任。
 * → 所以判据落在「本工具自己产出的地址」上，不去改协议白名单。
 *
 * @see com.litemask.cli.BrowserLauncherTest
 */
class LocalServerUrlTest {

    private static Workspace workspace;
    private static LocalServer server;
    private static int port;

    @BeforeAll
    static void 起服务(@TempDir Path tmp) throws Exception {
        workspace = Workspace.open(tmp);
        server = LocalServer.create(workspace, 0);
        server.start();
        port = server.port();
    }

    @AfterAll
    static void 关服务() {
        if (server != null) {
            server.close();
        }
        if (workspace != null) {
            workspace.close();
        }
    }

    @Test
    @DisplayName("url() 逐字等于 http://127.0.0.1:<端口>/ —— 不是 0.0.0.0，不是主机名")
    void urlIsExactlyLoopback() {
        String url = server.url();
        assertEquals("http://127.0.0.1:" + port + "/", url,
                "本工具交给浏览器的地址必须逐字是本机回环；实际：" + url);
    }

    @Test
    @DisplayName("url() 里的主机确实被解析成回环地址")
    void urlHostIsLoopback() throws Exception {
        String url = server.url();
        String host = hostOf(url);
        InetAddress addr = InetAddress.getByName(host);
        assertTrue(addr.isLoopbackAddress(),
                "url() 里的主机必须解析成回环地址，实际：" + host + " → " + addr);
        assertFalse(addr.isAnyLocalAddress(),
                "url() 里的主机不能是 0.0.0.0（那等于对本机所有网卡开放），实际：" + host);
    }

    @Test
    @DisplayName("url() 里的端口就是服务实际监听的端口")
    void urlPortMatchesListeningPort() {
        String url = server.url();
        int portInUrl = Integer.parseInt(
                url.substring(url.indexOf(':', url.indexOf("://") + 3) + 1, url.lastIndexOf('/')));
        assertEquals(port, portInUrl, "url() 里的端口与服务实际端口必须一致");
    }

    @Test
    @DisplayName("url() 里不含主机名、不含 0.0.0.0")
    void urlHasNoMachineName() {
        String url = server.url();
        assertFalse(url.contains("0.0.0.0"), "url() 里出现 0.0.0.0：" + url);
        String hostName = System.getenv("HOSTNAME");
        if (hostName != null && !hostName.isBlank()) {
            assertFalse(url.contains(hostName.trim()),
                    "url() 里出现了本机主机名（那会把服务暴露到网上）：" + url);
        }
    }

    @Test
    @DisplayName("起两个服务，地址里的主机都一样（主机是常量，不是每个实例各算各的）")
    void twoServersShareTheSameHost() throws Exception {
        Path tmp2 = java.nio.file.Files.createTempDirectory("litemask-url2-");
        Workspace ws2 = Workspace.open(tmp2);
        LocalServer s2 = LocalServer.create(ws2, 0);
        try {
            s2.start();
            String host2 = hostOf(s2.url());
            assertEquals(hostOf(server.url()), host2,
                    "两个实例的主机必须一致 —— 主机是写死的常量，不是各算各的");
            assertNotNull(host2, "url() 必须含主机");
            assertEquals("127.0.0.1", host2, "两个实例的主机都必须是回环");
        } finally {
            s2.close();
            ws2.close();
            deleteTree(tmp2);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!java.nio.file.Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删不掉就算了 —— 测试目录不该让测试失败
                }
            });
        }
    }

    private static String hostOf(String url) {
        int start = url.indexOf("://") + 3;
        int end = url.indexOf(':', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }
}
