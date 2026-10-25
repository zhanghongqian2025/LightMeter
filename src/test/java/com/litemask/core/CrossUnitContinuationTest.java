package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨单元续接：标识符被段落／单元格边界切断时的识别与脱敏。
 *
 * <p>这组用例针对的是一个<b>会静默漏脱</b>的缺陷：身份证被切成两段以后，
 * 每一段单独看都匹配不上任何规则，工具却照样报告「脱敏成功」，
 * 碎片原样留在交付物里。所以每条断言都盯着两件事：
 * <b>碎片一个字符都不许留在文档里</b>，以及<b>类型必须对</b>。
 */
class CrossUnitContinuationTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");

    /** 校验位正确的身份证（GB 11643）。 */
    private static final String ID = "11010519491231002X";

    // ------------------------------------------------------------------ 纯函数

    @Test
    @DisplayName("切成 6+12：两段都按身份证归类，覆盖区间落在碎片上")
    void splitSixPlusTwelve() {
        List<String> units = List.of("身份证110105号", "续19491231002X尾");
        List<CrossUnitContinuation.Override> overrides = CrossUnitContinuation.detect(units, RULES);
        assertEquals(2, overrides.size(), "应当认出两段碎片，实际：" + overrides);
        assertEquals("id_card", overrides.get(0).ruleKey(), "第一段应当归为身份证");
        assertEquals(3, overrides.get(0).start(), "第一段覆盖区间起点不对");
        assertEquals(9, overrides.get(0).end(), "第一段覆盖区间终点不对");
        assertEquals("id_card", overrides.get(1).ruleKey(), "第二段应当归为身份证");
        assertEquals(1, overrides.get(1).start(), "第二段覆盖区间起点不对");
        assertEquals(13, overrides.get(1).end(), "第二段覆盖区间终点不对");
    }

    @Test
    @DisplayName("切成 14+3：两段都匹配不上任何规则，正是整张泄漏的那种切法")
    void splitFourteenPlusThree() {
        List<String> units = List.of("身份证11010519491231号", "续002X尾");
        List<CrossUnitContinuation.Override> overrides = CrossUnitContinuation.detect(units, RULES);
        assertEquals(2, overrides.size(), "应当认出两段碎片，实际：" + overrides);
        assertEquals("id_card", overrides.get(0).ruleKey());
        assertEquals("id_card", overrides.get(1).ruleKey());
        // 14 位那一段单独看是「任何规则都不匹配」——这正是从前漏掉它的原因
        assertFalse(overrides.get(0).ruleKey().isEmpty());
    }

    @Test
    @DisplayName("切成三段：链式续接，三段都按身份证归类")
    void splitIntoThree() {
        List<String> units = List.of("身份证110105号", "19491231", "续002X尾");
        List<CrossUnitContinuation.Override> overrides = CrossUnitContinuation.detect(units, RULES);
        assertEquals(3, overrides.size(), "应当认出三段碎片，实际：" + overrides);
        for (CrossUnitContinuation.Override o : overrides) {
            assertEquals("id_card", o.ruleKey(), "第 " + o.unitIndex() + " 段类型不对");
        }
        assertEquals(0, overrides.get(1).start(), "中间那段应当整段被覆盖");
        assertEquals(8, overrides.get(1).end(), "中间那段应当整段被覆盖");
    }

    @Test
    @DisplayName("校验位不对：拼起来像身份证也不算续接")
    void badChecksumIsNotContinuation() {
        // 把校验位改掉：11010519491231002Y 过不了 GB 11643
        List<String> units = List.of("身份证110105号", "续19491231002Y尾");
        assertTrue(CrossUnitContinuation.detect(units, RULES).isEmpty(),
                "校验位不过就不该续接，实际：" + CrossUnitContinuation.detect(units, RULES));
    }

    @Test
    @DisplayName("左边本来就是完整的手机号：不续接（值没被切断）")
    void completeValueIsNotContinued() {
        List<String> units = List.of("电话13800138000", "地址北京市朝阳区");
        assertTrue(CrossUnitContinuation.detect(units, RULES).isEmpty(),
                "完整值不该被续接，实际：" + CrossUnitContinuation.detect(units, RULES));
    }

    @Test
    @DisplayName("两段无关：既不匹配规则也不续接")
    void unrelatedParagraphsAreNotContinued() {
        List<String> units = List.of("金额1200元", "数量300件");
        assertTrue(CrossUnitContinuation.detect(units, RULES).isEmpty(),
                "无关的两段不该续接，实际：" + CrossUnitContinuation.detect(units, RULES));
    }

    @Test
    @DisplayName("lastRun / firstRun：取的是「单元里最后／第一段」，不是「单元末尾／开头」")
    void runSpansAreNotEdges() {
        CrossUnitContinuation.Span last = CrossUnitContinuation.lastRun("身份证110105号");
        assertEquals(3, last.start());
        assertEquals(9, last.end());
        CrossUnitContinuation.Span first = CrossUnitContinuation.firstRun("续19491231002X尾");
        assertEquals(1, first.start());
        assertEquals(13, first.end());
        // 纯中文段落没有字母数字段
        assertTrue(CrossUnitContinuation.lastRun("地址北京市朝阳区").isEmpty());
        assertTrue(CrossUnitContinuation.firstRun("地址北京市朝阳区").isEmpty());
    }

    @Test
    @DisplayName("优先级：同时命中多条规则时取 sortOrder 高的")
    void highestPriorityRuleWins() {
        // 18 位数字串既像身份证又像信用代码：身份证 sortOrder 120 > 信用代码 118
        Rule rule = CrossUnitContinuation.matchRule(RULES, ID);
        assertNotNull(rule);
        assertEquals("id_card", rule.key());
    }

    @Test
    @DisplayName("依赖上下文的规则（后顾断言）不参与续接")
    void contextDependentRulesDoNotParticipate() {
        // 发票号码规则要求前面有「发票号码」标签；拼接串没有上下文，断言不成立。
        // 这串数字本身过得了 Luhn，会归到银行卡号——但绝不该归到发票号码。
        Rule rule = CrossUnitContinuation.matchRule(RULES, "622202020202020207");
        assertNotNull(rule, "这串数字至少应当被某条规则认领");
        assertEquals("bank_card", rule.key(), "认领它的规则不对");
        assertFalse(rule.key().equals("invoice_no"), "依赖上下文的发票号码规则不该参与续接");
    }

    // ------------------------------------------------------------------ 分组与范围

    @Test
    @DisplayName("分组：组内可以续接，组间一律不续接（PDF 侧按内容流分组）")
    void continuationStaysInsideItsGroup() {
        // 同一组：两段相邻 → 续接
        List<List<String>> sameGroup = List.of(List.<String>of("身份证110105号", "续19491231002X尾"));
        List<CrossUnitContinuation.Override> inside = CrossUnitContinuation.detectGroups(
                sameGroup, RULES, ContinuationScope.ANY);
        assertEquals(2, inside.size(), "组内应当续接，实际：" + inside);

        // 不同组：同样是两段，但隔着一条「流」的边界 → 不续接
        List<List<String>> acrossGroups = List.of(
                List.<String>of("身份证110105号"),
                List.<String>of("续19491231002X尾"));
        assertTrue(CrossUnitContinuation.detectGroups(acrossGroups, RULES, ContinuationScope.ANY).isEmpty(),
                "组间不该续接（PDF 里相邻块可能分属两栏或两页）");
    }

    @Test
    @DisplayName("分组：单元序号是跨组连续的全局序号（覆盖要落在正确的单元上）")
    void overrideIndicesAreGlobalAcrossGroups() {
        List<List<String>> groups = List.of(
                List.<String>of("身份证110105号", "续19491231002X尾"),
                List.<String>of("第三段110105", "第四段19491231002X尾"));
        List<CrossUnitContinuation.Override> overrides = CrossUnitContinuation.detectGroups(
                groups, RULES, ContinuationScope.ANY);
        assertEquals(4, overrides.size(), "两组各认出两段，实际：" + overrides);
        assertEquals(0, overrides.get(0).unitIndex());
        assertEquals(1, overrides.get(1).unitIndex());
        assertEquals(2, overrides.get(2).unitIndex(), "第二组的序号必须接着第一组");
        assertEquals(3, overrides.get(3).unitIndex(), "第二组的序号必须接着第一组");
    }

    @Test
    @DisplayName("范围：只有有真校验位的规则参与续接（PDF 侧口径）")
    void checkDigitOnlyScopeExcludesUnverifiableRules() {
        // 手机号没有校验位：ANY 下会续接，CHECK_DIGIT_ONLY 下不续接
        List<String> phoneUnits = List.of("电话1380013800", "0");
        assertFalse(CrossUnitContinuation.detect(phoneUnits, RULES, ContinuationScope.ANY).isEmpty(),
                "ANY 口径下手机号应当续接");
        assertTrue(CrossUnitContinuation.detect(
                        phoneUnits, RULES, ContinuationScope.CHECK_DIGIT_ONLY).isEmpty(),
                "CHECK_DIGIT_ONLY 口径下手机号不该续接（它过校验器等于什么都收）");

        // 身份证有校验位：两种口径下都续接
        List<String> idUnits = List.of("身份证110105号", "续19491231002X尾");
        assertFalse(CrossUnitContinuation.detect(
                        idUnits, RULES, ContinuationScope.CHECK_DIGIT_ONLY).isEmpty(),
                "身份证在 CHECK_DIGIT_ONLY 口径下应当续接");
    }

    @Test
    @DisplayName("校验器分类：哪些是真校验位（VIN 刻意不算，它不校验第 9 位）")
    void validatorCheckDigitClassification() {
        assertTrue(Validator.ID_CARD.hasCheckDigit(), "身份证有 GB 11643 校验位");
        assertTrue(Validator.CREDIT_CODE.hasCheckDigit(), "统一社会信用代码有 GB 32100 校验位");
        assertTrue(Validator.LUHN.hasCheckDigit(), "银行卡有 Luhn 校验");
        assertFalse(Validator.VIN.hasCheckDigit(), "VIN 刻意不校验校验位，不能当真校验位用");
        assertFalse(Validator.IPV4.hasCheckDigit(), "IPv4 只是形态校验");
        assertFalse(Validator.NONE.hasCheckDigit(), "不校验当然不是校验位");
    }

    // ------------------------------------------------------------------ 会话层

    @Test
    @DisplayName("会话：碎片按身份证脱敏，一个字符都不许留在文档里")
    void sessionRedactsFragments() {
        String full = "身份证110105号\n续19491231002X尾";
        RedactionSession session = new RedactionSession(full, RULES, Exclusions.NONE, List.of(), true);
        RedactionResult first = session.redact("身份证110105号");
        RedactionResult second = session.redact("续19491231002X尾");

        assertEquals(0, session.continuationAlignmentFailures(), "对齐不该失败");
        assertEquals("身份证<身份证1>号", first.sanitizedText());
        assertEquals("续<身份证2>尾", second.sanitizedText());
        assertFalse(first.sanitizedText().contains("110105"), "碎片还在文档里：" + first.sanitizedText());
        assertFalse(second.sanitizedText().contains("19491231002"), "碎片还在文档里：" + second.sanitizedText());
        assertFalse(second.sanitizedText().contains("<手机号"), "碎片仍被错标成手机号：" + second.sanitizedText());
    }

    @Test
    @DisplayName("会话：还原逐字一致（不合并 ⇒ 映射仍然对得上每一段）")
    void sessionRestoresExactly() {
        String full = "身份证11010519491231号\n续002X尾";
        RedactionSession session = new RedactionSession(full, RULES, Exclusions.NONE, List.of(), true);
        String a = session.redact("身份证11010519491231号").sanitizedText();
        String b = session.redact("续002X尾").sanitizedText();
        assertEquals("身份证11010519491231号", session.restore(a));
        assertEquals("续002X尾", session.restore(b));
        Map<String, String> mapping = session.mapping();
        assertEquals(2, mapping.size(), "映射应当有两项，实际：" + mapping);
        assertTrue(mapping.containsValue("11010519491231"), "映射里没有第一段：" + mapping);
        assertTrue(mapping.containsValue("002X"), "映射里没有第二段：" + mapping);
    }

    @Test
    @DisplayName("会话：遍历顺序与全文对不上时，续接整个关掉并记账（宁可不续接也不错配）")
    void misalignmentDisablesContinuation() {
        String full = "身份证110105号\n续19491231002X尾";
        RedactionSession session = new RedactionSession(full, RULES, Exclusions.NONE, List.of(), true);
        RedactionResult wrong = session.redact("这一段根本不在全文里");
        assertEquals(1, session.continuationAlignmentFailures(), "应当记一次对齐失败");
        assertFalse(session.crossUnitActive(), "对齐失败后应当关掉续接");
        assertFalse(wrong.sanitizedText().contains("<身份证"), "没对齐就不该注入任何覆盖");
        // 关掉之后仍然要能正常脱敏，不能因此抛异常
        assertNotNull(session.redact("电话13800138000"));
    }

    @Test
    @DisplayName("默认不开续接：行为与从前一致")
    void disabledByDefault() {
        String full = "身份证110105号\n续19491231002X尾";
        RedactionSession session = new RedactionSession(full, RULES, Exclusions.NONE, List.of());
        assertFalse(session.crossUnitActive());
        RedactionResult second = session.redact("续19491231002X尾");
        assertTrue(second.sanitizedText().contains("<手机号"), "默认关着时应当仍按段内形状归类：" + second.sanitizedText());
    }

    @Test
    @DisplayName("空单元不推进游标：空段在切分时已被跳过，两边要一致")
    void emptyUnitDoesNotAdvanceCursor() {
        String full = "身份证110105号\n\n续19491231002X尾";
        RedactionSession session = new RedactionSession(full, RULES, Exclusions.NONE, List.of(), true);
        session.redact("身份证110105号");
        session.redact("");           // 空单元：redactor 不会产生活动，但会调进来
        RedactionResult second = session.redact("续19491231002X尾");
        assertEquals(0, session.continuationAlignmentFailures(), "空单元不该造成错位");
        assertEquals("续<身份证2>尾", second.sanitizedText());
    }

    @Test
    @DisplayName("会话：隐藏路径（元数据）不推进游标，也不关掉续接")
    void hiddenPathDoesNotDisturbContinuation() {
        String full = "身份证110105号\n续19491231002X尾";
        RedactionSession session = new RedactionSession(full, RULES, Exclusions.NONE, List.of(), true);
        session.redact("身份证110105号");
        // 元数据（工作表名、作者、批注…）不在遍历单元序列里，
        // 它要是也去比对「当前单元」，一次就对不上 → 整个续接被静默关掉。
        session.redactHidden("某个元数据里的作者名字");
        RedactionResult second = session.redact("续19491231002X尾");
        assertEquals(0, session.continuationAlignmentFailures(), "隐藏路径不该造成对齐失败");
        assertTrue(session.crossUnitActive(), "隐藏路径不该关掉续接");
        assertEquals("续<身份证2>尾", second.sanitizedText(),
                "隐藏路径之后续接仍然应当生效，实际：" + second.sanitizedText());
    }

    // ------------------------------------------------------------------ 引擎层

    @Test
    @DisplayName("引擎：注入的命中与原文对不上时直接报错，不静默丢弃")
    void injectedMatchMustMatchTheText() {
        Match bogus = new Match(0, 6, "不对应", "id_card", "身份证", 120);
        assertThrows(IllegalArgumentException.class, () -> RedactionEngine.redact(
                "身份证110105号", RULES, new TokenAllocator("身份证110105号"),
                Exclusions.NONE, null, List.of(), List.of(bogus)));
    }

    @Test
    @DisplayName("引擎：注入的命中区间越界时直接报错")
    void injectedMatchOutOfRange() {
        Match bogus = new Match(0, 99, "身份证110105号", "id_card", "身份证", 120);
        assertThrows(IllegalArgumentException.class, () -> RedactionEngine.redact(
                "身份证110105号", RULES, new TokenAllocator("身份证110105号"),
                Exclusions.NONE, null, List.of(), List.of(bogus)));
    }
}
