package com.litemask.server;

import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DocumentService.aggregate} 的聚合键必须是 {@code (规则键, 原值)} 上的<b>单射</b>。
 *
 * <p>聚合键直接拼字符串。如果写成 {@code ruleKey + original}（不加分隔符），
 * 只要存在两组键值使拼接结果相同，两类命中的<b>计数、位置清单、令牌就会混成一行</b>。
 * 后果不是报错而是静默失真：界面上「手机号出现 3 次、第 2 次在第 12 段」，
 * 实际那 3 次里有 2 次根本是另一种规则命中，用户勾「第 2 次」排除了不相关的内容。
 *
 * <p><b>为什么这条测试要现造规则文件</b>：现有 18 条规则键两两之间不存在
 * 「A 是 B 的真前缀」的关系（{@code phone} 11 位、{@code telephone} 带连字符、
 * {@code bank_card} 16~19 位），因此<b>用内置规则库构造不出串键场景</b>——
 * 变异跑出来是绿的，那是探针空转，不是变异无效。
 * 这里用 {@link RuleLoader#load(Path)} 临时写一份规则文件，
 * 造出 {@code ("a","xy")} 与 {@code ("ax","y")}：拼接后都是 {@code "axy"}。
 * 规则本身刻意取得不自然，因为这里要验的是键函数本身的单射性，
 * 不是模拟一份看起来真实的文档。
 */
class AggregateKeyTest {

    /**
     * 两组命中的键拼接结果都是 {@code "axy"}。
     * 正则一律用零宽断言而不是 {@code \b}：免去一层 JSON/Java 双反斜杠转义，
     * 写错了正则会「匹配不到任何东西」而不是「匹配串了边界」——后者更难查。
     */
    private static final String COLLIDING_RULES = """
            {"version":1,"_comment":"仅用于验证聚合键单射性","rules":[
              {"key":"a","name":"甲","tokenPrefix":"甲","pattern":"(?<= )xy(?= )"},
              {"key":"ax","name":"乙","tokenPrefix":"乙","pattern":"(?<= )y(?=$)"}
            ]}
            """;

    /** 两次命中相距足够远（[3,5) 与 [9,10)），互不重叠，因此不会在重叠消解里互相挤掉。 */
    private static final String DOC = "aa xy bb y";

    @TempDir
    Path dir;

    @Test
    @DisplayName("两组键拼接结果相同的命中，必须在清单里保持为两行")
    void 聚合键不串行() throws IOException {
        List<Rule> rules = 造规则();

        DocumentService.Result r = 扫描(rules, Exclusions.NONE);

        assertEquals(2, r.hits().size(),
                "(\"a\",\"xy\") 与 (\"ax\",\"y\") 拼出来都是 axy，是两组不同的命中，"
                        + "不能被合并成一行。实际: " + 清单(r));

        assertEquals(Set.of("a|xy", "ax|y"),
                r.hits().stream().map(h -> h.ruleKey() + "|" + h.original())
                        .collect(java.util.stream.Collectors.toSet()),
                "合并之后剩下的 ruleKey/original 只能取其中一组，另一组就此消失: " + 清单(r));

        // 计数也必须分开：合并时第一行会报 2 次，而实际上每组只命中 1 次。
        for (DocumentService.HitItem h : r.hits()) {
            assertEquals(1, h.occurrences(),
                    "每个值只出现一次，被合并的行会虚报次数: " + h);
        }
        // 位置清单同样要分开，否则界面上的「第 N 次」指不到地方。
        Set<String> locators = r.hits().stream()
                .flatMap(h -> h.locators().stream())
                .map(DocumentService.HitItem.Loc::locator)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(2, locators.size(), "两处位置说明必须是两条互不相同的: " + locators);

        // 先证明正常路径真的在工作（防空转）：不排除时两处都应被换成各自的令牌。
        String 全文 = Files.readString(dir.resolve("out.txt"), StandardCharsets.UTF_8);
        assertTrue(全文.contains("<甲"), "规则 a 的命中应被替换成 <甲…>: " + 全文);
        assertTrue(全文.contains("<乙"), "规则 ax 的命中应被替换成 <乙…>: " + 全文);
        assertFalse(全文.contains("xy"), "原文不该剩下: " + 全文);
    }

    @Test
    @DisplayName("按 (规则键, 原值) 排除，只放开它自己那一条")
    void 排除只作用于本行() throws IOException {
        List<Rule> rules = 造规则();

        // 这条前置断言不是废话，删掉它整条测试就会在变异体下依然全绿。
        // 被排除的命中根本不进 changes，所以聚合时只剩另一条，它的键恰好就是
        // 「正确实现下另一条自己的键」——断言 3、4 在变异体下照样通过。
        // 真正咬住分隔符的是这句：不排除时清单里本来就该有两行，
        // 串键之后它们被并成一行，用户连「只放开其中一类」都表达不出来。
        assertEquals(2, 扫描(rules, Exclusions.NONE).hits().size(),
                "排除之前，两类命中必须已经是清单上的两行");

        DocumentService.Result r = 扫描(rules, Exclusions.ofList(List.of(
                new Exclusions.HitRef("a", "xy"))));

        String 全文 = Files.readString(dir.resolve("out.txt"), StandardCharsets.UTF_8);
        assertEquals("aa xy bb <乙1>", 全文.strip(),
                "排除 (\"a\",\"xy\") 之后：被排除的值保留原样，另一行照常脱敏");
        assertEquals(1, r.hits().size(), "排除之后清单上只剩一行");
        assertEquals("ax", r.hits().get(0).ruleKey(),
                "剩下的必须是另一行，不是被排除的那行改了名");
        assertNotEquals("xy", r.hits().get(0).original(), "剩下的不是被排除的那个值");
    }

    // ------------------------------------------------------------------ 辅助

    private DocumentService.Result 扫描(List<Rule> rules, Exclusions excl) throws IOException {
        Path in = dir.resolve("in.txt");
        Files.writeString(in, DOC, StandardCharsets.UTF_8);
        return DocumentService.redact(in, dir.resolve("out.txt"),
                DocumentService.Format.TXT, rules, excl);
    }

    /** 写一份临时规则文件再从磁盘加载——顺带验证按客户定制规则的这条入口本身没坏。 */
    private List<Rule> 造规则() throws IOException {
        Path f = dir.resolve("colliding-rules.json");
        Files.writeString(f, COLLIDING_RULES, StandardCharsets.UTF_8);
        List<Rule> rules = RuleLoader.load(f);
        assertEquals(2, rules.size(), "夹具自身没造好: " + rules);
        return rules;
    }

    private static String 清单(DocumentService.Result r) {
        return r.hits().toString();
    }
}
