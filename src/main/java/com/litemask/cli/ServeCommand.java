package com.litemask.cli;

import com.litemask.core.Rule;
import com.litemask.server.ApiHandlers;
import com.litemask.server.LocalServer;
import com.litemask.server.Workspace;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code serve} 子命令：启动本地 HTTP 服务。
 *
 * <p>拆成 {@link #start} 与 {@link #run} 是为了测试——测试要拿到一个已经跑起来的
 * {@link LocalServer}，而不能让进程挂在那儿等 Ctrl-C。
 */
public final class ServeCommand {

    /** 工作目录默认值：用户临时目录下的 litemask-workspace。 */
    public static Path defaultWorkspaceDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "litemask-workspace");
    }

    /**
     * 起服务并装配路由。调用方负责 {@code close()}。
     *
     * @param port          0 表示让系统分配
     * @param printToken    是否把会话令牌打到 stdout（排障用，默认否）
     */
    public static LocalServer start(Path workspaceDir, int port, boolean printToken) throws IOException {
        return start(workspaceDir, port, printToken, List.of());
    }

    /**
     * 带 AI 来源起服务。
     *
     * <p>来源由调用方注入而不是在这里去读全局单例：
     * 服务是长驻的，模型该在<b>启动时</b>确定一次并保持，
     * 而不是每处理一个请求就重新发现一次——那既慢，又会让同一批文件
     * 因为模型被发现/未被发现的不同时机而得到不同结果。
     */
    public static LocalServer start(Path workspaceDir, int port, boolean printToken,
                                    List<com.litemask.core.EntitySource> sources) throws IOException {
        return start(workspaceDir, port, printToken, sources, Workspace.MAX_UPLOAD_BYTES);
    }

    /**
     * 指定<b>上传体积上限</b>（字节）起服务。
     *
     * <p><b>为什么要能调</b>：上限判定如果只能靠「真的传 256MB」去触发，
     * 那这条判定就没法在**真实服务**上被验——端到端要么得造一个几百 MB 的文件，
     * 要么就只能验别的分支。而上限本来就是实例参数（{@link Workspace}），
     * 把它露成一个选项，磁盘小的用户也能自己收紧。
     */
    public static LocalServer start(Path workspaceDir, int port, boolean printToken,
                                    List<com.litemask.core.EntitySource> sources,
                                    long maxUploadBytes) throws IOException {
        return start(workspaceDir, port, printToken, sources, maxUploadBytes,
                com.litemask.server.TaskRunner.DEFAULT_TIMEOUT_MS);
    }

    /**
     * 指定<b>任务时限</b>（毫秒；<= 0 表示不设时限）起服务。
     *
     * <p>时限必须能调：有的文档就是很大，用户需要比默认更宽的时限；
     * 而端到端要能在几秒内造出一次真实的超时——没有这个出口，
     * 那条 e2e 就只能干等默认的 30 分钟，等同于没写。
     */
    public static LocalServer start(Path workspaceDir, int port, boolean printToken,
                                    List<com.litemask.core.EntitySource> sources,
                                    long maxUploadBytes, long taskTimeoutMs) throws IOException {
        return start(workspaceDir, port, printToken, sources, maxUploadBytes, taskTimeoutMs, null);
    }

    /**
     * 带模型管理器起服务。
     *
     * <p><b>为什么模型管理器要一路传进来</b>：界面上「安装模型 / 更新 / 回退」
     * 这些按钮，底下走的是同一套<b>真实的</b>模型生命周期服务
     * （验签、原子换装、真的加载一次）。
     * 服务与模型管理器必须共用<b>同一个实例</b>：
     * 两份的话，界面显示的「已就绪」与引擎实际用的模型可能不是同一个。
     *
     * @param models 模型管理器；为 {@code null} 时不挂模型管理路由
     */
    public static LocalServer start(Path workspaceDir, int port, boolean printToken,
                                    List<com.litemask.core.EntitySource> sources,
                                    long maxUploadBytes, long taskTimeoutMs,
                                    com.litemask.ner.ModelManager models) throws IOException {
        return start(workspaceDir, port, printToken, sources, maxUploadBytes,
                taskTimeoutMs, models, null);
    }

    /** 桌面进程可注入停服信号；普通内嵌调用不注入，也就不暴露退出路由。 */
    static LocalServer start(Path workspaceDir, int port, boolean printToken,
                             List<com.litemask.core.EntitySource> sources,
                             long maxUploadBytes, long taskTimeoutMs,
                             com.litemask.ner.ModelManager models,
                             Runnable shutdownRequest) throws IOException {
        Workspace workspace = Workspace.open(workspaceDir, maxUploadBytes);
        LocalServer server = LocalServer.create(workspace, port);
        if (shutdownRequest != null) {
            server.onShutdownRequest(shutdownRequest);
        }
        List<Rule> rules = ApiHandlers.defaultRules();
        com.litemask.core.ExclusiveGate gate = models == null
                ? com.litemask.core.ExclusiveGate.NONE : models;
        ApiHandlers.register(server, workspace, rules, sources, gate, models);
        server.start();
        if (printToken) {
            System.out.println("会话令牌: " + server.policy().token());
        }
        return server;
    }

    /** 解析参数、起服务、打开浏览器、然后一直等到被停掉。 */
    public static int run(String[] args) throws IOException {
        Path workspaceDir = defaultWorkspaceDir();
        int port = 0;
        boolean openBrowser = true;
        boolean printToken = false;
        String nerModelDir = null;
        long maxUploadBytes = Workspace.MAX_UPLOAD_BYTES;
        // 任务时限：默认走 TaskRunner 的默认值，用户可以用 --task-timeout 改；
        // 0 表示不设时限（永不自动中止）。
        long taskTimeoutMs = com.litemask.server.TaskRunner.DEFAULT_TIMEOUT_MS;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> {
                    if (++i >= args.length) {
                        System.err.println("--port 需要一个端口号");
                        return 2;
                    }
                    try {
                        port = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) {
                        System.err.println("端口号不合法: " + args[i]);
                        return 2;
                    }
                }
                case "--workspace" -> {
                    if (++i >= args.length) {
                        System.err.println("--workspace 需要一个目录");
                        return 2;
                    }
                    workspaceDir = Path.of(args[i]);
                }
                case "--ner-model" -> {
                    if (++i >= args.length) {
                        System.err.println("--ner-model 需要一个目录");
                        return 2;
                    }
                    nerModelDir = args[i];
                }
                case "--task-timeout" -> {
                    if (++i >= args.length) {
                        System.err.println("--task-timeout 需要一个毫秒数");
                        return 2;
                    }
                    try {
                        taskTimeoutMs = Long.parseLong(args[i]);
                    } catch (NumberFormatException e) {
                        System.err.println("--task-timeout 需要一个整数（毫秒）");
                        return 2;
                    }
                    if (taskTimeoutMs < 0) {
                        System.err.println("--task-timeout 不能为负（0 表示不设时限）");
                        return 2;
                    }
                }
                case "--no-browser" -> openBrowser = false;
                case "--print-token" -> printToken = true;
                case "-h", "--help" -> {
                    // 【为什么 --help 要短路在起服务之前】
                    //   块 38 开工前的实测：`serve --help` 报的是「未知选项: --help」，
                    //   退出码 2 —— 用户看到的是「错误」，不是用法。
                    //   而这一条命令会**真的起一个服务**（起完就 forever.await()），
                    //   所以更不能让「想看帮助」变成「真的起了个服务」。
                    CliHelp.print(System.out, usageText(), usageNotesText());
                    return 0;
                }
                case "--max-upload-bytes" -> {
                    if (++i >= args.length) {
                        System.err.println("--max-upload-bytes 需要一个字节数");
                        return 2;
                    }
                    try {
                        maxUploadBytes = Long.parseLong(args[i]);
                    } catch (NumberFormatException e) {
                        System.err.println("--max-upload-bytes 需要一个正整数（字节）");
                        return 2;
                    }
                    if (maxUploadBytes <= 0) {
                        System.err.println("--max-upload-bytes 必须大于 0");
                        return 2;
                    }
                }
                default -> {
                    // 【为什么不叫「未知选项」而统一叫「未知开关」】
                    //   块 38 的守卫要按一个**统一的词**去认「这是不是一个被报出来的
                    //   未知开关」——五个子命令各叫各的，守卫就得维护五个词。
                    //   → 统一成「未知开关: X」，并尽量补一句「你是不是想写 Y」。
                    System.err.println(CliHints.unknownSwitchMessage(args[i], KNOWN_FLAGS));
                    CliHelp.print(System.err, usageText(), usageNotesText());
                    return 2;
                }
            }
        }

        // ---- 模型 ----
        // 【为什么这里不再直接用 NerModelRegistry.discover】
        // discover 是「找到就用」，而用户真正要的是「**装不上就告诉他怎么办**」。
        // ModelManager 把发现、验签、原子换装、加载、报状态收在一处，
        // 并且能在服务运行期间被界面驱动（装好立刻生效）。
        com.litemask.ner.ModelManager models = new com.litemask.ner.ModelManager(
                com.litemask.ner.NerModelRegistry.defaultUserModelDir(),
                nerModelDir == null || nerModelDir.isBlank() ? null : Path.of(nerModelDir));
        if (nerModelDir != null && !nerModelDir.isBlank()
                && !Files.isDirectory(Path.of(nerModelDir))) {
            // 用户显式指定了目录而它不存在：这是**指示写错了**，不是「未安装」。
            // 报错并停下，别让用户以为服务带上了人名识别。
            System.err.println("错误: 指定的模型包目录不存在: " + nerModelDir);
            return 1;
        }
        // 同步做掉「恢复中断的换装」与「载入生效中的模型」，
        // 这样界面第一眼看到的状态就是真的。
        models.bootstrapSync();
        com.litemask.ner.NerModelRegistry registry = models.registry();
        com.litemask.ner.NerModelRegistry.set(registry);

        ServiceLifecycle lifecycle = new ServiceLifecycle(models);
        LocalServer server = start(workspaceDir, port, printToken, List.of(models.source()),
                maxUploadBytes, taskTimeoutMs, models, lifecycle::requestStop);
        lifecycle.attach(server);
        Runtime.getRuntime().addShutdownHook(new Thread(lifecycle::cleanup, "litemask-shutdown"));

        // ---- 安装目录被动过 → 自己让位 ----------------------------------
        // 【为什么需要这一步】
        //   装着 LightMeter 时，程序目录里的 jar 被本进程打开着。
        //   用户双击新版安装包升级时，Installer 要覆盖那个 jar，
        //   而 Windows 上**被打开的文件覆盖不了**。
        //   Installer 会找「谁占着」并请它退出，但它只能通过**窗口消息**请；
        //   本产品是控制台程序（没有窗口）→ 它被判成「critical application」而拒绝关，
        //   最后只留下一句「Close that application and retry」，把找进程的活儿推给用户。
        //   → 这里让程序**自己**看见「安装器来了」，自己把资源交还、自己退出。
        //   用户什么都不用做：不用找进程、不用执行命令。
        InstallWatcher.start(() -> {
            System.out.println("检测到安装程序正在更新本程序，正在退出以完成升级。");
            lifecycle.requestStop();
        });

        System.out.println("本地脱敏服务已启动");
        System.out.println("  地址    " + server.url());
        System.out.println("  绑定    127.0.0.1（仅本机；同网段的其它机器连不上）");
        System.out.println("  工作目录 " + server.workspace().sessionDir());
        System.out.println("  会话    " + server.workspace().sessionId().substring(0, 8)
                + "（退出时整目录删除）");
        System.out.println("  规则    " + ApiHandlers.defaultRules().size() + " 条");
        // 上限要打出来：用户改了它就该看得见，而且报错文案里说的也是这个数。
        System.out.println("  上传上限 " + Workspace.humanSize(server.workspace().maxUploadBytes()));
        // 时限要打出来：它决定了「跑太久会被自动中止」，
        // 而任务莫名消失时用户第一个要看的就是这条。
        System.out.println("  任务时限 " + (taskTimeoutMs > 0
                ? (taskTimeoutMs / 1000) + " 秒"
                : "不设时限"));
        // 这行不是「顺便打印一下」：NER 没启用时，人名与机构名不会被脱，
        // 而界面上「0 命中」与「文档干净」长得一模一样。必须让用户先看见这句话。
        System.out.println("  " + models.status().message());
        if (!printToken) {
            System.out.println("  令牌    不打印；浏览器打开首页时由服务端注入");
        }

        // 首次启动的自动准备：没有可用模型时，把随安装包分发的模型装上。
        // 放后台是因为它要解压并加载模型（几秒），界面不必等它。
        models.bootstrapAsync();

        if (openBrowser) {
            browseQuietly(server.url());
        }
        System.out.println("按 Ctrl-C 停止。");
        try {
            lifecycle.awaitStop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lifecycle.cleanup();
        }
        return 0;
    }

    /** GUI、安装器与 JVM shutdown hook 共用的单次清理器。 */
    private static final class ServiceLifecycle {
        private final CountDownLatch stop = new CountDownLatch(1);
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final com.litemask.ner.ModelManager models;
        private volatile LocalServer server;

        ServiceLifecycle(com.litemask.ner.ModelManager models) {
            this.models = models;
        }

        void attach(LocalServer server) {
            this.server = server;
        }

        void requestStop() {
            stop.countDown();
        }

        void awaitStop() throws InterruptedException {
            stop.await();
        }

        void cleanup() {
            requestStop();
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            LocalServer current = server;
            try {
                if (current != null) {
                    try {
                        current.close();
                    } finally {
                        current.workspace().close();
                    }
                }
            } finally {
                models.close();
            }
        }
    }

    /** 打开浏览器。失败一律吞掉——打不开浏览器不该让一个后台服务起不来。 */
    private static void browseQuietly(String url) {
        BrowserLauncher.open(url);
    }

    // ------------------------------------------------------------------
    // 用法（唯一真源）：顶层 help 与 `serve --help` 都从这里取。
    //
    // 【为什么要独立成一份】
    //   块 37 之后，顶层 help 里 serve 的命令行是**手抄**的一段文字，
    //   而 serve 自己的报错输出又是另一段手抄 —— 两份会漂。
    //   → 现在命令行只有一份（这里），顶层 help 嵌它，`serve --help` 也打它。
    // ------------------------------------------------------------------

    /** {@code serve} 的命令行。 */
    static String usageText() {
        return """
                  serve   [--port N] [--workspace 目录] [--no-browser] [--print-token]
                          [--ner-model 模型目录] [--max-upload-bytes 字节] [--task-timeout 毫秒]
                           启动本地 HTTP 服务（Word/WPS 加载项与浏览器都走它）
                """;
    }

    static void usageLines(PrintStream out) {
        out.print(usageText());
    }

    /** {@code serve} 的说明段。 */
    static String usageNotesText() {
        return """
                  serve 只绑 127.0.0.1，且校验 Host 头与 Origin；写操作还要求当次启动的
                    会话令牌。外部网页无法对本服务发起写请求。
                  serve 的 --print-token 会把会话令牌打到 stdout（排障用，默认不打）；
                    --max-upload-bytes 限制单份上传体积，--task-timeout 限制单个任务
                    的处理时限（毫秒，0 表示不设时限），两者都有各自的默认值。
                """;
    }

    static void usageNotes(PrintStream out) {
        out.print(usageNotesText());
    }

    /**
     * 本命令认识的全部开关 —— <b>从上面的用法文本现算</b>，不另抄一份清单。
     *
     * <p>另抄一份 = 第三份拷贝：加了开关忘了改清单，用户正在用的开关就会被
     * 报成「未知开关」，而清单自己不会喊。
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

    private ServeCommand() {
    }
}
