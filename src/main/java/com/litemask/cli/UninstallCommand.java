package com.litemask.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code LightMeter uninstall} 子命令：把本工具在磁盘上留下的东西清干净。
 *
 * <p><b>为什么要有这个命令</b>：卸载一个脱敏工具时，用户真正在意的不是
 * 「程序文件删了没」，而是<b>磁盘上还剩什么</b>：
 * <ul>
 *   <li>模型包（几十 MB，躺在 {@code ~/.litemask/models/ner}）；</li>
 *   <li>信任锚（用户加过的公钥）；</li>
 *   <li><b>任务工作区</b>——里面是脱敏产物和<b>映射文件</b>。
 *       映射文件里是<b>可还原的原始信息</b>，
 *       留在磁盘上等于「脱敏了但原始内容还在」，
 *       对一个处理律所卷宗的工具来说这是最不能留的。</li>
 * </ul>
 *
 * <p><b>为什么分两级</b>：模型包和信任锚重新装一次就有，删掉通常无害；
 * 而工作区里可能有用户<b>还没取走</b>的东西。
 * 所以默认只清<b>应用数据</b>，碰用户数据必须显式加 {@code --all}。
 *
 * <p><b>为什么默认先 dry-run</b>：不。默认就是<b>真删</b>——
 * 卸载的人想要的就是删干净，每次都要先看一遍再删反而是负担。
 * 但 {@code --dry-run} 一定要有：想确认会删什么时用得上。
 */
public final class UninstallCommand {

    private UninstallCommand() {
    }

    /**
     * 跑 {@code uninstall} 子命令。
     *
     * @param args 子命令及其参数
     * @param out  正常输出
     * @param err  错误输出
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, new AutostartCommand.RealLauncher(),
                System.getProperty("os.name", ""));
    }

    /**
     * 跑 {@code uninstall} 子命令（可注入与外部命令交互的一层，便于测试）。
     *
     * @param args 子命令及其参数
     * @param out  正常输出
     * @param err  错误输出
     * @param sys  与外部命令交互的一层
     * @return 退出码
     */
    public static int run(String[] args, PrintStream out, PrintStream err, AutostartCommand.Launcher sys) {
        return run(args, out, err, sys, System.getProperty("os.name", ""));
    }

    /**
     * 跑 {@code uninstall} 子命令（可注入操作系统名，便于测试平台分支）。
     *
     * @param args   子命令及其参数
     * @param out    正常输出
     * @param err    错误输出
     * @param sys    与外部命令交互的一层
     * @param osName 操作系统名。<b>做成参数是为了让「平台分支」这条判据可测</b>：
     *                读系统属性的写法在这台机器上永远是 macOS，
     *                那条分支就成了<b>恒真的断言</b> —— 变异掉它测试也不会红。
     * @return 退出码
     */
    public static int run(String[] args, PrintStream out, PrintStream err, AutostartCommand.Launcher sys,
            String osName) {
        boolean all = false;
        boolean dryRun = false;
        Path workspace = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--all" -> all = true;
                case "--dry-run" -> dryRun = true;
                case "-h", "--help" -> {
                    // --help 必须在清盘之前短路：
                    // 块 38 开工前 `uninstall --help` 报的是「不认识的参数: --help」，
                    // 退出码 2 —— 用户看到的是「错误」，不是用法。
                    CliHelp.print(out, usageText(), usageNotesText());
                    return 0;
                }
                case "--workspace" -> {
                    if (i + 1 >= args.length) {
                        err.println("--workspace 后面要跟目录。");
                        return 2;
                    }
                    workspace = Path.of(args[++i]);
                }
                default -> {
                    err.println(CliHints.unknownSwitchMessage(args[i], KNOWN_FLAGS));
                    CliHelp.print(err, usageText(), usageNotesText());
                    return 2;
                }
            }
        }

        Path userHome = Path.of(System.getProperty("user.home", "."));
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir", "."));
        Path ws = workspace != null ? workspace : AppDataCleaner.defaultWorkspaceDir(tmpDir);

        List<AppDataCleaner.Target> targets = AppDataCleaner.inventory(userHome, tmpDir, ws);
        AppDataCleaner.Scope scope = all ? AppDataCleaner.Scope.USER : AppDataCleaner.Scope.APP;

        if (!all) {
            out.println("默认只清**应用数据**（模型包、信任锚）。");
            out.println("任务工作区（脱敏产物与映射文件）属于用户数据，要一并清请加 --all。");
            out.println();
        }
        // 开机自启要先卸掉，否则「程序卸载了却留了个常驻进程、开机还会自己起来」。
        // 两个平台的「东西」不一样：
        //   macOS   —— launchd 里那个常驻服务（要 bootout）+ plist 文件
        //   Windows —— 注册表 Run 键里的那个值（没有文件，也没有服务可停）
        //
        // 但 --dry-run 的承诺是「**什么都不改**」——停服务/删注册表值也是改。
        // 所以 dry-run 只说「将要停/删」，不动手。
        if (WindowsAutostart.isWindows(osName)) {
            if (dryRun) {
                WindowsAutostart.describeIfPresent(sys, out, WindowsRunKey.DEFAULT_VALUE_NAME);
            } else {
                WindowsAutostart.removeIfPresent(sys, out, WindowsRunKey.DEFAULT_VALUE_NAME);
            }
        } else if (dryRun) {
            Path autostartPlist =
                    LaunchAgentPlist.plistPath(userHome, LaunchAgentPlist.DEFAULT_LABEL);
            if (Files.exists(autostartPlist)) {
                out.println("将要停止：开机自启的服务（launchd 里的常驻进程）—— " + autostartPlist);
            }
        } else {
            AutostartCommand.stopIfPresent(sys, userHome, LaunchAgentPlist.DEFAULT_LABEL, out);
        }

        return AppDataCleaner.clean(targets, scope, dryRun, out, err);
    }

    // ------------------------------------------------------------------
    // 用法（唯一真源）：顶层 help、`uninstall --help`、报错输出都从这里取。
    //
    // 【为什么拆成「命令行」与「说明」两段】
    //   顶层 help 的「用法:」块要的是**命令行那一行**，
    //   而「删掉什么、选项什么意思」是说明 —— 两段分开，
    //   顶层就能把命令行嵌进自己的用法块、把说明嵌进自己的说明段，
    //   不用把整段帮助抄一遍。
    // ------------------------------------------------------------------

    /** {@code uninstall} 的命令行。 */
    static String usageText() {
        return """
                  uninstall [--all] [--dry-run] [--workspace 目录]  卸载清理：删掉本工具在磁盘上留下的数据
                """;
    }

    static void usageLines(PrintStream out) {
        out.print(usageText());
    }

    /** {@code uninstall} 的说明段。 */
    static String usageNotesText() {
        return """
                  uninstall 删掉本工具在磁盘上留下的东西：
                    应用数据  模型包（~/.litemask/models/ner）与信任锚（~/.litemask/trust/anchors.json）
                    用户数据  任务工作区（默认 $TMPDIR/litemask-workspace）——
                              里面是脱敏产物与**映射文件**（映射里是可还原的原始信息）
                  --all 连用户数据一起清（默认只清应用数据）；
                    --dry-run 只列将要删除的内容，不真的删；
                    --workspace 指定工作区目录（服务端的 --workspace 指到别处时用它）
                """;
    }

    static void usageNotes(PrintStream out) {
        out.print(usageNotesText());
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

    static void usage(PrintStream out) {
        CliHelp.print(out, usageText(), usageNotesText());
    }
}
