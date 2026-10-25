package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎行为测试：优先级、幂等、偏移、配置容错。
 *
 * <p>这些是"看起来能跑、实际会算错"的地方，属于最值得钉死的用例。
 */
class EngineBehaviorTest {

    private static Rule rule(String key, String pattern, String prefix, int sortOrder, boolean active) {
        return new Rule(key, key, "", pattern, prefix, sortOrder, active, Category.REGEX, Validator.NONE);
    }

    @Test
    @DisplayName("命中重叠时高优先级胜出，与规则输入顺序无关")
    void higherSortOrderWinsOnOverlap() {
        Rule broad = rule("broad", "(?<![0-9])1234567890123456(?![0-9])", "宽泛", 1, true);
        Rule specific = rule("specific", "(?<![0-9])1234\\d{12}(?![0-9])", "精确", 200, true);
        String text = "账号 1234567890123456 已核验";

        RedactionResult b1 = RedactionEngine.redact(text, List.of(broad, specific));
        RedactionResult b2 = RedactionEngine.redact(text, List.of(specific, broad));

        assertEquals("账号 <精确1> 已核验", b1.sanitizedText());
        assertEquals(b1.sanitizedText(), b2.sanitizedText(), "规则顺序不得影响结果");
        assertEquals(text, b1.restore());
    }

    @Test
    @DisplayName("停用的规则不生效")
    void inactiveRuleIsSkipped() {
        Rule off = rule("off", "(?<![0-9])\\d{4}(?![0-9])", "关", 100, false);
        assertFalse(RedactionEngine.redact("编号 1234 内容", List.of(off)).isChanged());
    }

    @Test
    @DisplayName("命中片段首尾空白被裁掉，且偏移修正后仍能还原")
    void trimsSurroundingWhitespace() {
        Rule r = rule("num", " 1234 ", "数字", 100, true);
        RedactionResult result = RedactionEngine.redact("金额 1234 元", List.of(r));
        assertEquals("金额 <数字1> 元", result.sanitizedText());
        assertEquals("金额 1234 元", result.restore(), "空白必须留在原处，不能被吞进令牌");
    }

    @Test
    @DisplayName("幂等：对已脱敏文本再跑一次不产生新改动")
    void idempotentOnAlreadyRedactedText() {
        List<Rule> rules = RuleLoader.loadFromClasspath("rules/core-rules.json");
        String text = "身份证 11010519491231002X，手机 13812345678，邮箱 a.b@example.com";
        RedactionResult first = RedactionEngine.redact(text, rules);
        RedactionResult second = RedactionEngine.redact(first.sanitizedText(), rules);
        assertEquals(first.sanitizedText(), second.sanitizedText(), "二次脱敏不得改变文本");
        assertEquals(0, second.changes().size(), "二次脱敏不应产生新改动");
    }

    @Test
    @DisplayName("多规则并存时令牌前缀各自独立计数")
    void prefixesCountIndependently() {
        List<Rule> rules = RuleLoader.loadFromClasspath("rules/core-rules.json");
        String text = "身份证 11010519491231002X 和 310115198505201238，手机 13812345678 和 13912345678";
        RedactionResult result = RedactionEngine.redact(text, rules);
        String s = result.sanitizedText();
        assertTrue(s.contains("<身份证1>"), s);
        assertTrue(s.contains("<身份证2>"), s);
        assertTrue(s.contains("<手机号1>"), s);
        assertTrue(s.contains("<手机号2>"), s);
        assertEquals(text, result.restore());
    }

    @Test
    @DisplayName("非法正则在匹配期报明确错误，而不是静默跳过")
    void invalidRegexFailsLoudly() {
        Rule bad = rule("bad", "([unclosed", "坏", 100, true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RedactionEngine.redact("任意文本", List.of(bad)));
        assertTrue(e.getMessage().contains("bad"), e.getMessage());
    }

    @Test
    @DisplayName("规则集合打乱后结果稳定（随机 20 次）")
    void resultIsStableUnderShuffling() {
        List<Rule> rules = new ArrayList<>(RuleLoader.loadFromClasspath("rules/core-rules.json"));
        String text = "案号（2024）京0105民初123号，联系人 13812345678，代码 91350100M000100Y43。";
        String expected = RedactionEngine.redact(text, rules).sanitizedText();
        Random random = new Random(20261014L);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(rules, random);
            assertEquals(expected, RedactionEngine.redact(text, rules).sanitizedText(),
                    "第 " + i + " 次打乱后结果发生变化");
        }
    }

    @Test
    @DisplayName("规则加载：缺省字段补齐，非法枚举值报错")
    void loaderDefaultsAndErrors() {
        String json = """
                {"version":1,"rules":[
                  {"key":"k1","name":"规则一","pattern":"a"},
                  {"key":"k2","pattern":"b","sortOrder":7,"active":false,"validator":"LUHN"}
                ]}""";
        List<Rule> rules = RuleLoader.parse(json);
        assertEquals(2, rules.size());
        assertTrue(rules.get(0).active(), "active 缺省应为 true");
        assertEquals(100, rules.get(0).sortOrder(), "sortOrder 缺省应为 100");
        assertEquals(Validator.LUHN, rules.get(1).validator());
        assertFalse(rules.get(1).active());
        assertEquals("规则一", rules.get(0).name());
        assertEquals("规则一", rules.get(0).tokenPrefix(), "tokenPrefix 缺省应回落到 name");

        assertThrows(IllegalArgumentException.class,
                () -> RuleLoader.parse("{\"rules\":[{\"key\":\"x\",\"pattern\":\"a\",\"validator\":\"NOPE\"}]}"));
    }
}