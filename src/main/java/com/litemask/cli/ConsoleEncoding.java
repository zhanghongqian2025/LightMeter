package com.litemask.cli;

import java.io.IOException;

/**
 * Windows 控制台的代码页处理。
 *
 * <p><b>为什么需要它（2026-10-25 在真 Windows 上实测出来的）</b>：
 * 本产品统一用 UTF-8 输出（见 {@code tools/package-identity.properties} 的
 * {@code jvm.args}：{@code -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8}）。
 * 但 Windows 控制台按**自己的输出代码页**去解释那些字节：
 * <ul>
 *   <li>中文系统默认 936（GBK）→ UTF-8 的字节被当成 GBK 解释 → 满屏乱码；</li>
 *   <li>非中文系统默认 437/1252 → 那些代码页<b>根本没有</b>「用法:」这几个字
 *       → Java 只能把它们替换成 {@code ?}，用户看到的是 {@code ??:}</li>
 * </ul>
 * 两条路都指向同一个结论：**输出编码必须自己定，不能交给系统默认**。
 *
 * <p><b>为什么只切「自己拥有的那个控制台」</b>：
 * {@code chcp} 改的是**当前控制台**的代码页。如果用户在已有的 cmd / PowerShell 里
 * 跑 {@code LightMeter redact a.docx}，我们替他改代码页 = 擅自改他的终端设置，
 * 之后他别的程序（比如 Python、Git）可能就乱了。
 * → 所以只在**桌面双击**那条路上切：那条路的控制台是我们自己拉起来的，
 *   改它不影响任何人。
 *
 * <p><b>失败怎么办</b>：切不了就<b>继续跑</b>。代码页只影响「控制台怎么显示」，
 * 不影响「字节是什么」—— 字节已经是 UTF-8 了（管道、文件、脚本读到的都是对的）。
 * 为了显示好看而让整个程序起不来，是本末倒置。
 */
public final class ConsoleEncoding {

    private ConsoleEncoding() {
    }

    /**
     * 在 Windows 上把当前控制台的代码页切到 UTF-8（65001）。
     *
     * <p>非 Windows、或者没有控制台（输出被重定向到文件/管道）时什么都不做。
     */
    public static void ensureUtf8Console() {
        if (!isWindows()) {
            return;
        }
        try {
            // 有控制台才会被「显示」到屏幕上；被重定向时切了也没意义。
            if (System.console() == null) {
                return;
            }
            Process p = new ProcessBuilder("chcp.com", "65001")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            // 切不了就算了：字节本来就是 UTF-8，管道与文件里都是对的。
            // 这里绝不能抛 —— 为了「显示好看」而让程序起不来是本末倒置。
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 本机是不是 Windows。 */
    static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
