package com.litemask.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 开机自启：注册表 <b>Run 键</b>的约定（纯函数：不碰注册表、不调外部命令）。
 *
 * <p><b>为什么单独一个类</b>：与 macOS 侧的 {@link LaunchAgentPlist} 同一个理由 ——
 * 「开机自启」的全部约定就是「写哪个键、叫什么名字、命令行长什么样」，
 * 它必须能<b>脱离 Windows 单测</b>：这里只产出字符串与命令列表，
 * 于是「注册表那一行到底写成了什么」可以逐字断言，不必真往注册表里写一次。
 *
 * <p><b>为什么用 Run 键而不是任务计划程序</b>：任务计划程序能做的事更多
 * （延迟启动、按用户/系统触发、失败重试），但它要建 XML、注册任务、还要处理
 * 「任务被删了/被禁用了」这些状态 —— 判定口径比 Run 键复杂得多。
 * Run 键只有一个动作：「登录时执行这条命令行」，语义与本工具要的完全一样。
 * 代价是<b>没有自愈</b>：进程崩了不会自己回来（macOS 侧的 KeepAlive 能做到）。
 * 这条取舍写进了阶段文档的已知限制。
 *
 * <p><b>为什么是当前用户（HKCU）而不是本机（HKLM）</b>：
 * 写 HKLM 要管理员权限，普通用户会看到一个「需要提权」的弹窗；
 * 而自启这件事本来就<b>属于用户自己</b>——换个人登录就不该跟着起。
 * HKCU 不需要提权，也不需要管理员来卸载。
 *
 * <p><b>为什么命令行里要带引号</b>：Windows 的 Run 键的值是一整条命令行，
 * 解析器按「引号内是一个可执行文件路径」的规则切分。
 * 装到 {@code C:\Program Files\...} 这种带空格的路径下时，
 * 不加引号会被切成 {@code C:\Program} 和 {@code Files\...} 两段 →
 * 登录时弹出一个「找不到文件」的框。→ 可执行文件路径一律加双引号。
 *
 * <p><b>这里的双引号为什么<b>不</b>自己加反斜杠（2026-10-21 查证）</b>：
 * 微软 {@code reg add /?} 自己的说明写着 ——
 * 「要传一个含引号的值，必须在每个引号前加一个反斜杠（{@code \}）作为转义」，
 * 并给了例子：{@code reg add HKLM\Software\sav /v test /t REG_SZ /d \"%userprofile%\"}
 * 会把引号<b>保住</b>。由此可知 {@code reg.exe} 的两条规则：
 * <ol>
 *   <li>反斜杠<b>只</b>转义引号（路径里的 {@code \} 原样保留）；</li>
 *   <li>{@code /d} 取<b>一个</b> token，外层引号被剥掉。</li>
 * </ol>
 * 而 JDK 的 {@code ProcessBuilder} 在 Windows 上拼命令行时，
 * 也会把参数里的裸双引号转义成 {@code \"}。
 * → 所以<b>我们传给 argv 的那份数据里放真的双引号</b>：
 * JDK 转义一次、{@code reg.exe} 反转义一次，正好回到我们要的内容。
 * 如果我们自己先加了反斜杠，就会被转义两次，存进去的是 {@code \"} 两个字符。
 *
 * <p><b>未在真 Windows 上验证的部分</b>：本类只保证「我们写出去的字符串长这样」，
 * 不保证 {@code reg.exe} 在真机上的行为与预期完全一致
 * （例如 {@code reg query} 失败时的退出码、{@code /d} 里含引号时的解析细节）。
 * 所以 {@link WindowsAutostart} 装完会<b>读回来逐字核对</b>，
 * 对不上就报失败并打印「预期 vs 实际」——
 * 真机上如果转义假设错了，那会当场暴露，而不是留一个装上了却不工作的自启。
 * 那一段要靠 {@code tools/E2eWindowsAutostart.java} 在真 Windows 上兑现。
 */
public final class WindowsRunKey {

    /**
     * 当前用户的 Run 键（登录时执行这里的每个值）。
     *
     * <p>用 {@code HKCU} 而不是完整的 {@code HKEY_CURRENT_USER}：
     * 两者 {@code reg.exe} 都认，前者短、也更常见。
     */
    public static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    /**
     * 注册表值的名字（即 {@code --label}）。
     *
     * <p><b>为什么与 macOS 侧用同一个默认值</b>：两边默认值不一样的话，
     * 卸载那条路径就得知道「当前平台用的是哪个默认值」，
     * 多一个分支就多一处会漂移的地方。
     * 注册表值名允许带点（{@code com.litemask.lightmeter} 完全合法），
     * 所以两个平台用同一个字符串，卸载路径也就不用分平台。
     */
    public static final String DEFAULT_VALUE_NAME = LaunchAgentPlist.DEFAULT_LABEL;

    /** jpackage app-image 形态下的可执行文件名。 */
    public static final String EXE_NAME = "LightMeter.exe";

    /** 默认端口。与 macOS 侧一致（同一个端口号，用户不用记两套）。 */
    public static final int DEFAULT_PORT = LaunchAgentPlist.DEFAULT_PORT;

    private WindowsRunKey() {
    }

    /**
     * 写进 Run 键的那条命令行。
     *
     * <p>形态：{@code "C:\...\LightMeter.exe" serve --no-browser --port 8090}
     *
     * <p><b>为什么带 {@code --no-browser}</b>：与 macOS 侧同一个理由 ——
     * 开机登录时弹浏览器会打断用户，且那一瞬间用户多半还没打算用这个工具。
     *
     * @param exe  可执行文件的完整路径
     * @param port 固定端口
     * @return 命令行字符串
     */
    public static String commandData(Path exe, int port) {
        if (exe == null) {
            throw new IllegalArgumentException("可执行文件路径不能为空。");
        }
        if (!isAbsolutePath(exe.toString())) {
            throw new IllegalArgumentException("可执行文件必须给绝对路径: " + exe);
        }
        return quoteExecutable(exe) + " serve --no-browser --port " + port;
    }

    /**
     * 判断这个路径串是不是「绝对路径」。
     *
     * <p><b>为什么必须绝对</b>：Run 键里的值是一条命令行，
     * Windows 在登录时解析它；相对路径会被按「当时的工作目录」解析 ——
     * 而登录时的工作目录是不确定的（可能是 {@code C:\Windows\System32}）。
     * 结果就是「自启条目在，但永远起不来」。
     *
     * <p><b>为什么用字符串判断而不是 {@code Path.isAbsolute()}</b>：
     * {@code Path.isAbsolute()} 的答案取决于<b>运行这个代码的平台</b> ——
     * 在 macOS 上，{@code C:\Program Files\...} 会被当成一个<b>相对</b>路径
     * （macOS 的路径分隔符是 {@code /}，{@code \} 不是分隔符），
     * 于是「在 macOS 上测 Windows 路径」这件事会得到假阴性。
     * 而这条判据的<b>对象本来就是 Windows 路径</b>，
     * 所以按字符串形态判断才是不依赖平台的。
     *
     * <p>认三种形态：Windows 盘符路径（{@code C:\...}）、
     * Windows 根路径 / UNC（{@code \...}）、POSIX 绝对路径（{@code /...}）。
     * 最后一种是为了让本项目的单测能在 macOS 上用临时目录当「安装目录」。
     *
     * @param path 路径串
     * @return 是绝对路径就返回 true
     */
    public static boolean isAbsolutePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.charAt(0) == '/' || path.charAt(0) == '\\') {
            return true;
        }
        return path.length() >= 3
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && (path.charAt(2) == '\\' || path.charAt(2) == '/');
    }

    /**
     * 给可执行文件路径加引号。
     *
     * @param exe 可执行文件路径
     * @return 带双引号的路径
     * @throws IllegalArgumentException 路径里含双引号时
     *         （Windows 文件名里本来就不允许出现双引号，出现即说明路径有问题）
     */
    public static String quoteExecutable(Path exe) {
        String s = exe.toString();
        if (s.indexOf('"') >= 0) {
            throw new IllegalArgumentException("可执行文件路径里不能有双引号: " + s);
        }
        return "\"" + s + "\"";
    }

    /**
     * {@code reg add} 的命令。
     *
     * <p>{@code /f} = 不询问「确实要写吗」——自启是用户明确要求的动作，
     * 而且在登录脚本里没人能回答那个提问。
     *
     * <p><b>【2026-10-25 真 Windows 实测踩到的真缺陷】</b>：
     * {@code reg.exe} 会**吃掉** {@code /d} 里的双引号 ——
     * 直接把 {@code "C:\...\app.exe" serve --no-browser} 交给它，
     * 存进去的是<b>没有引号</b>的 {@code C:\...\app.exe serve --no-browser}。
     * 后果分两种，都很糟：
     * <ul>
     *   <li>路径<b>没有空格</b>（大多数用户）：值少了引号，
     *       本项目的自检会判「写进去的内容与预期不一致」→ <b>报失败</b>
     *       （哪怕它其实能跑）—— 用户看到的是「装不上自启」。</li>
     *   <li>路径<b>有空格</b>（用户名含空格，如 {@code C:\Users\John Smith\...}）：
     *       {@code reg add} <b>直接报错</b>（退出码 1）→ <b>自启根本装不上</b>。</li>
     * </ul>
     * → 交给 reg.exe 的值里把双引号<b>转义</b>（{@code \"}）：
     *   reg.exe 会把 {@code \"} 还原成 {@code "} 存进注册表。
     *   （本机实测：转义后存进去的值带引号，与预期逐字相同。）
     *
     * <p>注意：转义只发生在<b>交给 reg.exe 的那个参数</b>里；
     * 注册表里该长什么样、比对时该期待什么，都还是
     * {@link #commandData} 那个形态（带引号）。
     *
     * @param valueName 注册表值名
     * @param data      命令行内容（{@link #commandData} 的形态）
     * @return 命令与参数（可直接交给 ProcessBuilder）
     */
    public static List<String> addCommand(String valueName, String data) {
        return List.of("reg", "add", RUN_KEY, "/v", valueName, "/t", "REG_SZ", "/d",
                escapeQuotesForReg(data), "/f");
    }

    /**
     * 把值里的双引号转义成 {@code \"}，让 reg.exe 把它当**字面引号**存进注册表。
     *
     * <p>只处理双引号（Run 键的命令行里没有别的需要转义的字符）；
     * 反斜杠本身不动 —— Windows 路径里的 {@code \} 不是转义符。
     *
     * @param data 命令行内容
     * @return 转义后的内容
     */
    public static String escapeQuotesForReg(String data) {
        return data == null ? null : data.replace("\"", "\\\"");
    }

    /**
     * {@code reg delete} 的命令（只删这一个值，不删整个键）。
     *
     * @param valueName 注册表值名
     * @return 命令与参数
     */
    public static List<String> deleteCommand(String valueName) {
        return List.of("reg", "delete", RUN_KEY, "/v", valueName, "/f");
    }

    /**
     * {@code reg query} 的命令（查这一个值）。
     *
     * @param valueName 注册表值名
     * @return 命令与参数
     */
    public static List<String> queryCommand(String valueName) {
        return List.of("reg", "query", RUN_KEY, "/v", valueName);
    }

    /**
     * {@code --port} 参数的形状：<b>前面是空白或行首，后面紧跟一个非空白串</b>。
     *
     * <p><b>为什么要求「紧跟」</b>：{@code --port8090} 不是端口参数，
     * {@code --port} 后面没有值也不是。宽松一点就会把别的东西当成端口。
     */
    private static final Pattern PORT_ARG = Pattern.compile("(?:\\A|[ \\t])--port[ \\t]+(\\S+)");

    /**
     * 从 Run 键存的那条命令行里解析出 {@code --port} 的端口号。
     *
     * <p><b>为什么必须从「已存进去的那条命令行」里读</b>：
     * 端口是「服务在哪个端口」的唯一事实。它被写进 Run 键时是命令行里的
     * {@code --port N}；要回答「服务在不在」，就得拿<b>同一个</b>端口去探。
     * → 读 plist（macOS 侧）与读 Run 键（Windows 侧）是两个平台各自的唯一真源。
     * 若改用「猜一个默认端口」，就会去探一个不是本服务在用的端口 ——
     * 探不通 → 报「不可用」，而真相是「探错了地方」，那是<b>编造事实</b>。
     *
     * <p><b>为什么先把引号里的内容抹掉再找 {@code --port}</b>：
     * 可执行文件路径是<b>带引号</b>的，路径里可以含空格，
     * 理论上也能含 {@code --port} 这几个字。抹掉引号段之后，
     * 剩下的才是真正的参数区。
     *
     * <p><b>读不到就返回 -1，不猜默认端口</b>：
     * Run 键的值可能被用户手工改过，也可能被别的程序改过。
     * 那时「端口是多少」是<b>不知道</b>，不是「应该是 8090」。
     *
     * @param commandLine Run 键里存的那条命令行
     * @return 端口号；读不到或越界时返回 -1
     */
    public static int parsePort(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return -1;
        }
        // 先把引号段整段抹掉（路径里可能有空格，甚至可能有 "--port" 这几个字）。
        String args = commandLine.replaceAll("\"[^\"]*\"", " ");
        Matcher m = PORT_ARG.matcher(args);
        if (!m.find()) {
            return -1;
        }
        String num = m.group(1);
        if (!num.matches("\\d+")) {
            return -1;
        }
        try {
            int p = Integer.parseInt(num);
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 从 Run 键存的那条命令行里取出可执行文件路径。
     *
     * <p><b>为什么需要它</b>：Windows 的 Run 键<b>没有自愈</b> ——
     * 服务不在跑时不会自己回来（macOS 侧靠 {@code KeepAlive}）。
     * → status 在「服务不可用」时必须给用户一条<b>能自己执行</b>的命令，
     * 否则用户只能干看着「不可用」而不知道该敲什么。
     *
     * <p><b>为什么只认带引号的那种</b>：{@link #commandData} 一定给路径加引号，
     * 所以「引号里的第一段」就是可执行文件路径。没有引号 → 形态不认识 → null
     * （<b>不猜</b>：猜出来的路径可能根本不存在）。
     *
     * @param commandLine Run 键里存的那条命令行
     * @return 可执行文件路径；形态不认识时返回 null
     */
    public static String parseExecutable(String commandLine) {
        if (commandLine == null) {
            return null;
        }
        int open = commandLine.indexOf('"');
        if (open < 0) {
            return null;
        }
        int close = commandLine.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        String exe = commandLine.substring(open + 1, close);
        return exe.isBlank() ? null : exe;
    }

    /**
     * 从 {@code reg query} 的输出里取出那个值的内容。
     *
     * <p>输出形态（本机 Windows 10/11 上的 {@code reg query}）：
     * <pre>
     * HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
     *     LightMeter    REG_SZ    "C:\Program Files\LightMeter\LightMeter.exe" serve --no-browser --port 8090
     * </pre>
     *
     * <p><b>为什么不用「输出里含不含某个字符串」来判断装没装</b>：
     * macOS 侧在这上面栽过 —— {@code launchctl print} 的输出格式与手册不一致，
     * 按文本匹配导致「服务明明在跑，status 却说未装载」。
     * → 这里<b>按退出码</b>判断在不在（本类只负责取值，取不到就是 null）。
     *
     * <p><b>为什么按「值名 + 空白」定位那一行</b>：值名本身不允许含空白，
     * 所以「以值名开头且后面紧跟空白」就能唯一确定是哪一行；
     * 剩下的部分里，第一个空白之前是类型（{@code REG_SZ}），之后才是数据。
     * 数据里<b>可以</b>含空格（我们的命令行就有），所以不能按空白整体切。
     *
     * @param output    {@code reg query} 的输出
     * @param valueName 注册表值名
     * @return 值的内容；找不到或格式不认识时返回 null
     */
    public static String parseValue(String output, String valueName) {
        if (output == null || output.isBlank() || valueName == null || valueName.isEmpty()) {
            return null;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (!line.startsWith(valueName)) {
                continue;
            }
            // 值名后面必须是空白或行尾 —— 否则「值名是另一个值的前缀」会误匹配。
            if (line.length() > valueName.length()
                    && !Character.isWhitespace(line.charAt(valueName.length()))) {
                continue;
            }
            String rest = line.substring(valueName.length()).trim();
            int sp = rest.indexOf(' ');
            if (sp <= 0) {
                return null;
            }
            String type = rest.substring(0, sp);
            String data = rest.substring(sp + 1).trim();
            if (type.isEmpty() || data.isEmpty()) {
                return null;
            }
            return data;
        }
        return null;
    }
}
