package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.litemask.core.Change;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可逆性测试（目标 G1）：脱敏后再还原，必须与原文**逐字节相同**。
 *
 * <p>这里的用例刻意包含刁钻输入：原文里本来就写着占位符样式的文本、
 * 同一个值在全文出现多次、CRLF 换行、空串、纯空白。往返一致性一旦被破坏，
 * 在真实业务里意味着用户的原始文档被改坏了——这是脱敏工具的底线。
 */
class RedactionRoundTripTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");

    private static final String ID_A = "11010519491231002X";
    private static final String ID_B = "310115198505201238";
    private static final String CARD = "62220212345678903";

    @Test
    @DisplayName("基础往返：多实体文本脱敏后能完整还原")
    void roundTripBasic() {
        String text = """
                原告身份证号：11010519491231002X
                被告身份证号：310115198505201238
                收款银行卡号：62220212345678903
                联系电话：13812345678
                """;
        RedactionResult result = RedactionEngine.redact(text, RULES);
        assertTrue(result.isChanged());
        assertNotEquals(text, result.sanitizedText());
        assertEquals(text, result.restore(), "还原结果必须与原文逐字节相同");
    }

    @Test
    @DisplayName("同值同符：同一身份证出现多次复用同一令牌")
    void sameValueSharesToken() {
        String text = "甲 " + ID_A + " 与乙 " + ID_A + " 及丙 " + ID_A + " 系同一人。";
        RedactionResult result = RedactionEngine.redact(text, RULES);
        String sanitized = result.sanitizedText();
        // 三处出现是一条映射、一个令牌，但对应三次替换——change 记的是「替换了几处」，
        // 不是「建了几条映射」。少报替换位置会让核对报告漏掉实际改动。
        assertEquals(1, result.mapping().size(), "三个同值只应产生一条映射");
        assertEquals(3, changes(result).size(), "三处出现应记录三条替换");
        assertEquals(3, count(sanitized, "<身份证1>"), "三处出现都应被替换");
        assertFalse(sanitized.contains(ID_A), "原值仍在：" + sanitized);
        assertEquals(text, result.restore());
    }

    private static List<Change> changes(RedactionResult result) {
        return result.changes();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("原文自带占位符：不能被新分配的令牌撞号，还原后原文一字不动")
    void doesNotCollideWithExistingToken() {
        String text = "模板占位：<身份证3>；实际身份证：" + ID_A;
        RedactionResult result = RedactionEngine.redact(text, RULES);
        assertTrue(result.sanitizedText().contains("<身份证3>"), "原文里的占位符必须原样保留");
        assertTrue(result.sanitizedText().contains("<身份证4>"), "新令牌应避开已存在的编号");
        assertEquals(text, result.restore());
    }

    @Test
    @DisplayName("原文含方括号令牌样式：不误判、不还原破坏")
    void handlesBracketTokenStyle() {
        String text = "字段[姓名_2]对应：140101199203074321".replace("140101199203074321", "11010519491231002X");
        RedactionResult result = RedactionEngine.redact(text, RULES);
        assertTrue(result.sanitizedText().contains("[姓名_2]"));
        assertEquals(text, result.restore());
    }

    @Test
    @DisplayName("CRLF 换行与首尾空白：原样保留")
    void preservesWhitespaceAndLineEndings() {
        String text = "  身份证 " + ID_A + "  \r\n\r\n  银行卡 " + CARD + "\r\n";
        RedactionResult result = RedactionEngine.redact(text, RULES);
        assertEquals(text, result.restore());
    }

    @Test
    @DisplayName("无命中 / 空串 / null：安全返回且可还原")
    void handlesEmptyAndNoMatch() {
        assertEquals("", RedactionEngine.redact("", RULES).restore());
        assertEquals("什么都没有。", RedactionEngine.redact("什么都没有。", RULES).restore());
        assertEquals("", RedactionEngine.redact(null, RULES).sanitizedText());
    }

    @Test
    @DisplayName("还原幂等：反复还原结果不变")
    void restoreIsIdempotent() {
        String text = "电话 13812345678，邮箱 a.b@example.com，身份证 " + ID_A;
        RedactionResult result = RedactionEngine.redact(text, RULES);
        String once = result.restore();
        String twice = RedactionEngine.restore(once, result.mapping());
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("还原不级联：原文里含另一个令牌字面量也不会被连锁替换")
    void restoreDoesNotCascade() {
        String text = "说明：占位符 <身份证1> 表示身份证。实际值 " + ID_A;
        RedactionResult result = RedactionEngine.redact(text, RULES);
        // 原文的 <身份证1> 不会被分配给真实身份证（分配器避让），此处只验证还原稳定
        assertEquals(text, result.restore());
        assertEquals(result.restore(), result.restore());
    }
}