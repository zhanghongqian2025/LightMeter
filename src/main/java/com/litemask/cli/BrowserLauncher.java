package com.litemask.cli;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 用系统默认浏览器打开 URL，**不依赖 {@code java.awt}**。
 *
 * <h2>为什么不用 {@code java.awt.Desktop}</h2>
 * {@code java.desktop} 模块在 jlink 定制运行时里值 14.1MB，
 * 而我们用它做的事只有一件——把一个 URL 交给系统默认浏览器。
 * {@code ProcessBuilder} 调系统命令同样能做完这件事，代价是几十字节。
 * 体积硬指标是核心包 ≤90MB，14.1MB 不是可以随手放过去的零头。
 *
 * <h2>实测记录（本机 macOS arm64 / JDK 21）</h2>
 * <ul>
 *   <li>{@code open <url>} → exit 0，无输出，可行。</li>
 *   <li>系统里没有这个命令时，{@link ProcessBuilder#start()} 抛
 *       {@link IOException}（{@code Cannot run program ... error: 2}），
 *       <b>不是</b>「返回 false」。所以判据必须是 catch IOException，
 *       只判断「命令是否存在」的实现会把异常抛到调用方。</li>
 *   <li><b>子进程输出必须重定向掉</b>。实测让子进程写 20000 行，
 *       管道缓冲 65520 字节写满后子进程阻塞在 write，父进程 {@code waitFor} 等它退出——
 *       两边互等，服务起不来。{@link ProcessBuilder.Redirect#DISCARD} 直接给
 *       {@code /dev/null}，连管道都不建，这个风险从根上消失。</li>
 *   <li>{@code open} 会把<b>以 {@code -} 开头</b>的参数当成自己的选项
 *       （实测 {@code open -weird-flag} 报 {@code invalid option -- w}）。
 *       所以 URL 必须先过 {@link #isLaunchable(String)}，不能直接把字符串丢进去。</li>
 * </ul>
 *
 * <h2>失败一律吞掉</h2>
 * 打不开浏览器不该让后台服务起不来——地址已经打印在终端上，用户可以自己复制。
 * 这是 {@code Desktop} 版本一直遵守的约定，换实现不能偷偷改掉。
 */
final class BrowserLauncher {

    /** 等待子进程退出的上限。到点就强杀，不让一个外部命令卡住服务启动。 */
    private static final long EXIT_TIMEOUT_MILLIS = 2_000L;

    private BrowserLauncher() {
    }

    /**
     * 当前系统上打开 {@code url} 的命令；不认识这个系统返回空。
     *
     * <p>刻意做成纯函数：不启动任何进程，因此可以在单测里逐个平台断言。
     * 真正启动那一步留在 {@link #open(String)}。
     */
    static List<String> commandFor(String osName, String url) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            // -u 强制按 URL 处理。少了它，open 会按「文件」规则去找默认程序，
            // 对 http:// 开头的地址通常也对，但那是碰巧——一旦地址形态变了
            // （比如被网关重写成别的 scheme）就会静默地用文本编辑器打开。
            return List.of("open", "-u", url);
        }
        if (os.contains("win")) {
            // Windows 没有 open，用 ShellExecute 的 rundll32 入口。
            return List.of("rundll32", "url.dll,FileProtocolHandler", url);
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            return List.of("xdg-open", url);
        }
        return List.of();
    }

    /**
     * 这个地址能不能交给系统命令打开。
     *
     * <p>只放行 http/https。这一条同时挡住了 {@code open} 的参数注入：
     * 一个 {@code -} 开头的东西要么不以 {@code http://} 开头、要么
     * {@code "-http://…"} 整体也不以它开头，两种都会被拒。
     *
     * <p>第一版这里还额外写了一条 {@code url.startsWith("-")}，看起来是更早的意图，
     * 实际上在协议检查之后**永远不成立**——对照实验把它去掉，测试全绿。
     * 一段不改变任何可达行为的代码只会让人误以为它被测过，所以删了。
     */
    static boolean isLaunchable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /**
     * 尽力打开，失败一律吞掉。返回是否真的启动了进程。
     *
     * <p>返回值只用于日志与测试，调用方不因它失败而中断。
     */
    static boolean open(String url) {
        if (!isLaunchable(url)) {
            return false;
        }
        return open(url, commandFor(System.getProperty("os.name"), url));
    }

    /**
     * 直接给定命令启动。为的是让「命令不存在」这条路径**在任何平台上都可测**。
     *
     * <p>不这么做的话，这条路径只在「当前系统恰好缺那个命令」时才会被走到：
     * 在 macOS 上跑测试时 {@code rundll32} 根本不会被执行，
     * 于是「不 catch IOException」这个变异在 macOS 上是隐形的——
     * 对照实验会得到 GREEN，而它本该是红的。
     */
    static boolean open(String url, List<String> cmd) {
        if (!isLaunchable(url) || cmd == null || cmd.isEmpty()) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 不用管道：管道写满会死等，而浏览器命令的输出我们根本不关心。
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            // 必须关掉 stdin：父进程不关、子进程等 EOF，两边互等。
            p.getOutputStream().close();
            if (!p.waitFor(EXIT_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            // 命令不存在 / 没有权限 / 平台不支持：都不该让服务起不来。
            // 地址已经打印在终端上，用户可以自己复制。
            return false;
        } catch (InterruptedException e) {
            // **必须恢复中断标志**。吞掉它等于告诉上层「没被打断」，
            // 而本方法承诺「失败一律吞掉」针对的是浏览器打不开，不是线程被打断——
            // 打断通常意味着整个进程正在被关停，这时把标志清了，
            // 上层的关停逻辑会继续跑下去并可能让进程半死不活地留着。
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
