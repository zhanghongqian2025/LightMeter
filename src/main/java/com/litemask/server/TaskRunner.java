package com.litemask.server;

import com.litemask.core.Exclusions;
import com.litemask.core.TaskContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 后台任务：把「上传」与「脱敏」拆成两段，中间夹一个可轮询、可取消的状态。
 *
 * <p><b>为什么要有它</b>：脱敏是在请求线程里同步跑完的，一份 200 页的 PDF 会让
 * 界面停在「上传中」——用户既看不到进度，也没法放弃，只能等。
 * 更糟的是本地服务的 HTTP 线程池只有 4 个：几个大文件就能把它占满，
 * 连 {@code /api/health} 都答不上来。
 *
 * <p><b>任务表刻意不放在 {@link JobStore} 里。</b>轮询任务状态是「读」，
 * 而 {@code JobStore} 的监视器会被正在跑的脱敏占着（它要做落盘、登记这些短操作）。
 * 任务表独立之后，轮询完全不碰那把锁——**读进度的人不会被干活的人堵住**。
 *
 * <p><b>取消是协作式的</b>：{@link TaskContext} 在管线里布了检查点，
 * 取消之后跑到下一个检查点就停，而且**不产出任何文件**。
 * 那不是「标记一下就算了」——那会让用户拿到一份半成品，而界面上显示「已取消」。
 */
public final class TaskRunner implements AutoCloseable {

    /**
     * 同时最多跑几个任务。
     *
     * <p>脱敏是 CPU 密集的（正则 + 本地 NER 推理），线程数超过核数只会互相抢 CPU。
     * 2 个足够让「一份在跑、另一份在排队」这个常见场景不卡死。
     */
    public static final int WORKERS = 2;

    /** 任务表上限。超过就把最老的**已完成**任务挤掉。 */
    public static final int MAX_TASKS = 64;

    /** 任务状态。 */
    public enum Status {
        /** 还在跑。 */
        RUNNING,
        /** 跑完了，结果在 {@link Task#payload} 里。 */
        DONE,
        /** 出错了，原因在 {@link Task#error} 里。 */
        FAILED,
        /** 被取消了，没有产出任何文件。 */
        CANCELED,
        /**
         * 超过了时限被自动中止，没有产出任何文件。
         *
         * <p><b>它不复用 {@link #CANCELED}</b>：用户看到「已取消」会以为是自己按的，
         * 实际上他没按，是服务自动中止的。「为什么我的任务没了」和「任务怎么这么慢」
         * 是两个不同的问题，混成一句话就答不上来了。
         */
        TIMED_OUT;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 一个任务。字段都是 volatile：写的是工作线程，读的是 HTTP 线程。 */
    public static final class Task {
        final String taskId;
        final TaskContext context;
        volatile Status status = Status.RUNNING;
        volatile String phase = "";
        volatile int done = 0;
        /** 总单元数；<b>-1 表示不知道</b>（各格式的单元总数事先都算不出来）。 */
        volatile int total = -1;
        volatile String message = "";
        volatile boolean cancelRequested;
        /**
         * 是否**因超时**而被中止。
         *
         * <p>它是<b>原因标记</b>，不是状态：状态由工作线程在真正停下时写，
         * 而「为什么停」只有看门狗知道。这个标记就是两者之间的交接。
         */
        volatile boolean timedOut;
        /** 时限到期的那一刻（毫秒）。<b>0 表示还没开始计时</b>。 */
        volatile long deadlineAt;
        volatile String jobId;
        volatile Map<String, Object> payload;
        volatile String error;
        final long startedAt = System.currentTimeMillis();
        volatile long finishedAt;

        Task(String taskId) {
            this.taskId = taskId;
            this.context = TaskContext.create(this::onProgress);
        }

        /**
         * 任务真正<b>开跑</b>的那一刻。
         *
         * <p><b>时限从开跑算，不从提交算</b>：排队等待不算在时限内。
         * 否则 {@link TaskRunner#WORKERS} 只有 2 个的时候，
         * 第二个任务会先白等一轮排队时间，等轮到它时时限已经过了一半——
         * 用户会觉得「它刚排上队就被超时干掉了」。
         *
         * @param timeoutMs 任务时限（毫秒）；<= 0 表示不设时限
         */
        void markStarted(long now, long timeoutMs) {
            this.deadlineAt = timeoutMs > 0 ? now + timeoutMs : 0;
        }

        private void onProgress(String phase, int done, int total) {
            this.phase = phase == null ? "" : phase;
            this.done = done;
            this.total = total;
            this.message = describeProgress(this.phase, done, total);
        }

        public String taskId() {
            return taskId;
        }

        public Status status() {
            return status;
        }
    }

    /**
     * 把一次进度回调变成一句人能看懂的话。
     *
     * <p><b>不报百分比</b>：各格式的文本单元总数事先都算不出来
     * （PDF 按内容流、OOXML 按段落、OFD 按文本对象），
     * {@code total} 只能传 -1。拿一个估算值糊出「47%」比说「不知道」更糟——
     * 用户会以为它可信。
     *
     * <p>阶段名为空时用「处理中」兜底：否则消息会变成「：已处理 0 个文本单元」，
     * 那个前导冒号会直接显示在界面上。
     */
    private static String describeProgress(String phase, int done, int total) {
        String head = phase == null || phase.isBlank() ? "处理中" : phase;
        return total > 0
                ? head + "：" + done + "/" + total
                : head + "：已处理 " + done + " 个文本单元";
    }

    /**
     * 任务时限：超过这么久还在跑，就自动中止。
     *
     * <p><b>默认给得很宽</b>：一份几百页的 PDF 或一个大 PPTX 本来就要跑一会儿，
     * 时限定得太紧会把正常的活判成超时。
     * 但它<b>必须能在测试里调小</b>——否则没法在几秒内验「超时真的发生」，
     * 那种「测了但没真测」的用例比没有更糟。
     */
    public static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    private final JobStore jobs;
    private final ExecutorService workers;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final long timeoutMs;
    /** 看门狗：定期扫一遍有没有任务超了时限。 */
    private final ScheduledExecutorService watchdog;
    /** 因超时而被中止过的任务数（{@code /api/health} 如实报出来）。 */
    private final AtomicInteger timedOutCount = new AtomicInteger();
    /**
     * 「现在几点」。
     *
     * <p><b>为什么要能注入</b>：超时的判据是「现在 >= deadline」，
     * 而 deadline 是<b>相对时间</b>算出来的（「开跑时刻 + 时限」）。
     * 如果时钟只能来自系统，那么「超时到底会不会发生」就取决于
     * **机器有多快**——测试只能造一份大到跑不完的文档来赌它跑不完，
     * 那是和机器赛跑，换台机器结论就反了（实测踩过：0.735 秒就跑完了）。
     *
     * <p>注入时钟之后，测试可以**主动把时间推过时限**：
     * 「超时会发生」从一场赛跑，变成测试自己写下的一条事实。
     *
     * <p><b>看门狗和打 deadline 必须读同一个源</b>：
     * 一个用假时间、一个用真时间，那两条路会各自算出不同的结论，
     * 测出来的东西连自己都不自洽。
     */
    private final LongSupplier clock;

    public TaskRunner(JobStore jobs) {
        this(jobs, Executors.newFixedThreadPool(WORKERS, threadFactory()));
    }

    public TaskRunner(JobStore jobs, long timeoutMs) {
        this(jobs, Executors.newFixedThreadPool(WORKERS, threadFactory()), timeoutMs);
    }

    /**
     * 指定时限<b>与时钟</b>的构造器：<b>给测试用</b>。
     *
     * <p>传进来的时钟会同时被看门狗和「打 deadline」使用，
     * 保证两条路在同一条时间轴上。
     *
     * @param timeoutMs 任务时限（毫秒）；<= 0 表示<b>不设时限</b>
     * @param clock     「现在几点」；测试传自己控制的时钟
     */
    TaskRunner(JobStore jobs, ExecutorService workers, long timeoutMs, LongSupplier clock) {
        this.jobs = jobs;
        this.workers = workers;
        this.timeoutMs = timeoutMs;
        this.clock = clock;
        this.watchdog = timeoutMs > 0
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "litemask-watchdog");
                    t.setDaemon(true);
                    return t;
                })
                : null;
        if (watchdog != null) {
            long period = Math.max(1L, Math.min(1000L, timeoutMs / 4));
            watchdog.scheduleWithFixedDelay(this::enforceTimeouts,
                    period, period, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 指定工作线程池的构造器：<b>给测试用</b>。
     *
     * <p>为什么要留这个口子：有一类性质<b>必须</b>在「任务还没被工作线程捡起来」
     * 的那个窗口里才能验——比如「提交那一刻内容就已经在磁盘上了」、
     * 「任务表满了以后没人认领的上传不会留在盘上」。
     * 正常情况下那个窗口只有几毫秒，测试根本抓不住；
     * 传一个被闩锁住的执行器进去，就能把窗口撑开到一个确定的长度。
     *
     * <p>生产路径用默认构造器（固定 2 个线程）。这个构造器不校验池的大小，
     * 因为它本来就只服务于「把窗口撑开」这一个用途。
     */
    TaskRunner(JobStore jobs, ExecutorService workers) {
        this(jobs, workers, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 指定工作线程池**与时限**的构造器：<b>给测试用</b>。
     *
     * @param timeoutMs 任务时限（毫秒）；<= 0 表示<b>不设时限</b>
     */
    TaskRunner(JobStore jobs, ExecutorService workers, long timeoutMs) {
        this(jobs, workers, timeoutMs, System::currentTimeMillis);
    }

    /**
     * 看门狗的一趟：把所有**超了时限**的任务标记为超时中止。
     *
     * <p><b>它只做两件事：打标记 + 请求取消</b>。
     * 它<b>不</b>改任务的终态——终态由工作线程在真正停下的时候写。
     *
     * <p><b>为什么不让看门狗直接改终态</b>：那会出现两个线程抢着写状态，
     * 而且看门狗说「已超时」的时候任务<b>其实还在跑</b>（它可能还卡在某个
     * 不检查取消的地方）。让真正干活的那条线程来写终态，
     * 「超时了」和「真的停了」才是同一件事。
     */
    private void enforceTimeouts() {
        long now = clock.getAsLong();
        for (Task t : tasks.values()) {
            if (t.status != Status.RUNNING || t.deadlineAt == 0) {
                continue;
            }
            if (now < t.deadlineAt) {
                continue;
            }
            if (t.timedOut) {
                continue;
            }
            t.timedOut = true;
            t.cancelRequested = true;
            timedOutCount.incrementAndGet();
            t.context.cancel();
        }
    }

    /** 因超时被中止过的任务数。 */
    public int timedOutCount() {
        return timedOutCount.get();
    }

    /** 任务时限（毫秒）；<= 0 表示不设时限。 */
    public long timeoutMs() {
        return timeoutMs;
    }

    /** 本任务池用的工作区（上传的暂存件要落在它里面）。 */
    public Workspace workspace() {
        return jobs.workspace();
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "litemask-task-" + seq.incrementAndGet());
            // 守护线程：服务关掉时不用等这些线程跑完。
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 提交一个扫描任务。
     *
     * <p><b>上传内容在提交之前就已经落盘了</b>（见 {@link Workspace#stage}），
     * 所以任务在队列里等的那段时间，<b>内存里没有那份内容</b>——
     * 这正是以前缺的那道闸：任务表上限 64、上传上限 256MB，
     * 「连着提交 64 份大文件」在理论上就能把内存撑爆。
     *
     * @param staged 已落盘的上传；本方法接管它的去留
     * @throws IOException 任务表满了（同时进行的任务太多）
     */
    public Task submitScan(Workspace.StagedFile staged) throws IOException {
        Task task;
        try {
            task = newTask();
        } catch (IOException e) {
            // 任务表满了：没人会来认领这份上传，它不能留在盘上。
            // 留着等于「用户以为没上传成功、原文档却还在」。
            workspace().discard(staged);
            throw e;
        }
        tasks.put(task.taskId, task);
        try {
            workers.execute(() -> runScan(task, staged));
        } catch (RuntimeException e) {
            // 执行器拒收（池子关了）：任务不会跑，上传也就没人认领
            tasks.remove(task.taskId);
            workspace().discard(staged);
            throw e;
        }
        return task;
    }

    /**
     * 提交一个应用任务。
     *
     * @param jobId      已扫描的任务
     * @param exclusions 用户要保留的命中
     */
    public Task submitApply(String jobId, Exclusions exclusions) throws IOException {
        return submitApply(jobId, exclusions, null);
    }

    /**
     * 提交一个应用任务，映射件按给定口令封装。
     *
     * @param passphrase 映射件口令；{@code null} 表示不封装。
     *                  <b>口令数组的所有权随任务一起转移</b>：
     *                  提交成功后由执行线程擦掉，提交失败时由这里擦掉——
     *                  调用方不必再管它，也免得出现「谁负责擦」的悬案。
     */
    public Task submitApply(String jobId, Exclusions exclusions, char[] passphrase) throws IOException {
        Task task = newTask();
        tasks.put(task.taskId, task);
        try {
            workers.execute(() -> runApply(task, jobId, exclusions, passphrase));
        } catch (RuntimeException e) {
            // 执行器拒收（池子关了）：任务不会跑，口令也没人擦。
            tasks.remove(task.taskId);
            com.litemask.core.SecretMap.wipe(passphrase);
            throw e;
        }
        return task;
    }

    private Task newTask() throws IOException {
        evictFinished();
        if (tasks.size() >= MAX_TASKS) {
            // 挤不掉任何已完成的任务 = 全都在跑。此时拒绝是诚实的：
            // 默默把一个正在跑的任务从表里删掉，用户就再也问不到它的结果了。
            throw new IOException("同时进行的任务太多，请等手上的任务跑完再试");
        }
        return new Task(UUID.randomUUID().toString().replace("-", ""));
    }

    /** 挤掉最老的已完成任务，直到表回到上限以内。 */
    private void evictFinished() {
        List<Task> done = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.status != Status.RUNNING) {
                done.add(t);
            }
        }
        done.sort(Comparator.comparingLong(t -> t.finishedAt == 0 ? t.startedAt : t.finishedAt));
        int overflow = tasks.size() - MAX_TASKS + 1;
        for (int i = 0; i < done.size() && overflow > 0; i++, overflow--) {
            tasks.remove(done.get(i).taskId);
        }
    }

    // ------------------------------------------------------------------ 执行

    private void runScan(Task task, Workspace.StagedFile staged) {
        task.markStarted(clock.getAsLong(), timeoutMs);
        try {
            JobStore.Job job = jobs.scanStaged(staged, task.context);
            task.jobId = job.jobId();
            task.payload = JobStore.describe(job, job.scan, "scanned", 0);
            task.status = Status.DONE;
            task.message = "扫描完成";
        } catch (TaskContext.CanceledException e) {
            task.status = task.timedOut ? Status.TIMED_OUT : Status.CANCELED;
            task.message = task.timedOut
                    ? "超过时限被自动中止，没有产出任何文件"
                    : "已取消，没有产出任何文件";
            // 原因也要落到 error 上：界面失败分支读的是 error，
            // 只写 message 的话，超时会被显示成「任务失败」——
            // 那是在跟用户说假话（他们会以为是自己弄坏的，或者服务崩了）。
            task.error = task.message;
        } catch (Exception e) {
            task.status = Status.FAILED;
            task.error = describeFailure(e);
            task.message = "失败：" + task.error;
        } finally {
            // 兜底：任务终态时，暂存件必须已经不在盘上了。
            // 正常路径上它已经被「认领」成正式件（挪走了），这里删不到东西；
            // 万一在认领之前就失败了，这一行就是把它清掉的最后一道关。
            workspace().discard(staged);
            task.finishedAt = System.currentTimeMillis();
        }
    }

    private void runApply(Task task, String jobId, Exclusions exclusions, char[] passphrase) {
        task.markStarted(clock.getAsLong(), timeoutMs);
        try {
            JobStore.Job job = jobs.applyCancellable(jobId, exclusions, task.context, passphrase);
            task.jobId = job.jobId();
            task.payload = JobStore.describe(job, job.applied, "applied",
                    exclusions == null ? 0 : exclusions.size());
            task.status = Status.DONE;
            task.message = "应用完成";
        } catch (TaskContext.CanceledException e) {
            task.status = task.timedOut ? Status.TIMED_OUT : Status.CANCELED;
            task.message = task.timedOut
                    ? "超过时限被自动中止，没有产出任何文件"
                    : "已取消，没有产出任何文件";
            // 原因也要落到 error 上：界面失败分支读的是 error。
            task.error = task.message;
        } catch (JobStore.BadRequestException e) {
            task.status = Status.FAILED;
            task.error = e.getMessage();
            task.message = "失败：" + task.error;
        } catch (Exception e) {
            task.status = Status.FAILED;
            task.error = describeFailure(e);
            task.message = "失败：" + task.error;
        } finally {
            // 口令到此为止：映射件已经写完（或失败了），它没有理由再留在内存里。
            com.litemask.core.SecretMap.wipe(passphrase);
            task.finishedAt = System.currentTimeMillis();
        }
    }

    /**
     * 把异常变成一句人能看懂的话。
     *
     * <p>刻意<b>不带堆栈</b>：这段话会回到浏览器界面上，
     * 堆栈里的路径与类名对用户没有意义，还可能泄露本机目录结构。
     */
    private static String describeFailure(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    // ------------------------------------------------------------------ 查询

    /** 查一个任务；不存在返回 null。 */
    public Task task(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return tasks.get(taskId);
    }

    /**
     * 请求取消。
     *
     * @return true 表示这个任务存在且已被标记为取消中
     */
    public boolean cancel(String taskId) {
        Task task = task(taskId);
        if (task == null) {
            return false;
        }
        task.cancelRequested = true;
        // 已经跑完的任务不用取消：它有结果，取消它等于把一份做完的活扔掉。
        if (task.status == Status.RUNNING) {
            task.context.cancel();
        }
        return true;
    }

    /**
     * 任务状态的快照。
     *
     * <p>完成后 {@code result} 里是**与同步接口同形状**的一份结果，
     * 所以界面拿到任务结果之后就能直接用，不必再调一次 {@code /api/scan}。
     */
    public Map<String, Object> snapshot(Task task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("taskId", task.taskId);
        out.put("status", task.status.wire());
        out.put("phase", task.phase);
        out.put("done", task.done);
        out.put("total", task.total);
        out.put("message", task.message);
        out.put("cancelRequested", task.cancelRequested);
        // timedOut 单列一个字段：前端要能把它和「用户自己按的取消」分开显示。
        out.put("timedOut", task.timedOut);
        out.put("elapsedMs", (task.finishedAt == 0 ? System.currentTimeMillis() : task.finishedAt)
                - task.startedAt);
        if (task.jobId != null) {
            out.put("jobId", task.jobId);
        }
        if (task.payload != null) {
            out.put("result", task.payload);
        }
        if (task.error != null) {
            out.put("error", task.error);
        }
        return out;
    }

    /** 正在跑的任务数，供 /api/health 如实报出。 */
    public int running() {
        int n = 0;
        for (Task t : tasks.values()) {
            if (t.status == Status.RUNNING) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() {
        workers.shutdownNow();
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
    }
}
