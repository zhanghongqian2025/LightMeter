package com.litemask.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 规则召回/精度测试：每条规则的每个样本都是独立用例，失败时直接点名是哪条规则的哪条样本。
 *
 * <p>样本由 tools/gen-rule-samples.py 生成，其中校验位类样本（身份证/信用代码/银行卡）
 * 全部由独立实现计算得出——手写几乎必错，而"错的样本"算错会让测试假绿。
 *
 * <p>测试走真实引擎路径（单规则入参），不是直接调正则：规则被改坏、校验器被绕过、
 * 偏移算错，都会在这里暴露。
 */
class RuleSampleTest {

    private static Map<String, Rule> rules;
    private static JsonNode samples;

    @BeforeAll
    static void loadAll() {
        rules = RuleLoader.loadFromClasspath("rules/core-rules.json").stream()
                .collect(Collectors.toMap(Rule::key, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("规则 key 重复: " + a.key());
                }));
        try (InputStream in = RuleSampleTest.class.getClassLoader().getResourceAsStream("rule-samples.json")) {
            assertNotNull(in, "缺少 rule-samples.json，请先执行 tools/gen-rule-samples.py");
            samples = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestFactory
    Stream<DynamicTest> everySampleIsHonoured() {
        List<DynamicTest> tests = new ArrayList<>();
        Iterator<String> keys = samples.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode group = samples.get(key);
            tests.addAll(assertMatches(key, group.get("positive"), true));
            tests.addAll(assertMatches(key, group.get("negative"), false));
            tests.addAll(assertPartial(key, group.get("partial")));
        }
        return tests.stream();
    }

    /**
     * partial：上下文非法但内含一段语法合法的内容，引擎按「宁可多脱不可漏脱」提取其中合法片段。
     * 这里断言<b>提取出的具体片段</b>，比笼统的正/负断言更强——不仅要求命中，还要求命中的是哪一段。
     */
    private List<DynamicTest> assertPartial(String key, JsonNode array) {
        if (array == null) {
            return List.of();
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            String text = item.get("text").asText();
            String expect = item.get("expect").asText();
            String label = String.format("规则 %s · 片段提取 #%d · %s",
                    key, i + 1, abbreviate(text) + " → " + expect);
            tests.add(DynamicTest.dynamicTest(label, () -> {
                Rule rule = rules.get(key);
                if (rule == null) {
                    fail("样本里的规则 key 在规则库中不存在: " + key);
                }
                RedactionResult result = RedactionEngine.redact(text, List.of(rule));
                String extracted = result.changes().stream()
                        .map(Change::original)
                        .findFirst()
                        .orElse("(未命中)");
                assertEquals(expect, extracted,
                        "规则 " + key + " 应从非法上下文中提取出指定片段。样本: " + text);
            }));
        }
        return tests;
    }

    private List<DynamicTest> assertMatches(String key, JsonNode array, boolean shouldMatch) {
        List<DynamicTest> tests = new ArrayList<>();
        assertNotNull(array, "样本缺少分组: " + key);
        for (int i = 0; i < array.size(); i++) {
            String sample = array.get(i).asText();
            String label = String.format("规则 %s · %s样本 #%d · %s",
                    key, shouldMatch ? "正向" : "负向", i + 1, abbreviate(sample));
            tests.add(DynamicTest.dynamicTest(label, () -> {
                Rule rule = rules.get(key);
                if (rule == null) {
                    fail("样本里的规则 key 在规则库中不存在: " + key + "（规则库现有: " + rules.keySet() + "）");
                }
                RedactionResult result = RedactionEngine.redact(sample, List.of(rule));
                if (shouldMatch) {
                    assertTrue(result.isChanged(),
                            "规则 " + key + " 本应命中却没有命中。样本: " + sample);
                } else {
                    assertFalse(result.isChanged(),
                            "规则 " + key + " 本不该命中却误报了。样本: " + sample
                                    + " → 命中片段: " + result.changes());
                }
            }));
        }
        return tests;
    }

    private static String abbreviate(String s) {
        String flat = s.replace("\n", "\\n");
        return flat.length() <= 60 ? flat : flat.substring(0, 57) + "...";
    }
}