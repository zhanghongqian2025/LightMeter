package com.litemask.cli;

import com.litemask.core.ServiceIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * 探「服务到底在不在对外服务」——一个能独立单测的窄接口。
 *
 * <p><b>为什么要有这一层</b>：{@code autostart status} 原来只看
 * launchd 认不认这个服务（{@code launchctl print} 的退出码）。
 * 但<b>launchd 认得 ≠ 服务起得来</b>：端口被占时服务每次起来都立刻退出，
 * launchd 仍按 {@code KeepAlive} 把它反复拉起 ——
 * 于是 {@code status} 报「已装载」，而 {@code /api/health} 实际连不上。
 * <b>用户据此以为服务在跑，界面却打不开。</b>
 *
 * <p><b>实测证据（2026-10-21，macOS 27.2.0）</b>：
 * 占住端口后装自启，{@code launchctl print} 的 state 是
 * {@code spawn scheduled} / {@code active}，而 {@code /api/health} 是 timeout；
 * {@code autostart status} 当时报的是「运行状态 已装载」、退出码 0。
 *
 * <p><b>为什么探的是 {@code /api/health}</b>：它是服务端唯一一个
 * <b>不需要令牌</b>的只读端点（{@code ApiHandlers} 里路由的第三个参数是
 * {@code false} = 不鉴权），而且它报的是<b>服务真实在做的事</b>
 * （规则数、任务数、绑定地址、安全姿态），不是一句「我在」。
 *
 * <p><b>为什么不复用服务端代码</b>：这里要的是「<b>外部</b>视角」的探活——
 * 自己探自己不算数（同进程内当然连得上）。
 * 所以实现只用 JDK 的 {@link HttpURLConnection}，<b>不 import 任何服务端实现</b>。
 * 它只 import {@link ServiceIdentity} —— 那是<b>纯常量</b>（服务名与协议版本），
 * 不是实现：常量必须<b>只有一处定义</b>，写死两份必然漂。
 *
 * <p><b>块 34 补上的一环：「在线」不能只看 HTTP 200</b><br>
 * 原来判据是「{@code /api/health} 回了 200 → 在线」。
 * 那是<b>恒真</b>：端口上被<b>任何</b>服务占着、只要它回 200，
 * status 就说「在线」——而用户据此判断「重启后服务自动起来」有没有成。
 * → 现在「在线」要同时满足三件事：
 * <ol>
 *   <li>答话的服务<b>自称</b>是本工具（服务名）；</li>
 *   <li>它的<b>协议版本</b>与本工具期望的一致；</li>
 *   <li>它<b>当场回显</b>了探活发过去的<b>挑战</b>。</li>
 * </ol>
 * 前两条是静态声明，第三条是<b>当场握手</b>：不相干的服务不会照着这个协议应答。
 * 这挡不住「刻意模仿」的服务（如实说：那不是密码学保证，见 §三十八），
 * 但它挡住了现实里真会发生的那个场景：<b>端口被不相干的服务占着</b>。
 */
public interface ServiceProbe {

    /**
     * 探一次服务。
     *
     * @param port 服务端口
     * @return 探测结果
     */
    Result probe(int port);

    /**
     * 一次探测的<b>结论档位</b>。
     *
     * <p><b>为什么从「在线/不可用」两档拆成五档</b>：
     * 两档会把三件完全不同的事混成「不可用」——
     * 「端口上根本没有它」（要赶走占端口的人）、
     * 「它在但卡住了」（要重启它）、
     * 「那不是它」（两件事都不是）。
     * 而这三种情况要用户做的事<b>完全不同</b>。
     */
    enum State {
        /** 答话的是本工具的服务，且当场按协议应答了。 */
        ONLINE("在线"),
        /** 端口上有东西应答，但它<b>不是</b>本工具的服务（或版本对不上）。 */
        NOT_OURS("不可用（端口上不是本工具的服务）"),
        /** 服务在应答，但<b>没按时答</b>（超时 / 返回了非 200）。 */
        UNHEALTHY("不可用（在但不健康）"),
        /** 端口上<b>没有</b>服务在听（连接被拒）。 */
        DOWN("不可用（没在监听）"),
        /** 探活自己没得出结论（端口不知道 / 网络错误）。 */
        UNKNOWN("不可用（探活没得出结论）");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /** 给用户看的一行字。 */
        public String label() {
            return label;
        }
    }

    /**
     * 一次探测的结果。
     *
     * @param state  结论档位
     * @param detail 给用户看的具体说明（在线时是判断依据；
     *                不在线时是<b>原因</b>，例如「连接被拒绝」「超时」）
     */
    record Result(State state, String detail) {

        /** 是不是「服务确实在对外服务」。只有 {@link State#ONLINE} 才是。 */
        public boolean online() {
            return state == State.ONLINE;
        }
    }

    /**
     * 真实现：用 HTTP 打 {@code /api/health}。
     *
     * <p><b>超时为什么要设</b>：服务「半死不活」时（进程在、端口占着、
     * 但不响应 HTTP）连接会<b>一直挂着</b>。不设超时，
     * {@code status} 就可能永远卡住 —— 一个用来回答「服务在不在」的命令，
     * 自己卡住是最坏的失败形态。
     */
    final class Http implements ServiceProbe {

        /** 探活的超时（毫秒）。够短（不让人干等），够长（冷启动的服务也答得上）。 */
        public static final int TIMEOUT_MS = 1500;

        /** 挑战用的随机源。**静态**持有：每次探活都新建 {@code SecureRandom} 会拖慢它。 */
        private static final SecureRandom RANDOM = new SecureRandom();

        @Override
        public Result probe(int port) {
            if (port <= 0 || port > 65535) {
                // 【文案必须**中性** —— 2026-10-21 块 24】
                //   这一句原来写死「plist 里没读出 --port」，那是 macOS 侧的说法；
                //   Windows 侧的唯一真源是 **Run 键里的那条命令行**。
                //   → 平台专属的名词写在**调用方**那一句里，
                //     这一句只说「没读出 --port」，两边都读得通。
                return new Result(State.UNKNOWN, "不知道服务在哪个端口（没读出 --port）");
            }
            String challenge = newChallenge();
            String url = "http://127.0.0.1:" + port + "/api/health";
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestMethod("GET");
                // 明确不要任何凭据：探活用的是公开端点，
                // 带令牌反而会让「服务在不在」这件事依赖令牌是否有效。
                conn.setRequestProperty("Accept", "application/json");
                // 【块 34】挑战：让服务端**当场**按本工具的协议应答。
                //   没有它，「在线」就退化成「HTTP 200」——那是恒真。
                conn.setRequestProperty(ServiceIdentity.CHALLENGE_HEADER, challenge);
                int code = conn.getResponseCode();
                if (code != 200) {
                    return new Result(State.UNHEALTHY,
                            "健康检查返回 HTTP " + code + "（服务在，但答不上来）");
                }
                String body = readBody(conn);
                // ---- 三道核对，缺一不可 ----
                String name = fieldString(body, ServiceIdentity.FIELD_SERVICE);
                if (!ServiceIdentity.NAME.equals(name)) {
                    return new Result(State.NOT_OURS,
                            "端口 " + port + " 上答的不是本工具的服务"
                                    + "（它没报出服务名「" + ServiceIdentity.NAME + "」）");
                }
                int proto = fieldInt(body, ServiceIdentity.FIELD_PROTOCOL);
                if (proto != ServiceIdentity.PROTOCOL) {
                    return new Result(State.NOT_OURS,
                            "是本工具的服务，但协议版本是 " + proto
                                    + "（本工具期望 " + ServiceIdentity.PROTOCOL + "）"
                                    + "—— 多半是服务没重启");
                }
                String echoed = fieldString(body, ServiceIdentity.FIELD_CHALLENGE);
                if (!challenge.equals(echoed)) {
                    return new Result(State.NOT_OURS,
                            "端口 " + port + " 上那个服务没有按本工具的协议应答（挑战没被回显）");
                }
                return new Result(State.ONLINE,
                        "健康检查返回 200，服务名、协议版本、挑战回显三项都对得上");
            } catch (java.net.ConnectException e) {
                // 最常见的一种：端口上根本没有服务在听。
                return new Result(State.DOWN, "连不上（端口 " + port + " 上没有服务在听）");
            } catch (java.net.SocketTimeoutException e) {
                // 【为什么超时一定意味着「答得太慢」而不是「连不上」】
                //   探的是 127.0.0.1：连接要么立刻成功、要么立刻被拒（RST），
                //   **不会**卡在连接阶段（没有中间设备会给你丢弃 SYN）。
                //   → 所以这里的超时是「对方接了连接但没在时限内答完」。
                return new Result(State.UNHEALTHY,
                        "连上了但没在 " + TIMEOUT_MS + " 毫秒内应答（服务在跑但卡住了）");
            } catch (IOException e) {
                String m = e.getMessage();
                return new Result(State.UNKNOWN,
                        "探活失败：" + (m == null ? e.getClass().getSimpleName() : m));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        /**
         * 造一个挑战值。
         *
         * <p><b>形态必须是 {@link ServiceIdentity#isEchoableChallenge} 认得的</b>：
         * 服务端只回显那种形态的挑战（回显要进 JSON，转义写错就是注入）。
         * base64url 无填充天然满足。
         */
        private static String newChallenge() {
            byte[] buf = new byte[18];
            RANDOM.nextBytes(buf);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        }

        /** 读响应体（带上限）。读不到就当空串 —— 后面核对时自然判成「不是本工具」。 */
        private static String readBody(HttpURLConnection conn) {
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = in.readNBytes(8192);
                return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        /**
         * 从 JSON 里取一个<b>字符串</b>字段的值。
         *
         * <p><b>为什么自己扫而不引 JSON 库</b>：探活是「外部视角」的客户端，
         * 不该依赖本工具的解析器；而且这个字段值形态受限（服务名、base64url 挑战），
         * 一个小扫描就够。
         *
         * <p><b>为什么容忍空格</b>：不能把判据绑死在服务端当前的输出格式上
         * （服务端哪天加个空格，探活就会把好服务判成「不是本工具」）。
         */
        static String fieldString(String body, String field) {
            int i = indexOfField(body, field);
            if (i < 0) {
                return null;
            }
            int p = i;
            p = skipWs(body, p);
            if (p >= body.length() || body.charAt(p) != ':') {
                return null;
            }
            p = skipWs(body, p + 1);
            if (p >= body.length() || body.charAt(p) != '"') {
                return null;
            }
            p++;
            StringBuilder sb = new StringBuilder();
            while (p < body.length()) {
                char c = body.charAt(p);
                if (c == '\\' && p + 1 < body.length()) {
                    // 转义只处理够用的几种；其余原样带过去。
                    char n = body.charAt(p + 1);
                    sb.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        default -> n;
                    });
                    p += 2;
                    continue;
                }
                if (c == '"') {
                    return sb.toString();
                }
                sb.append(c);
                p++;
            }
            return null;
        }

        /** 从 JSON 里取一个<b>整数</b>字段的值；取不到返回 -1。 */
        static int fieldInt(String body, String field) {
            int i = indexOfField(body, field);
            if (i < 0) {
                return -1;
            }
            int p = skipWs(body, i);
            if (p >= body.length() || body.charAt(p) != ':') {
                return -1;
            }
            p = skipWs(body, p + 1);
            int start = p;
            if (p < body.length() && body.charAt(p) == '-') {
                p++;
            }
            while (p < body.length() && Character.isDigit(body.charAt(p))) {
                p++;
            }
            if (p == start) {
                return -1;
            }
            try {
                return Integer.parseInt(body.substring(start, p));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /**
         * 找到 {@code "field"} 这个键名，返回它<b>后面</b>那个位置；找不到返回 -1。
         *
         * <p><b>返回的是「键名之后」而不是「键名的开头」</b>：
         * 调用方紧接着要读的是 {@code :} 与值，不是键名本身。
         * （写错了这一点 → 解析永远失败 → 判据恒为「不是本工具」，
         * 而真服务也会被误判 —— 这就是判据恒真的另一副面孔。）
         */
        private static int indexOfField(String body, String field) {
            if (body == null) {
                return -1;
            }
            String needle = "\"" + field + "\"";
            int at = body.indexOf(needle);
            if (at < 0) {
                return -1;
            }
            return at + needle.length();
        }

        /** 跳过空白。 */
        private static int skipWs(String body, int from) {
            int p = from;
            while (p < body.length() && Character.isWhitespace(body.charAt(p))) {
                p++;
            }
            return p;
        }
    }

    /**
     * 读健康检查响应体的前若干字符（给用户看「它到底答了什么」）。
     *
     * <p><b>为什么要有这个</b>：{@code /api/health} 的 JSON 里有
     * {@code port}、{@code ruleCount}、{@code securityPosture} ——
     * 用户核对「这个服务是不是我以为的那个」时，需要看到这些。
     *
     * @param port 端口
     * @return 响应体摘要；读不到时返回空串
     */
    static String healthSummary(int port) {
        if (port <= 0 || port > 65535) {
            return "";
        }
        String url = "http://127.0.0.1:" + port + "/api/health";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(Http.TIMEOUT_MS);
            conn.setReadTimeout(Http.TIMEOUT_MS);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                return "";
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = in.readNBytes(512);
                return new String(buf, java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ").trim();
            }
        } catch (IOException e) {
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 便捷方法：把结果里的说明原样取出来。 */
    static String detail(Result r) {
        return r == null ? "" : (r.detail() == null ? "" : r.detail());
    }

    /** 便捷方法：把端口号写成人读的形态（非法端口就说「未知」）。 */
    static String portText(int port) {
        return port > 0 && port <= 65535 ? String.valueOf(port) : "未知";
    }

    /** 便捷方法：判断一个字符串里是否含某个关键词（忽略大小写）。 */
    static boolean mentions(String text, String keyword) {
        if (text == null || keyword == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }
}
