package com.litemask.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.litemask.core.EntitySource;
import com.litemask.core.Exclusions;
import com.litemask.core.Match;
import com.litemask.core.ProcessingIdentity;
import com.litemask.core.RuleLoader;
import com.litemask.core.ToolIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStoreIdentityTest {

    private static final String TOOL_SHA = "1".repeat(64);
    private static final ProcessingIdentity MODEL_A = signed("model-a", "a".repeat(64), "key-a");
    private static final ProcessingIdentity MODEL_B = signed("model-b", "b".repeat(64), "key-b");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("旧扫描身份不随当前模型变化；重扫与应用绑定实际使用的那一版")
    void identityIsImmutablePerProcessingPass() throws Exception {
        AtomicReference<ProcessingIdentity> current = new AtomicReference<>(MODEL_A);
        try (Workspace ws = Workspace.open(tmp.resolve("ws"))) {
            JobStore jobs = new JobStore(ws, RuleLoader.loadFromClasspath("rules/core-rules.json"),
                    List.of(activeSource()), com.litemask.core.ExclusiveGate.NONE, current::get);

            JobStore.Job jobA = jobs.scan("a.txt", sample());
            assertIdentity(JobStore.describe(jobA, jobA.scan(), "scanned", 0), MODEL_A);

            current.set(MODEL_B);
            assertIdentity(JobStore.describe(jobA, jobA.scan(), "scanned", 0), MODEL_A);

            jobs.addUserTerms(jobA.jobId(), List.of("合同"));
            assertIdentity(JobStore.describe(jobA, jobA.scan(), "scanned", 0), MODEL_B);

            current.set(MODEL_A);
            JobStore.Job reused = jobs.apply(jobA.jobId(), Exclusions.NONE);
            assertIdentity(JobStore.describe(reused, reused.applied, "applied", 0), MODEL_B);

            current.set(MODEL_B);
            JobStore.Job rerun = jobs.scan("b.txt", sample());
            current.set(MODEL_A);
            Exclusions exclusions = Exclusions.of(List.of(new Exclusions.HitRef("phone", "13800138000")));
            jobs.apply(rerun.jobId(), exclusions);
            assertIdentity(JobStore.describe(rerun, rerun.applied, "applied", 1), MODEL_A);
        }
    }

    @Test
    @DisplayName("显式开发模型如实标成未验签且不能进入严格生产审批")
    void unsignedDeveloperIdentityIsIneligible() throws Exception {
        ProcessingIdentity developer = new ProcessingIdentity("1.1.4", TOOL_SHA, true,
                "developer-model", null, null, null, false);
        AtomicReference<ProcessingIdentity> current = new AtomicReference<>(developer);
        try (Workspace ws = Workspace.open(tmp.resolve("dev-ws"))) {
            JobStore jobs = new JobStore(ws, RuleLoader.loadFromClasspath("rules/core-rules.json"),
                    List.of(activeSource()), com.litemask.core.ExclusiveGate.NONE, current::get);
            JobStore.Job job = jobs.scan("dev.txt", sample());
            Map<String, Object> identity = identityOf(JobStore.describe(job, job.scan(), "scanned", 0));
            assertEquals("developer-model", identity.get("modelId"));
            assertEquals(false, identity.get("signatureVerified"));
            assertEquals(false, identity.get("strictProductionEligible"));
            assertNull(identity.get("modelVersionKey"));
            assertNull(identity.get("modelPackageSha256"));
            assertNull(identity.get("modelSigningKeyId"));
        }
    }

    @Test
    @DisplayName("工具和模型摘要字段只接受明确的 SHA-256 形状")
    void identityDigestShapeIsStrict() {
        assertEquals("1.1.4", ToolIdentity.version());
        assertTrue(MODEL_A.strictProductionEligible());
        assertEquals(64, MODEL_A.toolArtifactSha256().length());
        assertEquals(64, MODEL_A.modelPackageSha256().length());
        assertThrows(IllegalArgumentException.class, () -> new ProcessingIdentity(
                "1.1.4", "not-a-digest", false, null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new ProcessingIdentity(
                "1.1.4", TOOL_SHA, true, "model", "version", null, "key", true));
        assertThrows(IllegalArgumentException.class, () -> new ProcessingIdentity(
                "1.1.4", TOOL_SHA, true, "  ", null, null, null, false));
        ProcessingIdentity noModel = ProcessingIdentity.withoutModel();
        assertFalse(noModel.usedModel());
        assertFalse(noModel.signatureVerified());
        assertFalse(noModel.strictProductionEligible());
    }

    private static ProcessingIdentity signed(String modelId, String packageSha, String keyId) {
        return new ProcessingIdentity("1.1.4", TOOL_SHA, true, modelId,
                modelId + "-0123456789ab", packageSha, keyId, true);
    }

    private static byte[] sample() {
        return "张三签订合同，电话13800138000。".getBytes(StandardCharsets.UTF_8);
    }

    private static EntitySource activeSource() {
        return new EntitySource() {
            @Override
            public String name() {
                return "identity-test-model";
            }

            @Override
            public List<Match> collect(String text) {
                int at = text.indexOf("张三");
                return at < 0 ? List.of() : List.of(new Match(at, at + 2, "张三",
                        "ner_person", "人名", 1));
            }
        };
    }

    private static void assertIdentity(Map<String, Object> result, ProcessingIdentity expected) {
        Map<String, Object> actual = identityOf(result);
        assertEquals(expected.modelId(), actual.get("modelId"));
        assertEquals(expected.modelVersionKey(), actual.get("modelVersionKey"));
        assertEquals(expected.modelPackageSha256(), actual.get("modelPackageSha256"));
        assertEquals(expected.modelSigningKeyId(), actual.get("modelSigningKeyId"));
        assertEquals(expected.signatureVerified(), actual.get("signatureVerified"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> identityOf(Map<String, Object> result) {
        return (Map<String, Object>) result.get("toolIdentity");
    }
}
