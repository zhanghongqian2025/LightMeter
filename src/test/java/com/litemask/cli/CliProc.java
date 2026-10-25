package com.litemask.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 起一个 CLI 子进程并把它的 stdout / stderr 都收回来 —— <b>不产生管道死锁</b>。
 *
 * <p><b>为什么要有它（2026-10-25 在 Windows 上实测出来的真缺陷）</b>：
 * 原来的写法是
 * <pre>
 *   String out = new String(proc.getInputStream().readAllBytes(), UTF_8);
 *   String err = new String(proc.getErrorStream().readAllBytes(), UTF_8);
 *   proc.waitFor(...);
 * </pre>
 * {@code readAllBytes()} 要**读到 EOF**（= 子进程关闭了流）才返回，
 * 而 EOF 只在子进程退出时才有。于是：
 * <ol>
 *   <li>父进程先读 stdout，一直等 EOF；</li>
 *   <li>子进程写 usage 到 stderr —— <b>Windows 的匿名管道默认只有 4KB</b>
 *       （macOS/Linux 是 64KB），写满就阻塞在 {@code write} 上；</li>
 *   <li>子进程不退出 → stdout 永远没有 EOF → 父进程永远等下去。</li>
 * </ol>
 * 互等，死锁。macOS 上没暴露，纯粹是因为 usage 文本恰好装得进 64KB 的管道缓冲 ——
 * 也就是说<b>这条测试在 macOS 上是「恰好没卡住」，不是「不会卡」</b>。
 *
 * <p><b>怎么解</b>：两条流<b>同时</b>排空（各自一个线程），谁先写满都不阻塞；
 * 等进程结束后再取两份输出。
 */
final class CliProc {

    /** 一次子进程运行的结果。 */
    record Result(int rc, String out, String err) {
    }

    private CliProc() {
    }

    /**
     * 收完两条流再等进程结束。
     *
     * @param proc 已经启动的进程
     * @param timeoutSeconds 超时秒数
     * @return 退出码与两路输出
     * @throws IOException 读流失败
     * @throws InterruptedException 被中断
     */
    static Result collect(Process proc, long timeoutSeconds) throws IOException, InterruptedException {
        Collector out = new Collector(proc.getInputStream());
        Collector err = new Collector(proc.getErrorStream());
        out.start();
        err.start();
        boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            proc.waitFor(10, TimeUnit.SECONDS);
            out.join(5000);
            err.join(5000);
            throw new IllegalStateException("CLI 没有在 " + timeoutSeconds + " 秒内返回（已强杀）");
        }
        // 进程退出了，两条流会自然到 EOF；再给一点时间让读取线程收尾。
        out.join(10_000);
        err.join(10_000);
        return new Result(proc.exitValue(), out.text(), err.text());
    }

    /** 在后台把一条流读干净。 */
    private static final class Collector extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Collector(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (InputStream s = in) {
                int n;
                while ((n = s.read(chunk)) >= 0) {
                    buf.write(chunk, 0, n);
                }
            } catch (IOException ignored) {
                // 进程被杀时读流会抛 —— 那时拿到的内容已经够判了，不算失败。
            }
        }

        String text() {
            return buf.toString(StandardCharsets.UTF_8);
        }
    }
}
