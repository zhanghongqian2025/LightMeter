package com.litemask.cli;

import java.nio.file.Path;

/**
 * 生成 macOS LaunchAgent 的 plist 内容（纯函数：不碰磁盘、不调 launchd）。
 *
 * <p><b>为什么单独一个类</b>：plist 的内容就是「开机自启」这个功能的**全部约定** ——
 * 哪个可执行文件、带哪些参数、崩了要不要重启、日志写哪。
 * 它必须能<b>脱离 launchd 单测</b>：这里只生成字符串，
 * 于是「plist 写对了吗」可以逐字段断言，不必真往 launchd 里装一次。
 *
 * <p><b>为什么 {@code KeepAlive} 用无条件 {@code true}</b>：
 * 想要的效果是「<b>服务崩了要自己好起来</b>」——
 * 自启服务是没人看着的，崩了没人拉它，工具就静默地不工作了。
 *
 * <p><b>这里踩过一个坑，结论是实测出来的（2026-10-21，macOS 27.2.0）</b>：
 * 手册（{@code launchd.plist}）写「{@code Crashed=true} 表示因信号退出时才重启」，
 * 但受控矩阵实测<b>与手册相反</b>：
 * <pre>
 *   KeepAlive 形态              SIGKILL   exit 0   exit 1
 *   {Crashed: true}             不重启    不重启   不重启   ← 与手册相反
 *   {Crashed: false}            重启      重启     重启
 *   {SuccessfulExit: true}      不重启    重启     不重启   ← 与手册一致
 *   {SuccessfulExit: false}     重启      不重启   重启     ← 与手册一致
 *   true                        重启      重启     重启
 *   false                       不重启    不重启   不重启
 * </pre>
 * → 要「崩了自愈」，唯一稳妥的写法是<b>无条件 true</b>。
 * （原来的写法 {@code {Crashed: true}} 看着更「精确」，
 *  实测却是<b>从不重启</b>：崩溃之后服务就永久下线了。）
 *
 * <p><b>那端口被占呢</b>：无条件 true 意味着服务因端口被占而正常退出时，
 * launchd 也会把它重新拉起。实测这<b>正是我们想要的</b>：
 * 端口一腾出来，服务自动就位，不用用户再装一次。
 * 代价是重试有频率上限——{@code ThrottleInterval} 定为 10 秒，
 * 所以长期占用端口时是「每 10 秒试一次」，而不是疯狂重试。
 * 这一点写进了阶段文档的已知限制。
 *
 * <p><b>为什么端口要固定</b>：服务默认端口是 0（让系统随机分配），
 * 那是「用户从终端启动、自己看着屏幕」的场景。
 * 开机自启时没人看着屏幕，随机端口意味着<b>没人知道该访问哪个地址</b>。
 * 所以自启走固定端口，且默认值与端口占用报错文案里建议的那个一致。
 */
public final class LaunchAgentPlist {

    /** 默认 label（launchd 里的服务标识）。 */
    public static final String DEFAULT_LABEL = "com.litemask.lightmeter";

    /** .app 里的可执行文件（相对 .app 根目录）。 */
    public static final String EXECUTABLE_RELATIVE = "Contents/MacOS/LightMeter";

    /** 默认端口。与「端口已被占用」报错文案里建议的 {@code serve --port 8090} 一致。 */
    public static final int DEFAULT_PORT = 8090;

    /**
     * LaunchAgents 目录（相对用户主目录）。
     *
     * <p><b>为什么把它放在这里</b>：plist 的<b>路径</b>和它的<b>内容</b>是同一件事的两面，
     * 而路径会被三处用到——安装时写入、卸载时删除、卸载清单里登记。
     * 三处各写一遍，早晚有一处改了、另一处没改，
     * 症状是「装上了但卸载删不掉」（或者反过来：卸载删了一个从没装过的文件）。
     */
    public static final String LAUNCH_AGENTS_SUBPATH = "Library/LaunchAgents";

    /**
     * 某个 label 对应的 plist 路径。
     *
     * @param userHome 用户主目录
     * @param label    launchd 服务标识
     * @return plist 的绝对路径
     */
    public static Path plistPath(Path userHome, String label) {
        return userHome.resolve(LAUNCH_AGENTS_SUBPATH).resolve(label + ".plist");
    }

    private LaunchAgentPlist() {
    }

    /**
     * 一份自启配置。
     *
     * @param label      launchd 的服务标识
     * @param executable .app 里可执行文件的绝对路径
     * @param port       固定端口
     * @param outLog     标准输出日志
     * @param errLog     标准错误日志
     * @param workDir    工作目录
     */
    public record Spec(String label, Path executable, int port, Path outLog, Path errLog, Path workDir) {
    }

    /**
     * 渲染 plist 文本。
     *
     * @param spec 配置
     * @return plist 的完整 XML 文本
     */
    public static String render(Spec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        sb.append("<plist version=\"1.0\">\n");
        sb.append("<dict>\n");
        sb.append("  <key>Label</key>\n  <string>").append(esc(spec.label())).append("</string>\n");
        sb.append("  <key>ProgramArguments</key>\n  <array>\n");
        sb.append("    <string>").append(esc(spec.executable().toString())).append("</string>\n");
        sb.append("    <string>serve</string>\n");
        // 自启时没人看着屏幕，开浏览器只会凭空弹窗。
        sb.append("    <string>--no-browser</string>\n");
        sb.append("    <string>--port</string>\n");
        sb.append("    <string>").append(spec.port()).append("</string>\n");
        sb.append("  </array>\n");
        sb.append("  <key>RunAtLoad</key>\n  <true/>\n");
        // KeepAlive 用无条件 true：要的是「崩了要自愈」。
        // 实测本机 launchd 的 {Crashed: true} 恰恰**从不重启**（与手册相反），见类注释。
        sb.append("  <key>KeepAlive</key>\n  <true/>\n");
        // 端口被占导致服务正常退出时 launchd 也会重启它（这是想要的：端口一腾出来就自动就位），
        // ThrottleInterval 限制重试频率 —— 没有它就是「每几秒启一次 JVM」的疯狂重试。
        sb.append("  <key>ThrottleInterval</key>\n  <integer>10</integer>\n");
        sb.append("  <key>ProcessType</key>\n  <string>Interactive</string>\n");
        sb.append("  <key>ExitTimeOut</key>\n  <integer>20</integer>\n");
        sb.append("  <key>StandardInPath</key>\n  <string>/dev/null</string>\n");
        sb.append("  <key>StandardOutPath</key>\n  <string>").append(esc(spec.outLog().toString())).append("</string>\n");
        sb.append("  <key>StandardErrorPath</key>\n  <string>").append(esc(spec.errLog().toString())).append("</string>\n");
        sb.append("  <key>WorkingDirectory</key>\n  <string>").append(esc(spec.workDir().toString())).append("</string>\n");
        sb.append("</dict>\n");
        sb.append("</plist>\n");
        return sb.toString();
    }

    /**
     * XML 文本转义。路径里出现 {@code &}、{@code <}、{@code >} 时不转义就是**坏 plist**，
     * 而坏 plist 的表现是「装上了但 launchd 什么都不说也不做」——很难查。
     */
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 从<b>已写出去的 plist</b> 里读回它配的端口。
     *
     * <p><b>为什么端口要从 plist 读，而不是让用户再传一次 {@code --port}</b>：
     * 端口是「这个自启服务实际监听的端口」这个事实的<b>唯一真源</b>就是 plist
     * （{@link #render} 把它写进 {@code ProgramArguments}）。
     * 如果 {@code status} 另开一个 {@code --port} 让用户填，
     * 就是<b>同一个事实两处定义</b> —— 用户填的和实际装的对不上时，
     * {@code status} 会去探一个错误的端口，然后报「服务不可用」，
     * 而服务其实好好的。→ 只能从 plist 读。
     *
     * <p><b>为什么不用 plist 解析库</b>：这个项目要的是<b>零新增依赖</b>
     * （每加一个依赖都要给出取舍与实测体积，见项目质量红线）。
     * 而这里要读的是一个<b>我们自己写出去的</b> plist ——
     * 格式由 {@link #render} 决定，形态固定为
     * {@code <string>--port</string>} 后面紧跟 {@code <string>数字</string>}。
     * 用一个针对**这个形态**的解析就够了，而且它可以单测。
     *
     * <p><b>读不到时返回什么</b>：返回 {@code -1}（表示「不知道」），
     * **不猜默认值** —— 猜一个默认端口然后去探它，
     * 探不通就会报「服务不可用」，而真相是「不知道服务在哪个端口」，
     * 那是在编造事实。
     *
     * @param plistPath plist 文件路径
     * @return 端口；读不到（文件不存在 / 形态不认识 / 不是数字）时返回 -1
     */
    public static int readPort(Path plistPath) {
        if (plistPath == null) {
            return -1;
        }
        String text;
        try {
            text = java.nio.file.Files.readString(plistPath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return -1;
        }
        return parsePort(text);
    }

    /**
     * 从 plist 文本里取 {@code --port} 的值（纯函数，便于单测）。
     *
     * <p>形态：{@code <string>--port</string>} 后面紧跟 {@code <string>NNNN</string>}。
     * 取的是<b>第一个</b> {@code --port} 之后<b>紧邻</b>的字符串值；
     * 中间夹了别的东西（不是紧邻）就算读不到 —— 宁可说「不知道」，
     * 也不要从一个不相干的数字里猜端口。
     *
     * @param xml plist 文本
     * @return 端口；读不到时返回 -1
     */
    static int parsePort(String xml) {
        if (xml == null) {
            return -1;
        }
        int at = xml.indexOf("<string>--port</string>");
        if (at < 0) {
            return -1;
        }
        int from = at + "<string>--port</string>".length();
        // 跳过紧邻的换行与缩进（渲染时是「一行一个键」，但不该把格式当契约）。
        int i = from;
        while (i < xml.length() && (xml.charAt(i) == '\n' || xml.charAt(i) == '\r'
                || xml.charAt(i) == ' ' || xml.charAt(i) == '\t')) {
            i++;
        }
        if (!xml.startsWith("<string>", i)) {
            return -1;
        }
        int end = xml.indexOf("</string>", i);
        if (end < 0) {
            return -1;
        }
        String v = xml.substring(i + "<string>".length(), end).trim();
        if (v.isEmpty()) {
            return -1;
        }
        for (int k = 0; k < v.length(); k++) {
            if (v.charAt(k) < '0' || v.charAt(k) > '9') {
                return -1;
            }
        }
        try {
            int p = Integer.parseInt(v);
            return p > 0 && p <= 65535 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
