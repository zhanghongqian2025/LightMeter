package com.litemask.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import com.litemask.ner.NerEntitySource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 服务端这条路径上 NER 有没有真的接上。
 *
 * <p>放在 {@code com.litemask.server} 包下而不是 {@code com.litemask.ner}：
 * {@code JobStore.Job.scan} 是包内可见的字段，
 * 而断言「服务端路径上人名确实被脱了」又必须直接读它——
 * 绕道 HTTP 层去测会把「JobStore 没接上」与「路由没转发」两件事混成一个。
 */
class JobStoreNerWiringTest {

    private static final String SAMPLE = "原告张三与被告北京大学签订合同，联系电话13800138000。";

    @TempDir
    Path tmp;

    private static List<Rule> rules() {
        return RuleLoader.loadFromClasspath("rules/core-rules.json");
    }

    /**
     * 写出样本并返回它的字节。
     *
     * <p>不返回 {@link Path}：{@code JobStore.scan} 要的是「客户端报来的文件名 + 字节」，
     * 路径只是写入用的中间物。返回路径容易让人顺手把它传进 {@code scan}——
     * 那样第一个参数就变成完整路径，后缀判定照样能过，反而掩盖了参数位置的错误。
     */
    private byte[] sampleBytes(String name) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, SAMPLE, StandardCharsets.UTF_8);
        return Files.readAllBytes(p);
    }

    @Test
    @DisplayName("不传来源时人名原样保留，且报告明说没脱")
    void withoutSourcesKeepsNames() throws Exception {
        try (Workspace ws = Workspace.open(tmp.resolve("ws-plain"))) {
            JobStore jobs = new JobStore(ws, rules());
            JobStore.Job job = jobs.scan("plain.txt", sampleBytes("plain.txt"));

            assertFalse(job.scan.mapping().containsKey("<人名1>"));
            assertTrue(String.join(" | ", job.scan.notes()).contains("人名与机构名没有被脱敏"),
                    "没接 NER 却不说，用户会拿这份文件当脱敏过的传出去");
            // 前提断言：正则那条通路必须先工作，否则「没脱人名」可能只是
            // 「整条管线根本没跑」。「没有 hits 就跳过」是最容易把测试变成永真的一种写法。
            assertTrue(job.scan.mapping().containsKey("<手机号1>"),
                    "前提：手机号应当被正则脱敏");
            assertFalse(job.scan.hits().isEmpty(),
                    "前提：没接来源时也应当至少命中一条规则");
            // 更硬的一条：直接读回预览件，确认「张三」真的还在文件里。
            // 映射表为空不排除命中被丢弃的可能，文件内容才是最终用户拿到的东西。
            assertTrue(new String(Files.readAllBytes(ws.pathOf(job.previewNameForTest())),
                            StandardCharsets.UTF_8).contains("张三"),
                    "前提：没接 NER 时预览件里「张三」应当原样保留");
        }
    }

    /**
     * 传来源时人名确实被脱。
     *
     * <p>用的是一个<b>恒真</b>的假来源而不是真模型：这里要证明的是
     * 「JobStore 有没有把来源往下传」，不是「模型准不准」。
     * 假来源在「原告张三」这个位置硬造一个 {@code <人名1>} 命中，
     * 于是这条测试与模型质量完全解耦——模型换版本时它不会红，
     * 而「JobStore 把 sources 吃掉了」它一定会红。
     */
    @Test
    @DisplayName("JobStore 把来源透传到脱敏结果里")
    void passesSourcesThrough() throws Exception {
        try (Workspace ws = Workspace.open(tmp.resolve("ws-ner"))) {
            JobStore jobs = new JobStore(ws, rules(), List.of(fakeNer()));
            JobStore.Job job = jobs.scan("ner.txt", sampleBytes("ner.txt"));

            assertTrue(job.scan.hits().stream()
                            .anyMatch(h -> h.ruleKey().equals(NerEntitySource.RULE_PERSON)),
                    "服务端路径也要真的脱掉人名，不能只在 CLI 里生效");
            assertTrue(job.scan.mapping().containsKey("<人名1>"));
            assertFalse(String.join(" | ", job.scan.notes()).contains("没有被脱敏"),
                    "带了来源就不该再说「人名没被脱」");
            正则通路仍然命中(job);

            // 决定性证据：读回最终产物。映射表里有 <人名1> 只说明分配了令牌，
            // 文件里还留着「张三」是完全可能的另一种情况。
            String preview = new String(
                    Files.readAllBytes(ws.pathOf(job.previewNameForTest())), StandardCharsets.UTF_8);
            assertFalse(preview.contains("张三"),
                    "预览件里不该再有「张三」：actual=" + preview);
            assertTrue(preview.contains("<人名1>"),
                    "预览件里应当出现替换令牌：actual=" + preview);
        }
    }

    /**
     * NER 接上之后，正则那条通路不许被挤掉。
     *
     * <p>共用同一条替换通路有个真实风险：NER 的实体可能与正则命中重叠，
     * 去重或优先级处理写错时症状是「手机号不见了」而不是报错。
     * 单独断言 {@code phone} 仍然命中，才能把「NER 生效」与「正则仍生效」分开看。
     */
    private static void 正则通路仍然命中(JobStore.Job job) {
        assertTrue(job.scan.hits().stream()
                        .anyMatch(h -> h.ruleKey().equals("phone")),
                "NER 与正则必须都命中——共用通路不代表只能走一条");
    }

    /**
     * 一个确定性的假 NER 来源。
     *
     * <p>它按原文里的「张三」造一个命中。这不是「测模型」，而是提供一个
     * <b>不依赖模型版本</b>的探针：模型换了、词表变了，这条用例都不会红，
     * 而它要抓的东西（JobStore 有没有把 sources 传给 DocumentService）与模型无关。
     */
    private static com.litemask.core.EntitySource fakeNer() {
        return new com.litemask.core.EntitySource() {
            @Override
            public String name() {
                return "ner";
            }

            @Override
            public List<com.litemask.core.Match> collect(String text) {
                int at = text.indexOf("张三");
                if (at < 0) {
                    return List.of();
                }
                return List.of(new com.litemask.core.Match(at, at + 2, "张三",
                        NerEntitySource.RULE_PERSON, "人名", NerEntitySource.SORT_ORDER));
            }
        };
    }
}
