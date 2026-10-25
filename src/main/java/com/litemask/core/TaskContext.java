package com.litemask.core;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次脱敏运行的「可取消 + 可报进度」作用域。
 *
 * <p><b>为什么它是一个作用域而不是一个普通参数</b>：脱敏管线有五层
 * （格式 redactor → 会话 → 引擎 → 规则），每一层都要能回答「现在到哪了、还该不该继续」。
 * 把这个上下文一层层传下去要改五个 redactor 的公开签名——那是产品 API，
 * 而它俩（进度、取消）都不是产品能力，是运行期状态。
 *
 * <p>所以这里用<b>线程作用域</b>：谁在哪个线程上跑这段管线，那段管线就看得见哪个上下文。
 * 三个前提让这件事是安全的：
 * <ol>
 *   <li>管线是<b>单线程</b>的——它内部不起子线程，所以上下文不会「传丢」；</li>
 *   <li>作用域用 {@code try/finally} 收尾，线程回池后不会残留上一个任务的上下文；</li>
 *   <li>读进度的人（轮询接口）读的是任务自己的字段，<b>不</b>读这个作用域——
 *       所以「谁在读」和「谁在写」不会互相阻塞。</li>
 * </ol>
 *
 * <p><b>取消是协作式的</b>：检查点落在「正在处理一个文本单元」和「正在跑一条规则」上。
 * 它不会把线程从中间拽走——那样会留下半写坏的文档。取消的含义是
 * 「做到下一个检查点就停，并且<b>不产出任何文件</b>」。
 */
public final class TaskContext {

    /** 不报进度、也不可取消的上下文。CLI 与同步调用走这个。 */
    public static final TaskContext NONE = new TaskContext(null);

    private static final ThreadLocal<TaskContext> CURRENT = new ThreadLocal<>();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ProgressListener listener;

    /**
     * 进度回调。
     *
     * @param phase 当前阶段（如「脱敏」）
     * @param done  已处理的单元数
     * @param total 总单元数；<b>未知时传 -1</b>，不要拿估算值糊弄——
     *               界面上一个假的百分比比「不知道总数」更糟
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String phase, int done, int total);
    }

    private TaskContext(ProgressListener listener) {
        this.listener = listener;
    }

    /** 造一个可取消、可报进度的上下文。 */
    public static TaskContext create(ProgressListener listener) {
        return new TaskContext(listener);
    }

    /** 请求取消。已在运行的那一段会跑到下一个检查点才停。 */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 取消了就抛。这是控制流异常，不是错误。 */
    public void checkCancelled() {
        if (cancelled.get()) {
            throw new CanceledException();
        }
    }

    /**
     * 报一个进度点。<b>不抛</b>——取消的判定权在调用方手里，
     * 这里只负责把「到哪了」说出来。
     */
    public void progress(String phase, int done, int total) {
        if (listener != null) {
            listener.onProgress(phase, done, total);
        }
    }

    /** 当前线程所处的上下文；不在任何作用域里时是 {@link #NONE}。 */
    public static TaskContext current() {
        TaskContext c = CURRENT.get();
        return c == null ? NONE : c;
    }

    /**
     * 在本上下文的作用域里执行。作用域内 {@link #current()} 返回它。
     *
     * <p>嵌套调用是允许的：内层作用域结束后会恢复外层，不会把外层的上下文丢掉。
     */
    public static <T> T in(TaskContext ctx, Body<T> body) throws IOException {
        TaskContext previous = CURRENT.get();
        CURRENT.set(ctx == null ? NONE : ctx);
        try {
            return body.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 作用域里要跑的活。允许抛 {@link IOException}——脱敏管线本来就会。 */
    @FunctionalInterface
    public interface Body<T> {
        T run() throws IOException;
    }

    /**
     * 取消异常。
     *
     * <p>它是 {@link RuntimeException}：管线里十几层方法都要能让它穿过去，
     * 每层都加一个「throws 已取消」既啰嗦又容易漏——漏一层的后果是
     * 取消之后还在往下写文件。
     */
    public static final class CanceledException extends RuntimeException {
        public CanceledException() {
            super("任务已取消");
        }
    }
}
