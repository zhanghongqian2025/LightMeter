package com.litemask.server;

import com.litemask.core.EntitySource;
import com.litemask.core.Exclusions;
import com.litemask.core.Match;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import com.litemask.pdf.PdfFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「一个标识符被切成多段」在<b>界面清单</b>上的呈现。
 *
 * <p>实测（探针，见阶段文档 §1.21）：一个假的人名来源把 {@code a.b} 与 {@code cn}
 * 认成人名（优先级 900），邮箱规则整段匹配 {@code a.b@x.cn}，
 * 重叠消解把它切成三段：{@code 人名:a.b}、{@code 邮箱:@x.}、{@code 人名:cn}。
 * 三段<b>严格相邻</b>，映射里是三条，界面上曾经是三行——用户看到的是一个地址
 * 被拆成三条互不相干的命中。
 *
 * <p>这一组用例盯住三件事：
 * <ol>
 *   <li><b>归组</b>：清单里是一条，原值是完整的那一段；</li>
 *   <li><b>映射不变</b>：映射里仍然是三条，文档里仍然是三个令牌——
 *       归组只改呈现，改了映射就会写出一个源文件里根本没有的原值；</li>
 *   <li><b>排除仍然精确</b>：勾这一行等于排除这一行里的每一处，
 *       而且因为每个成员的值全文档唯一，不会连带留下别处。</li>
 * </ol>
 */
class HitGroupingServiceTest {

    @TempDir
    Path dir;

    /**
     * 假的人名来源：把 {@code a.b} 与 {@code cn} 认成人名。
     *
     * <p>优先级 900 与真实 NER 同量级：它必须压过邮箱规则（120），
     * 邮箱那一整段才会被切开。优先级比它低的话，邮箱整段胜出，
     * 就构造不出「切成多段」这个场景。
     *
     * <p>另外认 {@code P1/S1/P2/S2}：它们用来构造「跨文本块」的场景——
     * 每个值在全文只出现一次（否则「成员的值全文档唯一」那条硬条件会先挡下来），
     * 于是两块的命中各自能归成一行，而一旦单元标识没区分到文本块，
     * 就会拼出 {@code P2S1} 这种文档里从来没有过的串。
     */
    private static EntitySource 假人名() {
        return new EntitySource() {
            @Override
            public String name() {
                return "test-fake-ner";
            }

            @Override
            public List<Match> collect(String text) {
                List<Match> out = new ArrayList<>();
                for (String 字 : List.of("a.b", "cn", "P1", "S1", "P2", "S2")) {
                    int at = text.indexOf(字);
                    while (at >= 0) {
                        out.add(new Match(at, at + 字.length(), 字, "person", "人名", 900));
                        at = text.indexOf(字, at + 字.length());
                    }
                }
                out.sort(java.util.Comparator.comparingInt(Match::start));
                return out;
            }
        };
    }

    private static List<Rule> 规则() {
        return RuleLoader.loadFromClasspath("rules/core-rules.json");
    }

    /** 造一份 txt 并脱敏，返回结果。 */
    private DocumentService.Result 跑Txt(String 内容, Exclusions 排除) throws IOException {
        Path src = dir.resolve("in.txt");
        Files.writeString(src, 内容, StandardCharsets.UTF_8);
        Path out = dir.resolve("out.txt");
        return DocumentService.redact(src, out, DocumentService.Format.TXT,
                规则(), 排除, List.of(假人名()));
    }

    @Test
    @DisplayName("切成三段的标识符在清单里是一条，原值是完整的那一段")
    void 清单里归成一条() throws IOException {
        DocumentService.Result r = 跑Txt("备用 a.b@x.cn 联系人", Exclusions.NONE);

        assertEquals(1, r.hits().size(), "三段紧挨着，清单里应当只有一行");
        DocumentService.HitItem h = r.hits().get(0);
        assertEquals("a.b@x.cn", h.original(), "原值应当是拼接出来的那一整段");
        assertEquals(3, h.valueCount(), "这一行覆盖 3 个值");
        assertEquals(3, h.members().size());
        assertEquals("person + email", h.ruleKey(), "规则列要列出全部规则");
        assertEquals(1, h.occurrences());
    }

    @Test
    @DisplayName("归组不改映射：映射里仍是三条，文档里仍是三个令牌")
    void 映射不变() throws IOException {
        DocumentService.Result r = 跑Txt("备用 a.b@x.cn 联系人", Exclusions.NONE);

        assertEquals(3, r.mapping().size(),
                "映射必须仍是 3 条：合并会写出一个源文件里从未整体出现过的原值");
        assertEquals(3, r.occurrences(), "命中总处数不受归组影响");
        Path out = dir.resolve("out.txt");
        String text = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(text.contains("<人名1><邮箱1><人名2>"),
                "文档里应当仍是三个紧挨着的令牌，实际: " + text);
    }

    @Test
    @DisplayName("勾掉归组行 = 排除这一行里的每一处，那一段原文整体保留")
    void 排除整行() throws IOException {
        DocumentService.Result scan = 跑Txt("备用 a.b@x.cn 联系人", Exclusions.NONE);
        List<Exclusions.HitRef> refs = new ArrayList<>();
        for (DocumentService.HitItem.Member m : scan.hits().get(0).members()) {
            refs.add(new Exclusions.HitRef(m.ruleKey(), m.original()));
        }
        assertEquals(3, refs.size(), "一行 3 个成员要发出 3 条排除项");

        DocumentService.Result applied = 跑Txt("备用 a.b@x.cn 联系人", Exclusions.ofList(refs));
        Path out = dir.resolve("out.txt");
        String text = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(text.contains("a.b@x.cn"),
                "三处都排除后，原文应当整体保留，实际: " + text);
        assertFalse(text.contains("<人名"), "不该还留着令牌，实际: " + text);
    }

    @Test
    @DisplayName("PDF 归组：同一文本块里紧挨着的三段合成一行")
    void pdf归组() throws IOException {
        Path src = 造带文字的Pdf("mail a.b@x.cn end");
        Path out = dir.resolve("out.pdf");
        DocumentService.Result r = DocumentService.redact(src, out, DocumentService.Format.PDF,
                规则(), Exclusions.NONE, List.of(假人名()));

        assertEquals(1, r.hits().size(),
                "三段在同一文本块里紧挨着，清单里应当归成一行，实际: " + r.hits().size() + " 行");
        DocumentService.HitItem h = r.hits().get(0);
        assertEquals("a.b@x.cn", h.original(), "原值应当是拼接出来的那一整段");
        assertEquals(3, h.valueCount(), "这一行覆盖 3 个值");
        assertEquals(3, h.members().size());
        // 归组只改呈现，不改映射
        assertEquals(3, r.mapping().size(), "映射必须仍是 3 条");
        assertEquals(3, r.occurrences(), "命中总处数不受归组影响");
    }

    /**
     * 跨文本块的两处命中<b>不许</b>归组。
     *
     * <p><b>为什么这条比上一条更要紧</b>：上一条只验「该归的归了」，
     * 这一条验「不该归的没归」。两处的偏移都是<b>块内</b>偏移，
     * 如果单元标识只记到页（没记到文本块），两块的偏移就会撞在一起——
     * 而「偏移看起来接得上」正是归组判断的依据，
     * 于是两块不相干的文本会被拼成一个根本不存在的标识符。
     *
     * <p>所以这里用两套互不重复的值（{@code P1S1} 与 {@code P2S2}）：
     * 一旦单元标识没区分开，就会出现「P2 + S1」这种跨块的假归组，
     * 原值会变成一个文档里从来没有过的串。
     */
    @Test
    @DisplayName("PDF 跨文本块不归组（拼出来的原值在原文里根本不存在）")
    void pdf跨文本块不归组() throws IOException {
        Path src = 造两段Pdf("P1S1", "P2S2");
        Path out = dir.resolve("out.pdf");
        DocumentService.Result r = DocumentService.redact(src, out, DocumentService.Format.PDF,
                规则(), Exclusions.NONE, List.of(假人名()));

        assertEquals(2, r.hits().size(),
                "两个文本块各归成一行，实际: " + r.hits().size() + " 行");
        assertEquals("P1S1", r.hits().get(0).original());
        assertEquals("P2S2", r.hits().get(1).original());
        for (DocumentService.HitItem h : r.hits()) {
            assertEquals(2, h.valueCount(), "每一行覆盖自己那一块里的 2 个值");
        }
    }

    @Test
    @DisplayName("PDF 的每一处命中都带位置（页 + 文本块）")
    void pdf带位置() throws IOException {
        Path src = 造带文字的Pdf("mail a.b@x.cn end");
        Path out = dir.resolve("out.pdf");
        DocumentService.Result r = DocumentService.redact(src, out, DocumentService.Format.PDF,
                规则(), Exclusions.NONE, List.of(假人名()));

        assertTrue(r.hits().stream().allMatch(DocumentService.HitItem::hasLocators),
                "PDF 的命中必须带位置信息");
        DocumentService.HitItem h = r.hits().get(0);
        assertTrue(h.locators().stream().allMatch(l -> l.locator().contains("第 1 页")),
                "位置必须说得出页码，实际: " + h.locators());
        assertTrue(h.locators().stream().allMatch(l -> l.locator().contains("文本块")),
                "位置必须说得出文本块，实际: " + h.locators());
    }

    @Test
    @DisplayName("PDF 不谎报「出现次数超过 20 次、另有 N 处未展开」")
    void pdf不谎报位置截断() throws IOException {
        Path src = 造带文字的Pdf("mail a.b@x.cn end");
        Path out = dir.resolve("out2.pdf");
        DocumentService.Result r = DocumentService.redact(src, out, DocumentService.Format.PDF,
                规则(), Exclusions.NONE, List.of(假人名()));

        assertFalse(r.notes().stream().anyMatch(n -> n.contains("位置清单只展开前")),
                "出现 1 次的值不该报位置被截断，实际 notes: " + r.notes());
    }

    /**
     * 造一份<b>带文本层</b>的 PDF。
     *
     * <p>{@code PdfFixtures.writeLatin} 写的是原样内容流，得自己拼出
     * {@code BT / Tf / Tm (…) Tj / ET} 这一段，否则 PDF 里没有文本，
     * 脱敏器会报「没有文本层」——那正是本用例要避开的干扰。
     */
    private Path 造带文字的Pdf(String 文字) throws IOException {
        String 流 = "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n(" + 文字 + ") Tj\nET\n";
        return PdfFixtures.writeLatin(dir.resolve("in-" + Math.abs(文字.hashCode()) + ".pdf"), 流);
    }

    /**
     * 造一份<b>同一页里两段</b>的 PDF：两段之间换一次定位算子（Tm），
     * 于是内容流被切成两个文本块。
     */
    private Path 造两段Pdf(String 上, String 下) throws IOException {
        String 流 = "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n(" + 上 + ") Tj\n"
                + "1 0 0 1 50 680 Tm\n(" + 下 + ") Tj\nET\n";
        return PdfFixtures.writeLatin(dir.resolve("in2-" + Math.abs((上 + 下).hashCode()) + ".pdf"), 流);
    }

    @Test
    @DisplayName("清单的 JSON 里必须带 members 与 valueCount——界面靠它把一行拆成多条排除项")
    void json带成员() throws IOException {
        DocumentService.Result r = 跑Txt("备用 a.b@x.cn 联系人", Exclusions.NONE);
        JobStore.Job job = new JobStore.Job("j1", "in.txt", "out.txt", "in.txt", ".txt",
                DocumentService.Format.TXT, r, false);
        Map<String, Object> payload = JobStore.describe(job, r, "scanned", 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) payload.get("hits");
        assertEquals(1, hits.size());
        assertEquals(3, hits.get(0).get("valueCount"), "必须告诉界面这一行代表几个值");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) hits.get(0).get("members");
        assertEquals(3, members.size(), "成员必须逐个回传，界面才能展开成多条排除项");
        assertEquals("person", members.get(0).get("ruleKey"));
        assertEquals("a.b", members.get(0).get("original"));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) payload.get("summary");
        assertEquals(3, summary.get("distinctHits"), "计数按值：这一行代表 3 个值");
        assertEquals(1, summary.get("rows"), "行数与清单一致");
    }

    @Test
    @DisplayName("只排除归组行里的一部分 → 请求当场失败，不给一份保留了一半的标识符")
    void 半行排除必须失败() throws IOException {
        Workspace ws = Workspace.open(dir);
        JobStore jobs = new JobStore(ws, 规则(), List.of(假人名()));
        try {
            JobStore.Job job = jobs.scan("a.txt",
                    "备用 a.b@x.cn 联系人".getBytes(StandardCharsets.UTF_8));
            assertEquals(1, job.scan.hits().size(), "扫描结果应当是归成的一行");

            // 只排除 3 个成员里的 1 个
            Exclusions 半行 = Exclusions.ofList(List.of(
                    new Exclusions.HitRef("person", "a.b")));
            JobStore.BadRequestException e = assertThrows(JobStore.BadRequestException.class,
                    () -> jobs.apply(job.jobId(), 半行));
            assertTrue(e.getMessage().contains("整行勾选"),
                    "报错要说清怎么做，实际: " + e.getMessage());
        } finally {
            ws.close();
        }
    }
}
