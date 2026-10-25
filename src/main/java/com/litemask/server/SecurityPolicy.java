package com.litemask.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;

/**
 * 本地服务的访问控制。
 *
 * <p><b>为什么一个「只监听 127.0.0.1」的服务还需要这一层</b>：浏览器里的任意外部网页
 * 都能向 {@code http://127.0.0.1:PORT/...} 发请求。受同源策略限制，它<b>读不到</b>响应，
 * 但<b>请求本身会真的执行</b>。再叠加 DNS rebinding（让 evil.com 解析到 127.0.0.1），
 * 连「读不到」都可能被绕过成「以为自己在跟 evil.com 说话」。
 *
 * <p>本机 HTTP 服务最常被钻的两条缝：
 * <ol>
 *   <li><b>不校验 Host 头</b> —— 上面的 rebinding 直接成立，服务等于对外公开。
 *       浏览器发过来的 {@code Host} 一定是攻击者控制的域名，校验它等于要求「你真的是通过
 *       localhost 访问我的」。</li>
 *   <li><b>不校验写操作来源</b> —— 任何网页都能 POST 上来让用户本机处理文件、读回结果。</li>
 * </ol>
 *
 * <p>所以本类做四件事，全部是<b>纯函数</b>，能在不启服务的情况下把每一种组合都测一遍：
 * <ol>
 *   <li>{@link #isAllowedHost} —— Host 头必须是 {@code 127.0.0.1} / {@code localhost} / {@code ::1}
 *       且端口必须等于本服务端口；</li>
 *   <li>{@link #isAllowedOrigin} —— 带 Origin 的请求，Origin 必须同源；</li>
 *   <li>token —— 每次启动随机生成，只有本服务的页面能拿到；</li>
 *   <li><b>刻意不返回 {@code Access-Control-Allow-Origin}</b>：不配 CORS 是这里的安全默认值，
 *       跨源脚本连响应都读不到，token 自然也偷不走。Word/WPS 加载项走本机进程调接口，
 *       不受 CORS 约束。</li>
 * </ol>
 */
public final class SecurityPolicy {

    private final String token;
    private final int port;

    public SecurityPolicy(String token, int port) {
        this.token = Objects.requireNonNull(token, "token");
        this.port = port;
    }

    /** 生成一个够长的随机会话令牌。 */
    public static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public String token() {
        return token;
    }

    public int port() {
        return port;
    }

    /**
     * Host 头是否指向本服务。
     *
     * <p>接受的形态只有两种，<b>逐字符精确比对</b>：
     * <pre>
     *   &lt;回环主机名&gt;              127.0.0.1 / localhost / [::1]
     *   &lt;回环主机名&gt;:&lt;端口&gt;     端口必须等于本服务端口
     * </pre>
     *
     * <p>大小写不敏感（主机名规范如此），端口<b>按字符串比</b>而不是 parseInt——
     * {@code parseInt("08931")} 等于 {@code 8931}，带前导零的畸形值会被当成正确端口放行。
     * 畸形的一律拒：{@code 127.0.0.1:}（有冒号没端口）、{@code 127.0.0.1:abc}、
     * 未加方括号的 IPv6（{@code ::1}）。
     *
     * <p>不带端口是允许的，因为 {@code serve --port 80} 时它恰好就是对的；
     * 端口不符时 {@code 127.0.0.1} 与 {@code 127.0.0.1:8931} 是两回事，
     * 只校验主机名会让同一个回环上的所有服务共享这道防线。
     *
     * @param hostHeader 请求里的 Host 头，可为 null（HTTP/1.0 允许缺省）
     */
    public boolean isAllowedHost(String hostHeader) {
        if (hostHeader == null) {
            return false;
        }
        String value = hostHeader.trim();
        if (value.isEmpty()) {
            return false;
        }
        String host;
        String portText;                       // null 表示头里没带端口
        if (value.startsWith("[")) {           // IPv6 字面量
            int close = value.indexOf(']');
            if (close < 0) {
                return false;
            }
            host = value.substring(0, close + 1);
            String rest = value.substring(close + 1);
            if (rest.isEmpty()) {
                portText = null;
            } else if (rest.charAt(0) == ':' && rest.length() > 1) {
                portText = rest.substring(1);
            } else {
                return false;
            }
        } else {
            int firstColon = value.indexOf(':');
            int lastColon = value.lastIndexOf(':');
            if (firstColon != lastColon) {
                // 多个冒号却不带方括号：只可能是漏了方括号的 IPv6 字面量。
                // 显式拒掉，而不是让它落进「按最后一个冒号切端口」的分支——
                // 那样 "::1" 会被切成 host="::" port="1"，端口恰好是 1 时就放行了。
                // 那是「意外成立」而不是「设计成立」，两类都该拒绝。
                return false;
            }
            if (firstColon < 0) {
                host = value;
                portText = null;
            } else {
                host = value.substring(0, firstColon);
                portText = value.substring(firstColon + 1);
                if (portText.isEmpty()) {
                    return false;            // "127.0.0.1:"：有冒号却没有端口
                }
            }
        }
        String lower = host.toLowerCase(Locale.ROOT);
        // 没有裸 "::1"：IPv6 字面量按 RFC 7230 必须带方括号，
        // 上面那个多冒号分支已经把不带方括号的写法全部拒掉了。
        if (!lower.equals("127.0.0.1")
                && !lower.equals("localhost")
                && !lower.equals("[::1]")) {
            return false;
        }
        return portText == null || portText.equals(Integer.toString(port));
    }

    /**
     * Origin 是否同源。
     *
     * <p>返回 true 表示<b>没有 Origin 头</b>的请求也要放行——非浏览器客户端
     * （Word 加载项、本机脚本、带正确 token 的调用）不带 Origin。
     * 浏览器发起的跨源写请求一定会带 Origin，那才是要拦的情况。
     */
    public boolean isAllowedOrigin(String originHeader) {
        if (originHeader == null || originHeader.isBlank()) {
            return true;
        }
        String value = originHeader.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("http://127.0.0.1:" + port)
                || lower.equals("http://localhost:" + port);
    }

    /** 写操作是否被放行：Host 合法、Origin 同源、token 对得上。三条缺一不可。 */
    public boolean isAllowedWrite(String hostHeader, String originHeader, String presentedToken) {
        return isAllowedHost(hostHeader)
                && isAllowedOrigin(originHeader)
                && token.equals(presentedToken);
    }

    /**
     * 启动自检：确认真的只绑在回环上。
     *
     * <p>这条不是形式主义——把 0.0.0.0 换成 127.0.0.1 只需要改一个字符串，
     * 而后果是整个局域网可访问。本机工具犯这种错的概率远高于直觉。
     */
    public static boolean isLoopback(InetSocketAddress address) {
        if (address == null) {
            return false;
        }
        InetAddress host = address.getAddress();
        return host != null && host.isLoopbackAddress();
    }

    /** 拒绝时给调用方的统一措辞：对外一律只说 token 不对，不泄露"到底是哪一条没过"。 */
    public static final String DENIED = "拒绝：不是本机页面发起的请求";
}
