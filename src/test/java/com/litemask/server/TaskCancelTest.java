package com.litemask.server;

import com.litemask.core.Exclusions;
import com.litemask.core.TaskContext;
import com.litemask.docx.DocxFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消的语义：<b>停得下来，而且什么都没留下</b>。
 *
 * <p><b>为什么取消必须验「磁盘上什么都没留下」</b>：用户点「取消」时，
 * 心里想的是「我没要这份文件，别给我留一份」。
 * 如果取消只是打个标记，而原件与半成品还躺在会话目录里，
 * 那就是一份用户以为没上传过、实际却躺在磁盘上的敏感文档——
 * 对一个脱敏工具来说，这是最不能出现的那类落差。
 *
 * <p><b>为什么这里不走 HTTP</b>：取消要「在管线中途生效」才说明它真的接上了检查点。
 * 走 HTTP 时「中途」取决于机器快慢，测试会变成一场竞态比赛。
 * 这里直接用管线本身，让取消在<b>第一次进度上报</b>时触发——
 * 那是一个确定的时刻，而且正好在引擎入口的检查点之前。
 * HTTP 那层（令牌、状态码、报文形状）由 {@link TaskApiTest} 负责。
 */
class TaskCancelTest {

    private static final String PHONE = "13800138000";

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

    /**
     * 落盘并提交一个扫描任务。
     *
     * <p>走的是和 HTTP 那条一样的路：先落盘成暂存件，再交给任务。
     * 测试不自己造一份「字节进任务」的捷径——
     * 那条捷径已经被生产代码删掉了，测试要是还留着它，
     * 验的就不是生产上跑的那条路了。
     */
    private TaskRunner.Task 提交(TaskRunner runner, String 名字, byte[] 内容) throws IOException {
        try {
            return runner.submitScan(workspace.stage(new ByteArrayInputStream(内容), 名字));
        } catch (Workspace.UnsupportedFileTypeException | Workspace.FileTooLargeException e) {
            // 本测试里的名字都是 .docx/.txt，不会走到这里；
            // 真走到了说明测试自己写错了名字，报出来比静默吞掉好。
            throw new IOException("测试样本的后缀有问题：" + 名字, e);
        }
    }

    /** 会话目录里现在有几个普通文件。 */
    private long 会话文件数() throws IOException {
        try (var s = Files.list(workspace.sessionDir())) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    // ------------------------------------------------------------ 取消不留痕

    @Test
    @DisplayName("同一上下文只有第一个取消请求能赢得线性化边界")
    void 重复取消只有第一次成功() {
        TaskContext ctx = TaskContext.create(null);

        assertTrue(ctx.requestCancel(), "第一次取消应当生效");
        assertFalse(ctx.requestCancel(),
                "后来的超时或重复点击不能夺走已记录的取消原因");
        assertTrue(ctx.isCancelled());
    }

    /**
     * 取消之后：没有作业、没有文件。
     *
     * <p>取消由进度回调触发——那是管线里一个<b>确定</b>的时刻，
     * 不必和机器快慢赛跑。
     */
    @Test
    @DisplayName("取消后不留任何产物（原件、半成品都没了，也没登记成作业）")
    void 取消后不留任何产物() throws IOException {
        AtomicReference<TaskContext> 自引用 = new AtomicReference<>();
        List<int[]> 进度 = new ArrayList<>();
        TaskContext ctx = TaskContext.create((phase, done, total) -> {
            进度.add(new int[]{done, total});
            自引用.get().cancel();
        });
        自引用.set(ctx);

        assertThrows(TaskContext.CanceledException.class,
                () -> jobs.scanStaged(
                        workspace.stage(new ByteArrayInputStream(docx(6)), "样本.docx"), ctx),
                "取消后管线必须抛取消异常，不能悄悄跑完");

        assertEquals(0, jobs.size(), "取消的任务不该登记成作业——那会让它出现在下载列表里");
        assertEquals(0, 会话文件数(),
                "取消后磁盘上不能留下任何文件：用户以为没上传过，实际却躺在会话目录里");
        assertFalse(进度.isEmpty(), "应当至少报过一次进度，否则取消根本没接上管线");
    }

    /**
     * 对照组：没取消时管线照常跑完。
     *
     * <p>取消机制是加在管线上的检查点。如果这套机制写错了——
     * 比如作用域没收尾、标志位默认就是真的——那么<b>每一条</b>管线都会在半路停掉。
     * 那是个「全绿但全错」的方向：只测「能取消」的话，
     * 「永远在取消」也会让那条测试通过。
     */
    @Test
    @DisplayName("没取消时管线照常跑完（对照组：防止「永远在取消」）")
    void 没取消时照常跑完() throws IOException {
        List<int[]> 进度 = new ArrayList<>();
        TaskContext ctx = TaskContext.create((phase, done, total) -> 进度.add(new int[]{done, total}));

        Path 源 = 夹具目录.resolve("对照样本.docx");
        Files.write(源, docx(4));
        Path 成品 = 夹具目录.resolve("对照样本.out.docx");

        DocumentService.Result r = TaskContext.in(ctx, () -> DocumentService.redact(源, 成品,
                DocumentService.Format.DOCX, ApiHandlers.defaultRules(),
                Exclusions.NONE, List.of()));

        assertTrue(Files.exists(成品), "没取消就应当产出成品");
        assertTrue(r.occurrences() > 0, "样本文档里的手机号应当被命中");
        assertFalse(进度.isEmpty(), "应当报过进度");
        assertFalse(ctx.isCancelled(), "没取消过的上下文不该显示已取消");
    }

    // ------------------------------------------------------------ 进度契约

    /**
     * 进度必须单调递增，而且总量报 -1。
     *
     * <p>总量事先真的算不出来（docx 归组前有多少段，取决于用户怎么排版），
     * 所以报 -1 是诚实做法。报一个估算值会让人以为它可信。
     */
    @Test
    @DisplayName("进度单调递增，总量报 -1（不糊一个假百分比）")
    void 进度单调递增() throws IOException {
        List<int[]> 记录 = new ArrayList<>();
        TaskContext ctx = TaskContext.create((phase, done, total) -> 记录.add(new int[]{done, total}));

        Path 源 = 夹具目录.resolve("进度样本.docx");
        Files.write(源, docx(6));
        Path 成品 = 夹具目录.resolve("进度样本.out.docx");

        TaskContext.in(ctx, () -> DocumentService.redact(源, 成品,
                DocumentService.Format.DOCX, ApiHandlers.defaultRules(),
                Exclusions.NONE, List.of()));

        assertFalse(记录.isEmpty(), "应当报过进度");
        // 样本必须多于一条，否则「单调递增」这句话无从谈起：
        // 只报一次的话，把 done 永远钉成 1 也能过这条断言。
        assertTrue(记录.size() >= 2,
                "样本文档应当产生多段（实际 " + 记录.size() + " 段）——只有一段的话验不了单调性");
        int 上一 = 0;
        for (int[] 一次 : 记录) {
            assertTrue(一次[0] > 上一,
                    "进度必须单调递增：上一次 " + 上一 + "，这一次 " + 一次[0]);
            assertEquals(-1, 一次[1], "总量事先算不出来，必须报 -1 而不是估一个数");
            上一 = 一次[0];
        }
    }

    // ------------------------------------------------------------ 任务表状态机

    /**
     * 排队中的任务被取消 → 终态是 canceled，且没留下作业。
     *
     * <p><b>怎么保证它真的在排队</b>：工作线程只有 {@link TaskRunner#WORKERS} 个，
     * 所以先用两个<b>慢</b>任务把两个线程都占住（等它们的 {@code done} 真的开始涨，
     * 说明各自都在跑），这时提交的第三个任务必然在队列里。
     * 这样「取消一个还没开始的任务」就是确定的事实，而不是和机器快慢赛跑。
     */
    @Test
    @DisplayName("排队中的任务被取消后终态是 canceled，且没登记成作业")
    void 排队中的任务被取消后终态是canceled() throws Exception {
        TaskRunner runner = new TaskRunner(jobs);
        try {
            TaskRunner.Task a = 提交(runner, "a.docx", docx(1500));
            TaskRunner.Task b = 提交(runner, "b.docx", docx(1500));
            // 等两个都真的开跑：done 涨了，且还处在 RUNNING
            等到开跑(runner, a);
            等到开跑(runner, b);

            TaskRunner.Task c = 提交(runner, "c.docx", docx(2));
            assertTrue(runner.cancel(c.taskId()), "存在的任务必须能被取消");

            TaskRunner.Task 终 = 等到终态(runner, c);
            assertEquals(TaskRunner.Status.CANCELED, 终.status(),
                    "被取消的任务终态必须是 canceled");
            assertNull(终.jobId, "取消的任务不该有作业号——有作业号就意味着界面上会出现它");

            // 把两个慢任务也收掉，别让它们在测试结束后还在后台跑
            runner.cancel(a.taskId());
            runner.cancel(b.taskId());
        } finally {
            runner.close();
        }
    }

    /** 取消一个不存在的任务：如实返回 false，不要假装受理了。 */
    @Test
    @DisplayName("取消不存在的任务返回 false")
    void 取消不存在的任务返回false() throws IOException {
        TaskRunner runner = new TaskRunner(jobs);
        try {
            assertFalse(runner.cancel("根本没有这个任务号"),
                    "不存在的任务不能假装取消成功");
            assertNull(runner.task("根本没有这个任务号"));
        } finally {
            runner.close();
        }
    }

    /**
     * 取消一个<b>已经跑完</b>的任务是空操作。
     *
     * <p>那份活已经做完了、文件已经产出了，把它改成 canceled 只会让界面
     * 显示「已取消」而磁盘上明明有一份成品——那是撒谎。
     */
    @Test
    @DisplayName("取消已结束的任务不改变它的状态（活已经做完了）")
    void 取消已结束的任务不改状态() throws Exception {
        TaskRunner runner = new TaskRunner(jobs);
        try {
            TaskRunner.Task t = 提交(runner, "样本.txt",
                    ("联系电话 " + PHONE + "\n").getBytes(StandardCharsets.UTF_8));
            TaskRunner.Task 终 = 等到终态(runner, t);
            assertEquals(TaskRunner.Status.DONE, 终.status());
            assertFalse(终.context.requestCancel(),
                    "结果提交已经赢得线性化边界，晚到的取消不能再宣称成功");

            assertTrue(runner.cancel(t.taskId()), "任务存在，所以取消请求被受理");
            assertFalse(终.cancelRequested,
                    "兼容的 true 只表示任务存在，不能把已提交任务标成取消中");
            assertEquals(TaskRunner.Status.DONE, 终.status(),
                    "已跑完的任务不能被改成 canceled：磁盘上明明有一份成品");
        } finally {
            runner.close();
        }
    }

    /** 快照的字段要齐全：界面全靠它画进度条。 */
    @Test
    @DisplayName("任务快照字段齐全（界面靠它画进度）")
    void 快照字段齐全() throws Exception {
        TaskRunner runner = new TaskRunner(jobs);
        try {
            TaskRunner.Task t = 提交(runner, "样本.txt",
                    ("联系电话 " + PHONE + "\n").getBytes(StandardCharsets.UTF_8));
            等到终态(runner, t);
            Map<String, Object> snap = runner.snapshot(t);
            assertEquals(Boolean.TRUE, snap.get("ok"));
            assertEquals(t.taskId(), snap.get("taskId"));
            assertEquals("done", snap.get("status"));
            assertNotNull(snap.get("phase"), "快照里应有阶段名");
            assertNotNull(snap.get("done"), "快照里应有已处理数");
            assertEquals(-1, snap.get("total"), "总量未知时应报 -1");
            assertNotNull(snap.get("message"), "快照里应有一句人能看懂的说明");
            assertNotNull(snap.get("elapsedMs"), "快照里应有耗时");
        } finally {
            runner.close();
        }
    }

    // ------------------------------------------------------------ 等待助手

    /**
     * 任务表满、而且挤不掉任何已完成任务时，<b>拒绝</b>新任务。
     *
     * <p>替代方案是「默默删掉一个正在跑的任务」——那样用户就再也问不到它的结果了：
     * 界面上那个任务号变成「不存在」，而它其实还在跑、跑完还会占着磁盘。
     *
     * <p><b>怎么保证表真的满</b>：用一份<b>慢</b>文档把工作线程占住，
     * 交满 {@link TaskRunner#MAX_TASKS} 个任务后，它们全都还处在 RUNNING
     * （跑着的两个 + 排队的若干个），此时再交一个必须被拒。
     */
    @Test
    @DisplayName("任务表满且都在跑时拒绝新任务（不悄悄删掉正在跑的任务）")
    void 任务表满时拒绝新任务() throws Exception {
        TaskRunner runner = new TaskRunner(jobs);
        List<TaskRunner.Task> 已交 = new ArrayList<>();
        try {
            byte[] 慢文档 = docx(1500);
            for (int i = 0; i < TaskRunner.MAX_TASKS; i++) {
                已交.add(提交(runner, "慢-" + i + ".docx", 慢文档));
            }
            // 全都还处在 RUNNING：跑着的两个在处理慢文档，排队的那些还没开始
            for (TaskRunner.Task t : 已交) {
                assertEquals(TaskRunner.Status.RUNNING, t.status(),
                        "交进去的任务应当都还在 RUNNING（排队中的也算），实际 " + t.status());
            }
            IOException e = assertThrows(IOException.class,
                    () -> 提交(runner, "挤爆.docx", 慢文档),
                    "表满且挤不掉时必须拒绝新任务，不能默默删掉一个正在跑的任务");
            assertTrue(e.getMessage() != null && e.getMessage().contains("太多"),
                    "拒绝时要说清楚为什么：" + e.getMessage());
            // 正在跑的那些一个都没少
            for (TaskRunner.Task t : 已交) {
                assertNotNull(runner.task(t.taskId()),
                        "正在跑的任务不能被悄悄从表里删掉——那样用户再也问不到它的结果");
            }
        } finally {
            // 收干净：取消 + 等它们真的停下，别让后台任务在下一个用例里还在写盘。
            for (TaskRunner.Task t : 已交) {
                runner.cancel(t.taskId());
            }
            long 截止 = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < 截止) {
                boolean 还有在跑 = false;
                for (TaskRunner.Task t : 已交) {
                    if (t.status() == TaskRunner.Status.RUNNING) {
                        还有在跑 = true;
                    }
                }
                if (!还有在跑) {
                    break;
                }
                Thread.sleep(10);
            }
            runner.close();
        }
    }

    /** 等这个任务真的开跑（进度开始涨），并且还处在 RUNNING。 */
    private static void 等到开跑(TaskRunner runner, TaskRunner.Task t) throws InterruptedException {
        long 截止 = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < 截止) {
            if (t.done >= 1 && t.status() == TaskRunner.Status.RUNNING) {
                return;
            }
            if (t.status() != TaskRunner.Status.RUNNING) {
                throw new AssertionError("任务在开跑之前就结束了，状态=" + t.status()
                        + "——测试样本不够慢，换更多段数");
            }
            Thread.sleep(2);
        }
        throw new AssertionError("等了 30 秒，任务还没开跑");
    }

    /** 等任务走到终态。 */
    private static TaskRunner.Task 等到终态(TaskRunner runner, TaskRunner.Task t)
            throws InterruptedException {
        long 截止 = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < 截止) {
            if (t.status() != TaskRunner.Status.RUNNING) {
                return t;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("等了 30 秒，任务还没结束");
    }
}
