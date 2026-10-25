package com.litemask.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.litemask.core.Exclusions;
import com.litemask.core.PrivateFile;
import com.litemask.core.TaskContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStoreApplyTransactionTest {

    @TempDir
    Path tmp;

    @Test
    void mappingWriteFailureLeavesNoPublishedOrOrphanedApplyAndRetrySucceeds() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        JobStore.MappingWriter failOnce = (path, content) -> {
            if (writes.getAndIncrement() == 0) {
                Files.writeString(path, "partial-sensitive-map", StandardCharsets.UTF_8);
                throw new IOException("injected mapping failure");
            }
            PrivateFile.write(path, content);
        };

        try (Workspace ws = Workspace.open(tmp.resolve("transaction"))) {
            JobStore jobs = new JobStore(ws, ApiHandlers.defaultRules(), List.of(),
                    com.litemask.core.ExclusiveGate.NONE, null, failOnce);
            JobStore.Job job = jobs.scan("case.txt",
                    "电话13800138000。".getBytes(StandardCharsets.UTF_8));
            long filesBeforeApply = fileCount(ws.sessionDir());
            Exclusions exclusions = Exclusions.of(List.of(
                    new Exclusions.HitRef("phone", "13800138000")));

            IOException failure = assertThrows(IOException.class,
                    () -> jobs.apply(job.jobId(), exclusions));
            assertTrue(failure.getMessage().contains("injected mapping failure"));
            assertNull(job.outputNameForTest());
            assertNull(job.mappingNameForTest());
            assertNull(job.applied);
            assertNull(job.appliedIdentity);
            assertFalse(job.mappingSealed);
            assertEquals(filesBeforeApply, fileCount(ws.sessionDir()),
                    "失败的成品和可能已写出的含原值映射都必须清掉");

            JobStore.Job retried = jobs.apply(job.jobId(), exclusions);
            assertNotNull(retried.outputNameForTest());
            assertNotNull(retried.mappingNameForTest());
            assertNotNull(retried.applied);
            assertNotNull(retried.appliedIdentity);
            assertTrue(Files.exists(ws.pathOf(retried.outputNameForTest())));
            assertTrue(Files.exists(ws.pathOf(retried.mappingNameForTest())));
            assertTrue(jobs.mappingBytes(retried).length > 0);
        }
    }

    @Test
    void cancellationAfterMappingWriteDeletesMapPreservesPreviewAndAllowsRetry() throws Exception {
        AtomicReference<TaskContext> context = new AtomicReference<>();
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        JobStore.MappingWriter cancelAfterWrite = (path, content) -> {
            PrivateFile.write(path, content);
            if (firstWrite.getAndSet(false)) {
                context.get().cancel();
            }
        };

        try (Workspace ws = Workspace.open(tmp.resolve("cancel-after-map"))) {
            JobStore jobs = new JobStore(ws, ApiHandlers.defaultRules(), List.of(),
                    com.litemask.core.ExclusiveGate.NONE, null, cancelAfterWrite);
            JobStore.Job job = jobs.scan("case.txt",
                    "电话13800138000。".getBytes(StandardCharsets.UTF_8));
            long filesBeforeApply = fileCount(ws.sessionDir());
            TaskContext ctx = TaskContext.create(null);
            context.set(ctx);

            assertThrows(TaskContext.CanceledException.class,
                    () -> jobs.applyCancellable(job.jobId(), Exclusions.NONE, ctx));
            assertNull(job.outputNameForTest());
            assertNull(job.mappingNameForTest());
            assertNull(job.applied);
            assertEquals(filesBeforeApply, fileCount(ws.sessionDir()),
                    "已写完但尚未发布的映射必须回滚，扫描预览必须保留");
            assertTrue(Files.exists(ws.pathOf(job.previewNameForTest())));

            JobStore.Job retried = jobs.apply(job.jobId(), Exclusions.NONE);
            assertNotNull(retried.outputNameForTest());
            assertNotNull(retried.mappingNameForTest());
        }
    }

    private static long fileCount(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.count();
        }
    }
}
