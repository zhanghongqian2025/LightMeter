import com.litemask.cli.ServiceProbe;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 探活身份判据：端到端驱动。<b>跑的是打包后的 jar 里的产品探活</b>。
 *
 * <p><b>这一条验的是什么</b>：
 * 「{@code autostart status} 说的『服务在线』，到底是不是指<b>本工具的服务</b>在线」。
 * 块 24 / 27 / 28 登记过一个老缺口：{@code /api/health} 不需要令牌，
 * 于是原来的判据是「这个端口回 200 就算在线」——
 * <b>端口被不相干的服务占着时，它照样说在线</b>。
 * 而 status 恰恰是用户验收「重启后服务自动起来」的那条命令。
 *
 * <p><b>为什么要做成「真服务 vs 假服务」两场对照</b>：
 * 只验「真服务 → 在线」证明不了任何事 ——
 * 恒真的判据（只看 200）在这一场里也是绿的。
 * <b>必须有一场「端口上是个不相干的服务 → 不许说在线」</b>，
 * 恒真的判据在这一场里会红。这一场才是这一条的**判别力所在**。
 *
 * <p><b>三种假服务形态，各打一处</b>：
 * <ol>
 *   <li><b>plain</b>：回 200 + {@code {"ok":true}}（什么都不声明）——
 *       这是最常见的现实场景（别的工具占着端口）。</li>
 *   <li><b>declared</b>：回 200 + 声明了服务名与协议版本，但<b>不回显挑战</b> ——
 *       它专打「只看静态声明就放行」那种退化。</li>
 *   <li><b>wrong-protocol</b>：回 200 + 声明了服务名，但协议版本不对 ——
 *       它专打「不查版本」那种退化（多半是旧版服务还在跑）。</li>
 * </ol>
 *
 * <p><b>判据独立于被测实现</b>（e2e 的头号纪律）：
 * <ul>
 *   <li><b>期望的档位不在本驱动里</b> —— 断言写在 shell 侧，
 *       那边拿字面量（{@code ONLINE} / {@code NOT_OURS}）去比。
 *       本驱动只负责<b>跑探活</b>与<b>打印结论</b>。</li>
 *   <li>假服务是自己写的原始 HTTP 响应，<b>不经过产品任何代码</b>。</li>
 *   <li>本驱动<b>不复用</b>产品的枚举去做任何比较 ——
 *       复用只能证明「产品和自己一致」，证明不了「产品对」。</li>
 * </ul>
 *
 * <p><b>用法</b>：
 * <pre>
 *   java E2eServiceIdentity probe &lt;端口&gt;          # 探一次，打印档位
 *   java E2eServiceIdentity fake-plain &lt;端口&gt;       # 起一个不相干的服务
 *   java E2eServiceIdentity fake-declared &lt;端口&gt;
 *   java E2eServiceIdentity fake-wrong-protocol &lt;端口&gt;
 * </pre>
 *
 * <p><b>输出格式</b>（shell 侧要读，所以格式是契约）：
 * <pre>
 *   STATE=&lt;档位名&gt;
 *   ONLINE=&lt;true|false&gt;
 *   DETAIL=&lt;说明&gt;
 * </pre>
 * 档位名用<b>产品自己的枚举名</b>（{@code ONLINE} / {@code NOT_OURS} /
 * {@code UNHEALTHY} / {@code DOWN} / {@code UNKNOWN}），
 * 因为「档位叫什么」是产品对外的**输出契约**，shell 侧要按名字断言。
 *
 * <p><b>退出码</b>：0 = 跑完（不论探活结论是什么 —— 结论由调用方判）；
 * 1 = 驱动自己出错（起不了假服务之类）。
 */
public final class E2eServiceIdentity {

    /** 假服务声明的服务名。 */
    private static final String DECLARED_SERVICE = "litemask";

    /** 假服务声明的**别的**服务名（专打「服务名核对」那一道）。 */
    private static final String DECLARED_OTHER_SERVICE = "some-other-tool";

    /** 假服务「正确」声明的协议版本。 */
    private static final int DECLARED_RIGHT_PROTOCOL = 1;

    /** 假服务声明的协议版本（与产品当前值<b>故意不同</b>，专打「协议版本核对」）。 */
    private static final int DECLARED_WRONG_PROTOCOL = 999;

    /** 挑战请求头名。手写，与产品常量分开。 */
    private static final String CHALLENGE_HEADER = "X-Litemask-Challenge";

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("FAIL 用法: java E2eServiceIdentity "
                    + "<probe|fake-plain|fake-wrong-name|fake-wrong-protocol|fake-declared> <端口>");
            System.exit(1);
        }
        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        try {
            switch (mode) {
                case "probe" -> probe(port);
                case "fake-plain" -> fake(port, Kind.PLAIN);
                case "fake-wrong-name" -> fake(port, Kind.WRONG_NAME);
                case "fake-wrong-protocol" -> fake(port, Kind.WRONG_PROTOCOL);
                case "fake-declared" -> fake(port, Kind.DECLARED);
                default -> {
                    System.out.println("FAIL 未知模式：" + mode);
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.out.println("FAIL 驱动执行出错：" + e);
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /** 探一次并打印结论。 */
    private static void probe(int port) {
        // 用产品自己的探活实现（这正是被测对象）。
        ServiceProbe.Result r = new ServiceProbe.Http().probe(port);
        System.out.println("STATE=" + r.state().name());
        System.out.println("ONLINE=" + r.online());
        System.out.println("DETAIL=" + r.detail());
    }

    /** 假服务的四种形态。 */
    private enum Kind {
        /** 什么都不声明（现实里最常见的不相干服务）。 */
        PLAIN,
        /** 声明了<b>别的</b>服务名，协议版本与挑战都对 —— 专打「服务名核对」那一道。 */
        WRONG_NAME,
        /** 服务名对，协议版本不对，挑战也对 —— 专打「协议版本核对」那一道。 */
        WRONG_PROTOCOL,
        /** 服务名对、协议版本对，但<b>不回显挑战</b> —— 专打「挑战回显核对」那一道。 */
        DECLARED
    }

    /**
     * 起一个假服务，一直跑到进程被杀。
     *
     * <p><b>自己写原始 HTTP 响应</b>（不经过产品任何代码）：
     * 这一场要验的是「产品探活会不会被这种应答骗过」，
     * 所以应答必须是<b>这个驱动自己写的</b>，产品的代码一点都碰不到。
     *
     * <p><b>【关键】除 PLAIN 外，三种形态都要老实回显挑战</b>：
     * 否则「协议版本不对」那一场会被**挑战核对**挡住而不是被**版本核对**挡住，
     * 于是「拆掉版本核对」这个变异**照样红**（红在另一场）——
     * 那一道核对就<b>永远没被单独验过</b>。
     * → 每个形态必须**只**被它要打的那一道核对挡住。
     */
    private static void fake(int port, Kind kind) throws IOException {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/api/health", ex -> {
            String challenge = ex.getRequestHeaders().getFirst(CHALLENGE_HEADER);
            byte[] body;
            switch (kind) {
                case PLAIN -> body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                case WRONG_NAME -> body = ("{\"service\":\"" + DECLARED_OTHER_SERVICE
                        + "\",\"protocol\":" + DECLARED_RIGHT_PROTOCOL
                        + ",\"challenge\":\"" + safe(challenge) + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                case WRONG_PROTOCOL -> body = ("{\"service\":\"" + DECLARED_SERVICE
                        + "\",\"protocol\":" + DECLARED_WRONG_PROTOCOL
                        + ",\"challenge\":\"" + safe(challenge) + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                case DECLARED -> body = ("{\"service\":\"" + DECLARED_SERVICE
                        + "\",\"protocol\":" + DECLARED_RIGHT_PROTOCOL + "}")
                        .getBytes(StandardCharsets.UTF_8);
                default -> body = "{}".getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        System.out.println("FAKE-READY kind=" + kind.name() + " port=" + port);
        System.out.flush();
        // 一直待着，直到进程被杀。
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 挑战可能是 null（有人没带挑战就问）；写成 JSON 安全的空串。 */
    private static String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private E2eServiceIdentity() {
    }
}
