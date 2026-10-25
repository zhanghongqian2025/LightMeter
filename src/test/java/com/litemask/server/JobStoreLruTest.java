package com.litemask.server;

import com.litemask.core.Exclusions;
import com.litemask.docx.DocxFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务表超上限时的行为：淘汰最久没碰的那份，并把它的文件从磁盘上清掉。
 *
 * <p>判据是<b>文件系统里还剩几个文件</b>，不是任务表的条数：只在内存里抹掉条目的话，
 * 用户以为「处理完就没了」，而原始文档还躺在临时目录里——对一个脱敏工具来说，
 * 那是最不能出现的落差。
 *
 * <p>走 {@link JobStore#scan(String, byte[])} 而不是 HTTP：要连续传 65 份文件，
 * 每份起一次真实 socket 会把测试拖慢十倍，而这里验的是淘汰与清盘，与 HTTP 无关。
 */
class JobStoreLruTest {

    private static final String PHONE = "13800138000";

    private Workspace workspace;
    private JobStore jobs;
    private Path dir;
    private int 序号;

    @TempDir
    Path tmp;

    @BeforeEach
    void 起() throws IOException {
        workspace = Workspace.open(tmp);
        dir = workspace.sessionDir();
        jobs = new JobStore(workspace, ApiHandlers.defaultRules());
        序号 = 0;
    }

    @AfterEach
    void 停() {
        if (workspace != null) {
            workspace.close();
        }
    }

    /** 造一份最小可用的 docx（正文含一个手机号），当成新上传传进来。 */
    private JobStore.Job 上传一份() throws IOException {
        序号++;
        Path p = tmp.resolve("sample-" + 序号 + ".docx");
        DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "编号" + 序号 + " 电话 " + PHONE)))
                .writeTo(tmp, p.getFileName().toString());
        return jobs.scan("样本" + 序号 + ".docx", Files.readAllBytes(p));
    }

    /**
     * 传满上限（传完不触发淘汰）。
     *
     * <p>有些用例先自己传了 1 份再调这里，所以按<b>已有条数</b>补齐到上限，
     * 而不是无条件再传 {@code MAX_JOBS} 份——后者会多传一份、白白触发一次淘汰，
     * 然后断言「传满不应触发淘汰」就必然失败。那是测试自己算错了上传份数。
     */
    private void 传满() throws IOException {
        while (jobs.size() < JobStore.MAX_JOBS) {
            上传一份();
        }
        assertEquals(JobStore.MAX_JOBS, jobs.size());
        assertEquals(0, jobs.evicted(), "传到上限为止不应触发淘汰");
    }

    @Test
    @DisplayName("传满上限后继续传不报错，而是淘汰最久没碰的那份")
    void 超上限淘汰最旧() throws Exception {
        JobStore.Job first = 上传一份();
        传满();

        JobStore.Job latest = 上传一份();
        assertEquals(JobStore.MAX_JOBS, jobs.size(), "淘汰后条数应维持在上限");
        assertEquals(1, jobs.evicted());
        assertEquals(latest.jobId(), jobs.job(latest.jobId()).jobId(), "刚传的那份应当还在");
        assertThrows(IOException.class, () -> jobs.job(first.jobId()), "最早那份应被淘汰");
    }

    @Test
    @DisplayName("淘汰把磁盘上的原件与预览件一起清掉")
    void 淘汰清盘() throws Exception {
        JobStore.Job first = 上传一份();
        String source = first.sourceNameForTest();
        String preview = first.previewNameForTest();
        assertTrue(Files.exists(dir.resolve(source)), "前提：原件应落盘");
        assertTrue(Files.exists(dir.resolve(preview)), "前提：预览件应落盘");

        传满();
        上传一份();

        assertFalse(Files.exists(dir.resolve(source)),
                "被淘汰任务的原件应从磁盘删掉：用户以为处理完就没了，实际还在就是泄漏");
        assertFalse(Files.exists(dir.resolve(preview)), "被淘汰任务的预览件也应删掉");
    }

    @Test
    @DisplayName("淘汰已应用的任务时，成品件也不留在磁盘上")
    void 淘汰清掉成品件() throws Exception {
        JobStore.Job first = 上传一份();
        JobStore.Job applied = jobs.apply(first.jobId(),
                Exclusions.of(List.of(new Exclusions.HitRef("phone", PHONE))));
        String output = applied.outputNameForTest();
        assertTrue(Files.exists(dir.resolve(output)), "前提：成品件应落盘");

        传满();
        上传一份();

        assertFalse(Files.exists(dir.resolve(output)),
                "成品件同样含用户内容，不该在任务消失后仍留在磁盘上");
    }

    /**
     * 映射件也要跟着淘汰一起清掉。
     *
     * <p>映射里全是原值：留着它等于把用户文档里的原值留在磁盘上，
     * 而界面上早就看不见这个任务了——用户以为「处理完就没了」，
     * 实际上敏感数据还在。
     */
    @Test
    @DisplayName("淘汰已应用的任务时，映射件也不留在磁盘上")
    void 淘汰清掉映射件() throws Exception {
        JobStore.Job first = 上传一份();
        JobStore.Job applied = jobs.apply(first.jobId(),
                Exclusions.of(List.of(new Exclusions.HitRef("phone", PHONE))));
        String mapping = applied.mappingNameForTest();
        assertTrue(Files.exists(dir.resolve(mapping)), "前提：映射件应落盘");

        传满();
        上传一份();

        assertFalse(Files.exists(dir.resolve(mapping)),
                "映射件里全是原值，不该在任务消失后仍留在磁盘上");
    }

    @Test
    @DisplayName("淘汰的是最久没被访问的那份，不是最早传的那份")
    void 淘汰按访问顺序() throws Exception {
        List<JobStore.Job> all = new ArrayList<>();
        for (int i = 0; i < JobStore.MAX_JOBS; i++) {
            all.add(上传一份());
        }
        JobStore.Job oldest = all.get(0);
        JobStore.Job second = all.get(1);

        // 碰一下最早的那份，让它变成「最近用过」——这正是用户在挑排除项时的动作
        assertEquals(oldest.jobId(), jobs.job(oldest.jobId()).jobId());

        上传一份();

        // 判据用「磁盘上还在不在」而不是「取任务会不会抛异常」。
        // 后者在实现退化成插入序时是**抛 IOException**，测试会以 Error 而非 Failure 变红——
        // 红灯原因不对，对照实验就得判 INVALID，这条测试等于白写。
        // 磁盘状态是用户真正看得见的事实，也不会因为取不到就炸。
        assertTrue(Files.exists(dir.resolve(oldest.sourceNameForTest())),
                "刚访问过的那份不该被淘汰——那恰恰是用户正在处理的文件");
        assertFalse(Files.exists(dir.resolve(second.sourceNameForTest())),
                "真正最久没碰的第二份才该被淘汰");
        assertEquals(1, jobs.evicted());
    }

    @Test
    @DisplayName("淘汰之后会话目录里只留着还活着的任务的文件")
    void 目录里不留孤儿() throws Exception {
        传满();
        JobStore.Job keep = jobs.scan("最新.docx", Files.readAllBytes(
                造样本(tmp.resolve("keep.docx"))));

        long 文件数;
        try (var stream = Files.list(dir)) {
            文件数 = stream.count();
        }
        // 每个活着的任务最多两份（原件 + 预览件）
        assertTrue(文件数 <= JobStore.MAX_JOBS * 2L,
                "淘汰后目录里不该堆着历次被淘汰任务的残留，实际 " + 文件数);
        assertEquals(keep.jobId(), jobs.job(keep.jobId()).jobId());
    }

    @Test
    @DisplayName("淘汰计数如实累加，界面上要让用户能核对")
    void 淘汰计数() throws Exception {
        传满();
        上传一份();
        上传一份();
        上传一份();
        assertEquals(3, jobs.evicted());
        assertEquals(JobStore.MAX_JOBS, jobs.size());
    }

    private Path 造样本(Path target) throws IOException {
        DocxFixture.simple(DocxFixture.paragraph(
                DocxFixture.run(null, "电话 " + PHONE))).writeTo(target.getParent(), target.getFileName().toString());
        return target;
    }
}
