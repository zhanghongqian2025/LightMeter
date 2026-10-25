package com.litemask.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨文本槽替换的行为测试。
 *
 * <p>这里刻意用手写的刁钻输入：Word 把一句话拆成几个 run 是常态，
 * 而身份证横跨三个 run 的文档如果按"每个槽单独匹配"处理，会一条都匹配不上——
 * 工具却报告"脱敏成功"。这组用例就是把那种文档拆出来的最小形式。
 */
class SlotTextMapperTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");
    private static final String ID_A = "11010519491231002X";
    private static final String ID_B = "310115198505201238";

    private static List<String> map(String sessionText, List<String> slots) {
        RedactionSession session = new RedactionSession(sessionText, RULES);
        return SlotTextMapper.map(slots, session).texts();
    }

    @Test
    @DisplayName("实体完整落在一个槽：只改那一个槽，其余原样")
    void singleSlot() {
        assertEquals(List.of("身份证：", "<身份证1>"),
                map("", List.of("身份证：", ID_A)));
    }

    @Test
    @DisplayName("实体横跨两个槽：前缀+令牌写进首槽，后缀留在末槽")
    void spansTwoSlots() {
        assertEquals(List.of("号码：<身份证1>", "，男"),
                map("", List.of("号码：1101051949", "1231002X，男")));
    }

    @Test
    @DisplayName("实体横跨三个槽：中间槽被清空，不留残片")
    void spansThreeSlots() {
        assertEquals(List.of("号码：<身份证1>", "", ""),
                map("", List.of("号码：110105", "19491231", "002X")));
    }

    @Test
    @DisplayName("实体从头开始：首槽整段被令牌取代，不留悬空前缀")
    void entityStartsAtFirstSlot() {
        assertEquals(List.of("<身份证1>", "", "，汉族"),
                map("", List.of("1101051949", "1231002X", "，汉族")));
    }

    @Test
    @DisplayName("同段多处命中：按从文末向前应用，长度变化不顶偏前面的位置")
    void multipleHitsInOneParagraph() {
        List<String> slots = List.of("甲 " + ID_A + " 乙 " + ID_B + " 丙");
        List<String> out = map("", slots);
        assertEquals(List.of("甲 <身份证1> 乙 <身份证2> 丙"), out);
        assertFalse(String.join("", out).contains(ID_A));
        assertFalse(String.join("", out).contains(ID_B));
    }

    @Test
    @DisplayName("空槽不承载字符：前后留白槽保持为空")
    void emptySlotsAreTransparent() {
        assertEquals(List.of("", "手机 <手机号1>", ""),
                map("", List.of("", "手机 13812345678", "")));
    }

    @Test
    @DisplayName("同一实体出现在不同槽：同值同符")
    void sameValueSharesToken() {
        RedactionSession session = new RedactionSession("", RULES);
        List<String> first = SlotTextMapper.map(List.of("甲 " + ID_A), session).texts();
        List<String> second = SlotTextMapper.map(List.of("乙 " + ID_A), session).texts();
        assertEquals(List.of("甲 <身份证1>"), first);
        assertEquals(List.of("乙 <身份证1>"), second, "同一身份证在不同段落必须复用同一令牌");
    }

    @Test
    @DisplayName("无命中：逐字节原样返回")
    void noMatchReturnsInput() {
        List<String> slots = List.of("这是一段没有敏感信息的普通文字", "第二槽");
        assertEquals(slots, map("", slots));
    }

    @Test
    @DisplayName("令牌本身不被再次匹配：写回后不产生套娃占位符")
    void tokenIsNotRematched() {
        List<String> out = map("", List.of("身份证 " + ID_A));
        String joined = String.join("", out);
        assertEquals(1, joined.chars().filter(c -> c == '<').count(), "只应出现一对尖括号，实得：" + joined);
        assertTrue(joined.contains("<身份证1>"));
    }

    @Test
    @DisplayName("槽内前后缀在替换后仍原样保留")
    void surroundingTextPreserved() {
        assertEquals(List.of("（身份证：", "<身份证1>", "）"),
                map("", List.of("（身份证：", ID_A, "）")));
    }

    @Test
    @DisplayName("空输入与 null 安全返回")
    void handlesEmptyInput() {
        RedactionSession session = new RedactionSession("", RULES);
        assertEquals(List.of(), SlotTextMapper.map(List.of(), session).texts());
        assertEquals(List.of(), SlotTextMapper.map(null, session).texts());
        assertFalse(SlotTextMapper.map(List.of("甲"), null).changed());
    }
}
