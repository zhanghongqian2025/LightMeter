package com.litemask.server;

import com.litemask.docx.DocxFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传<b>先落盘</b>这件事的性质。
 *
 * <p><b>这块在补什么缺口</b>：后台任务从「提交」到「被工作线程捡起来」之间，
 * 上传的内容一直在某个地方等着。以前它等在<b>堆</b>里（一个 byte[]），
 * 而任务表上限是 64、上传上限是 256MB——
 * 于是「连着提交 64 份大文件」在理论上就能把内存撑爆，
 * 而那只是用户多拖了几个文件而已。
 * 改成先落盘之后，等的那份内容变成了磁盘空间。
 *
 * <p><b>主判据是「提交那一刻内容已经在磁盘上」</b>：
 * 它对着旧实现（内容只在堆里）会红，所以它承重。
 * 其余几条（不留半截文件、认领与丢弃、上限可配）是纵深。
 *
 * <p><b>怎么把「还没被捡起来」那个窗口撑开</b>：
 * 正常情况下它只有几毫秒，测试根本抓不住。
 * 这里传一个<b>只收不跑</b>的执行器进去（见 {@link 攒着的执行器}），
 * 窗口就一直开着，断言也就不会变成一场竞态比赛。
 */
class TaskUploadStagingTest {

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

    // ------------------------------------------------------------ 工具

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

    private Workspace.StagedFile 暂存(String 名字, byte[] 内容) throws IOException {
        try {
            return workspace.stage(new ByteArrayInputStream(内容), 名字);
        } catch (Workspace.UnsupportedFileTypeException | Workspace.FileTooLargeException e) {
            throw new IOException("测试样本有问题：" + 名字, e);
        }
    }

    /** 会话目录里的普通文件（按大小排，好找）。 */
    private List<Path> 会话文件() throws IOException {
        try (var s = Files.list(workspace.sessionDir())) {
            List<Path> out = new ArrayList<>();
            s.filter(Files::isRegularFile).forEach(out::add);
            return out;
        }
    }

    /** 暂存件：文件名以 upload- 开头、以 .tmp 结尾。 */
    private List<Path> 暂存件() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path p : 会话文件()) {
            String n = p.getFileName().toString();
            if (n.startsWith("upload-") && n.endsWith(".tmp")) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 一个<b>只收不跑</b>的执行器。
     *
     * <p>任务被提交进来但一直不执行，于是「任务还没被工作线程捡起来」这个状态
     * 可以一直保持——那正是要验的那个窗口。
     */
    private static final class 攒着的执行器 extends AbstractExecutorService {
        final List<Runnable> 攒着 = new ArrayList<>();
        boolean shutdown;

        @Override
        public void execute(Runnable command) {
            攒着.add(command);
        }

        @Override
        public void shutdown() {
            shutdown = true;
            攒着.clear();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> queued = List.copyOf(攒着);
            攒着.clear();
            return queued;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    // ------------------------------------------------------------ 落盘

    /**
     * 暂存件落盘，内容一字不差。
     *
     * <p>它是整块的地基：暂存件要是写歪了（截断、少一段），
     * 后面的脱敏就是在处理一份残缺的原文档——
     * 而界面上会显示「成功」。
     */
    @Test
    @DisplayName("暂存件落盘且内容与上传完全一致")
    void 暂存件落盘且内容一致() throws Exception {
        byte[] 内容 = docx(3);
        Workspace.StagedFile staged = 暂存("样本.docx", 内容);

        assertTrue(Files.exists(staged.path()), "暂存件必须在磁盘上");
        assertEquals(内容.length, Files.size(staged.path()),
                "落盘的长度必须与上传一致：截断的原文档脱敏出来是「成功」的废品");
        assertArrayEquals(内容, Files.readAllBytes(staged.path()),
                "落盘的内容必须与上传一字不差");
        assertEquals(".docx", staged.extension());
        assertTrue(workspace.sessionDir().normalize()
                .startsWith(staged.path().getParent().normalize()),
                "暂存件必须落在会话目录里——否则进程退出时没人能把它清掉");
    }

    /**
     * 不支持的后缀：<b>连盘都不占</b>。
     *
     * <p>先判后缀再写盘：为一个注定要被拒的文件占磁盘没有意义，
     * 而且留下的半截文件看起来像一份完整的原文档。
     */
    @Test
    @DisplayName("不支持的后缀：直接拒，连磁盘都不占")
    void 不支持的后缀不占盘() throws Exception {
        long 前 = 会话文件().size();
        assertThrows(Workspace.UnsupportedFileTypeException.class,
                () -> workspace.stage(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                        "样本.exe"),
                "不支持的后缀必须在落盘前就被拒");
        assertEquals(前, 会话文件().size(),
                "被拒的上传不该在磁盘上留下任何东西（半截文件看起来像一份完整的原文档）");
    }

    /**
     * 超限：抛异常，而且<b>不留半截文件</b>。
     *
     * <p>上限用实例参数而不是常量——不然这条测试要么得真造 256MB，
     * 要么得为迁就测试把上限改小，而改小的上限就不是生产用的那个上限了。
     */
    @Test
    @DisplayName("超限：报错，而且不留半截文件")
    void 超限不留半截文件() throws Exception {
        try (Workspace 小 = Workspace.open(根.resolve("小工作区"), 1024)) {
            byte[] 太大 = new byte[4096];
            assertThrows(Workspace.FileTooLargeException.class,
                    () -> 小.stage(new ByteArrayInputStream(太大), "大文件.docx"),
                    "超过上限必须被拒");
            try (var s = Files.list(小.sessionDir())) {
                assertEquals(0, s.filter(Files::isRegularFile).count(),
                        "超限之后不能留下半截文件：它看起来像一份完整的原文档");
            }
        }
    }

    /** 认领：暂存件被挪成正式件，内容不变。 */
    @Test
    @DisplayName("认领：暂存件被挪走，正式件在会话目录里，内容不变")
    void 认领把暂存件挪成正式件() throws Exception {
        byte[] 内容 = docx(2);
        Workspace.StagedFile staged = 暂存("样本.docx", 内容);
        Path 原来的位置 = staged.path();

        Workspace.StoredFile stored = workspace.store(staged);

        assertFalse(Files.exists(原来的位置), "认领之后暂存件不该还在原处");
        assertTrue(Files.exists(stored.path()), "正式件必须在会话目录里");
        assertEquals(内容.length, Files.size(stored.path()));
        assertEquals(".docx", stored.extension());
        assertEquals("样本.docx", stored.displayName());
    }

    /** 丢弃：没人认领的暂存件必须被删掉。 */
    @Test
    @DisplayName("丢弃：没人认领的上传不会留在盘上")
    void 丢弃删掉暂存件() throws Exception {
        Workspace.StagedFile staged = 暂存("样本.docx", docx(1));
        assertTrue(Files.exists(staged.path()));

        workspace.discard(staged);

        assertFalse(Files.exists(staged.path()),
                "没人认领的上传必须被删掉：用户以为没上传成功，原文档却躺在磁盘上");
    }

    // ------------------------------------------------------------ 任务侧

    /**
     * <b>主判据</b>：提交那一刻，内容已经在磁盘上了。
     *
     * <p>旧实现里提交之后磁盘上什么都没有（内容在堆里等着），
     * 所以这条断言对着旧实现会红——它承重。
     *
     * <p>用「只收不跑」的执行器把窗口撑开，于是「任务还没被捡起来」是确定的事实，
     * 不是一场和机器快慢的赛跑。
     */
    @Test
    @DisplayName("提交那一刻内容已经在磁盘上（等任务时内存里没有那份内容）")
    void 提交那一刻内容已落盘() throws Exception {
        byte[] 内容 = docx(4);
        攒着的执行器 池 = new 攒着的执行器();
        TaskRunner runner = new TaskRunner(jobs, 池);
        try {
            Workspace.StagedFile staged = 暂存("样本.docx", 内容);
            TaskRunner.Task t = runner.submitScan(staged);

            // 任务被攒着，没被捡起来：此刻内容必须已经在盘上
            assertEquals(1, 池.攒着.size(), "任务应当还没被执行");
            assertEquals(TaskRunner.Status.RUNNING, t.status(),
                    "还没开始跑的任务状态应当是 running");

            List<Path> 暂存 = 暂存件();
            assertEquals(1, 暂存.size(),
                    "提交之后、内容被工作线程捡起来之前，磁盘上必须已经有那份内容（实际 "
                            + 会话文件().size() + " 个文件）");
            assertEquals(内容.length, Files.size(暂存.get(0)),
                    "落盘的长度必须与上传一致");
        } finally {
            runner.close();
        }
    }

    /**
     * 任务跑完之后：会话目录里<b>没有暂存件残留</b>。
     *
     * <p>暂存件要么被「认领」成正式件（挪走了），要么被清掉——
     * 没有第三条路。
     *
     * <p><b>等的是「任务到终态」，不是「暂存件消失」</b>。
     * 这两个是两件事：暂存件是在工作线程<b>刚起步</b>时就被认领（挪走）的，
     * 那一刻扫描还在跑、产物还在往会话目录里写。
     * 早先这里等的是「暂存件消失」，于是测试经常在扫描还没结束时就返回；
     * JUnit 紧接着删临时目录，而工作线程还在往里写 →
     * 目录删不掉 → 这条测试报 ERROR（而且错报在它自己头上）。
     * → 前提不成立就没有资格断言：先等终态，并把「真的到终态了」写成显式断言。
     */
    @Test
    @DisplayName("任务跑完后没有暂存件残留")
    void 任务跑完后无暂存件残留() throws Exception {
        TaskRunner runner = new TaskRunner(jobs);
        try {
            TaskRunner.Task t = runner.submitScan(暂存("样本.docx", docx(3)));

            long 截止 = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < 截止
                    && t.status() == TaskRunner.Status.RUNNING) {
                Thread.sleep(10);
            }
            // 前提断言：任务必须真的跑到终态了。
            // 不到终态就断言「没有残留」是拿「还没开始」冒充「已经结束」——
            // 那种断言在实现坏掉时也不会红。
            assertNotEquals(TaskRunner.Status.RUNNING, t.status(),
                    "任务必须在 30 秒内跑到终态（实际状态：" + t.status() + "）");
            assertTrue(暂存件().isEmpty(),
                    "任务终态之后不能还有暂存件：它是一份没人认领的原文档（实际 "
                            + 暂存件().size() + " 个）");
        } finally {
            runner.close();
        }
    }

    /**
     * 任务表满了：<b>没人认领的上传不留盘</b>。
     *
     * <p>被拒的提交对用户来说就是「没传成」——文件却还躺在磁盘上，
     * 那等于「用户以为没上传过、原文档却还在」。
     */
    @Test
    @DisplayName("任务表满：被拒的那份上传不留盘")
    void 任务表满时上传不留盘() throws Exception {
        攒着的执行器 池 = new 攒着的执行器();
        TaskRunner runner = new TaskRunner(jobs, 池);
        try {
            for (int i = 0; i < TaskRunner.MAX_TASKS; i++) {
                runner.submitScan(暂存("慢-" + i + ".docx", docx(1)));
            }
            Workspace.StagedFile 被拒的 = 暂存("挤爆.docx", docx(1));
            Path 位置 = 被拒的.path();
            assertTrue(Files.exists(位置), "被拒的那份在提交前是落盘的");

            assertThrows(IOException.class, () -> runner.submitScan(被拒的),
                    "表满且挤不掉时必须拒绝新任务");
            assertFalse(Files.exists(位置),
                    "被拒的上传必须被清掉：用户已经收到拒绝了，文件却还在盘上");
        } finally {
            runner.close();
        }
    }

    /**
     * <b>辅助判据</b>：提交任务不再接受上传字节。
     *
     * <p>这条是**辅助**——真正承重的是上面那条「提交那一刻内容已落盘」。
     * 这一条只守住 API 的形状：一旦有人把 {@code submitScan(String, byte[])}
     * 加回来，「内容在内存里等着」这条老路就又通了，而单看别的测试未必发现得了。
     *
     * <p>它自己也带一条前提断言：{@code submitScan} 至少得存在一个重载，
     * 否则这个循环空转，恒为真。
     */
    @Test
    @DisplayName("提交任务不再接受上传字节（那条老路不能被加回来）")
    void 提交不接受上传字节() {
        int 重载数 = 0;
        for (Method m : TaskRunner.class.getMethods()) {
            if (!"submitScan".equals(m.getName())) {
                continue;
            }
            重载数++;
            for (Class<?> p : m.getParameterTypes()) {
                assertNotSame(byte[].class, p,
                        "submitScan 不该再接受上传字节（参数 " + p.getName()
                                + "）：那份内容会一直等在堆里，64 份 256MB 就能撑爆内存");
            }
        }
        assertTrue(重载数 > 0,
                "submitScan 必须存在——否则上面那条断言什么都没验（恒真）");
    }

    // ------------------------------------------------------------ 小工具

}
