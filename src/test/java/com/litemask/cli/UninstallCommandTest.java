package com.litemask.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code uninstall} 子命令：卸载时磁盘上到底还剩什么。
 *
 * <p><b>这里防的是一条真实存在的缺陷</b>：卸载一个脱敏工具，
 * 用户真正在意的是<b>磁盘上还剩什么</b>——
 * 尤其是<b>映射文件</b>（里面是可还原的原始信息）。
 * 只删程序文件、把映射留在临时目录里，
 * 等于「脱敏了但原始内容还在磁盘上」。
 *
 * <p><b>测试标尺独立于被测实现</b>：
 * 「应该清掉哪些路径」这份清单在本类里<b>手写</b>，
 * <b>不复用</b> {@code AppDataCleaner.inventory()}——
 * 拿被测实现自己的清单当期望值，只能证明它跟自己一致。
 * 若实现漏了一项，本测试里的手写清单会立刻暴露。
 */
class UninstallCommandTest {

    private record Result(int code, String text) {
    }

    /**
     * 假 launchd：记录被要求发哪些命令。
     *
     * <p><b>为什么单测里不碰真的 launchd</b>：真跑 {@code launchctl bootout}
     * 会去动<b>这台机器</b>的 launchd —— 单测不该在开发机上摘掉谁的服务。
     * 换成假实现后，断言的是「我们要求它发哪些命令」，
     * 而不是「launchd 最后怎么样」。
     */
    /**
     * 本机是不是 Windows。
     *
     * <p><b>为什么测试要分平台</b>：开机自启在两个平台上是<b>两套机制</b>
     * （macOS = launchd 的常驻服务 + plist 文件；Windows = 注册表 Run 键里的一个值），
     * 卸载要做的动作自然也不同。断言必须跟着机制走，
     * 否则在 Windows 上只能断言一条 macOS 的措辞 —— 那条断言在 Windows 上
     * 要么恒假（红），要么被改松（失去意义）。
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private final class FakeLauncher implements AutostartCommand.Launcher {
        final List<List<String>> calls = new ArrayList<>();
        /** 观察点：bootout 被调用的那一刻，被观察的文件还在不在。 */
        Path watch;
        Boolean watchPresentAtBootout;

        @Override
        public int exec(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            if (cmd.contains("bootout") && watch != null) {
                watchPresentAtBootout = Files.exists(watch);
            }
            return 0;
        }

        @Override
        public int execQuiet(List<String> cmd) {
            return exec(cmd);
        }

        @Override
        public String output(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return "501";
        }

        @Override
        public AutostartCommand.ExecResult execCapture(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return new AutostartCommand.ExecResult(0, "501");
        }

        boolean called(String... needle) {
            for (List<String> c : calls) {
                if (c.containsAll(List.of(needle))) {
                    return true;
                }
            }
            return false;
        }
    }

    private Result run(AutostartCommand.Launcher sys, String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int code = UninstallCommand.run(args, ps, ps, sys);
        return new Result(code, buf.toString(StandardCharsets.UTF_8));
    }

    /** 默认走假 launchd：单测不该在本机 launchd 上动手脚。 */
    private Result run(String... args) {
        return run(new FakeLauncher(), args);
    }

    /** {@code user.home} / {@code java.io.tmpdir} 都是系统属性，测完必须还原。 */
    private String savedUserHome;
    private String savedTmpDir;
    private Path home;
    private Path tmp;

    @BeforeEach
    void setUpIsolation(@TempDir Path root) {
        savedUserHome = System.getProperty("user.home");
        savedTmpDir = System.getProperty("java.io.tmpdir");
        home = root.resolve("home");
        tmp = root.resolve("tmp");
        try {
            Files.createDirectories(home);
            Files.createDirectories(tmp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.setProperty("user.home", home.toString());
        System.setProperty("java.io.tmpdir", tmp.toString());
    }

    @AfterEach
    void tearDownIsolation() {
        if (savedUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", savedUserHome);
        }
        if (savedTmpDir == null) {
            System.clearProperty("java.io.tmpdir");
        } else {
            System.setProperty("java.io.tmpdir", savedTmpDir);
        }
    }

    // ------------------------------------------------------------------
    // 期望清单：手写，不复用被测实现
    // ------------------------------------------------------------------

    private Path modelDir() {
        return home.resolve(".litemask").resolve("models").resolve("ner");
    }

    private Path trustFile() {
        return home.resolve(".litemask").resolve("trust").resolve("anchors.json");
    }

    private Path workspaceDir() {
        return tmp.resolve("litemask-workspace");
    }

    /**
     * 开机自启配置的路径（<b>手写</b>，不复用 {@code LaunchAgentPlist.DEFAULT_LABEL}）。
     *
     * <p>手写是有意的：哪天 label 变了，这份手写清单就会红 ——
     * 那正是我们要的信号（安装器写的 plist 与卸载器清的 plist 必须是同一个）。
     */
    private Path autostartPlist() {
        return home.resolve("Library/LaunchAgents/com.litemask.lightmeter.plist");
    }

    /** 造出「装过开机自启」的样子：LaunchAgents 目录里躺着一个 plist。 */
    private void seedAutostartPlist() throws Exception {
        Files.createDirectories(autostartPlist().getParent());
        Files.writeString(autostartPlist(), "<plist/>");
    }

    /** 造出「装过模型、加过信任锚、跑过任务」之后的样子。 */
    private void seedEverything() throws Exception {
        Files.createDirectories(modelDir());
        Files.writeString(modelDir().resolve("manifest.json"), "{}");
        Files.createDirectories(modelDir().resolve("v1"));
        Files.writeString(modelDir().resolve("v1").resolve("ner.onnx"), "x");

        Files.createDirectories(trustFile().getParent());
        Files.writeString(trustFile(), "{\"anchors\":[]}");

        Files.createDirectories(workspaceDir().resolve("session-1"));
        Files.writeString(workspaceDir().resolve("session-1").resolve("卷宗.map.json"),
                "{\"原件\":\"张伟明\"}");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("默认清应用数据：模型包与信任锚删掉，工作区保留")
    void cleansAppDataByDefault() throws Exception {
        seedEverything();
        Result r = run();

        assertEquals(0, r.code(), "退出码应为 0，实际输出：\n" + r.text());
        assertFalse(Files.exists(modelDir()), "模型包目录应被删掉");
        assertFalse(Files.exists(trustFile()), "信任锚文件应被删掉");
        assertTrue(Files.exists(workspaceDir()), "默认**不该**删用户数据（工作区）");
        assertTrue(Files.exists(workspaceDir().resolve("session-1").resolve("卷宗.map.json")),
                "映射文件在默认模式下必须留着");
    }

    @Test
    @DisplayName("--all 连用户数据一起清：映射文件必须真的没了")
    void cleansUserDataWithAll() throws Exception {
        seedEverything();
        Result r = run("--all");

        assertEquals(0, r.code(), "退出码应为 0，实际输出：\n" + r.text());
        assertFalse(Files.exists(modelDir()), "模型包目录应被删掉");
        assertFalse(Files.exists(trustFile()), "信任锚文件应被删掉");
        assertFalse(Files.exists(workspaceDir()), "加了 --all 就该把工作区删掉");
        assertFalse(Files.exists(workspaceDir().resolve("session-1").resolve("卷宗.map.json")),
                "映射文件必须被删掉——它里面是可还原的原始信息");
    }

    @Test
    @DisplayName("映射文件是本工具留下的最要紧的东西：--all 之后逐个核对全没了")
    void removesEveryMapFile() throws Exception {
        seedEverything();
        // 再塞几个不同名字的映射，模拟真实工作区里的多个任务
        for (String n : List.of("a.map.json", "b.map.json", "甲.map.json")) {
            Files.writeString(workspaceDir().resolve("session-1").resolve(n), "{\"x\":\"y\"}");
        }
        run("--all");

        List<String> left = AppDataCleaner.listRemaining(workspaceDir());
        assertTrue(left.isEmpty(), "工作区里还剩着：" + left);
    }

    @Test
    @DisplayName("--dry-run 不删任何东西，也不该改任何状态（停服务也是改）")
    void dryRunDeletesNothing() throws Exception {
        seedEverything();
        seedAutostartPlist();
        FakeLauncher sys = new FakeLauncher();
        Result r = run(sys, "--all", "--dry-run");

        assertEquals(0, r.code(), "退出码应为 0，实际输出：\n" + r.text());
        assertTrue(Files.exists(modelDir()), "--dry-run 不该删模型包");
        assertTrue(Files.exists(trustFile()), "--dry-run 不该删信任锚");
        assertTrue(Files.exists(workspaceDir()), "--dry-run 不该删工作区");
        assertTrue(Files.exists(autostartPlist()), "--dry-run 不该删开机自启配置");
        if (isWindows()) {
            // Windows 的机制是「注册表 Run 键里的一个值」：没有文件，也没有服务可停。
            // 「查」是只读的，dry-run 允许；「删」是改，dry-run 绝不允许。
            assertFalse(sys.called("reg", "delete"),
                    "--dry-run 承诺「什么都不改」，删注册表值也是改：" + sys.calls);
        } else {
            assertFalse(sys.called("launchctl"),
                    "--dry-run 承诺「什么都不改」，停服务也是改，不该发 launchctl：" + sys.calls);
        }
        assertTrue(r.text().contains("将要删除"), "输出里应说清将要删什么，实际：\n" + r.text());
        assertTrue(r.text().contains("没有真的删"), "输出里应说明这次没真删，实际：\n" + r.text());
        // 【2026-10-25 真 Windows 实测】原来这里断言文案里有「将要停止」——
        //   那是 macOS 分支的措辞（launchd 里的常驻进程）。
        //   Windows 分支说的是「将要删除：开机自启（注册表 Run 键里的值 …）」，
        //   机制不同、措辞自然不同 —— 但**承诺的那件事是同一件**：要说清开机自启会被动。
        // → 断言改成平台中立的那一段（「开机自启」），
        //   再各自断言本平台该出现的措辞。
        assertTrue(r.text().contains("开机自启"),
                "dry-run 要说清「开机自启会被动」，实际：\n" + r.text());
        assertTrue(r.text().contains(isWindows() ? "将要删除：开机自启" : "将要停止"),
                "dry-run 的措辞不对，实际：\n" + r.text());
    }

    @Test
    @DisplayName("磁盘上什么都没有时不算失败（幂等）")
    void isIdempotentOnCleanDisk() {
        Result r = run("--all");
        assertEquals(0, r.code(), "空盘上卸载不该报错，实际输出：\n" + r.text());
        assertTrue(r.text().contains("本来就不存在"), "应说清这些位置本来就不存在，实际：\n" + r.text());
    }

    @Test
    @DisplayName("默认模式跳过的用户数据要**说出来**，不能默默留着")
    void saysWhatItKept() throws Exception {
        seedEverything();
        Result r = run();

        assertTrue(r.text().contains("保留"), "默认模式应明说保留了用户数据，实际：\n" + r.text());
        assertTrue(r.text().contains("--all"), "应告诉用户怎么一并清掉，实际：\n" + r.text());
    }

    @Test
    @DisplayName("不认识参数一律拒掉，退出码 2")
    void rejectsUnknownArgument() {
        Result r = run("--乱写");
        assertEquals(2, r.code(), "退出码应为 2，实际输出：\n" + r.text());
    }

    @Test
    @DisplayName("--workspace 后面没跟目录要报错，而不是当成没给")
    void rejectsDanglingWorkspace() {
        Result r = run("--workspace");
        assertEquals(2, r.code(), "退出码应为 2，实际输出：\n" + r.text());
        assertTrue(r.text().contains("--workspace"), "应说清是哪个参数有问题，实际：\n" + r.text());
    }

    @Test
    @DisplayName("指定 --workspace 时清的是那个目录，不是默认那个")
    void honoursExplicitWorkspace() throws Exception {
        seedEverything();
        Path other = home.resolve("自定义工作区");
        Files.createDirectories(other.resolve("s1"));
        Files.writeString(other.resolve("s1").resolve("x.map.json"), "{}");

        Result r = run("--all", "--workspace", other.toString());

        assertEquals(0, r.code(), "退出码应为 0，实际输出：\n" + r.text());
        assertFalse(Files.exists(other), "被指定的目录应被删掉");
        assertTrue(Files.exists(workspaceDir()),
                "没被指定的工作区不该被删——清错了地方比没清更糟");
    }

    @Test
    @DisplayName("绝不删不属于本工具的东西：用户主目录下的其他文件必须原样不动")
    void neverTouchesUnrelatedFiles() throws Exception {
        seedEverything();
        Path unrelated = home.resolve("我的合同.docx");
        Files.writeString(unrelated, "重要");
        Path unrelatedDir = home.resolve("照片");
        Files.createDirectories(unrelatedDir);
        Files.writeString(unrelatedDir.resolve("1.jpg"), "x");

        run("--all");

        assertTrue(Files.exists(unrelated), "用户主目录下的文件不该被删");
        assertTrue(Files.exists(unrelatedDir.resolve("1.jpg")), "用户主目录下的目录不该被删");
    }

    @Test
    @DisplayName("符号链接：删链接本身，**不删它指向的目录里的东西**")
    void deletesSymlinkItselfNotItsTarget() throws Exception {
        // 目标目录在**工作区之外**（模拟用户把工作区指到某个共享目录）。
        Path target = home.resolve("共享目录");
        Files.createDirectories(target);
        Path keep = target.resolve("别人的文件.txt");
        Files.writeString(keep, "不能动");

        // 工作区里放一个指向它的符号链接
        Files.createDirectories(workspaceDir());
        Path link = workspaceDir().resolve("链接");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // 这台机器/这个文件系统不支持符号链接 → 前提不成立，跳过并说明。
            // 不能静默跳过（否则这条断言就永远为真）。
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "这台机器建不了符号链接，跳过：" + e.getMessage());
        }

        run("--all");

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "链接本身应该被删掉");
        assertTrue(Files.exists(keep),
                "**链接指向的目录里的东西不能被删**——那不属于本工具");
    }

    @Test
    @DisplayName("清单的完整性：手写的期望路径与实现的清单逐条对上")
    void inventoryMatchesHandWrittenExpectation() {
        List<AppDataCleaner.Target> targets =
                AppDataCleaner.inventory(home, tmp, workspaceDir());

        List<Path> paths = targets.stream().map(AppDataCleaner.Target::path).toList();
        // 手写期望（不复用被测实现的任何常量）
        assertTrue(paths.contains(modelDir()),
                "清单里应包含模型包目录，实际：" + paths);
        assertTrue(paths.contains(trustFile()),
                "清单里应包含信任锚文件，实际：" + paths);
        assertTrue(paths.contains(autostartPlist()),
                "清单里应包含开机自启配置（只删文件不卸载服务的话，重启后它还会自己起来），实际：" + paths);
        assertTrue(paths.contains(workspaceDir()),
                "清单里应包含工作区目录，实际：" + paths);
        assertEquals(4, paths.size(), "清单应正好 4 项，多了说明有没被清掉的东西：");
    }

    @Test
    @DisplayName("分级：模型包与信任锚是应用数据，工作区是用户数据")
    void scopesAreRight() {
        List<AppDataCleaner.Target> targets =
                AppDataCleaner.inventory(home, tmp, workspaceDir());

        for (AppDataCleaner.Target t : targets) {
            if (t.path().equals(workspaceDir())) {
                assertEquals(AppDataCleaner.Scope.USER, t.scope(),
                        "工作区必须是用户数据（默认不删）");
            } else {
                assertEquals(AppDataCleaner.Scope.APP, t.scope(),
                        "模型包与信任锚是应用数据：" + t.path());
            }
        }
    }

    @Test
    @DisplayName("默认工作区目录与服务端一致（同一个事实只定义一次）")
    void defaultWorkspaceNameMatchesServer() {
        assertEquals("litemask-workspace",
                AppDataCleaner.defaultWorkspaceDir(tmp).getFileName().toString());
        assertNotEquals(tmp, AppDataCleaner.defaultWorkspaceDir(tmp),
                "默认工作区应该是 tmp 下的一个子目录，不能就是 tmp 本身");
        // **与服务端的定义逐字相同**——不是「碰巧一样」。
        // 这条断言盯的是「AppDataCleaner 不要自己再写一遍目录名」。
        assertEquals(ServeCommand.defaultWorkspaceDir().getFileName(),
                AppDataCleaner.defaultWorkspaceDir(tmp).getFileName(),
                "卸载清的工作区与服务端用的工作区必须同一个目录名");
    }

    // ------------------------------------------------------------------
    // 开机自启：卸载必须两件事都做
    // ------------------------------------------------------------------

    @Test
    @DisplayName("开机自启配置属于应用数据：默认模式（不加 --all）也要删掉它")
    void removesAutostartPlistWithoutAll() throws Exception {
        seedAutostartPlist();

        Result r = run();

        assertEquals(0, r.code(), "退出码应为 0，实际输出：\n" + r.text());
        assertFalse(Files.exists(autostartPlist()),
                "开机自启配置必须删掉——留着它，下次登录它还会自己起来");
        assertTrue(r.text().contains("开机自启配置"),
                "输出里应说清删了什么，实际：\n" + r.text());
    }

    @Test
    @DisplayName("卸载必须先把开机自启的常驻项停掉（只删文件 = 程序没了但它下次登录还会自己起来）")
    void stopsAutostartService() throws Exception {
        seedAutostartPlist();
        FakeLauncher sys = new FakeLauncher();
        sys.watch = autostartPlist();

        Result r = run(sys);

        assertEquals(0, r.code(), "退出码应为 0，实际输出：\n" + r.text());
        if (isWindows()) {
            // 【2026-10-25 真 Windows 实测】Windows 上没有 launchd，
            //   开机自启是「注册表 Run 键里的一个值」——
            //   卸载必须把它删掉，否则下次登录它还会自己起来。
            //   而且必须**先查在不在**再删：不知道在不在就删 = 动别人的东西。
            assertTrue(sys.called("reg", "query"),
                    "删之前必须先查那个值在不在：" + sys.calls);
            assertTrue(sys.called("reg", "delete"),
                    "必须删掉注册表 Run 键里的值，否则下次登录它还会自己起来：" + sys.calls);
            boolean own = false;
            for (List<String> c : sys.calls) {
                if (c.contains("delete") && c.contains("com.litemask.lightmeter")) {
                    own = true;
                }
            }
            assertTrue(own, "只能删本工具自己的那个值：" + sys.calls);
        } else {
            assertTrue(sys.called("launchctl", "bootout"),
                    "必须 bootout，否则 launchd 里还留着常驻服务：" + sys.calls);
            // 只能停本工具自己的服务，不能误伤别人的。
            boolean own = false;
            for (List<String> c : sys.calls) {
                if (c.contains("bootout")
                        && c.stream().anyMatch(s -> s.endsWith("/com.litemask.lightmeter"))) {
                    own = true;
                }
            }
            assertTrue(own, "只能停本工具自己的服务：" + sys.calls);
            // 停服务时 plist 还得在：实现是「先看文件在不在、在才去停」。
            // 顺序颠倒的话会先删掉文件 → 实现以为没装 → **服务永远停不下来**。
            assertEquals(Boolean.TRUE, sys.watchPresentAtBootout,
                    "bootout 的时候 plist 应当还在（否则服务停不掉）：" + sys.calls);
        }
    }

    @Test
    @DisplayName("没装开机自启时不该去动 launchd（不要碰别人的服务）")
    void doesNotTouchLaunchdWhenNotInstalled() {
        FakeLauncher sys = new FakeLauncher();

        Result r = run(sys);

        assertEquals(0, r.code(), "退出码应为 0，实际输出：\n" + r.text());
        assertFalse(sys.called("launchctl"),
                "没装自启就不该发任何 launchctl 命令：" + sys.calls);
    }

    @Test
    @DisplayName("三参数入口（走真实 launchd 通道）也能跑通")
    void realLauncherEntryPointWorks() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);

        int code = UninstallCommand.run(new String[]{"--all"}, ps, ps);

        assertEquals(0, code, "实际输出：\n" + buf.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Windows 侧：开机自启是「注册表里的一个值」，不是 launchd 服务
    // ------------------------------------------------------------------

    /**
     * 假 {@code reg.exe}：只模拟「查得到 / 查不到 / 删得掉」三件事。
     *
     * <p><b>为什么单独一个</b>：macOS 侧那个假实现是「记录发过哪些 launchctl 命令」，
     * 两边要断言的东西根本不同，混在一个类里会互相迁就。
     */
    private static final class FakeRegLauncher implements AutostartCommand.Launcher {
        final List<List<String>> calls = new ArrayList<>();
        /** 注册表里现在有的值（值名 → 内容）。 */
        final java.util.Map<String, String> registry = new java.util.LinkedHashMap<>();
        /** 模拟 reg.exe 根本跑不起来。 */
        boolean regMissing = false;

        @Override
        public int exec(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return 0;
        }

        @Override
        public int execQuiet(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            if (regMissing) {
                return -1;
            }
            if (cmd.contains("delete")) {
                registry.remove(cmd.get(cmd.indexOf("/v") + 1));
                return 0;
            }
            return 0;
        }

        @Override
        public String output(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            return "";
        }

        @Override
        public AutostartCommand.ExecResult execCapture(List<String> cmd) {
            calls.add(List.copyOf(cmd));
            if (regMissing) {
                return new AutostartCommand.ExecResult(-1, "");
            }
            String name = cmd.get(cmd.indexOf("/v") + 1);
            String data = registry.get(name);
            if (data == null) {
                return new AutostartCommand.ExecResult(1,
                        "ERROR: The system was unable to find the specified registry key or value.\n");
            }
            return new AutostartCommand.ExecResult(0,
                    "    " + name + "    REG_SZ    " + data + "\n");
        }

        boolean calledWith(String... needle) {
            for (List<String> c : calls) {
                if (c.containsAll(List.of(needle))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 跑一次 Windows 侧的 uninstall。 */
    private Result runWindows(FakeRegLauncher sys, String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int code = UninstallCommand.run(args, ps, ps, sys, "Windows 11");
        return new Result(code, buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Windows 上卸载必须删掉注册表 Run 键里的那个值（留着它，下次登录还会自己起来）")
    void windowsUninstallRemovesRunKeyValue() {
        FakeRegLauncher sys = new FakeRegLauncher();
        sys.registry.put("com.litemask.lightmeter",
                "\"C:\\Program Files\\LightMeter\\LightMeter.exe\" serve --no-browser --port 8090");

        Result r = runWindows(sys);

        assertEquals(0, r.code(), "实际输出：\n" + r.text());
        assertFalse(sys.registry.containsKey("com.litemask.lightmeter"),
                "开机自启的注册表值必须删掉");
        assertTrue(sys.calledWith("reg", "delete"), "应该发 reg delete：" + sys.calls);
    }

    @Test
    @DisplayName("Windows 上卸载不去碰 launchd（那边根本没有 launchd）")
    void windowsUninstallNeverTouchesLaunchd() {
        FakeRegLauncher sys = new FakeRegLauncher();
        sys.registry.put("com.litemask.lightmeter", "x");

        runWindows(sys);

        assertFalse(sys.calledWith("launchctl"),
                "不该发任何 launchctl 命令：" + sys.calls);
    }

    @Test
    @DisplayName("Windows 上没装开机自启时不发删除命令（不要碰别人的东西）")
    void windowsUninstallWhenNotInstalledTouchesNothing() {
        FakeRegLauncher sys = new FakeRegLauncher();

        Result r = runWindows(sys);

        assertEquals(0, r.code(), "实际输出：\n" + r.text());
        assertFalse(sys.calledWith("reg", "delete"),
                "没装就不该发删除命令：" + sys.calls);
    }

    @Test
    @DisplayName("Windows 上 --dry-run 只说不改：不删注册表值")
    void windowsDryRunChangesNothing() {
        FakeRegLauncher sys = new FakeRegLauncher();
        sys.registry.put("com.litemask.lightmeter", "x");

        Result r = runWindows(sys, "--all", "--dry-run");

        assertEquals(0, r.code(), "实际输出：\n" + r.text());
        assertTrue(sys.registry.containsKey("com.litemask.lightmeter"),
                "--dry-run 承诺「什么都不改」，删注册表值也是改");
        assertFalse(sys.calledWith("reg", "delete"), "--dry-run 不该发删除命令：" + sys.calls);
        assertTrue(r.text().contains("开机自启"), "要说清会动开机自启，实际输出：\n" + r.text());
    }

    @Test
    @DisplayName("Windows 上查不了注册表时要说「可能还留着」，不能静默当清干净了")
    void windowsUninstallSaysWhenRegistryUnreachable() {
        FakeRegLauncher sys = new FakeRegLauncher();
        sys.regMissing = true;

        Result r = runWindows(sys);

        assertTrue(r.text().contains("可能还留着"), "实际输出：\n" + r.text());
    }
}
