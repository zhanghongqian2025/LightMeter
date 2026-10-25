package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「排除误报」能力的测试。
 *
 * <p>这个功能最容易出的错是<b>静默的</b>：界面上用户点了「不脱」，文档里却还是被脱了，
 * 或者只脱了一半。用户不会看到报错，只会以为工具不听话，然后不再信任它的提示。
 * 所以下面三条顺序语义逐条钉死。
 *
 * <p>跨规则的重叠用例用<b>自造规则</b>而不是内置规则：内置规则之间究竟有没有
 * 「一条规则的命中被另一条规则的命中包住」这种关系，是规则库的性质，会随规则增删而变；
 * 而这里要验的是<b>引擎的顺序</b>，不该跟着规则库变。
 */
class ExclusionTest {

    private static final List<Rule> REAL = RuleLoader.loadFromClasspath("rules/core-rules.json");

    private static Rule rule(String key, String pattern, String prefix, int sortOrder) {
        return new Rule(key, key, "测试用", pattern, prefix, sortOrder, true,
                Category.REGEX, Validator.NONE);
    }

    // 两条规则命中**同一段**：高优先级规则被排除后，低优先级规则不许接管。
    private static final Rule HIGH = rule("high", "\\d{11}", "甲", 200);
    private static final Rule LOW = rule("low", "1\\d{10}", "乙", 100);

    // 两条规则的命中**互相包含**：排除外层之后，内层也必须一起让开。
    // 注意外层是 17 位不是 18 位：大陆身份证去掉末位校验位正好 17 位，
    // 写成 \d{18} 会一条都匹配不到，前置断言立刻就把它拆穿了。
    private static final Rule OUTER = rule("outer", "\\d{17}", "外", 200);
    private static final Rule INNER = rule("inner", "\\d{11}", "内", 100);

    // 同上，但**赢家/输家**反过来：赢家只吃前 11 位，输家吃满 17 位。
    private static final Rule WINNER = rule("winner", "\\d{11}", "丁", 200);
    private static final Rule LOSER = rule("loser", "\\d{17}", "丙", 100);

    @Test
    @DisplayName("排除的值原样保留，且不进映射、不分配令牌")
    void 排除的值原样保留() {
        String text = "手机 13800138000，邮箱 zhang@example.com";
        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("phone", "13800138000")));

        RedactionResult r = RedactionEngine.redact(text, REAL, new TokenAllocator(text), ex);

        assertTrue(r.sanitizedText().contains("13800138000"), "被排除的值应原样保留: " + r.sanitizedText());
        assertFalse(r.sanitizedText().contains("<手机"), r.sanitizedText());
        // 映射里不能有它——有的话还原时会拿令牌去换一个根本没被替换过的字符串
        assertFalse(r.mapping().containsValue("13800138000"),
                "被排除的值不应进入映射: " + r.mapping());
        // 另一条规则照常脱敏，说明排除没有误伤全局
        assertTrue(r.sanitizedText().contains("<邮箱"), r.sanitizedText());
    }

    @Test
    @DisplayName("排除先于重叠消解：输掉重叠的那条被排除时，赢家也不许留下")
    void 排除先于重叠消解() {
        String text = "联系方式 13800138000 完毕";
        // 不排除时：高优先级胜出
        RedactionResult normal = RedactionEngine.redact(text, List.of(HIGH, LOW), new TokenAllocator(text));
        assertTrue(normal.sanitizedText().contains("<甲1>"), normal.sanitizedText());

        // 排除高优先级那条。若排除发生在重叠消解**之后**，
        // 低优先级规则会看到这段没人占，于是拿走它 —— 用户点了"别脱"却还是被脱了。
        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("high", "13800138000")));
        RedactionResult r = RedactionEngine.redact(text, List.of(HIGH, LOW),
                new TokenAllocator(text), ex);

        assertEquals(text, r.sanitizedText(),
                "被排除的跨度必须完全没人碰（包括别的规则）");
        assertTrue(r.mapping().isEmpty(), String.valueOf(r.mapping()));
    }

    @Test
    @DisplayName("排除输掉重叠的那条时，赢家必须跟着让开——这条才真的区分得出顺序")
    void 排除输家时赢家也要让开() {
        // 上一条排除的是重叠里的**赢家**，那种情况下"排除放在消解前"与"放在消解后"
        // 结果相同（赢家被去掉后没人接手），所以它其实咬不住顺序。
        // 真正能区分的是排除**输家**：正确实现按跨度保护，输家一被排除，赢家也失去依据；
        // 错误实现（先消解重叠再排除）里输家早就被丢掉了，那次排除是空操作，赢家照旧留下。
        String text = "证件 11010519491231002 结束";

        RedactionResult normal = RedactionEngine.redact(text, List.of(WINNER, LOSER),
                new TokenAllocator(text));
        // 前置条件的期望值改过一次：原来写的是「证件 <丁1>231002 结束」，
        // 也就是把 LOSER 的残余片段（身份证后 6 位）裸露出去当正常结果。
        // 引擎改成「落败命中只丢重叠部分、独有部分仍脱敏」之后，正确的正常态是
        // 两段都被脱敏。把泄漏写进前置条件，等于让后面的断言站在一个错的起点上。
        assertEquals("证件 <丁1><丙1> 结束", normal.sanitizedText(),
                "前置条件：高优先级规则确实压过低优先级规则，且输家的残余不会被裸露");

        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("loser", "11010519491231002")));
        RedactionResult r = RedactionEngine.redact(text, List.of(WINNER, LOSER),
                new TokenAllocator(text), ex);

        assertEquals(text, r.sanitizedText(),
                "用户排除的是这个值，不是「这一次的这段位置」；赢家必须一起让开");
    }

    @Test
    @DisplayName("排除保护整个跨度：内层规则不许从被排除的值里再抠一段")
    void 排除保护整个跨度() {
        String text = "证件 11010519491231002 结束";
        // inner 的 \d{11} 会命中里面的 19491231002
        RedactionResult normal = RedactionEngine.redact(text, List.of(OUTER, INNER),
                new TokenAllocator(text));
        assertEquals("证件 <外1> 结束", normal.sanitizedText(),
                "前置条件：不排除时外层确实压过内层");

        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("outer", "11010519491231002")));
        RedactionResult r = RedactionEngine.redact(text, List.of(OUTER, INNER),
                new TokenAllocator(text), ex);

        assertEquals(text, r.sanitizedText(),
                "排除外层后内层必须一起让开，否则用户排除整段却还剩半段没脱");
    }

    @Test
    @DisplayName("排除只影响指定的值，同规则的其他值照常脱敏")
    void 只影响指定值() {
        String text = "甲 13800138000 乙 13900139000";
        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("phone", "13800138000")));

        RedactionResult r = RedactionEngine.redact(text, REAL, new TokenAllocator(text), ex);

        assertTrue(r.sanitizedText().contains("13800138000"), r.sanitizedText());
        assertFalse(r.sanitizedText().contains("13900139000"), r.sanitizedText());
        assertTrue(r.sanitizedText().contains("<手机"), r.sanitizedText());
    }

    @Test
    @DisplayName("排除键跨扫描稳定——界面拿第一遍的键回来，第二遍还对得上")
    void 排除键稳定() {
        String text = "甲 13800138000 乙 11010519491231002X 丙 zhang@example.com 丁 24112000000048542163";

        Set<Exclusions.HitRef> 第一遍 = 键集合(RedactionEngine.redact(text, REAL, new TokenAllocator(text)));
        Set<Exclusions.HitRef> 第二遍 = 键集合(RedactionEngine.redact(text, REAL, new TokenAllocator(text)));
        assertEquals(第一遍, 第二遍, "同文件同规则两次扫描出的 (规则, 值) 集合必须相同");
        assertFalse(第一遍.isEmpty(), "前置条件：这份文本应当真的命中若干条");

        // 把第一遍的全部键都排除，结果必须是「什么都没脱」
        RedactionResult r = RedactionEngine.redact(text, REAL, new TokenAllocator(text),
                Exclusions.of(第一遍));
        assertEquals(text, r.sanitizedText());
    }

    private static Set<Exclusions.HitRef> 键集合(RedactionResult result) {
        return result.changes().stream()
                .map(c -> new Exclusions.HitRef(c.ruleKey(), c.original()))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    @Test
    @DisplayName("会话级排除贯穿整篇文档——漏传一段就会变成只脱了一半")
    void 会话级排除贯穿全文() {
        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("phone", "13800138000")));
        // 同一个值出现在两段里，两段都必须原样保留
        RedactionSession session = new RedactionSession("13800138000 13800138000", REAL, ex);

        RedactionResult a = session.redact("第一段 13800138000");
        RedactionResult b = session.redact("第二段 13800138000");

        assertEquals("第一段 13800138000", a.sanitizedText(), a.sanitizedText());
        assertEquals("第二段 13800138000", b.sanitizedText(), b.sanitizedText());
        assertTrue(session.mapping().isEmpty(), String.valueOf(session.mapping()));
    }

    @Test
    @DisplayName("排除之后往返仍然一致：没脱的保持原样，脱了的能还原")
    void 排除后往返一致() {
        String text = "联系人 13800138000，邮箱 zhang@example.com";
        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("phone", "13800138000")));

        RedactionResult r = RedactionEngine.redact(text, REAL, new TokenAllocator(text), ex);
        Map<String, String> mapping = r.mapping();

        String restored = RedactionEngine.restore(r.sanitizedText(), mapping);
        assertEquals("联系人 13800138000，邮箱 zhang@example.com", restored, restored);
    }

    @Test
    @DisplayName("不传排除项时行为与以前完全一致（NONE 不是空壳分支）")
    void 空排除项等价于不传() {
        String text = "联系人 13800138000，邮箱 zhang@example.com";
        RedactionResult 不传 = RedactionEngine.redact(text, REAL);
        RedactionResult 传None = RedactionEngine.redact(text, REAL, new TokenAllocator(text), Exclusions.NONE);
        RedactionResult 传空 = RedactionEngine.redact(text, REAL, new TokenAllocator(text),
                new Exclusions(Set.of()));

        assertEquals(不传.sanitizedText(), 传None.sanitizedText());
        assertEquals(不传.sanitizedText(), 传空.sanitizedText());
        assertEquals(不传.mapping(), 传None.mapping());
    }

    @Test
    @DisplayName("排除项对不上的命中时不做无谓过滤（防御性：不能让一次手滑废掉整篇文档）")
    void 对不上的排除项不误伤() {
        String text = "联系人 13800138000";
        // 规则键写错：这条排除谁也排除不掉，但文档必须照常脱敏
        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("phone_typo", "13800138000")));

        RedactionResult r = RedactionEngine.redact(text, REAL, new TokenAllocator(text), ex);

        assertFalse(r.sanitizedText().contains("13800138000"),
                "排除项没对上任何命中，不应导致整篇放弃脱敏: " + r.sanitizedText());
        assertTrue(r.sanitizedText().contains("<手机"), r.sanitizedText());
    }

    @Test
    @DisplayName("空数组丢弃；残缺项让整个请求失败，而不是悄悄少排除一条")
    void 残缺排除项被拒绝() {
        // 数组里的 null 元素：丢弃。JSON 数组出现 null 元素是可能的，没有语义。
        Exclusions ex = Exclusions.ofList(java.util.Arrays.asList(
                new Exclusions.HitRef("phone", "13800138000"),
                null));
        assertEquals(1, ex.size(), ex.hits().toString());

        assertTrue(Exclusions.ofList(List.of()).isEmpty());
        assertTrue(Exclusions.ofList(java.util.Arrays.asList((Exclusions.HitRef) null)).isEmpty());
        assertTrue(Exclusions.ofList(null).isEmpty());

        // 残缺项：**拒绝**。
        // 悄悄丢掉一条排除 = 朝着"多脱敏"错——密不透风，但用户以为保留的值没保留，
        // 于是不再信任界面上的每一条提示。那比当场报错坏得多。
        assertThrows(IllegalArgumentException.class,
                () -> Exclusions.ofList(List.of(new Exclusions.HitRef("", "x"))));
    }

    @Test
    void 空原值与空规则键被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> new Exclusions.HitRef("phone", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new Exclusions.HitRef("", "13800138000"));
        assertThrows(IllegalArgumentException.class,
                () -> new Exclusions.HitRef(null, "13800138000"));
    }

    @Test
    @DisplayName("null 排除项等价于 NONE，不抛异常")
    void null排除项() {
        String text = "联系人 13800138000";
        RedactionResult r = RedactionEngine.redact(text, REAL, new TokenAllocator(text), null);
        assertTrue(r.sanitizedText().contains("<手机"), r.sanitizedText());
        assertEquals(0, new RedactionSession(text, REAL, null).exclusions().size());
    }
}
