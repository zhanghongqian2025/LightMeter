package com.litemask.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code LightMeter autostart <install|uninstall|status>}：macOS 开机自启。
 *
 * <p><b>为什么要有这个命令</b>：脱敏工具是「用之前先跑起来」的东西 ——
 * 用户把文档拖进去之前，服务得已经在跑。
 * 手工开终端敲一次 {@code serve} 是可行的，但每次都要敲，
 * 而且**很容易忘**（于是用户以为工具坏了）。开机自启把这个动作变成一次性的。
 *
 * <p><b>两个平台各用各的机制</b>：macOS 是 LaunchAgent（一个 plist 放进
 * {@code ~/Library/LaunchAgents/}，{@code launchctl} 负责装载）；
 * Windows 是注册表 Run 键（{@code HKCU\...\Run} 下一个值）。
 * 两者<b>机制完全不同</b>，所以代码上也是<b>两套</b>：
 * macOS 侧走 {@link LaunchAgentPlist} + {@code launchctl}，
 * Windows 侧走 {@link WindowsRunKey} + {@link WindowsAutostart}。
 * 硬塞进同一套流程只会得到一堆平台分支，而每个分支都是一处会漂移的地方。
 *
 * <p><b>但两边共用两样东西</b>：同一个 {@code --label} 语义与<b>同一个默认值</b>
 * （{@code com.litemask.lightmeter}），以及同一个「不是本平台就明确报错、
 * 不静默假装成功」的规矩。
 * 值名/label 用同一个字符串，卸载那条路径就不用分平台判断「该删哪个名字」。
 *
 * <p><b>为什么用户自己就能装、能卸</b>：装自启 = 让一个进程常驻本机。
 * 这属于「用户要能自己决定的事」，不该藏在安装器里；
 * 而且卸载工具时必须能把它一起关掉 —— 所以 plist 也进了
 * {@link AppDataCleaner} 的清单（{@code uninstall} 会清它）。
 */
public final class AutostartCommand {

    /** label 的合法形态：字母数字开头，后面可带 {@code . _ -}。 */
    private static final Pattern LABEL_OK = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** 日志目录（相对用户主目录）。 */
    private static final String LOG_SUBPATH = ".litemask/logs";

    /**
     * 与 launchd 交互的一层。抽出来是为了<b>能测</b>：
     * 单测里不能真往 launchd 里装东西（那会真的在本机留一个常驻服务）。
     */
    public interface Launcher {
        /** 跑一条命令，继承它的输入输出；返回退出码。 */
        int exec(List<String> cmd);

        /**
         * 跑一条命令并<b>吞掉它的输出</b>；返回退出码。
         *
         * <p><b>为什么要有这一条 —— 2026-10-21 端到端实测发现的噪音</b>：
         * {@code launchctl bootout} 在服务没装载时会往 stderr 打一行
         * 「Boot-out failed: 3: No such process」。
         * 那本来是**正常情况**（先卸后装时必然先卸一次没装过的），
         * 可它直接 inheriting IO 打到了用户终端上 ——
         * 一次成功的安装，屏幕上却出现一行「failed」。
         * → 这类「预期内的失败」必须安静地处理。
         */
        int execQuiet(List<String> cmd);

        /** 跑一条命令并取标准输出（失败时返回空串）。 */
        String output(List<String> cmd);

        /**
         * 跑一条命令，<b>同时</b>拿到退出码与标准输出。
         *
         * <p><b>为什么不能只用 {@link #output}</b>：那个方法的契约是
         * 「失败就返回空串」——那是 macOS 侧 {@code launchctl print} 的需要
         * （服务不存在时退出码 113、错误信息走 stderr，必须靠退出码判「在不在」）。
         * 但 Windows 侧要区分三件事：<b>值存在</b>、<b>值不存在</b>、
         * <b>命令根本跑不起来</b>（{@code reg.exe} 不可用）。
         * 后两者在 {@code output()} 眼里都是空串 → 会把「查不了」说成「没装」，
         * 那是 fail-open。→ 所以这里把退出码原样带出来。
         *
         * @param cmd 命令
         * @return 退出码与标准输出
         */
        ExecResult execCapture(List<String> cmd);
    }

    /**
     * 一次命令执行的结果。
     *
     * @param code   退出码（&lt;0 表示命令<b>根本没跑起来</b>，例如可执行文件不存在）
     * @param output 标准输出（stderr 已并入）
     */
    record ExecResult(int code, String output) {
    }

    /** 真实现：直接跑外部命令。 */
    public static final class RealLauncher implements Launcher {
        @Override
        public int exec(List<String> cmd) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();
                return pb.start().waitFor();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        }

        @Override
        public int execQuiet(List<String> cmd) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                return pb.start().waitFor();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        }

        @Override
        public String output(List<String> cmd) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                byte[] buf = proc.getInputStream().readAllBytes();
                int rc = proc.waitFor();
                // **命令失败就当没有输出**（返回空串）。
                //
                // 【为什么 —— 2026-10-21 端到端实测抓到的真缺陷】
                //   `launchctl print gui/<uid>/<label>` 在服务不存在时，
                //   退出码是 113，错误信息走 stderr。
                //   这里把 stderr 并进了 stdout，于是「服务不存在」会返回
                //   「Bad request. Could not find service ...」**一段非空文本**。
                //   → 之前 status 用「输出里含不含某段文字」判断服务在不在，
                //     判错了方向：真格式里根本没有那段文字（那是 launchctl print
                //     旧格式的写法），于是**服务明明在跑，status 却说「未装载」**。
                //   → 现在改成用**退出码**：退出码 0 = 服务存在。
                if (rc != 0) {
                    return "";
                }
                return new String(buf, StandardCharsets.UTF_8).trim();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return "";
            }
        }

        @Override
        public ExecResult execCapture(List<String> cmd) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                byte[] buf = proc.getInputStream().readAllBytes();
                int rc = proc.waitFor();
                return new ExecResult(rc, new String(buf, StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // -1 = 命令根本没跑起来（可执行文件不存在等），
                // 与「跑了但退出码非 0」是两回事，调用方要分开处理。
                return new ExecResult(-1, "");
            }
        }
    }

    private AutostartCommand() {
    }

    /**
     * 跑 {@code autostart} 子命令。
     *
     * @param args 子命令及其参数
     * @param out  正常输出
     * @param err  错误输出
     * @return 退出码（0 成功；2 参数错；1 执行失败）
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, new RealLauncher(), Path.of(java.lang.System.getProperty("user.home", ".")),
                java.lang.System.getProperty("os.name", ""));
    }

    /**
     * 可注入的实现（单测用）。
     *
     * @param osName 操作系统名。<b>做成参数是为了让「非 macOS 要明确报错」这条判据可测</b>：
     *                读系统属性的写法在这台机器上永远是 macOS，
     *                那条分支就成了<b>恒真的断言</b> —— 变异掉它测试也不会红。
     */
    public static int run(String[] args, PrintStream out, PrintStream err, Launcher sys, Path userHome,
            String osName) {
        return run(args, out, err, sys, userHome, osName, new ServiceProbe.Http());
    }

    /**
     * 同上，但<b>探活方式可注入</b>。
     *
     * <p><b>为什么要多这一个重载</b>：{@code status} 现在要回答
     * 「服务<b>真的</b>在不在对外服务」——这件事只能靠<b>真的去探一次</b>。
     * 单测里不能真起一个服务（慢且不稳，而且「服务没起来」和「服务起不来」
     * 在单测里长得一样），所以探活必须能换成假的、
     * 能精确控制「在线 / 连不上 / 超时」三档的实现。
     *
     * <p><b>老签名为什么保留</b>：既有调用点（{@code Main}、既有单测）不传 probe，
     * 走真实现 —— 少改一处是一处，也避免「忘了传 probe 就悄悄不探了」这种哑掉。
     *
     * @param args  参数
     * @param out   正常输出
     * @param err   错误输出
     * @param sys   与 launchd 交互的一层
     * @param userHome 用户主目录
     * @param osName 操作系统名
     * @param probe 探活方式
     * @return 退出码
     */
    public static int run(String[] args, PrintStream out, PrintStream err, Launcher sys, Path userHome,
            String osName, ServiceProbe probe) {
        if (args.length == 0) {
            usage(err);
            return 2;
        }
        String action = args[0];
        // 【块 38：`autostart --help` 以前报的是「未知动作: --help」】
        //   用户想看用法，看到的却是「错误」—— 而这条命令会**真的去装/卸开机自启**，
        //   让「想看帮助」变成「动了系统设置」是不能接受的。
        if ("-h".equals(action) || "--help".equals(action)) {
            CliHelp.print(out, usageText(), usageOptionsText());
            return 0;
        }
        String label = LaunchAgentPlist.DEFAULT_LABEL;
        Path app = null;
        int port = LaunchAgentPlist.DEFAULT_PORT;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--label" -> {
                    if (i + 1 >= args.length) {
                        err.println("--label 后面要跟服务标识。");
                        return 2;
                    }
                    label = args[++i];
                }
                case "--app" -> {
                    if (i + 1 >= args.length) {
                        err.println("--app 后面要跟程序路径。");
                        return 2;
                    }
                    app = Path.of(args[++i]);
                }
                case "--port" -> {
                    if (i + 1 >= args.length) {
                        err.println("--port 后面要跟端口号。");
                        return 2;
                    }
                    try {
                        port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        err.println("端口号不合法: " + args[i]);
                        return 2;
                    }
                    if (port < 0 || port > 65535) {
                        err.println("端口超出范围: " + port);
                        return 2;
                    }
                }
                case "-h", "--help" -> {
                    // `autostart install --help` 同样短路在装自启之前。
                    CliHelp.print(out, usageText(), usageOptionsText());
                    return 0;
                }
                default -> {
                    err.println(CliHints.unknownSwitchMessage(args[i], KNOWN_FLAGS));
                    usage(err);
                    return 2;
                }
            }
        }

        // label 会拼进文件路径 → 必须先校验，否则能写出 ~/Library/LaunchAgents 之外的地方。
        if (!LABEL_OK.matcher(label).matches()) {
            err.println("服务标识只能用字母数字开头加 . _ - ：" + label);
            return 2;
        }
        if (WindowsAutostart.isWindows(osName)) {
            return runWindows(sys, out, err, action, label, app, port, probe);
        }
        if (!isMac(osName)) {
            err.println("开机自启目前只实现了 macOS（LaunchAgent）与 Windows（注册表 Run 键）。");
            err.println("当前系统 " + osName + " 两个都不适用 —— 这条在阶段文档里记为已知限制。");
            return 2;
        }

        Path plistPath = LaunchAgentPlist.plistPath(userHome, label);
        Path logDir = userHome.resolve(LOG_SUBPATH);

        return switch (action) {
            case "install" -> install(sys, out, err, label, app, port, plistPath, logDir, userHome);
            case "uninstall" -> uninstall(sys, out, err, label, plistPath);
            case "status" -> status(sys, out, err, label, plistPath, probe);
            default -> {
                err.println("未知动作: " + action);
                usage(err);
                yield 2;
            }
        };
    }

    /**
     * Windows 分支：注册表 Run 键。
     *
     * <p><b>为什么不复用上面那套</b>：两边的「东西」根本不是一回事 ——
     * macOS 侧要写一个 plist 文件、还要跟 {@code launchctl} 打交道；
     * Windows 侧没有文件，只有一个注册表值。
     * 硬塞进同一套流程只会得到一堆 {@code if (windows)} 分支，
     * 而<b>每个分支都是一处会漂移的地方</b>。
     *
     * @param sys   与外部命令交互的一层
     * @param out   正常输出
     * @param err   错误输出
     * @param action install / uninstall / status
     * @param label  注册表值名
     * @param app    {@code --app} 给的路径（可空 = 自动推断）
     * @param port   固定端口
     * @param probe  探活方式（{@code status} 要拿它回答「服务在不在」）
     * @return 退出码
     */
    private static int runWindows(Launcher sys, PrintStream out, PrintStream err, String action,
            String label, Path app, int port, ServiceProbe probe) {
        return switch (action) {
            case "install" -> {
                Path exe = app != null
                        ? WindowsAutostart.resolveExecutable(app)
                        : WindowsAutostart.currentExecutable();
                if (exe == null) {
                    err.println("判断不出当前程序在哪个 .exe 里。");
                    err.println("请显式指定：autostart install --app \"C:\\Program Files\\LightMeter\\LightMeter.exe\"");
                    err.println("（--app 也可以给含 " + WindowsRunKey.EXE_NAME + " 的目录）");
                    yield 2;
                }
                yield WindowsAutostart.install(sys, out, err, label, exe, port);
            }
            case "uninstall" -> WindowsAutostart.uninstall(sys, out, err, label);
            case "status" -> WindowsAutostart.status(sys, out, err, label, probe);
            default -> {
                err.println("未知动作: " + action);
                usage(err);
                yield 2;
            }
        };
    }

    private static int install(Launcher sys, PrintStream out, PrintStream err, String label,
            Path app, int port, Path plistPath, Path logDir, Path userHome) {
        Path appRoot = app != null ? app : currentAppRoot();
        if (appRoot == null) {
            err.println("判断不出当前程序在哪个 .app 里。");
            err.println("请显式指定：autostart install --app /Applications/LightMeter.app");
            return 2;
        }
        Path exe = appRoot.resolve(LaunchAgentPlist.EXECUTABLE_RELATIVE);
        if (!Files.isExecutable(exe)) {
            err.println("找不到可执行文件：" + exe);
            err.println("请确认 --app 指的是本工具的 .app 目录。");
            return 2;
        }

        Path outLog = logDir.resolve("serve.out.log");
        Path errLog = logDir.resolve("serve.err.log");
        String xml = LaunchAgentPlist.render(new LaunchAgentPlist.Spec(
                label, exe, port, outLog, errLog, userHome));

        boolean changed;
        try {
            Files.createDirectories(plistPath.getParent());
            Files.createDirectories(logDir);
            String old = Files.exists(plistPath)
                    ? Files.readString(plistPath, StandardCharsets.UTF_8) : null;
            changed = !xml.equals(old);
            Files.writeString(plistPath, xml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("写 plist 失败：" + e.getMessage());
            return 1;
        }

        // 先卸后装：改过 plist 时 launchd 不会自动重载。
        bootout(sys, label);
        int rc = sys.exec(List.of("launchctl", "bootstrap", "gui/" + uid(sys), plistPath.toString()));
        if (rc != 0) {
            err.println("launchctl bootstrap 失败（退出码 " + rc + "）。");
            err.println("plist 已经写到 " + plistPath + "，可以手动执行：");
            err.println("  launchctl bootstrap gui/" + uid(sys) + " " + plistPath);
            return 1;
        }

        out.println(changed ? "已安装开机自启。" : "开机自启已经是这个配置（重新装载了一次）。");
        out.println("  服务标识 " + label);
        out.println("  访问地址 http://127.0.0.1:" + port + "/");
        out.println("  配置文件 " + plistPath);
        out.println("  日志     " + outLog);
        out.println("  停止     LightMeter autostart uninstall");
        return 0;
    }

    private static int uninstall(Launcher sys, PrintStream out, PrintStream err, String label, Path plistPath) {
        boolean wasLoaded = bootout(sys, label) == 0;
        boolean removed = false;
        try {
            if (Files.exists(plistPath)) {
                Files.delete(plistPath);
                removed = true;
            }
        } catch (IOException e) {
            err.println("删 plist 失败：" + e.getMessage());
            return 1;
        }
        if (!wasLoaded && !removed) {
            out.println("本来就没装开机自启。");
            return 0;
        }
        out.println("已卸载开机自启。");
        out.println("  停止装载 " + (wasLoaded ? "成功" : "本来就没装载"));
        out.println("  删配置   " + (removed ? "成功" : "本来就没有") + "（" + plistPath + "）");
        return 0;
    }

    /**
     * 看开机自启的状态。
     *
     * <p><b>这里回答的是两个不同的问题</b>，必须分开：
     * <ol>
     *   <li><b>装没装</b>：plist 在不在、launchd 认不认这个服务；</li>
     *   <li><b>服务在不在对外服务</b>：真的去探一次。</li>
     * </ol>
     * 原来只答第 1 个 → 于是<b>服务起不来时也报「已装载」</b>。
     *
     * <p><b>实测（2026-10-21，macOS 27.2.0）</b>：占住端口后装自启，
     * {@code launchctl print} 的 state 是 {@code spawn scheduled} / {@code active}
     * （launchd 正按 KeepAlive 反复拉起那个起不来的进程），
     * 而 {@code /api/health} 是 timeout。老实现此时报「运行状态 已装载」、
     * 退出码 0 —— <b>用户据此以为服务在跑，界面却打不开</b>。
     *
     * <p><b>端口从 plist 读</b>（{@link LaunchAgentPlist#readPort}）：
     * plist 是「这个服务实际监听哪个端口」的唯一真源；
     * 让用户再传一次 {@code --port} 就是同一个事实两处定义。
     *
     * <p><b>退出码语义</b>（能被脚本分辨）：
     * <ul>
     *   <li>{@code 0}：状态正常 —— 要么没装（这是正常状态，不是故障），
     *       要么装了且服务真的在线；</li>
     *   <li>{@code 3}：<b>装了，但服务不可用</b> —— 这是真问题，
     *       脚本应当报警。</li>
     * </ul>
     * 「没装」是 0 而不是非 0：它是<b>用户还没要求装</b>，不是出错。
     * 而「装了但服务不在线」是用户装完之后**没得到想要的东西**，
     * 静悄悄返回 0 就是把故障说成成功。
     *
     * @param sys       与 launchd 交互的一层
     * @param out       正常输出
     * @param err       错误输出
     * @param label     服务标识
     * @param plistPath plist 路径
     * @param probe     探活方式
     * @return 退出码
     */
    private static int status(Launcher sys, PrintStream out, PrintStream err, String label,
            Path plistPath, ServiceProbe probe) {
        boolean exists = Files.exists(plistPath);
        // 「服务在不在」以 **launchctl 的退出码**为准：
        // print 对不存在的服务退出码非 0，output() 于是返回空串。
        //
        // 【不要写成「输出里含不含某段文字」—— 2026-10-21 端到端实测踩过】
        //   原来判的是输出里有没有 `"label" = "<label>"`，
        //   那是 launchctl print **旧格式**的写法；本机（macOS 27.2.0）实测输出里
        //   **根本没有这一行**（开头是 `gui/<uid>/<label> = {`）→
        //   于是服务明明在跑，status 却报「未装载」。
        String print = sys.output(List.of("launchctl", "print", "gui/" + uid(sys) + "/" + label));
        boolean loaded = !print.isEmpty();
        out.println("开机自启 " + label);
        out.println("  配置文件 " + (exists ? "在 " + plistPath : "不在（没装）"));
        out.println("  运行状态 " + (loaded ? "已装载" : "未装载"));
        if (loaded) {
            String pid = between(print, "pid = ", "\n");
            if (pid != null && !pid.isBlank()) {
                out.println("  进程号   " + pid.trim());
            }
        }
        if (!exists && !loaded) {
            out.println("  装它     LightMeter autostart install");
            return 0;
        }

        // ---- 装了的话，再问一句「服务到底在不在对外服务」----
        //
        // 【为什么这一步不能省 —— 2026-10-21 实测】
        //   launchd 认得这个服务 ≠ 服务起得来。端口被占时，
        //   launchd 会按 KeepAlive 把它反复拉起、每次都立刻退出，
        //   于是 launchctl 一直说「已装载」，而端口上什么都没有。
        int port = LaunchAgentPlist.readPort(plistPath);
        if (port <= 0) {
            // 读不到端口就说读不到，**不去猜一个默认端口**。
            // 猜了就去探它，探不通就会报「服务不可用」——
            // 而真相是「不知道服务在哪个端口」，那是在编造事实。
            out.println("  服务检查 跳过（plist 里没读出 --port，不知道该探哪个端口）");
            out.println("  访问地址 未知（plist 里没有 --port）");
            return 3;
        }
        ServiceProbe.Result r = probe.probe(port);
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
        err.println("开机自启已装载，但服务当前不可用 —— 界面打不开就是这个原因。");
        // 【块 34】按**分档**给不同的下一步。
        //   「端口上不是本工具的服务」与「服务在但卡住了」要用户做的事完全不同：
        //   前者要把占端口的人赶走，后者要看日志/重启服务。
        //   不分档就只能说一句「不可用」——用户还是不知道该做什么。
        if (r.state() == ServiceProbe.State.NOT_OURS) {
            err.println("  注意     端口 " + port + " 上答的不是本工具的服务"
                    + "（多半是别的程序占了这个端口）：把它停掉，或换一个端口重装自启。");
        } else if (r.state() == ServiceProbe.State.UNHEALTHY) {
            err.println("  注意     服务在跑但没按时应答（多半是它卡住了）："
                    + "先看下面的日志，必要时手动重启一次。");
        } else if (r.state() == ServiceProbe.State.UNKNOWN) {
            err.println("  注意     探活没得出结论：界面打不开不一定是因为它，先手工查一次。");
        }
        if (loaded) {
            err.println("  launchd 会按 KeepAlive 自动重试；端口腾出来后服务会自己就位。");
            err.println("  日志     " + logPathOf(plistPath, label));
        }
        err.println("  手工查   curl -s http://127.0.0.1:" + port + "/api/health");
        return 3;
    }

    /**
     * 从 plist 路径推出日志目录下的错误日志路径（只用于给用户一个能去看的地方）。
     *
     * <p>日志目录与 {@link #LOG_SUBPATH} 一致；推不出来时返回一个说明性文字，
     * 而不是编一个路径。
     */
    private static String logPathOf(Path plistPath, String label) {
        Path home = plistPath;
        // ~/Library/LaunchAgents/<label>.plist → 上溯到用户主目录
        for (int i = 0; i < 3 && home != null; i++) {
            home = home.getParent();
        }
        if (home == null) {
            return "（推不出日志路径）";
        }
        return home.resolve(LOG_SUBPATH).resolve("serve.err.log").toString();
    }

    /**
     * 如果开机自启装着，就把它从 launchd 里卸下来（<b>只停服务，不删 plist</b>）。
     *
     * <p><b>为什么要有这个方法</b>：卸载工具时如果只删 plist 文件，
     * launchd 里那个服务<b>会继续跑</b> —— launchd 不管文件还在不在，
     * 它只认「装载过」这件事。结果是「程序卸载了，但还有个常驻进程在跑、
     * 开机还会自己起来」。所以卸载必须<b>先停服务</b>，再删文件。
     *
     * <p><b>失败不算卸载失败</b>：非 macOS 上压根没有 launchd，
     * launchctl 不存在也是常态。停不掉只说明一句，卸载本身照常进行。
     *
     * @param sys      与 launchd 交互的一层
     * @param userHome 用户主目录
     * @param label    服务标识
     * @param out      输出
     * @return 装了自启并尝试停掉时返回 true
     */
    public static boolean stopIfPresent(Launcher sys, Path userHome, String label, PrintStream out) {
        Path plist = LaunchAgentPlist.plistPath(userHome, label);
        if (!Files.exists(plist)) {
            return false;
        }
        String uid = uid(sys);
        int rc = sys.execQuiet(List.of("launchctl", "bootout", "gui/" + uid + "/" + label));
        if (rc == 0) {
            out.println("已停掉开机自启的服务（launchd 里的常驻进程）。");
        } else {
            out.println("开机自启的服务没能从 launchd 里卸下来（launchctl 退出码 " + rc + "）。");
            out.println("可以手动执行：launchctl bootout gui/" + uid + "/" + label);
        }
        return true;
    }

    /** 卸载装载。返回 launchctl 的退出码（没装载时 launchctl 会非 0 退出）。 */
    private static int bootout(Launcher sys, String label) {
        // 用安静通道：bootout 失败（本来就没装载）是**预期内**的，
        // 不该把 launchctl 的「Boot-out failed」打到用户终端上。
        return sys.execQuiet(List.of("launchctl", "bootout", "gui/" + uid(sys) + "/" + label));
    }

    private static String uid(Launcher sys) {
        String u = sys.output(List.of("id", "-u"));
        return u.isBlank() ? "0" : u.trim();
    }

    /** 从多行文本里取出 {@code marker} 与 {@code end} 之间的内容。 */
    static String between(String text, String marker, String end) {
        int i = text.indexOf(marker);
        if (i < 0) {
            return null;
        }
        int j = text.indexOf(end, i + marker.length());
        return j < 0 ? text.substring(i + marker.length()) : text.substring(i + marker.length(), j);
    }

    /**
     * 推断当前程序所在的 .app 根目录。
     *
     * @return .app 根目录；判断不出时返回 null（调用方要明确报错，不能猜一个）
     */
    static Path currentAppRoot() {
        try {
            Path loc = Path.of(AutostartCommand.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path p = Files.isDirectory(loc) ? loc : loc.getParent();
            for (int i = 0; i < 6 && p != null; i++) {
                Path name = p.getFileName();
                if (name != null && name.toString().endsWith(".app")
                        && Files.isExecutable(p.resolve(LaunchAgentPlist.EXECUTABLE_RELATIVE))) {
                    return p;
                }
                p = p.getParent();
            }
        } catch (Exception e) {
            // 推断失败不是错误：调用方会要求用户显式指定 --app。
            return null;
        }
        return null;
    }

    private static boolean isMac(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    /**
     * {@code autostart} 的命令行清单（不带「用法:」抬头）。
     *
     * <p>顶层 {@code --help} 以前**完全没有** autostart 这一块 ——
     * 用户照 README 敲 {@code autostart install} 之前，根本不知道这个命令存在。
     * 现在顶层直接调这里，这一份是唯一真源。
     *
     * <p>选项说明在 {@link #usageOptions}，不在这里重复写。
     *
     * @param out 输出流
     */
    static void usageLines(PrintStream out) {
        out.print(usageText());
    }

    /**
     * {@code autostart} 的命令行（唯一真源）。
     *
     * <p>顶层 help 与 {@code autostart --help} 都从这里取 ——
     * 块 37 之后顶层已经改成嵌入它，块 38 补上的是「{@code --help} 也打这一份」。
     */
    static String usageText() {
        return """
                  autostart install [--app 路径] [--port N] [--label 标识]  装开机自动启动（登录后自动起本地服务）
                  autostart uninstall [--app 路径]                          卸掉开机自动启动
                  autostart status [--app 路径]                            看装没装、进程号、配置在哪
                """;
    }

    /**
     * {@code autostart} 的选项说明。
     *
     * <p>顶层 help 与本命令自己的报错输出共用这一份 ——
     * 选项改一个（比如加一个默认值）只改这里一处。
     *
     * @param out 输出流
     */
    static void usageOptions(PrintStream out) {
        out.print(usageOptionsText());
    }

    /** {@code autostart} 的选项说明（唯一真源）。 */
    static String usageOptionsText() {
        return """
                  --app <路径>    程序位置（macOS：.app 目录；Windows：LightMeter.exe 或含它的目录）
                  --port <端口>   固定端口（默认 %d）
                  --label <标识>  服务标识（macOS 是 launchd 的 label，Windows 是注册表值名；默认 %s）
                """.formatted(LaunchAgentPlist.DEFAULT_PORT, LaunchAgentPlist.DEFAULT_LABEL);
    }

    /**
     * 本命令认识的全部开关 —— <b>从用法文本现算</b>，不另抄一份清单。
     */
    static final List<String> KNOWN_FLAGS = knownFlags();

    private static List<String> knownFlags() {
        List<String> flags = new ArrayList<>(CliHints.flagsIn(usageText()));
        if (!flags.contains("-h")) {
            flags.add("-h");
        }
        if (!flags.contains("--help")) {
            flags.add("--help");
        }
        return List.copyOf(flags);
    }

    private static void usage(PrintStream out) {
        CliHelp.print(out, usageText(), usageOptionsText());
    }
}
