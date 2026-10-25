package com.litemask.server;

import com.litemask.docx.DocxFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时的语义：<b>跑太久就自动停，而且什么都没留下</b>。
 *
 * <p><b>为什么这个缺口值得做</b>：工作线程只有 {@link TaskRunner#WORKERS} 个。
 * 一个卡住的任务就占掉一半产能，两个卡住 = 整个脱敏服务瘫掉，
 * 连 {@code /api/health} 都答不上来。用户那边的表现是「界面一直转圈，
 * 重启服务才恢复」——而重启会**丢掉还没取走的映射文件**。
 *
 * <p><b>为什么超时必须走「取消」那条路，而不是强杀线程</b>：
 * 强杀会留下**半写坏的文档**（写到一半的 docx、截断的 pdf）。
 * 那比「慢」糟得多——用户拿到的是一份看起来正常、实则残缺的文件。
 * 所以超时是<b>协作式</b>的：设取消标志，管线跑到下一个检查点就停，
 * <b>不产出任何文件</b>。
 *
 * <p><b>为什么终态不能复用 canceled</b>：用户看到「已取消」会以为是自己按的，
 * 实际上他没按，是服务自动中止的。「为什么我的任务没了」和「任务怎么这么慢」
 * 是两个不同的问题，混成一句话就答不上来。
 *
 * <p><b>标尺独立于被测实现</b>：本测试里期望的终态是<b>手写的字符串</b>
 * （"timed_out"），不是引用 {@link TaskRunner.Status} 的常量。
 * 拿被测实现自己的常量当期望值，只能证明它自洽。
 *
 * <p><b>为什么用注入的时钟，而不是「造一份大到跑不完的文档」</b>：
 * 第一版就是这么写的（4000 段 docx + 1 秒时限），本轮实测**前提不成立**——
 * 那份文档在热过的机器上 <b>0.735 秒就扫完了</b>，超时压根没发生，
 * 两条测试直接红。而这个前提<b>依赖机器快慢</b>：冷启动的机器上它成立，
 * 于是同一个测试有时绿有时红。
 * 红灯原因不是「实现错了」，是「和机器赛跑输了」——那比没有测试更糟。
 * <br>
 * 现在的做法：把时钟注入 {@link TaskRunner}，<b>由测试主动把时间推过时限</b>。
 * 「超时会发生」于是从一场赛跑，变成测试自己写下的一条事实。
 */
class TaskTimeoutTest {

    private static final String PHONE = "13800138000";

    /** 期望的终态名：<b>手写</b>，不复用被测实现的常量。 */
    private static final String 终态_超时 = "timed_out";
    private static final String 终态_取消 = "canceled";
    private static final String 终态_完成 = "done";

    /** 时限：1 秒（注入时钟下这个数只决定「推过多少」才叫超时）。 */
    private static final long 时限毫秒 = 1_000L;

    /**
     * 测试自己控制的时钟。
     *
     * <p><b>初值取真实的系统时刻</b>，不是某个固定的过去时刻。
     * 原因：任务对象在创建时记下了自己的创建时刻（{@code startedAt}），
     * 而「时限从开跑算」和「时限从提交算」这两个实现的差别，
     * 就藏在「用哪个时刻起算」上。
     * <br>
     * 如果这个时钟的初值跟系统时钟对不上（差了好几年），
     * 那么两个时刻根本不在同一条时间轴上，
     * 「用创建时刻起算」这个实现就**测不出来**——
     * 它跟真实实现算出来的结果永远不一样，而测试恰恰要能区分它们。
     * <br>
     * 注：这个时钟是<b>冻结</b>的——它不会跟着真实时间走，
     * 只有测试主动调用 {@code 时钟.set(...)} 时才前进。
     */
    private final AtomicLong 时钟 = new AtomicLong(System.currentTimeMillis());

    private Workspace workspace;
    private JobStore jobs;
    private Path 夹具目录;
    private int 序号;

    @TempDir
    Path 根;

    @BeforeEach
    void 起() throws IOException {
        workspace = Workspace.open(根);
        jobs = new JobStore(workspace, ApiHandlers.defaultRules());
        夹具目录 = Files.createDirectories(根.resolve("夹具"));
        序号 = 0;
    }

    @AfterEach
    void 停() {
        if (workspace != null) {
            workspace.close();
        }
    }

    /** 造一份有若干段的 docx，每段一个手机号。 */
    private byte[] docx(int 段数) throws IOException {
        序号++;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 段数; i++) {
            body.append(DocxFixture.paragraph(
                    DocxFixture.run(null, "第" + i + "条 联系电话 " + PHONE)));
        }
        Path p = DocxFixture.simple(body.toString())
                .writeTo(夹具目录, "样本-" + 序号 + ".docx");
        return Files.readAllBytes(p);
    }

    private TaskRunner.Task 提交(TaskRunner runner, String 名字, byte[] 内容) throws IOException {
        try {
            return runner.submitScan(workspace.stage(new ByteArrayInputStream(内容), 名字));
        } catch (Workspace.UnsupportedFileTypeException | Workspace.FileTooLargeException e) {
            throw new IOException("测试样本的后缀有问题：" + 名字, e);
        }
    }

    /** 会话目录里现在有几个普通文件。 */
    private long 会话文件数() throws IOException {
        try (var s = Files.list(workspace.sessionDir())) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    /** 等任务到终态；超时了就说清楚。 */
    private TaskRunner.Task 等到终态(TaskRunner runner, TaskRunner.Task t) throws InterruptedException {
        long 截止 = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < 截止) {
            if (t.status() != TaskRunner.Status.RUNNING) {
                return t;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("任务在 60 秒内没有到终态（status=" + t.status() + "）");
    }

    /**
     * 等任务<b>真正开跑</b>（时限开始计的那一刻）。
     *
     * <p>判据是 {@code deadlineAt != 0}——它只在开跑时才被打上，
     * 所以它精确地表示「这个任务已经开跑了」。
     */
    private void 等开跑(TaskRunner.Task t) throws InterruptedException {
        long 截止 = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < 截止) {
            if (t.deadlineAt != 0) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("任务在 30 秒内没有开跑（deadline 一直没打上）");
    }

    /**
     * 把时钟<b>推过时限</b>。
     *
     * <p>这一步是本测试与「和机器赛跑」的根本区别：
     * 推完之后，「超时会发生」不再取决于机器多快，
     * 而是一条由测试写下的事实。
     */
    private void 推过时限(TaskRunner.Task t) {
        assertTrue(t.deadlineAt != 0, "推时限之前必须确认任务已经开跑");
        时钟.set(t.deadlineAt + 1);
    }

    /**
     * 从<b>公开的</b>快照里读 {@code timedOut} 标记。
     *
     * <p>走 {@link TaskRunner#snapshot} 而不是直接读字段：
     * 那是用户（前端）真正看到的东西——如果快照里没有这个标记，
     * 界面就分不出「超时」和「用户自己取消」，那么这个标记等于没做。
     */
    private boolean 快照里说超时(TaskRunner runner, TaskRunner.Task t) {
        Object v = runner.snapshot(t).get("timedOut");
        return Boolean.TRUE.equals(v);
    }

    // -------------------------------------------------------------- 主判据

    /**
     * 超时之后：任务<b>真的停了</b>，而且<b>磁盘上什么都没留下</b>。
     *
     * <p>断言「磁盘上什么都没有」而不是「状态是超时」：
     * 状态只是说法，<b>文件才是后果</b>。
     */
    @Test
    @DisplayName("超时的任务真的停了，且磁盘上没留下任何产物")
    void 超时的任务真的停了且不留产物() throws Exception {
        TaskRunner runner = new TaskRunner(jobs, Executors.newFixedThreadPool(2), 时限毫秒, 时钟::get);
        try {
            TaskRunner.Task t = 提交(runner, "大文档.docx", docx(4000));
            等开跑(t);

            // 前提断言：任务必须还在跑。
            // 它要是已经跑完了，「超时」这件事根本无从发生——
            // 这时必须**明确报错**，而不是让后面那句「期望 timed_out」含含糊糊地红。
            assertEquals(TaskRunner.Status.RUNNING, t.status(),
                    "前提：任务必须还在跑（它跑完了就测不出超时——样本要更大，或让它更慢）");

            推过时限(t);

            TaskRunner.Task 终 = 等到终态(runner, t);

            assertEquals(终态_超时, 终.status().wire(),
                    "超时的终态必须能被说成「超时」，不能是别的");
            assertNotNull(终.error, "超时必须**说清楚为什么停**——用户需要知道不是自己弄坏的");
            assertEquals(0, jobs.size(),
                    "被超时的任务不该登记成作业——那会让它出现在下载列表里");
            assertEquals(0, 会话文件数(),
                    "超时后磁盘上不能留下任何文件：用户以为没上传过，实际却躺在会话目录里");
            assertTrue(runner.timedOutCount() >= 1, "超时次数必须被记下来，供 /api/health 如实报出");
        } finally {
            runner.close();
        }
    }

    /**
     * 超时的终态<b>不能</b>是 canceled。
     *
     * <p>这条看着像是重复上一条，但它防的是<b>另一种错误</b>：
     * 有人图省事，把超时直接复用取消那条路，
     * 状态改一改就完事——那么用户看到的就是「已取消」，
     * 他会以为是自己按的，而事实上是服务把它掐了。
     * 上一条在「终态恰好等于 canceled」时会红，这条则把它钉死成一条独立判据。
     */
    @Test
    @DisplayName("超时的终态与「用户自己取消」可区分（不能偷懒复用 canceled）")
    void 超时的终态与取消可区分() throws Exception {
        TaskRunner runner = new TaskRunner(jobs, Executors.newFixedThreadPool(2), 时限毫秒, 时钟::get);
        try {
            TaskRunner.Task t = 提交(runner, "大文档2.docx", docx(4000));
            等开跑(t);
            assertEquals(TaskRunner.Status.RUNNING, t.status(),
                    "前提：任务必须还在跑（它跑完了就测不出超时）");
            推过时限(t);

            TaskRunner.Task 终 = 等到终态(runner, t);

            assertNotEquals(终态_取消, 终.status().wire(),
                    "超时的终态不能是 canceled：用户会以为是自己按的取消");
            assertTrue(快照里说超时(runner, 终),
                    "快照里必须能看出这次是被超时中止的（timedOut 标记）");
        } finally {
            runner.close();
        }
    }

    /**
     * 用户自己取消的任务，终态仍然是 canceled，**不是** timed_out。
     *
     * <p>这是上一条的<b>反向对照</b>：如果实现把「所有非完成的终态」都写成超时，
     * 上一条会绿、这条会红。两条合起来才把两个方向都钉住。
     */
    @Test
    @DisplayName("用户自己取消的终态是 canceled，不是 timed_out（反向对照）")
    void 用户取消的终态是canceled() throws Exception {
        TaskRunner runner = new TaskRunner(jobs, Executors.newFixedThreadPool(2), 时限毫秒, 时钟::get);
        try {
            TaskRunner.Task t = 提交(runner, "大文档3.docx", docx(4000));
            等开跑(t);
            // 这时候时钟**不动**——看门狗永远看不到「过了时限」，
            // 所以这条测的纯粹是「用户自己按的取消」这一条路。
            assertTrue(runner.cancel(t.taskId()), "存在的任务必须能被取消");

            TaskRunner.Task 终 = 等到终态(runner, t);
            assertEquals(终态_取消, 终.status().wire(),
                    "用户按的取消必须仍然是 canceled，不能被说成超时");
            assertFalse(快照里说超时(runner, 终),
                    "用户主动取消的任务不该被标成超时中止");
        } finally {
            runner.close();
        }
    }

    // -------------------------------------------------------------- 不误伤

    /**
     * 时限内跑完的任务<b>不受影响</b>。
     *
     * <p>这条防的是「一律超时」这种最坏情况：如果看门狗写错了条件
     * （比如把「超过时限」写成「已启动」），那么<b>每一条</b>任务都会被掐掉，
     * 而只测「能超时」的话，那种实现照样全绿。
     *
     * <p><b>这条必须用真实时钟</b>：注入时钟且不推进的话，时限永远不会到，
     * 那这条断言就变成了「永远为真」——测不出「一律超时」这个缺陷。
     * 所以这里用默认构造器（系统时钟）+ 宽松的 60 秒时限：
     * 三段的小文档在 60 秒内跑完是有巨大余量的事实。
     */
    @Test
    @DisplayName("时限内跑完的任务不受影响（防止「一律超时」）")
    void 时限内跑完的任务不受影响() throws Exception {
        TaskRunner runner = new TaskRunner(jobs, 60_000L);
        try {
            TaskRunner.Task t = 提交(runner, "小文档.docx", docx(3));

            TaskRunner.Task 终 = 等到终态(runner, t);
            assertEquals(终态_完成, 终.status().wire(),
                    "时限内的正常任务必须跑完，不能被误判成超时");
            assertFalse(快照里说超时(runner, t), "没超时的任务不该带超时标记");
            assertEquals(0, runner.timedOutCount(), "没有任务超时，超时计数就该是 0");
        } finally {
            runner.close();
        }
    }

    /**
     * 排队等待<b>不计入</b>时限。
     *
     * <p>用两个长任务占满工作线程（{@link TaskRunner#WORKERS} = 2），
     * 第三个必然在队列里等。如果时限从<b>提交</b>算起，第三个任务会在
     * **还没开跑**的时候就被判超时——用户会看到「它刚排上队就被超时干掉了」。
     *
     * <p>注入时钟让这条判据变得完全确定：
     * 前两个开跑后，把时钟推过它们的时限 → 它们超时；
     * 第三个这时才轮到开跑，它的时限是<b>推进之后</b>才起的 → 它不该超时。
     * <br>
     * 如果实现错成「从提交算」，第三个的时限在提交时就已经起算，
     * 推过时钟之后它立刻被判超时 → 这条会红。
     */
    @Test
    @DisplayName("排队等待不计入时限（时限从开跑算，不是从提交算）")
    void 排队等待不计入时限() throws Exception {
        TaskRunner runner = new TaskRunner(jobs, Executors.newFixedThreadPool(2), 时限毫秒, 时钟::get);
        try {
            TaskRunner.Task a = 提交(runner, "占位1.docx", docx(4000));
            TaskRunner.Task b = 提交(runner, "占位2.docx", docx(4000));
            TaskRunner.Task c = 提交(runner, "排队.docx", docx(3));

            // 前两个先开跑（第三个在队列里等）
            等开跑(a);
            等开跑(b);
            assertEquals(TaskRunner.Status.RUNNING, c.status(),
                    "前提：第三个任务此刻应当还在排队（没开跑）");

            // 把时钟推过前两个的时限 → 它们超时并腾出位置。
            // 推进量取 100 秒而不是「刚好过线」：
            // 差 1 毫秒的余量会让这条测试在慢一点的机器上翻车，
            // 而这条要验的是**性质**（排队不算进时限），不是**时刻**。
            时钟.set(Math.max(a.deadlineAt, b.deadlineAt) + 100_000L);

            // 第三个这时才开跑，它的时限是从**现在**起的
            等开跑(c);
            assertTrue(c.deadlineAt > 时钟.get(),
                    "前提：第三个任务的时限必须是从它开跑那一刻算起的（不能从提交算）");

            TaskRunner.Task 终c = 等到终态(runner, c);
            assertEquals(终态_完成, 终c.status().wire(),
                    "排队等的那个任务不该被判超时——时限必须从开跑算起");
            assertFalse(快照里说超时(runner, 终c), "排队等待不该给它盖上超时标记");
        } finally {
            runner.close();
        }
    }

    /**
     * 关掉时限（&lt;= 0）就没有超时这回事。
     *
     * <p>「不设时限」必须真的等于「不设」：看门狗一旦被创建，
     * 它会一直扫；扫描本身不是问题，<b>误判</b>才是。
     */
    @Test
    @DisplayName("时限 <= 0 表示不设时限（看门狗不创建）")
    void 时限非正表示不设时限() throws Exception {
        TaskRunner runner = new TaskRunner(jobs, 0L);
        try {
            assertEquals(0L, runner.timeoutMs(), "时限 <= 0 时应当被归一为「不设时限」");
            TaskRunner.Task t = 提交(runner, "小文档2.docx", docx(3));
            TaskRunner.Task 终 = 等到终态(runner, t);
            assertEquals(终态_完成, 终.status().wire(), "不设时限时任务应当正常跑完");
        } finally {
            runner.close();
        }
    }

    // ------------------------------------------------------ 与执行器的关系

    /**
     * 看门狗不能妨碍任务的正常执行路径。
     *
     * <p>这条用<b>被闩锁住的工作线程池</b>：任务被提交但<b>没有真正开跑</b>。
     * 这时看门狗会扫到它——但它<b>不能</b>把它判成超时，
     * 因为它压根还没开跑（时限从开跑算，{@code deadlineAt} 还是 0）。
     *
     * <p>如果看门狗的判据写错（「只要在表里就算超时」），这条会红。
     */
    @Test
    @DisplayName("还没开跑的任务不会被看门狗判超时（deadline 未打上就不算数）")
    void 没开跑的任务不会被判超时() throws Exception {
        CountDownLatch 闩 = new CountDownLatch(1);
        ExecutorService 卡住的 = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        // 先占住唯一的线程
        卡住的.submit(() -> {
            try {
                闩.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        TaskRunner runner = new TaskRunner(jobs, 卡住的, 时限毫秒, 时钟::get);
        try {
            TaskRunner.Task t = 提交(runner, "没开跑.docx", docx(3));

            // 把时钟推得远远越过时限：看门狗下一轮就会看到「早就过了时限」。
            // 而这个任务压根没开跑，deadline 还没打上——所以它不该被判超时。
            时钟.set(时钟.get() + 100_000L);

            Thread.sleep(1_500L);   // 足够看门狗扫好几轮（周期 250ms）

            assertEquals(TaskRunner.Status.RUNNING, t.status(),
                    "还没开跑的任务不该被判超时：时限是从开跑那一刻算起的");
            assertFalse(快照里说超时(runner, t), "没开跑的任务不该带超时标记");
        } finally {
            runner.close();
            闩.countDown();
            卡住的.shutdownNow();
        }
    }
}
