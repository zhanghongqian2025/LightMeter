package com.litemask.pdf;

import com.litemask.core.Change;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 命中的<b>位置信息</b>。
 *
 * <p><b>为什么要有这一组</b>：PDF 侧的命中以前是摊平的——只有「哪条规则、哪个原值、
 * 换成哪个令牌」，没有「在哪儿」。用户拿到清单只能知道「这个值出现了 3 次」，
 * 却不知道 3 次分别在哪一页、哪一段。清单是用来核对的，
 * 核对不了位置的清单等于把工具的判断甩给用户自己复现一遍。
 *
 * <p>位置信息在 PDF 里不是现成的：内容流被拆成一个个显示算子，
 * 真正能指认「哪儿」的是<b>页</b>与<b>文本块</b>——
 * 文本块 = 同一条内容流里由定位算子（Tm、Td、TD、T*、BT、ET）切出的一段文本。
 *
 * <p><b>判据的落点</b>：不是「页码出现了吗」，而是
 * 「命中所在的那一页，与它在文档里的真实位置是不是同一页」——
 * 页码从 0 数还是从 1 数、跨页时会不会串页，只有真有多页文档才验得出来。
 */
class PdfHitLocationTest {

    /** PDF 里的令牌是 ASCII 形式（{@code <ID_1>}），不是引擎的中文令牌。 */
    private static final Pattern ASCII_TOKEN = Pattern.compile("<[A-Z0-9_]+_\\d+>");

    @TempDir
    Path dir;

    private static List<Rule> 规则() {
        return RuleLoader.loadFromClasspath("rules/core-rules.json");
    }

    /** 一页一份的内容流：{@code BT / Tf / Tm (…) Tj / ET}。 */
    private static String 页(String 文字) {
        return "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n(" + 文字 + ") Tj\nET\n";
    }

    /** 同一页里的第二段：换一次 Tm，于是多出一个文本块。 */
    private static String 页两段(String 上, String 下) {
        return "BT\n/F1 12 Tf\n1 0 0 1 50 700 Tm\n(" + 上 + ") Tj\n"
                + "1 0 0 1 50 680 Tm\n(" + 下 + ") Tj\nET\n";
    }

    private PdfRedactor.Report 脱敏(Path src) throws IOException {
        return PdfRedactor.redact(src, dir.resolve("out.pdf"), 规则());
    }

    @Test
    @DisplayName("每一处命中都带页码与文本块（脱敏后能回溯到页/段）")
    void 每处命中都带位置() throws IOException {
        Path src = PdfFixtures.writeLatinPages(dir.resolve("in.pdf"),
                List.of(页("id 11010519491231002X"), 页("phone 13800138000")));
        PdfRedactor.Report report = 脱敏(src);

        assertEquals(2, report.hits().size(), "两页各一处命中，应当有两处");
        for (PdfRedactor.Hit h : report.hits()) {
            assertNotNull(h.part(), "命中必须带内容流标签");
            assertTrue(h.part().startsWith("第 ") && h.part().endsWith(" 页"),
                    "内容流标签应当是「第 N 页」，实际: " + h.part());
            assertTrue(h.locator().startsWith("文本块 "),
                    "块内位置应当是「文本块 N」，实际: " + h.locator());
            assertNotNull(h.unit(), "命中必须带归组用的单元标识，否则 PDF 无法参与归组");
            assertTrue(h.start() >= 0 && h.end() > h.start(),
                    "块内偏移必须有效，实际: " + h.start() + ".." + h.end());
        }
    }

    @Test
    @DisplayName("页码与命中所在的真实页面一致（第 2 页的命中不许写成第 1 页）")
    void 页码不许串页() throws IOException {
        Path src = PdfFixtures.writeLatinPages(dir.resolve("in.pdf"),
                List.of(页("id 11010519491231002X"), 页("phone 13800138000")));
        PdfRedactor.Report report = 脱敏(src);

        PdfRedactor.Hit 第一页 = report.hits().get(0);
        PdfRedactor.Hit 第二页 = report.hits().get(1);
        assertEquals("第 1 页", 第一页.part(),
                "第一页的命中必须报第 1 页，实际: " + 第一页.part());
        assertEquals("第 2 页", 第二页.part(),
                "第二页的命中必须报第 2 页，实际: " + 第二页.part());
        // 两页的单元标识必须不同：否则两页的命中会被当成同一段文本里的相邻命中
        assertFalse(第一页.unit().equals(第二页.unit()),
                "不同页的命中不能共用同一个单元标识");
    }

    @Test
    @DisplayName("同一页里的两段是两个文本块（位置不能只到页，不到段）")
    void 同页分两段() throws IOException {
        Path src = PdfFixtures.writeLatinPages(dir.resolve("in.pdf"),
                List.of(页两段("id 11010519491231002X", "phone 13800138000")));
        PdfRedactor.Report report = 脱敏(src);

        assertEquals(2, report.hits().size());
        assertEquals("第 1 页", report.hits().get(0).part());
        assertEquals("文本块 1", report.hits().get(0).locator(),
                "第一段应当是文本块 1，实际: " + report.hits().get(0).locator());
        assertEquals("文本块 2", report.hits().get(1).locator(),
                "第二段应当是文本块 2，实际: " + report.hits().get(1).locator());
        assertFalse(report.hits().get(0).unit().equals(report.hits().get(1).unit()),
                "不同文本块的命中不能共用同一个单元标识");
    }

    @Test
    @DisplayName("命中数与改动数一一对应（位置清单与「第 N 次出现」的编号不能错位）")
    void 命中数与改动数一致() throws IOException {
        Path src = PdfFixtures.writeLatinPages(dir.resolve("in.pdf"),
                List.of(页("id 11010519491231002X phone 13800138000"),
                        页("id 11010519491231002X")));
        PdfRedactor.Report report = 脱敏(src);

        assertEquals(report.result().changes().size(), report.hits().size(),
                "位置清单与改动清单必须一一对应："
                        + "界面上的「第 N 次出现」是按这份顺序数的，"
                        + "两者错位时用户勾「第 2 次」勾到的不是那一处");
    }

    @Test
    @DisplayName("位置清单与改动清单逐条同序（第 N 条位置对得上第 N 处改动）")
    void 位置与改动同序() throws IOException {
        // 为什么要单独一条：上面那条只比**条数**。两个等长的序列完全可以顺序不同，
        // 而顺序错了的代价与条数错了一样——用户勾「第 2 次」勾到的不是那一处，
        // 界面上同样看不出异常。所以顺序必须单独被断言。
        //
        // 夹具故意做成「两页 + 一页两段 + 同一个值出现两次」：
        // 跨页、跨块、同值多次三种最容易让顺序走样的形态一次凑齐。
        Path src = PdfFixtures.writeLatinPages(dir.resolve("in.pdf"),
                List.of(页("id 11010519491231002X phone 13800138000"),
                        页两段("phone 13800138000", "id 11010519491231002X")));
        PdfRedactor.Report report = 脱敏(src);

        List<Change> changes = report.result().changes();
        assertEquals(changes.size(), report.hits().size(),
                "位置与改动的条数必须一一对应");
        assertFalse(changes.isEmpty(), "夹具应当真的命中了几处，否则这条断言会空转");
        for (int i = 0; i < changes.size(); i++) {
            Change c = changes.get(i);
            PdfRedactor.Hit h = report.hits().get(i);
            assertEquals(c.ruleKey(), h.ruleKey(),
                    "第 " + (i + 1) + " 条的规则键对不上：位置清单与改动清单错位了");
            assertEquals(c.original(), h.original(),
                    "第 " + (i + 1) + " 条的原值对不上：界面上「第 " + (i + 1)
                            + " 次出现」对应的位置不是实际改动的那一处");
        }
    }

    @Test
    @DisplayName("令牌是 PDF 里实际写的那个 ASCII 形式（不是引擎的中文令牌）")
    void 令牌是Ascii形式() throws IOException {
        Path src = PdfFixtures.writeLatinPages(dir.resolve("in.pdf"),
                List.of(页("id 11010519491231002X")));
        PdfRedactor.Report report = 脱敏(src);

        PdfRedactor.Hit h = report.hits().get(0);
        assertTrue(ASCII_TOKEN.matcher(h.token()).matches(),
                "令牌应当是 ASCII 形式（如 <ID_1>），实际: " + h.token());
        assertNotNull(h.engineToken());
        assertFalse(h.engineToken().equals(h.token()),
                "中文令牌与 ASCII 令牌必须都记下来：映射文件里两个都写，"
                        + "免得有人拿着中文令牌去 PDF 里找那个根本不存在的串");
    }

    @Test
    @DisplayName("还原件的报告不报位置（还原件里的令牌位置与原文位置不对应）")
    void 还原不报位置() throws IOException {
        Path src = PdfFixtures.writeLatinPages(dir.resolve("in.pdf"),
                List.of(页("id 11010519491231002X")));
        PdfRedactor.Report redacted = 脱敏(src);
        Path red = dir.resolve("red.pdf");
        PdfRedactor.redact(src, red, 规则());

        PdfRedactor.Report restored = PdfRedactor.restore(red, dir.resolve("back.pdf"),
                redacted.mapping());
        assertTrue(restored.hits().isEmpty(),
                "还原的命中不报位置：报「第 N 页」会让人以为原件的敏感信息也在那一页");
    }
}
