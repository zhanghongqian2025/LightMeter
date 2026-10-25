package com.litemask.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecurityPolicy} 的测试。
 *
 * <p>本类全部是纯函数测试，不启服务——访问控制的每一种组合都能在这里穷举一遍。
 * 真正「发 HTTP」的验证在 {@link LocalServerApiTest}。
 */
class SecurityPolicyTest {

    private static final int PORT = 8931;
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";

    private final SecurityPolicy policy = new SecurityPolicy(TOKEN, PORT);

    @Nested
    @DisplayName("Host 头白名单")
    class Host {

        @ParameterizedTest(name = "允许 {0}")
        @ValueSource(strings = {
                "127.0.0.1:8931",
                "localhost:8931",
                "[::1]:8931",
                "LOCALHOST:8931",        // 主机名大小写不敏感
                "LocalHost:8931",
                "  127.0.0.1:8931  ",    // 头里的可选空白
                "127.0.0.1",            // 无端口：浏览器不会这么发（无端口即 80），但语义上仍指回环
                "[::1]"                 // IPv6 字面量无端口
        })
        void 本机地址放行(String host) {
            assertTrue(policy.isAllowedHost(host), "应当放行: " + host);
        }

        @ParameterizedTest(name = "拒绝 {0}")
        @ValueSource(strings = {
                "evil.com:8931",          // DNS rebinding 的本体
                "evil.com",
                "evil.com:80",
                "localhost.evil.com:8931",
                "127.0.0.1.evil.com:8931",
                "127.0.0.1:8930",         // 端口不符：同一个回环上的别的服务
                "127.0.0.1:99999",
                "127.0.0.1:8931.evil.com",// 端口位置塞域名
                "[::1]:8930",
                "[::1",                   // 括号不闭合
                "::1",                    // 裸 IPv6 未加方括号（RFC 7230 要求加）
                "::1:8931",
                "0.0.0.0:8931",           // 通配地址不是回环
                "192.168.1.5:8931",
                "127.0.0.2:8931",         // 整个 127/8 都是回环，但只放行 127.0.0.1 这一个
                "[fe80::1]:8931",
                "127.0.0.1:",
                "127.0.0.1:abc",
                "",
                "   "
        })
        void 非本机地址拒绝(String host) {
            assertFalse(policy.isAllowedHost(host), "应当拒绝: " + host);
        }

        @Test
        void 缺省Host拒绝() {
            // HTTP/1.0 允许不带 Host。不带就意味着我们无从判断它冲着谁来的。
            assertFalse(policy.isAllowedHost(null));
        }

        @Test
        @DisplayName("127.0.0.0/8 只放行 127.0.0.1")
        void 回环网段不放行整个() {
            // 这条容易被当成 bug 报上来，所以钉死：整段 127/8 技术上都是回环，
            // 但我们只监听 127.0.0.1，放行整段等于放行没监听的地址。
            assertTrue(policy.isAllowedHost("127.0.0.1:" + PORT));
            assertFalse(policy.isAllowedHost("127.0.0.2:" + PORT));
            assertFalse(policy.isAllowedHost("127.1.2.3:" + PORT));
        }

        @Test
        @DisplayName("端口必须是本服务的端口——只校验主机名等于和回环上所有服务共享防线")
        void 端口必须相等() {
            SecurityPolicy self = new SecurityPolicy(TOKEN, 8931);
            assertTrue(self.isAllowedHost("127.0.0.1:8931"));
            assertFalse(self.isAllowedHost("127.0.0.1:8932"));
            assertFalse(self.isAllowedHost("127.0.0.1:80"));
        }
    }

    @Nested
    @DisplayName("Origin 同源")
    class Origin {

        @Test
        void 无Origin放行() {
            // 非浏览器客户端（Word 加载项、本机脚本）不带 Origin。
            // 带 Origin 的浏览器请求才是要拦的那种。
            assertTrue(policy.isAllowedOrigin(null));
            assertTrue(policy.isAllowedOrigin(""));
            assertTrue(policy.isAllowedOrigin("   "));
        }

        @ParameterizedTest(name = "允许 {0}")
        @ValueSource(strings = {
                "http://127.0.0.1:8931",
                "http://localhost:8931",
                "HTTP://LOCALHOST:8931",
                "  http://127.0.0.1:8931  "
        })
        void 同源放行(String origin) {
            assertTrue(policy.isAllowedOrigin(origin), "应当放行: " + origin);
        }

        @ParameterizedTest(name = "拒绝 {0}")
        @ValueSource(strings = {
                "https://evil.com",
                "http://evil.com",
                "http://evil.com:8931",
                "https://127.0.0.1:8931",   // https 是另一个源，端口一样也不行
                "http://127.0.0.1:8932",
                "http://127.0.0.1",
                "http://127.0.0.1.evil.com:8931",
                "http://localhost.evil.com:8931",
                "null",                       // 部分浏览器对 data:/file: 发这个字面量
                "http://[::1]:8931",           // 本机自己也不放行 IPv6 源，见下方说明
                "file://",
                "null:null",
                "http://127.0.0.1:08931"
        })
        void 跨源拒绝(String origin) {
            assertFalse(policy.isAllowedOrigin(origin), "应当拒绝: " + origin);
        }

        @Test
        @DisplayName("同源判定是精确相等，不是前缀匹配")
        void 不用前缀匹配() {
            // "http://127.0.0.1:8931.evil.com" 以合法源开头。
            // 用 startsWith 判同源的话，这里会被放行——那正好是最典型的钓鱼源构造。
            assertFalse(policy.isAllowedOrigin("http://127.0.0.1:8931.evil.com"));
            assertFalse(policy.isAllowedOrigin("http://127.0.0.1:89310"));
        }
    }

    @Nested
    @DisplayName("写操作放行条件")
    class Write {

        @Test
        void 三条全过才放行() {
            assertTrue(policy.isAllowedWrite("127.0.0.1:" + PORT, null, TOKEN));
            assertTrue(policy.isAllowedWrite("127.0.0.1:" + PORT,
                    "http://127.0.0.1:" + PORT, TOKEN));
        }

        @Test
        @DisplayName("Host 不对 → 拒（哪怕令牌正确）")
        void host不对() {
            assertFalse(policy.isAllowedWrite("evil.com:" + PORT, null, TOKEN));
        }

        @Test
        @DisplayName("Origin 跨源 → 拒（哪怕 Host 与令牌都对）")
        void origin不对() {
            assertFalse(policy.isAllowedWrite("127.0.0.1:" + PORT, "https://evil.com", TOKEN));
        }

        @Test
        @DisplayName("令牌不对 → 拒（哪怕 Host 与 Origin 都对）")
        void token不对() {
            assertFalse(policy.isAllowedWrite("127.0.0.1:" + PORT, null, null));
            assertFalse(policy.isAllowedWrite("127.0.0.1:" + PORT, null, ""));
            assertFalse(policy.isAllowedWrite("127.0.0.1:" + PORT, null, TOKEN + "x"));
            assertFalse(policy.isAllowedWrite("127.0.0.1:" + PORT, null, TOKEN.substring(1)));
            // 前缀相同：只比长度的话这里会过
            assertFalse(policy.isAllowedWrite("127.0.0.1:" + PORT, null, TOKEN.substring(0, 40)));
        }

        @Test
        @DisplayName("令牌比较不是前缀比较")
        void 令牌比较是全等() {
            String a = SecurityPolicy.newToken();
            String b = SecurityPolicy.newToken();
            assertNotEquals(a, b);
            assertFalse(policy.isAllowedWrite("127.0.0.1:" + PORT, null, a.substring(0, 47)));
        }
    }

    @Nested
    @DisplayName("令牌生成")
    class Token {

        @Test
        void 长度与字符集() {
            String t = SecurityPolicy.newToken();
            assertEquals(48, t.length(), "24 字节 → 48 位十六进制");
            assertTrue(t.matches("[0-9a-f]{48}"), "只应是十六进制: " + t);
        }

        @Test
        void 每次不同() {
            // 连开两次不能一样，否则上一次进程留下的页面还能用。
            String a = SecurityPolicy.newToken();
            String b = SecurityPolicy.newToken();
            assertNotEquals(a, b);
        }

        @Test
        void 可重复生成() {
            // 一次性随机源坏掉的极端情况会在这里表现为抛异常或全零，
            // 不该表现为「返回了相同值」。跑 200 次全等概率可忽略。
            for (int i = 0; i < 200; i++) {
                String t = SecurityPolicy.newToken();
                if (t.matches("0{48}")) {
                    throw new AssertionError("第 " + i + " 次生成了全零令牌");
                }
            }
        }
    }

    @Nested
    @DisplayName("启动自检")
    class Loopback {

        @Test
        void 回环放行() {
            assertTrue(SecurityPolicy.isLoopback(new InetSocketAddress("127.0.0.1", 8080)));
            assertTrue(SecurityPolicy.isLoopback(new InetSocketAddress("::1", 8080)));
            assertTrue(SecurityPolicy.isLoopback(new InetSocketAddress("localhost", 8080)));
        }

        @Test
        @DisplayName("通配与局域网地址不放行")
        void 非回环拒绝() {
            assertFalse(SecurityPolicy.isLoopback(new InetSocketAddress("0.0.0.0", 8080)),
                    "0.0.0.0 是全网可达，正是这条自检要拦的");
            assertFalse(SecurityPolicy.isLoopback(new InetSocketAddress("192.168.1.10", 8080)));
            assertFalse(SecurityPolicy.isLoopback(new InetSocketAddress("::", 8080)));
        }

        @Test
        void null与未解析地址拒绝() {
            assertFalse(SecurityPolicy.isLoopback(null));
            // 未解析的地址拿不到 InetAddress —— 不能「拿不到就当通过」
            assertFalse(SecurityPolicy.isLoopback(InetSocketAddress.createUnresolved("evil.com", 80)));
        }
    }

    @Test
    void 拒绝措辞不泄露哪一条没过() {
        // 三种失败原因必须共用一句话：让外部页面能通过报错区分
        // 「Host 不对」「令牌不对」，等于给了它一个逐条试探的信号。
        assertEquals("拒绝：不是本机页面发起的请求", SecurityPolicy.DENIED);
        assertThrows(NullPointerException.class, () -> new SecurityPolicy(null, 1),
                "令牌不能为 null");
    }

    @Test
    void 端口对外可见() {
        assertEquals(PORT, policy.port());
        assertEquals(TOKEN, policy.token());
    }
}
