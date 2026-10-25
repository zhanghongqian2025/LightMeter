package com.litemask.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.litemask.core.EntitySource;
import com.litemask.core.Exclusions;
import com.litemask.core.Match;
import com.litemask.core.ProcessingIdentity;
import com.litemask.core.RuleLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStoreMutationRaceTest {

    private static final ProcessingIdentity MODEL_A = identity("model-a", 'a');
    private static final ProcessingIdentity MODEL_B = identity("model-b", 'b');

    @TempDir
    Path tmp;

    @Test
    void zeroExclusionsWaitsForRescanAndUsesItsCompleteSnapshot() throws Exception {
        BlockingSource source = new BlockingSource();
        AtomicReference<ProcessingIdentity> current = new AtomicReference<>(MODEL_A);
        try (Workspace ws = Workspace.open(tmp.resolve("zero"));
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            JobStore jobs = store(ws, source, current);
            JobStore.Job job = jobs.scan("case.txt", sample());

            current.set(MODEL_B);
            source.arm();
            Future<JobStore.Job> terms = pool.submit(
                    () -> jobs.addUserTerms(job.jobId(), List.of("合同")));
            assertTrue(source.awaitEntered(), "补词重扫应进入实体来源");

            CountDownLatch applyStarted = new CountDownLatch(1);
            Future<JobStore.Job> apply = pool.submit(() -> {
                applyStarted.countDown();
                return jobs.apply(job.jobId(), Exclusions.NONE);
            });
            assertTrue(applyStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> apply.get(200, TimeUnit.MILLISECONDS),
                    "apply 必须等重扫把预览、命中和身份一起发布");

            source.release();
            terms.get(5, TimeUnit.SECONDS);
            JobStore.Job applied = apply.get(5, TimeUnit.SECONDS);
            assertEquals(2L, applied.scanRevision());
            assertEquals(MODEL_B, applied.appliedIdentity);
            assertEquals(applied.previewNameForTest(), applied.outputNameForTest());
            String output = Files.readString(ws.pathOf(applied.outputNameForTest()));
            assertFalse(output.contains("合同"));
            assertTrue(applied.applied.mapping().containsValue("合同"));
        }
    }

    @Test
    void nonEmptyExclusionsBlockTermsUntilApplyPublishesFinalState() throws Exception {
        BlockingSource source = new BlockingSource();
        AtomicReference<ProcessingIdentity> current = new AtomicReference<>(MODEL_A);
        try (Workspace ws = Workspace.open(tmp.resolve("nonzero"));
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            JobStore jobs = store(ws, source, current);
            JobStore.Job job = jobs.scan("case.txt", sample());
            long originalRevision = job.scanRevision();

            current.set(MODEL_B);
            source.arm();
            Exclusions exclusions = Exclusions.of(List.of(
                    new Exclusions.HitRef("phone", "13800138000")));
            Future<JobStore.Job> apply = pool.submit(() -> jobs.apply(job.jobId(), exclusions));
            assertTrue(source.awaitEntered(), "有排除项的 apply 应进入实体来源重跑");

            CountDownLatch termsStarted = new CountDownLatch(1);
            Future<JobStore.Job> terms = pool.submit(() -> {
                termsStarted.countDown();
                return jobs.addUserTerms(job.jobId(), List.of("合同"));
            });
            assertTrue(termsStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> terms.get(200, TimeUnit.MILLISECONDS),
                    "补词不得在 apply 校验和重跑之间改写扫描状态");

            source.release();
            JobStore.Job applied = apply.get(5, TimeUnit.SECONDS);
            assertEquals(MODEL_B, applied.appliedIdentity);
            assertEquals(originalRevision, applied.scanRevision());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> terms.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof JobStore.BadRequestException);
            assertTrue(Files.exists(ws.pathOf(applied.outputNameForTest())));
        }
    }

    private static JobStore store(Workspace ws, EntitySource source,
                                  AtomicReference<ProcessingIdentity> identity) throws Exception {
        return new JobStore(ws, RuleLoader.loadFromClasspath("rules/core-rules.json"),
                List.of(source), com.litemask.core.ExclusiveGate.NONE, identity::get);
    }

    private static byte[] sample() {
        return "张三签订合同，电话13800138000。".getBytes(StandardCharsets.UTF_8);
    }

    private static ProcessingIdentity identity(String modelId, char digestChar) {
        String digest = String.valueOf(digestChar).repeat(64);
        return new ProcessingIdentity("1.1.4", "1".repeat(64), true, modelId,
                modelId + "-0123456789ab", digest, "key-" + modelId, true);
    }

    private static final class BlockingSource implements EntitySource {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicReference<CountDownLatch> entered = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> released = new AtomicReference<>();

        void arm() {
            entered.set(new CountDownLatch(1));
            released.set(new CountDownLatch(1));
            armed.set(true);
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.get().await(2, TimeUnit.SECONDS);
        }

        void release() {
            released.get().countDown();
        }

        @Override
        public String name() {
            return "blocking-model";
        }

        @Override
        public List<Match> collect(String text) {
            if (armed.compareAndSet(true, false)) {
                entered.get().countDown();
                try {
                    if (!released.get().await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test latch timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            int at = text.indexOf("张三");
            return at < 0 ? List.of() : List.of(
                    new Match(at, at + 2, "张三", "ner_person", "人名", 1));
        }
    }
}
