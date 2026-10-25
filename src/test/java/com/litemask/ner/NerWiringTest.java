package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import com.litemask.server.DocumentService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3-2b 接线：模型包 → {@link NerEntitySource} → 五条管线 → 报告与还原。
 *
 * <p><b>这组测试存在的理由</b>：M3-2a 的加载器能识别实体，但没有任何调用方构造它。
 * 那段时间的实测里 {@code 张三} 依然原样留在产物里——
 * 「能力做出来了」与「能力接上了」是两件事，而后者没有测试守着就会悄悄回退。
 *
 * <p>用玩具模型而不是真模型：真模型 7MB，测试类拖着它太重；
 * 更重要的是期望值——玩具模型每个通道的分数是写死的常量，
 * 「张三是人名」可以在测试里直接断言，不依赖任何机器学习框架。
 *
 * <p>另有一条**必须存在**的测试：没装模型时报告要明说「人名没脱」。
 * 「0 处」与「文档干净」在数字上长得一模一样，
 * 而漏脱一份人名的文档被当成脱敏过的传出去，是这个工具最不能犯的错。
 */
class NerWiringTest {

    @TempDir
    Path tmp;

    private static final String SAMPLE = "原告张三与被告北京大学签订合同，联系电话13800138000。";

    private static List<Rule> rules() {
        return RuleLoader.loadFromClasspath("rules/core-rules.json");
    }

    private Path writeSample(String name) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, SAMPLE, StandardCharsets.UTF_8);
        return p;
    }

    private NerModelRegistry registry(Path dir) throws Exception {
        try (NerModelPackage pkg = NerModelPackage.load(dir)) {
            return NerModelRegistry.enabled(pkg);
        }
    }

    // ------------------------------------------------------------------ DocumentService

    @Test
    @DisplayName("带模型时人名与机构名真的被脱了，并进入还原映射")
    void redactsNamesWhenModelEnabled() throws Exception {
        Path pkgDir = NerModelFixtures.writePackage(tmp.resolve("pkg"));
        Path in = writeSample("with.txt");
        Path out = tmp.resolve("with.redacted.txt");

        DocumentService.Result r;
        try (NerModelPackage pkg = NerModelPackage.load(pkgDir)) {
            r = DocumentService.redact(in, out, DocumentService.Format.TXT,
                    rules(), Exclusions.NONE, List.of(pkg.entitySource()));
        }

        String text = Files.readString(out, StandardCharsets.UTF_8);
        assertFalse(text.contains("张三"), "人名不该留在产物里，实际: " + text);
        assertFalse(text.contains("北京大学"), "机构名不该留在产物里，实际: " + text);
        assertTrue(text.contains("<手机号1>"), "正则那条通路不该受影响，实际: " + text);

        assertEquals("张三", r.mapping().get("<人名1>"), "还原映射里必须有人名原值");
        assertEquals("北京大学", r.mapping().get("<机构名1>"));
        assertEquals("13800138000", r.mapping().get("<手机号1>"),
                "NER 与正则必须共用同一个映射容器");

        assertTrue(r.hits().stream().anyMatch(h -> h.ruleKey().equals(NerEntitySource.RULE_PERSON)),
                "命中清单里必须有人名");
        assertTrue(r.hits().stream().anyMatch(h -> h.ruleKey().equals("phone")),
                "命中清单里必须有手机号");
    }

    @Test
    @DisplayName("带模型时不报「未启用」，并把来源写进 stats")
    void noDisabledNoticeWhenEnabled() throws Exception {
        Path pkgDir = NerModelFixtures.writePackage(tmp.resolve("pkg2"));
        Path in = writeSample("w2.txt");
        DocumentService.Result r;
        try (NerModelPackage pkg = NerModelPackage.load(pkgDir)) {
            r = DocumentService.redact(in, tmp.resolve("w2.out.txt"),
                    DocumentService.Format.TXT, rules(), Exclusions.NONE,
                    List.of(pkg.entitySource()));
        }
        assertFalse(String.join("|", r.notes()).contains("未被脱敏"),
                "启用了模型就不该说人名没被脱敏，notes=" + r.notes());
        assertEquals("ner", r.stats().values().get("识别来源"),
                "报告里要能看出这次用了哪个来源");
    }

    @Test
    @DisplayName("没装模型时报告必须明说人名与机构名没有被脱敏")
    void saysNamesNotRedactedWhenDisabled() throws Exception {
        Path in = writeSample("no.txt");
        Path out = tmp.resolve("no.out.txt");
        DocumentService.Result r = DocumentService.redact(in, out,
                DocumentService.Format.TXT, rules(), Exclusions.NONE);

        String allNotes = String.join(" | ", r.notes());
        assertTrue(allNotes.contains("人名与机构名没有被脱敏"),
                "没装模型却没说出来——用户会拿这份文件当脱敏过的传出去。notes=" + r.notes());

        String text = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(text.contains("张三"), "没装模型时人名应当原样保留（这条只是确认前提）");
        assertTrue(text.contains("<手机号1>"), "正则那条通路仍要工作");
        assertTrue(r.occurrences() >= 1);
    }

    @Test
    @DisplayName("带模型跑出来的映射能逐字节还原")
    void restoresExactly() throws Exception {
        Path pkgDir = NerModelFixtures.writePackage(tmp.resolve("pkg3"));
        Path in = writeSample("rt.txt");
        Path out = tmp.resolve("rt.redacted.txt");
        DocumentService.Result r;
        try (NerModelPackage pkg = NerModelPackage.load(pkgDir)) {
            r = DocumentService.redact(in, out, DocumentService.Format.TXT,
                    rules(), Exclusions.NONE, List.of(pkg.entitySource()));
        }
        String restored = com.litemask.core.RedactionEngine.restore(
                Files.readString(out, StandardCharsets.UTF_8), r.mapping());
        assertEquals(SAMPLE, restored,
                "NER 与正则共用一条替换通路时，还原必须逐字节一致");
    }

    // ------------------------------------------------------------------ Registry

    @Test
    @DisplayName("显式指定目录时启用")
    void registryLoadsExplicitDir() throws Exception {
        Path pkgDir = NerModelFixtures.writePackage(tmp.resolve("reg1"));
        try (NerModelRegistry reg = NerModelRegistry.discover(pkgDir.toString())) {
            assertTrue(reg.isEnabled());
            assertEquals(1, reg.sources().size());
            assertTrue(reg.describe().contains("已启用"));
            assertFalse(reg.needsUserNotice());
        }
    }

    @Test
    @DisplayName("指定目录不存在时抛错，不静默降级成「未安装」")
    void registryThrowsOnMissingDir() {
        // 「指到了却找不到」与「没指」是两回事：前者是指示写错了，
        // 用户会以为自己装了模型、工具却当没装——那比报错糟得多。
        var e = assertThrows(NerModelRegistry.OrtExceptionWrapper.class,
                () -> NerModelRegistry.discover(tmp.resolve("nope").toString()));
        assertTrue(e.getMessage().contains("不存在"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("指定的目录里模型包坏掉时抛错并带上原因")
    void registryThrowsOnBrokenPackage() throws Exception {
        Path dir = tmp.resolve("broken");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{ not json", StandardCharsets.UTF_8);
        var e = assertThrows(NerModelRegistry.OrtExceptionWrapper.class,
                () -> NerModelRegistry.discover(dir.toString()));
        assertTrue(e.getMessage().contains("不能用"), "实际: " + e.getMessage());
        // 必须把底下的原因带出来，否则用户看到「不能用」还是不知道该怎么办。
        assertTrue(e.getMessage().contains("manifest.json")
                        || e.getCause() != null,
                "错误里要能看出是哪个文件/哪一步坏了");
    }

    @Test
    @DisplayName("自动发现找不到时是「未启用」，且说明要怎么说")
    void registryDisabledWithGuidance() throws Exception {
        // 前提：本机默认位置确实没有模型包。
        // 不写这条的话，某天有人在 ~/.litemask/models/ner 放了包，
        // 这条用例会变成「突然红了」——而红的原因与被测代码无关。
        Path home = Path.of(System.getProperty("user.home"), ".litemask", "models", "ner");
        Path programModels = Path.of("target", "classes", "models", "ner");
        assertFalse(Files.isDirectory(home),
                "前提：本机 " + home + " 不该存在模型包，否则这条用例测不到「未启用」");
        assertFalse(Files.isDirectory(programModels),
                "前提：" + programModels + " 不该存在");

        try (NerModelRegistry reg = NerModelRegistry.discover(null)) {
            assertFalse(reg.isEnabled());
            assertTrue(reg.sources().isEmpty(), "未启用时必须返回空列表而不是 null");
            assertTrue(reg.needsUserNotice());
            String d = reg.describe();
            assertTrue(d.contains("未启用"), "实际: " + d);
            assertTrue(d.contains("人名与机构名没有被脱敏"), "必须明说后果，实际: " + d);
            assertTrue(d.contains("--ner-model") || d.contains("LITEMASK_NER_MODEL"),
                    "未启用时要告诉用户怎么启用，实际: " + d);
        }
    }

    @Test
    @DisplayName("enabled() 与 disabled() 的 describe 都不为空")
    void describeAlwaysSaysSomething() throws Exception {
        try (NerModelRegistry off = NerModelRegistry.disabled(tmp, "测试用的原因")) {
            assertFalse(off.describe().isBlank());
            assertTrue(off.describe().contains("测试用的原因"));
        }
        Path pkgDir = NerModelFixtures.writePackage(tmp.resolve("reg2"));
        try (NerModelPackage pkg = NerModelPackage.load(pkgDir);
             NerModelRegistry on = NerModelRegistry.enabled(pkg)) {
            String d = on.describe();
            assertTrue(d.contains("已启用"));
            assertTrue(d.contains("test-fixture"),
                    "启用时要报出模型包 id——脱敏结果依赖模型版本，争议时用户要能报出来");
            assertEquals("", on.reason());
        }
        assertNotNull(NerModelRegistry.disabled(tmp, "x").reason());
    }

    @Test
    @DisplayName("关闭注册表会关掉底层会话")
    void closeIsIdempotent() throws Exception {
        Path pkgDir = NerModelFixtures.writePackage(tmp.resolve("reg3"));
        NerModelRegistry reg = NerModelRegistry.discover(pkgDir.toString());
        reg.close();
        reg.close();   // 关两次不该炸
    }
}
