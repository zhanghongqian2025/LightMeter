package com.litemask.core;

/**
 * 「独占闸门」：某些动作必须保证此刻<b>没有别的任务正在使用</b>某样资源。
 *
 * <h2>为什么要有它</h2>
 * 本地 NER 模型是<b>一份磁盘上的文件</b>（{@code ner.onnx}），推理引擎把它映射进内存。
 * 要把这份模型换成新版本，就得先把旧的那份挪走——而 Windows 上，
 * <b>文件正被打开时挪不动</b>（{@code ERROR_SHARING_VIOLATION}）。
 * 于是顺序只能是：<b>先停用 → 再挪 → 再换回</b>。
 *
 * <p>「停用」不是「杀掉进程」——那是用户要自己做的事，而目标恰恰是
 * 「不要求用户自行找进程或执行命令」。所以程序要自己协调：
 * <ul>
 *   <li>正在跑的任务<b>跑完当前这一步</b>（不能半途丢下用户的文档）；</li>
 *   <li><b>新任务排队</b>（不要在模型换到一半时开始扫）；</li>
 *   <li>换完之后<b>自动恢复</b>（模型照常可用，用户不用重开程序）。</li>
 * </ul>
 *
 * <p>这三件事正好就是一把读写锁的语义：
 * <b>扫描拿读锁（可并行），换模型拿写锁（独占且公平排队）</b>。
 *
 * <h2>为什么要放在 core 而不是 ner</h2>
 * {@code core} 是脱敏引擎，它不认识「模型」；{@code ner} 是模型侧。
 * 但「扫描期间要独占什么」这件事是<b>引擎的调度需求</b>，
 * 与具体资源是什么无关。
 * → 放在 core，两边都能看见它，且 core 不需要因此依赖 ner。
 */
public interface ExclusiveGate {

    /**
     * 取得独占权。
     *
     * <p><b>必须在 try-with-resources 里用</b>：
     * <pre>{@code
     * try (ExclusiveGate.Release release = gate.acquire()) {
     *     ... 需要独占资源的动作 ...
     * }
     * }</pre>
     *
     * @return 释放动作。释放失败不应影响业务结果，因此 {@link Release#close()} 不抛异常。
     */
    Release acquire();

    /**
     * 释放动作。
     *
     * <p><b>为什么不直接抛 {@link Exception}</b>：{@link AutoCloseable#close()} 的签名带
     * {@code throws Exception}，而释放闸门几乎不可能失败——
     * 失败的话硬抛出来会让「业务成功、清理失败」变成一次整体失败，
     * 那比不清理更糟。所以这里把签名收窄成不抛异常。
     */
    @FunctionalInterface
    interface Release extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 什么都不挡的闸门。
     *
     * <p>给「没有独占资源」的场景用（例如纯正则模式、命令行批处理）。
     * 写成常量而不是让每个调用方写一个 lambda，是为了让
     * 「这里到底挡没挡」在代码里一眼看得见。
     */
    ExclusiveGate NONE = () -> () -> {
    };
}
