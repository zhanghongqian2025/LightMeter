import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows 开机自启（注册表 Run 键）：端到端驱动。<b>只在 Windows 上真跑</b>。
 *
 * <p><b>它验的是什么</b>：<b>reg.exe 的真实行为</b>——
 * 「我们让 {@code reg add} 写进去的那条命令行，注册表里到底存成了什么」。
 * 这是本块<b>唯一</b>没法在 macOS 上验的东西：
 * 单测能逐字钉死「我们发出去的字符串」，但钉不死「reg.exe 怎么解释它」。
 *
 * <p><b>判据独立于被测实现</b>（这是 e2e 的头号纪律）：
 * <ul>
 *   <li>读注册表用 {@code reg.exe}，<b>不经产品代码</b>；</li>
 *   <li>期望的命令行在本文件里<b>自己拼一遍</b>，不复用
 *       {@code WindowsRunKey.commandData}；</li>
 *   <li>解析 {@code reg query} 输出的那几行代码也是本文件<b>自己写的</b>，
 *       不复用 {@code WindowsRunKey.parseValue} ——
 *       复用只能证明「产品和自己一致」，证明不了「产品对」。</li>
 * </ul>
 *
 * <p><b>为什么额外做一次「转义形式探测」</b>：
 * 我们查证过（微软 {@code reg add /?} 原文 + JDK 的 Windows argv 转义规则），
 * 认定「数据里放真的双引号、由两边各转义一次」是对的，
 * 但<b>没在真 Windows 上验过</b>。
 * 探测会分别用两种写法各写一个探针值再读回来，
 * <b>直接告诉我们这台机器上 reg.exe 到底认哪一种</b> ——
 * 于是第一次在真机上跑就能把这件事钉死，而不是「跑一次、看着像对了就算」。
 *
 * <p><b>用法</b>：
 * <pre>
 *   java E2eWindowsAutostart &lt;LightMeter.exe 的完整路径&gt; [端口]
 * </pre>
 *
 * <p><b>退出码</b>：0 = 全部通过；1 = 有断言失败；77 = 环境不具备（不是 Windows）。
 */
public final class E2eWindowsAutostart {

    /** 当前用户的 Run 键。<b>手写</b>，不复用产品代码里的常量。 */
    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    /** 注册表值名。<b>手写</b>，不复用产品代码里的常量。 */
    private static final String VALUE_NAME = "com.litemask.lightmeter";

    /** 探测用的另一个值名（跟产品无关，跑完删掉）。 */
    private static final String PROBE_NAME = "com.litemask.lightmeter.probe";

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("windows")) {
            System.out.println("SKIP 这不是 Windows（os.name=" + os + "）。"
                    + "Windows 开机自启的端到端必须在真 Windows 上跑。");
            System.exit(77);
        }
        if (args.length < 1) {
            System.out.println("FAIL 用法: java E2eWindowsAutostart <LightMeter.exe> [端口]");
            System.exit(1);
        }
        String exeArg = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8090;

        Path exe = Path.of(exeArg);
        if (!Files.isRegularFile(exe)) {
            System.out.println("FAIL 找不到可执行文件: " + exe);
            System.exit(1);
        }

        // **期望值自己拼一遍**（判据独立于被测实现）。
        String expected = "\"" + exe + "\" serve --no-browser --port " + port;

        try {
            run(exe, expected, port);
        } catch (Exception e) {
            // 【为什么要自己接住】不让异常直接冲出 main：
            //   一来退出码会变成 JVM 的 1，看不出是「哪一条断言」；
            //   二来更关键 —— finally 里若抛异常会**顶掉**这里的真因，
            //   「install 失败」就被「清理失败」盖住了。
            fail++;
            System.out.println("FAIL 端到端过程中抛出异常：" + e);
            e.printStackTrace(System.out);
        } finally {
            // 收尾：不管成没成，都不能把注册表里的东西留在别人的机器上。
            // 【为什么清理要单独 try】finally 里抛出的异常会**顶掉**原来的异常 ——
            // 真因（比如 install 失败）就被「清理失败」盖住了。
            for (String name : new String[]{VALUE_NAME, PROBE_NAME}) {
                try {
                    regDelete(name);
                } catch (Exception e) {
                    fail++;
                    System.out.println("FAIL 清理注册表值 " + name + " 失败：" + e);
                }
            }
        }

        System.out.println();
        System.out.println("=== e2e-windows-autostart: pass=" + pass + " fail=" + fail + " ===");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * 按 Run 键里存的那条命令把服务拉起来。
     *
     * <p>命令形态：{@code "C:\...\LightMeter.exe" serve --no-browser --port 8090}。
     * 这里**按 Run 键的解析规则**拆：开头的双引号包住可执行文件路径，
     * 剩下的按空白拆成参数。
     *
     * <p><b>为什么不能用 {@code cmd /c} 跑</b>：那会再过一层 shell 的引号规则，
     * 跟我们正在验的那条命令就不是同一条了 —— 验的就不是 Windows 登录时会执行的东西。
     *
     * @param cmd Run 键里的命令
     * @return 起起来的进程；起不来返回 null
     */
    private static Process startServiceFromRunKey(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return null;
        }
        List<String> argv = new ArrayList<>();
        String rest = cmd.trim();
        if (rest.startsWith("\"")) {
            int close = rest.indexOf('"', 1);
            if (close < 0) {
                return null;
            }
            argv.add(rest.substring(1, close));
            rest = rest.substring(close + 1).trim();
        } else {
            int sp = rest.indexOf(' ');
            if (sp < 0) {
                argv.add(rest);
                rest = "";
            } else {
                argv.add(rest.substring(0, sp));
                rest = rest.substring(sp + 1).trim();
            }
        }
        if (!rest.isEmpty()) {
            for (String a : rest.split("\\s+")) {
                if (!a.isEmpty()) {
                    argv.add(a);
                }
            }
        }
        try {
            return new ProcessBuilder(argv).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception e) {
            return null;
        }
    }

    /** 等服务真的能应答 {@code /api/health}。 */
    private static boolean waitHealthy(int port) {
        for (int i = 0; i < 40; i++) {
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(
                                "http://127.0.0.1:" + port + "/api/health")
                                .openConnection();
                c.setConnectTimeout(800);
                c.setReadTimeout(800);
                int code = c.getResponseCode();
                if (code == 200) {
                    try (var in = c.getInputStream()) {
                        String body = new String(in.readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (body.contains("\"ok\"")) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 还没起来，继续等
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void run(Path exe, String expected, int port) throws Exception {        System.out.println("== 1. 前置：注册表里本来不该有我们的值 ==");
        Result before = regQuery(VALUE_NAME);
        check("前置：目标值本来不存在（不该在别人的机器上留下前人的残留）",
                before.code != 0, "reg query 退出码应为非 0，实际 " + before.code);

        System.out.println("== 2. 探测：reg.exe 到底认哪一种引号写法 ==");
        probeQuotingForms(exe, port);

        System.out.println("== 3. 装：走产品自己的 CLI ==");
        Result install = runExe(exe, "autostart", "install", "--app", exe.toString());
        System.out.println("    退出码 " + install.code);
        System.out.println("    " + install.text.replace("\n", "\n    "));
        check("install 退出码为 0", install.code == 0, "实际 " + install.code);
        check("install 说装了", install.text.contains("已安装开机自启")
                        || install.text.contains("已经是这个配置"),
                "输出里没有「已安装开机自启」：" + install.text);

        System.out.println("== 4. 读回注册表：存进去的内容必须与预期逐字相同 ==");
        Result q = regQuery(VALUE_NAME);
        check("reg query 查得到这个值（退出码 0）", q.code == 0, "实际 " + q.code);
        String stored = q.code == 0 ? parseValue(q.text, VALUE_NAME) : null;
        if (stored == null) {
            check("能解析出值的内容", false, "解析不出来。reg query 的原始输出：\n" + q.text
                    + "\n（原始字节的十六进制）\n" + hex(q.bytes));
        } else {
            check("注册表里存的内容与预期逐字相同", expected.equals(stored),
                    "期望 [" + expected + "]\n实际 [" + stored + "]");
        }

        System.out.println("== 5. 按 Run 键里那条命令真的把服务拉起来，再看状态 ==");
        // 【为什么必须真的启动服务】
        //   `autostart status` 的退出码 0 的含义是「**服务真的在对外服务**」，
        //   不是「注册表里有这条目」—— 这正是本项目修过的一个真缺陷
        //   （原来「launchd 认得这个服务」就被当成「服务在跑」，用户据此以为能用，
        //     界面却打不开）。
        // → 所以这里**按注册表里存的那条命令**把服务拉起来，
        //   等它真的能应答，再看 status。
        //   这一步同时就是「开机登录时会发生什么」的**真机证据**：
        //   登录时 Windows 执行的就是这条命令。
        String cmd = stored != null ? stored : expected;
        check("拿得到 Run 键里那条命令（登录时 Windows 执行的就是它）",
                cmd != null && cmd.contains("serve"), "命令：" + cmd);
        Process svc = startServiceFromRunKey(cmd);
        check("按 Run 键的命令把服务拉起来了", svc != null,
                "启动失败：" + (svc == null ? "（没起来）" : ""));
        boolean healthy = waitHealthy(port);
        check("服务真的在应答 /api/health（这才算「在对外服务」）", healthy,
                "端口 " + port + " 上没等到 /api/health");
        Result status = runExe(exe, "autostart", "status");
        System.out.println("    " + status.text.replace("\n", "\n    "));
        check("status 退出码为 0（服务确实在跑）", status.code == 0, "实际 " + status.code);
        check("status 说已安装", status.text.contains("已安装"), "输出：" + status.text);
        check("status 报出了命令行", status.text.contains("serve"), "输出：" + status.text);
        if (svc != null) {
            svc.destroyForcibly();
            svc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        System.out.println("== 6. 卸 ==");
        Result uninstall = runExe(exe, "autostart", "uninstall");
        System.out.println("    " + uninstall.text.replace("\n", "\n    "));
        check("uninstall 退出码为 0", uninstall.code == 0, "实际 " + uninstall.code);
        Result after = regQuery(VALUE_NAME);
        check("卸完注册表里查不到这个值了", after.code != 0, "reg query 退出码 " + after.code);

        System.out.println("== 7. 幂等：再卸一次不该报错 ==");
        Result again = runExe(exe, "autostart", "uninstall");
        check("再卸一次退出码为 0", again.code == 0, "实际 " + again.code);
        check("再卸一次说「本来就没装」", again.text.contains("本来就没装"), "输出：" + again.text);

        Result status2 = runExe(exe, "autostart", "status");
        check("再查状态说「未安装」", status2.text.contains("未安装"), "输出：" + status2.text);
    }

    /**
     * 探测 reg.exe 认哪一种引号写法。
     *
     * <p><b>为什么必须探</b>：我们按微软 {@code reg add /?} 的说明与 JDK 的 argv 转义规则，
     * 认定「数据里放真的双引号」是对的；但这没在真机验过。
     * 两种写法各写一个探针值再读回来，<b>直接给出这台机器上的答案</b>。
     */
    private static void probeQuotingForms(Path exe, int port) throws Exception {
        String plain = "\"" + exe + "\" serve --no-browser --port " + port;
        // 「自己先加反斜杠」那一种：如果 JDK 与 reg.exe 各转义一次，
        // 这一种会被转义两次，存进去的是 \" 两个字符。
        String preEscaped = plain.replace("\"", "\\\"");

        probeOne("写法甲（数据里放真的双引号）", plain);
        probeOne("写法乙（数据里放反斜杠+双引号）", preEscaped);
    }

    private static void probeOne(String title, String data) throws Exception {
        regDelete(PROBE_NAME);
        Result add = regRaw("add", RUN_KEY, "/v", PROBE_NAME, "/t", "REG_SZ", "/d", data, "/f");
        Result q = regQuery(PROBE_NAME);
        String got = q.code == 0 ? parseValue(q.text, PROBE_NAME) : null;
        System.out.println("    " + title);
        System.out.println("      reg add 退出码 " + add.code);
        System.out.println("      写进去的   [" + data + "]");
        System.out.println("      读回来的   " + (got == null ? "(读不到)" : "[" + got + "]"));
        System.out.println("      → 本机 reg.exe " + (data.equals(got) ? "认这一种" : "**不**认这一种"));
        regDelete(PROBE_NAME);
    }

    // ------------------------------------------------------------------
    // reg.exe 通道
    // ------------------------------------------------------------------

    /** 一次命令执行的结果。 */
    private static final class Result {
        final int code;
        final String text;
        final byte[] bytes;

        Result(int code, String text, byte[] bytes) {
            this.code = code;
            this.text = text;
            this.bytes = bytes;
        }
    }

    private static Result regRaw(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("reg");
        for (String a : args) {
            cmd.add(a);
        }
        return run(cmd);
    }

    private static Result regQuery(String valueName) throws Exception {
        return regRaw("query", RUN_KEY, "/v", valueName);
    }

    private static Result regDelete(String valueName) throws Exception {
        return regRaw("delete", RUN_KEY, "/v", valueName, "/f");
    }

    /** 跑产品的 CLI。 */
    private static Result runExe(Path exe, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        for (String a : args) {
            cmd.add(a);
        }
        return run(cmd);
    }

    private static Result run(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        byte[] buf = proc.getInputStream().readAllBytes();
        int code = proc.waitFor();
        return new Result(code, new String(buf, StandardCharsets.UTF_8), buf);
    }

    /**
     * 从 {@code reg query} 的输出里取出值的内容。<b>本文件自己写的</b>，不复用产品代码。
     *
     * <p>形态（reg query 的真实输出）：
     * <pre>
     * HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
     *     com.litemask.lightmeter    REG_SZ    "C:\Program Files\LightMeter\LightMeter.exe" serve ...
     * </pre>
     *
     * @param text      reg query 的输出
     * @param valueName 值名
     * @return 值的内容；取不到时返回 null
     */
    static String parseValue(String text, String valueName) {
        if (text == null || text.isBlank() || valueName == null) {
            return null;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (!line.startsWith(valueName)) {
                continue;
            }
            if (line.length() > valueName.length()
                    && !Character.isWhitespace(line.charAt(valueName.length()))) {
                continue;
            }
            String rest = line.substring(valueName.length()).trim();
            int sp = rest.indexOf(' ');
            if (sp <= 0) {
                return null;
            }
            String data = rest.substring(sp + 1).trim();
            return data.isEmpty() ? null : data;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
            pass++;
        } else {
            System.out.println("FAIL " + name + " —— " + detail);
            fail++;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x ", x));
        }
        return sb.toString();
    }

    private E2eWindowsAutostart() {
    }
}
