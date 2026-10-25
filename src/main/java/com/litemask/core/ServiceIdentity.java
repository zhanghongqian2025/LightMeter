package com.litemask.core;

import java.util.regex.Pattern;

/**
 * 「这个端口上答话的**是不是本工具的服务**」这件事的<b>唯一真源</b>。
 *
 * <p><b>为什么要有这个类（块 34）</b><br>
 * 原来 {@code autostart status} 判断「服务在不在」只看一件事：
 * <b>{@code /api/health} 回了 HTTP 200</b>。
 * 这是一条<b>恒真</b>的判据 —— 端口上被<b>任何</b>服务占着，
 * 只要它回 200，status 就说「在线」。
 * 而 status 恰恰是用户验收「重启后服务自动起来」的那条命令：
 * 它说「在线」而界面打不开，用户就永远查不出问题在哪。
 *
 * <p><b>所以「在线」必须多问两句</b>：
 * <ol>
 *   <li>答话的服务<b>自称</b>是本工具吗（{@link #NAME}）；</li>
 *   <li>它的<b>协议版本</b>与本工具期望的一致吗（{@link #PROTOCOL}）；</li>
 *   <li>它<b>当场</b>按本工具的协议回显了探活发过去的<b>挑战</b>吗
 *       （{@link #CHALLENGE_HEADER}）—— 前两条是「静态声明」，
 *       这一条是「当场握手」。</li>
 * </ol>
 *
 * <p><b>为什么放在 core（最低层）里</b><br>
 * 服务端（{@code server} 包）与探活（{@code cli} 包）都要用它，
 * 而 core 不依赖任何上层 → 放 core，两边都能引，且没有环。
 *
 * <p><b>为什么探活可以引它，却不许引服务端的实现</b><br>
 * 探活的全部意义是「<b>外部视角</b>」：自己探自己不算数（同进程里当然连得上）。
 * 所以探活<b>不 import 任何服务端实现</b>（那会让它变成在测自己）。
 * 但<b>常量不是实现</b> —— 身份名与协议版本是「两端必须各知道一份」的<b>线上协议</b>，
 * 写死两份必然会漂 → 集中到这一处，两边都从这里取。
 *
 * <p><b>这算不算密码学保证</b><br>
 * <b>不算</b>，如实说清楚：身份名与协议版本是<b>公开的</b>，
 * 任何进程都能照着写一份一模一样的应答来骗过探活。
 * 它真正挡住的是<b>现实里会发生的那个场景</b>：
 * 端口被一个不相干的服务（开发服务器、别的工具、残留进程）占着，
 * 而那个服务<b>不会</b>照着本工具的协议应答 —— 挑战回显这一条，
 * 让「碰巧占着端口」与「确实是本工具」分得开。
 * 要更强的保证需要共享密钥，那会把「手工 curl 一下就能查」这件事也毁掉，
 * 代价大于收益（见 §三十八）。
 */
public final class ServiceIdentity {

    private ServiceIdentity() {
    }

    /** 服务名。健康检查报出来，探活拿它核对。 */
    public static final String NAME = "litemask";

    /**
     * 健康检查的<b>协议版本</b>。
     *
     * <p><b>为什么要有版本号</b>：改了健康检查的字段形状（比如换了字段名、
     * 加了必填项）之后，<b>旧的服务进程</b>还在跑时，
     * 新版的探活会读到一份「不是自己要的形状」的应答。
     * 报「协议版本对不上」比报「不是本工具的服务」准确得多，
     * 因为两种情况要做的动作完全不同：前者要<b>重启服务</b>，
     * 后者要<b>把占端口的人赶走</b>。
     */
    public static final int PROTOCOL = 1;

    /** 探活把挑战放在这个请求头里；服务端原样回显到响应体的 {@link #FIELD_CHALLENGE}。 */
    public static final String CHALLENGE_HEADER = "X-Litemask-Challenge";

    /** 健康检查响应体里的字段名（服务名）。 */
    public static final String FIELD_SERVICE = "service";

    /** 健康检查响应体里的字段名（协议版本）。 */
    public static final String FIELD_PROTOCOL = "protocol";

    /** 健康检查响应体里的字段名（挑战回显）。 */
    public static final String FIELD_CHALLENGE = "challenge";

    /**
     * 挑战的合法形态：base64url（无填充），16~64 个字符。
     *
     * <p><b>为什么服务端只回显「这个形态」的挑战</b>：
     * 回显值要原样进 JSON 响应体 → 值里带引号/反斜杠/控制字符就要转义，
     * 转义写错就是一条**注入**。与其在服务端写一套转义，
     * 不如<b>只回显一个形态受限的值</b>：不合形态的挑战直接不认。
     * 探活自己发的挑战一定是 base64url → 永远在形态内。
     */
    private static final Pattern CHALLENGE_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    /**
     * 这个挑战值能不能原样回显。
     *
     * @param challenge 收到的挑战值（可能为 null）
     * @return 能不能原样回显
     */
    public static boolean isEchoableChallenge(String challenge) {
        return challenge != null && CHALLENGE_SHAPE.matcher(challenge).matches();
    }
}
