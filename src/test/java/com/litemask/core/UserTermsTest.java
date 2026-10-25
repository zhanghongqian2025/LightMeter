package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserTerms}：用户补充的敏感词怎么变成规则、怎么被校验。
 *
 * <p><b>为什么校验要在这里</b>：界面只是入口。命令行与任何别的调用方都要走同一条校验 ——
 * 否则「界面上不让补」只是一层界面上的限制，换个入口就绕过去了。
 */
class UserTermsTest {

    @Test
    @DisplayName("补的词变成字面量规则（词里的正则元字符不当正则解释）")
    void 变成字面量规则() {
        List<Rule> rules = UserTerms.toRules(List.of("a.b*c", "（某）"));
        assertEquals(2, rules.size());
        // Pattern.quote 的结果：元字符被 \Q…\E 包住
        assertTrue(rules.get(0).pattern().startsWith("\\Q"), rules.get(0).pattern());
        assertTrue(rules.get(0).pattern().endsWith("\\E"), rules.get(0).pattern());
        // 规则键共用一个 → 令牌序号连着排（<补充1>、<补充2>）
        assertEquals(UserTerms.RULE_KEY, rules.get(0).key());
        assertEquals(UserTerms.RULE_KEY, rules.get(1).key());
        assertEquals(UserTerms.TOKEN_PREFIX, rules.get(0).tokenPrefix());
        // 优先级高于内置规则（用户指认的应当压过通用形态规则）
        assertTrue(rules.get(0).sortOrder() > 200, "优先级应当高于内置规则：" + rules.get(0).sortOrder());
        assertTrue(rules.get(0).active());
    }

    @Test
    @DisplayName("空白词被丢掉（不生成规则）")
    void 空白词被丢掉() {
        // 用 Arrays.asList 而不是 List.of：后者不接受 null（会当场 NPE），
        // 而「词表里混进一个 null」正是这里要验的情况之一。
        assertEquals(0, UserTerms.toRules(java.util.Arrays.asList("", "   ", null)).size());
        assertEquals(0, UserTerms.toRules(null).size());
    }

    @Test
    @DisplayName("校验：太短的词要拒（一个字会命中全文每一个字）")
    void 太短被拒() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> UserTerms.validate(List.of("张")));
        assertTrue(e.getMessage().contains("太短"), e.getMessage());
    }

    @Test
    @DisplayName("校验：太长的词要拒")
    void 太长被拒() {
        String 太长 = "甲".repeat(UserTerms.MAX_TERM_LENGTH + 1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> UserTerms.validate(List.of(太长)));
        assertTrue(e.getMessage().contains("太长"), e.getMessage());
    }

    @Test
    @DisplayName("校验：一次太多词要拒")
    void 太多被拒() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= UserTerms.MAX_TERMS; i++) {
            many.add("词" + i);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> UserTerms.validate(many));
        assertTrue(e.getMessage().contains("最多"), e.getMessage());
    }

    @Test
    @DisplayName("校验：空表与全空白都要拒（不能当成「什么都没加」就默默通过）")
    void 空表被拒() {
        assertThrows(IllegalArgumentException.class, () -> UserTerms.validate(null));
        assertThrows(IllegalArgumentException.class, () -> UserTerms.validate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> UserTerms.validate(List.of(" ", "　")));
    }

    @Test
    @DisplayName("校验：去空白、去重、保序")
    void 规范化() {
        List<String> out = UserTerms.validate(List.of("  李某  ", "张某", "李某", " 王某 "));
        assertEquals(List.of("李某", "张某", "王某"), out);
    }

    @Test
    @DisplayName("刚好两个字的词要放行（那是最常见的用法）")
    void 两个字放行() {
        assertEquals(List.of("李某"), UserTerms.validate(List.of("李某")));
    }
}
