package com.litemask.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Windows 开机自启（注册表 <b>Run 键</b>）的流程部分：装、卸、看状态。
 *
 * <p><b>与 macOS 侧的对称关系</b>：{@link LaunchAgentPlist} 管「plist 长什么样」，
 * 本类管「把那个东西装上、卸掉、看状态」；
 * Windows 侧的对应物是 {@link WindowsRunKey}（键名/值名/命令行长什么样）。
 * 纯逻辑全在 {@code WindowsRunKey} 里，本类只做编排，所以<b>能在 macOS 上逐字段测死</b>。
 *
 * <p><b>为什么写完还要读回来核对</b>：这是 macOS 侧踩出来的教训
 * （「装上了但没生效」，plist 写错 / launchd 没装载，命令却报成功）。
 * Windows 侧的等价风险是<b>注册表里存的内容与预期不一致</b> ——
 * 最典型的是可执行文件路径里的双引号被 {@code reg.exe} 吃掉：
 * 值变成 {@code C:\Program Files\LightMeter\LightMeter.exe serve ...}
 * （引号没了）→ 登录时 Windows 把命令行切成 {@code C:\Program} 与
 * {@code Files\...} 两段 → 弹一个「找不到文件」。
 * → 所以装完必须 {@code reg query} 读回来，<b>逐字比对</b>，不一致就报失败。
 *
 * <p><b>关于双引号的转义（2026-10-21 查证，不是猜的）</b>：
 * 微软 {@code reg add /?} 自己的说明写着——
 * <blockquote>
 * To pass a value that contains quotes, you must add a slash (\) before each quote
 * as an escape sequence. … {@code reg add HKLM\Software\sav /v test /t REG_SZ
 * /d \"%userprofile%\"} maintains the quotes around the user profile value.
 * </blockquote>
 * 由此定下两条：
 * <ol>
 *   <li>{@code reg.exe} 认的转义是「反斜杠 + 引号」→ 一个字面双引号，
 *       <b>反斜杠只转义引号</b>（路径里的 {@code \} 原样保留）；
 *       而且 {@code /d} 取的是<b>一个</b> token，外层引号被剥掉。</li>
 *   <li>JDK 的 {@code ProcessBuilder} 在 Windows 上拼命令行时，
 *       也会把参数里的裸双引号转义成 {@code \"}。
 *       → 所以<b>我们传给 argv 的那份数据里要放真的双引号、不要自己加反斜杠</b>：
 *       JDK 转义一次、{@code reg.exe} 反转义一次，正好回到我们要的内容。
 *       如果我们自己先加反斜杠，就会被转义两次，存进去的是 {@code \"} 两个字符。</li>
 * </ol>
 * 这一段仍<b>未在真 Windows 上验证</b>（本机没有 Windows），
 * 所以才有上面那条「写完读回来核对」——
 * 真机上如果转义假设错了，命令会当场报失败并打印「预期 vs 实际」，
 * 而不是留一个装上了却不工作的自启。
 *
 * <p><b>为什么 Windows 侧不杀已运行的进程</b>：Run 键只管「登录时执行这条命令行」，
 * 不管进程死活。要在卸载时按端口杀进程，就得知道端口、还得防误杀别人的服务。
 * → 这条作为<b>已知限制</b>写进阶段文档，不在这里偷偷实现一个危险的近似。
 *
 * <p><b>为什么用 HKCU（当前用户）而不是 HKLM（本机）</b>：
 * 写 HKLM 要管理员权限，普通用户会看到一个提权弹窗；
 * 而自启本来就是<b>属于用户自己</b>的事——换个人登录就不该跟着起。
 * HKCU 不需要提权，卸载也不需要管理员。
 */
public final class WindowsAutostart {

    private WindowsAutostart() {
    }

    /**
     * 一次 {@code reg query} 的结果。
     *
     * @param code  {@code reg query} 的退出码（&lt;0 表示命令根本跑不起来）
     * @param stored 取回来的值内容；读不出来时为 null（<b>不等于「没装」</b>）
     */
    public record QueryResult(int code, String stored) {

        /** 查询本身失败（例如 {@code reg.exe} 不可用）——不能当成「没装」。 */
        public boolean failed() {
            return code < 0;
        }

        /** 这个值存在。 */
        public boolean present() {
            return code == 0;
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 查注册表里那个值在不在、内容是什么。
     *
     * <p><b>为什么按退出码判定「在不在」而不是按输出文本</b>：
     * macOS 侧在这上面栽过 —— {@code launchctl print} 的输出格式与手册不一致，
     * 按文本匹配导致「服务明明在跑，status 却说未装载」。
     * 注册表这边同理：{@code reg query} 找不到值时退出码非 0、错误信息走 stderr，
     * 而那段文本<b>不能</b>被当成「值的内容」。
     *
     * @param sys  与外部命令交互的一层
     * @param label 注册表值名
     * @return 查询结果
     */
    public static QueryResult query(AutostartCommand.Launcher sys, String label) {
        AutostartCommand.ExecResult r = sys.execCapture(WindowsRunKey.queryCommand(label));
        if (r.code() != 0) {
            return new QueryResult(r.code(), null);
        }
        return new QueryResult(0, WindowsRunKey.parseValue(r.output(), label));
    }

    // ------------------------------------------------------------------
    // 装
    // ------------------------------------------------------------------

    /**
     * 装开机自启。
     *
     * @param sys   与外部命令交互的一层
     * @param out   正常输出
     * @param err   错误输出
     * @param label 注册表值名
     * @param exe   可执行文件的完整路径
     * @param port  固定端口
     * @return 退出码（0 成功；1 执行失败；2 参数错）
     */
    public static int install(AutostartCommand.Launcher sys, PrintStream out, PrintStream err,
            String label, Path exe, int port) {
        String data;
        try {
            data = WindowsRunKey.commandData(exe, port);
        } catch (IllegalArgumentException e) {
            err.println("命令行不合法：" + e.getMessage());
            return 2;
        }

        // 先查一次：为了知道「原来是不是就是这个配置」（幂等时要说清楚）。
        QueryResult before = query(sys, label);
        boolean sameAsBefore = before.present() && data.equals(before.stored());

        int rc = sys.execQuiet(WindowsRunKey.addCommand(label, data));
        if (rc != 0) {
            err.println("写注册表 Run 键失败（reg add 退出码 " + rc + "）。");
            err.println("多半是当前用户没有写 HKCU 的权限。可以手动执行：");
            // 【2026-10-25】手动执行时**也要转义引号** ——
            //   reg.exe 会把不加转义的 \" 吃掉，存进去的值就没有引号了，
            //   路径含空格时开机自启直接起不来。
            err.println("  reg add \"" + WindowsRunKey.RUN_KEY + "\" /v " + label
                    + " /t REG_SZ /d " + WindowsRunKey.escapeQuotesForReg(data) + " /f");
            return 1;
        }

        // **写完必须读回来核对**：reg add 返回 0 只说明「写进去了」，
        // 不说明「写进去的内容是我们要的」。引号被吃掉是真实存在的失败模式。
        QueryResult after = query(sys, label);
        if (after.failed()) {
            err.println("写完了但读不回来（reg query 退出码 " + after.code() + "）—— 不敢报成功。");
            return 1;
        }
        if (!after.present()) {
            err.println("写完了但注册表里查不到这个值（值名 " + label + "）—— 不敢报成功。");
            return 1;
        }
        if (!data.equals(after.stored())) {
            err.println("写进注册表的内容与预期不一致 —— 这样开机自启不会工作，不能当成功。");
            err.println("  预期：" + data);
            err.println("  实际：" + (after.stored() == null ? "（读不出来）" : after.stored()));
            err.println("多半是 reg.exe 对双引号的处理与预期不同；"
                    + "请把这两行反馈出来（这一段在真 Windows 上验证过才对）。");
            return 1;
        }

        out.println(sameAsBefore ? "开机自启已经是这个配置（重新写了一次）。" : "已安装开机自启。");
        out.println("  值名     " + label);
        out.println("  注册表   " + WindowsRunKey.RUN_KEY);
        out.println("  命令行   " + data);
        out.println("  访问地址 http://127.0.0.1:" + port + "/");
        out.println("  停止     LightMeter autostart uninstall");
        return 0;
    }

    // ------------------------------------------------------------------
    // 卸
    // ------------------------------------------------------------------

    /**
     * 卸开机自启：删掉注册表里那个值。
     *
     * <p><b>先查再删</b>：值不存在时 {@code reg delete} 会报错并返回非 0，
     * 而那本来是<b>正常情况</b>（先卸后装时必然先卸一次没装过的）。
     * 查一下就能把「本来就没装」和「删失败」分开说话。
     *
     * @param sys   与外部命令交互的一层
     * @param out   正常输出
     * @param err   错误输出
     * @param label 注册表值名
     * @return 退出码
     */
    public static int uninstall(AutostartCommand.Launcher sys, PrintStream out, PrintStream err,
            String label) {
        QueryResult q = query(sys, label);
        if (q.failed()) {
            err.println("查注册表失败（reg query 退出码 " + q.code() + "），没敢动它。");
            return 1;
        }
        if (!q.present()) {
            out.println("本来就没装开机自启。");
            return 0;
        }
        int rc = sys.execQuiet(WindowsRunKey.deleteCommand(label));
        if (rc != 0) {
            err.println("删注册表值失败（reg delete 退出码 " + rc + "）。");
            err.println("可以手动执行：reg delete \"" + WindowsRunKey.RUN_KEY + "\" /v "
                    + label + " /f");
            return 1;
        }
        out.println("已卸载开机自启。");
        out.println("  删注册表值 " + label + "（" + WindowsRunKey.RUN_KEY + "）");
        return 0;
    }

    /**
     * 如果开机自启装着，就把那个注册表值删掉（供 {@code uninstall} 命令调用）。
     *
     * <p><b>为什么卸载工具时必须做这件事</b>：Run 键里的值只要还在，
     * 下次登录它就会自己起来 —— 程序卸载了却留了个常驻进程，
     * 而且是<b>用户看不见</b>的那种（没有 launchd 那样可以随时查的服务列表）。
     *
     * <p><b>失败不算卸载失败</b>：没装、或查不到，都不算错。
     * 但<b>查询本身失败</b>要说明白——那意味着「可能还留着」，
     * 不能让用户以为已经清干净了。
     *
     * @param sys   与外部命令交互的一层
     * @param out   输出
     * @param label 注册表值名
     * @return 确实删掉了就返回 true
     */
    public static boolean removeIfPresent(AutostartCommand.Launcher sys, PrintStream out, String label) {
        QueryResult q = query(sys, label);
        if (q.failed()) {
            out.println("没能查注册表（reg query 退出码 " + q.code() + "）—— 开机自启可能还留着。");
            return false;
        }
        if (!q.present()) {
            return false;
        }
        int rc = sys.execQuiet(WindowsRunKey.deleteCommand(label));
        if (rc == 0) {
            out.println("已删掉开机自启的注册表值（" + label + "）—— 下次登录不会再自己起来。");
            return true;
        }
        out.println("开机自启的注册表值没能删掉（reg delete 退出码 " + rc + "）。");
        out.println("可以手动执行：reg delete \"" + WindowsRunKey.RUN_KEY + "\" /v " + label + " /f");
        return false;
    }

    /**
     * 只说不改：把「将要删掉什么」讲清楚（供 {@code uninstall --dry-run} 调用）。
     *
     * @param sys   与外部命令交互的一层
     * @param out   输出
     * @param label 注册表值名
     */
    public static void describeIfPresent(AutostartCommand.Launcher sys, PrintStream out, String label) {
        QueryResult q = query(sys, label);
        if (q.failed()) {
            out.println("没能查注册表（reg query 退出码 " + q.code() + "）—— 开机自启可能还留着。");
            return;
        }
        if (!q.present()) {
            return;
        }
        out.println("将要删除：开机自启（注册表 Run 键里的值 " + label + "）—— 下次登录就不再自己起来");
        if (q.stored() != null) {
            out.println("  当前内容 " + q.stored());
        }
    }

    // ------------------------------------------------------------------
    // 看状态
    // ------------------------------------------------------------------

    /**
     * 看开机自启的状态。
     *
     * <p><b>为什么要两段式（2026-10-21 块 24）</b>：
     * 「Run 键里有这个值」只说明「<b>登录时</b>会执行这条命令行」，
     * 一点也说明不了「<b>现在</b>这个服务在不在跑」。
     * 而 Windows 侧的 Run 键<b>没有自愈</b>（macOS 侧有 {@code KeepAlive}）→
     * 服务挂了、被关了，<b>永远不会自己回来</b>。
     * → 用户看到「已安装」就以为界面一直能开，实际可能早就打不开了。
     *
     * <p><b>退出码三档</b>（与 macOS 侧对齐，但多一档「查不了」）：
     * <ul>
     *   <li>{@code 0} —— 没装（不探）或服务在线；</li>
     *   <li>{@code 3} —— 装了但服务不可用，或**不知道该探哪个端口**；</li>
     *   <li>{@code 1} —— <b>连注册表都查不了</b>（{@code reg.exe} 不可用）。
     *       这一档 macOS 侧没有：那边「装没装」从文件存在性能读到，
     *       不会「完全查不到」。</li>
     * </ul>
     *
     * @param sys   与外部命令交互的一层
     * @param out   正常输出
     * @param err   错误输出
     * @param label 注册表值名
     * @param probe 探活方式
     * @return 退出码
     */
    public static int status(AutostartCommand.Launcher sys, PrintStream out, PrintStream err,
            String label, ServiceProbe probe) {
        QueryResult q = query(sys, label);
        if (q.failed()) {
            err.println("查注册表失败（reg query 退出码 " + q.code() + "），多半是 reg.exe 不可用。");
            return 1;
        }
        out.println("开机自启 " + label);
        out.println("  注册表   " + WindowsRunKey.RUN_KEY);
        out.println("  状态     " + (q.present() ? "已安装" : "未安装"));
        if (!q.present()) {
            // 没装就**不探**：连「装没装」都还没回答，去探端口没有意义。
            out.println("  装它     LightMeter autostart install");
            return 0;
        }
        if (q.stored() == null) {
            // 「装了但读不出内容」与「没装」是两回事，不能混成一句。
            out.println("  内容     （读不出来：输出格式不认识，或控制台代码页不是 UTF-8）");
        } else {
            out.println("  内容     " + q.stored());
        }

        // ---- 装了的话，再问一句「服务到底在不在对外服务」----
        //
        // 【端口的唯一真源是「已写进 Run 键的那条命令行」】
        //   与 macOS 侧「已写出的 plist」对称 —— 同一个事实，两个平台各自的真源。
        //   读不到就明说「不知道」，**不去猜一个默认端口**
        //   （猜了就去探它，探不通就报「不可用」，而真相是「不知道」——那是编造事实）。
        int port = WindowsRunKey.parsePort(q.stored());
        if (port <= 0) {
            out.println("  服务检查 跳过（Run 键里没读出 --port，不知道该探哪个端口）");
            out.println("  访问地址 未知（Run 键里没有 --port）");
            return 3;
        }
        ServiceProbe.Result r = probe.probe(port);
        // 判据的落点是「服务检查」这一行 —— 不要用全文 contains。
        out.println("  服务检查 " + r.state().label());
        out.println("  访问地址 http://127.0.0.1:" + port + "/");
        if (r.online()) {
            out.println("  说明     " + r.detail());
            String summary = ServiceProbe.healthSummary(port);
            if (!summary.isEmpty()) {
                out.println("  健康检查 " + summary);
            }
            return 0;
        }
        // 不可用：把**原因**说清楚，并给出下一步能做什么。
        err.println("  原因     " + r.detail());
        err.println("开机自启已安装，但服务当前不可用 —— 界面打不开就是这个原因。");
        // 【块 34】按**分档**给不同的下一步（与 macOS 侧同一个判据）。
        if (r.state() == ServiceProbe.State.NOT_OURS) {
            err.println("  注意     端口 " + port + " 上答的不是本工具的服务"
                    + "（多半是别的程序占了这个端口）：把它停掉，或换一个端口重装自启。");
        } else if (r.state() == ServiceProbe.State.UNHEALTHY) {
            err.println("  注意     服务在跑但没按时应答（多半是它卡住了）："
                    + "必要时手动重启一次。");
        } else if (r.state() == ServiceProbe.State.UNKNOWN) {
            err.println("  注意     探活没得出结论：界面打不开不一定是因为它，先手工查一次。");
        }
        // 【Windows 特有的一句，必须说】
        //   Run 键只在**登录时**触发一次，且**没有自愈** →
        //   服务不在跑不会自己回来（macOS 侧有 KeepAlive 会自动重试）。
        //   不说这一句，用户会以为「等一会儿就好了」。
        err.println("  注意     Windows 的 Run 键只在「登录时」触发一次，"
                + "服务不在跑不会自己回来（macOS 侧有 KeepAlive 会自动重试）；"
                + "要恢复得手动启动一次，或重新登录。");
        String exe = WindowsRunKey.parseExecutable(q.stored());
        if (exe != null) {
            err.println("  手工启动 \"" + exe + "\" serve --no-browser --port " + port);
        }
        err.println("  手工查   curl -s http://127.0.0.1:" + port + "/api/health");
        return 3;
    }

    // ------------------------------------------------------------------
    // 找可执行文件
    // ------------------------------------------------------------------

    /**
     * 把 {@code --app} 给的东西解析成可执行文件的路径。
     *
     * <p><b>接受两种形态</b>：直接给 {@code .exe} 文件，
     * 或者给一个<b>含有 {@code LightMeter.exe} 的目录</b>
     * （jpackage 在 Windows 上产出的就是这种「解压即用」目录）。
     *
     * <p><b>为什么不用「按当前程序位置推断」那一套</b>：macOS 侧能推断，
     * 是因为 {@code .app} 目录结构固定（{@code Contents/MacOS/}）；
     * Windows 侧 jpackage 产物的布局随版本变过，所以推断只作兜底，
     * 推断不出来就<b>明确要求用户指定</b>，不猜。
     *
     * @param app 用户给的那个路径（可以是文件，也可以是目录）
     * @return 可执行文件路径；判断不出来时返回 null
     */
    public static Path resolveExecutable(Path app) {
        if (app == null) {
            return null;
        }
        if (Files.isDirectory(app)) {
            Path direct = app.resolve(WindowsRunKey.EXE_NAME);
            return Files.isRegularFile(direct) ? direct : null;
        }
        if (Files.isRegularFile(app)) {
            return app;
        }
        return null;
    }

    /**
     * 推断当前程序所在的可执行文件（兜底用；推断不出来就要求用户显式指定）。
     *
     * @return 可执行文件路径；判断不出来时返回 null
     */
    public static Path currentExecutable() {
        try {
            Path loc = Path.of(WindowsAutostart.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path p = Files.isDirectory(loc) ? loc : loc.getParent();
            for (int i = 0; i < 6 && p != null; i++) {
                Path exe = p.resolve(WindowsRunKey.EXE_NAME);
                if (Files.isRegularFile(exe)) {
                    return exe;
                }
                p = p.getParent();
            }
        } catch (Exception e) {
            // 推断失败不是错误：调用方会要求用户显式指定 --app。
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 平台判定
    // ------------------------------------------------------------------

    /**
     * 判断操作系统名是不是 Windows。
     *
     * <p><b>为什么只能用 {@code contains("windows")} 而不能用 {@code contains("win")}</b>：
     * {@code "Mac OS X"} 那种系统名里<b>不含</b> {@code win}，但
     * {@code "Darwin"} 含（d-a-r-**w-i-n**）！
     * 一旦写成 {@code contains("win")}，在 macOS 上就会走进 Windows 分支 ——
     * 去写注册表（写不进去），而 macOS 侧的 plist 反而没人管。
     * 判据写错方向，症状是「在 macOS 上装自启永远失败」，且非常难查。
     *
     * @param osName 操作系统名
     * @return 是 Windows 就返回 true
     */
    public static boolean isWindows(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("windows");
    }

    /**
     * 给 {@code uninstall --dry-run} 用的说明行（Windows 侧）。
     *
     * @param sys   与外部命令交互的一层
     * @param label 注册表值名
     * @return 值是否存在
     */
    static boolean installed(AutostartCommand.Launcher sys, String label) {
        QueryResult q = query(sys, label);
        return q.present();
    }

    /**
     * 供自证用：本类发出的命令里，{@code reg} 是第一个词。
     *
     * @param cmd 命令
     * @return 是不是一条 {@code reg} 命令
     */
    static boolean isRegCommand(List<String> cmd) {
        return !cmd.isEmpty() && cmd.get(0).endsWith("reg");
    }
}
