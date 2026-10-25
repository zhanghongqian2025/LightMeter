package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位置级排除（同一值的「只放过第 N 次」）的语义验证。
 *
 * <p>{@link ExclusionTest} 管的是值级排除，这里管的是序号那一层。两者最容易出的错不是
 * 「排除没生效」，而是<b>序号悄悄对不上</b>——用户勾了第 2 处，文件里改的是第 3 处，
 * 而且程序看起来一切正常。所以这里的判据大多不是「排除生效了」，
 * 而是「<b>序号在排除前后保持不变</b>」和「<b>没被排除的那些照样被脱</b>」。
 *
 * <p>这一条是刻意加的负向判据：只断言「该保留的留下了」的话，
 * 一个「整篇都不脱」的错误实现照样能通过所有用例——那正好是最危险的方向。
 */
class OccurrenceExclusionTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");
    private static final String PHONE = "13800138000";

    private static Exclusions nth(int occurrence) {
        return Exclusions.of(List.of(Exclusions.HitRef.nth("phone", PHONE, occurrence)));
    }

    // ------------------------------------------------------------ 编号本身

    @Test
    @DisplayName("同一值跨三段出现：仍然同值同符（改造没破坏原有不变式）")
    void 同值跨段仍同符() {
        RedactionSession s = new RedactionSession("x", RULES);
        for (int i = 0; i < 3; i++) {
            s.redact("电话 " + PHONE + " 结束");
        }
        assertEquals(3, s.changes().size(), "三次出现都应被脱敏");
        Map<String, String> 映射 = s.mapping();
        assertEquals(1, 映射.size(),
                "同一个值只能有一个令牌，跨段共用分配器: " + 映射);
        assertTrue(映射.containsValue(PHONE), String.valueOf(映射));
    }

    @Test
    @DisplayName("不同值各自从 0 编号，互不串台")
    void 不同值各自从零编号() {
        String 甲 = "13800138000";
        String 乙 = "13900139000";
        String text = "甲 " + 甲 + " 乙 " + 乙;
        Exclusions ex = Exclusions.of(List.of(Exclusions.HitRef.nth("phone", 甲, 0)));

        String out = new RedactionSession(text, RULES, ex).redact(text).sanitizedText();

        assertTrue(out.contains("甲 " + 甲), "甲应按它自己的第 0 次被放过: " + out);
        assertFalse(out.contains(乙), "乙不该跟着一起留下（两个值的序号空间是分开的）: " + out);
    }

    // ---------------------------------------------------------- 位置级排除

    @Test
    @DisplayName("排除第 1 次出现：只有中间那次保留原样，前后两次照常脱敏")
    void 只放过第N次() {
        String text = "甲 " + PHONE + " 乙 " + PHONE + " 丙 " + PHONE;

        RedactionSession s = new RedactionSession(text, RULES, nth(1));
        String out = s.redact(text).sanitizedText();

        // 负向判据先摆出来：必须真的是「只剩一处原样」，不是「整篇都原样」
        assertEquals(1, countOccurrences(out, PHONE),
                "只应放过第 1 次（0 基），实际输出: " + out);
        assertTrue(out.startsWith("甲 <"), "第 0 次应被脱敏: " + out);
        assertTrue(out.contains("乙 " + PHONE), "第 1 次应保留原样: " + out);
        assertTrue(out.endsWith(" <" + tokenOf(out) + ">"),
                "第 2 次应被脱敏（且用同一个令牌，因为同值同符）: " + out);
    }

    /** 从输出里取回令牌文本，用于断言「末尾那处也被脱了且用的是同一个令牌」。 */
    private static String tokenOf(String out) {
        int a = out.indexOf('<');
        int b = out.indexOf('>', a);
        return out.substring(a + 1, b);
    }

    @Test
    @DisplayName("排除多条时按各自序号独立生效")
    void 排除多条() {
        String text = "甲 " + PHONE + " 乙 " + PHONE + " 丙 " + PHONE;
        Exclusions ex = Exclusions.of(List.of(
                Exclusions.HitRef.nth("phone", PHONE, 0),
                Exclusions.HitRef.nth("phone", PHONE, 2)));

        RedactionSession s = new RedactionSession(text, RULES, ex);
        String out = s.redact(text).sanitizedText();

        // 排除 0 和 2 ⇒ 恰好这两处保留原样，第 1 处照常脱敏
        assertEquals(2, countOccurrences(out, PHONE), "应保留第 0 与第 2 次: " + out);
        assertTrue(out.startsWith("甲 " + PHONE), "第 0 次应保留: " + out);
        assertFalse(out.contains("乙 " + PHONE), "第 1 次未被排除，应被脱敏: " + out);
        assertTrue(out.endsWith("丙 " + PHONE), "第 2 次应保留: " + out);
    }

    @Test
    @DisplayName("关键不变式：排除前面几次不会让后面的序号前移")
    void 排除后序号不前移() {
        String text = "甲 " + PHONE + " 乙 " + PHONE + " 丙 " + PHONE;

        // 如果序号会随排除前移，用户勾的「2」就会落到第 1 次上，
        // 结果变成排除掉 0 和 1、留下 2 —— 也就是「甲」和「乙」保留原样。
        // 所以判据是：只有「甲」和「丙」保留，中间的「乙」必须是脱敏后的。
        Exclusions ex = Exclusions.of(List.of(
                Exclusions.HitRef.nth("phone", PHONE, 0),
                Exclusions.HitRef.nth("phone", PHONE, 2)));
        String out = new RedactionSession(text, RULES, ex).redact(text).sanitizedText();

        assertFalse(out.contains("乙 " + PHONE),
                "序号若前移，第 1 次会顶替第 2 次被排除，「乙」就会留在文档里: " + out);
        assertTrue(out.contains("乙 <"), "第 1 次应被脱敏: " + out);
        assertTrue(out.startsWith("甲 " + PHONE) && out.endsWith("丙 " + PHONE),
                "被排除的两次应原样保留: " + out);
    }

    @Test
    @DisplayName("排除整条（值级）时，全部出现处都保留")
    void 值级排除覆盖所有出现处() {
        String text = "甲 " + PHONE + " 乙 " + PHONE + " 丙 " + PHONE;
        Exclusions ex = Exclusions.of(List.of(new Exclusions.HitRef("phone", PHONE)));

        String out = new RedactionSession(text, RULES, ex).redact(text).sanitizedText();

        assertEquals(3, countOccurrences(out, PHONE), "值级排除应覆盖全部三处: " + out);
    }

    @Test
    @DisplayName("位置级排除跨段生效（序号在整篇文档里累计，不是每段从 0 重来）")
    void 位置级排除跨段() {
        RedactionSession s = new RedactionSession("x", RULES, nth(1));
        String out1 = s.redact("甲 " + PHONE).sanitizedText();      // 第 0 次
        String out2 = s.redact("乙 " + PHONE).sanitizedText();      // 第 1 次 → 被排除
        String out3 = s.redact("丙 " + PHONE).sanitizedText();      // 第 2 次

        assertFalse(out1.contains(PHONE), "第 0 次应被脱敏: " + out1);
        assertTrue(out2.contains(PHONE),
                "第 1 次应保留原样；若序号每段从 0 重来，这段会变成第 0 次而照样被脱: " + out2);
        assertFalse(out3.contains(PHONE), "第 2 次应被脱敏: " + out3);
    }

    // -------------------------------------------------------- 隐形路径不占号

    @Test
    @DisplayName("redactHidden 不消耗出现序号：先扫一段隐形文本，可见段仍是第 0 次")
    void 隐形路径不消耗序号() {
        RedactionSession s = new RedactionSession("x", RULES, nth(0));
        // PDF 元数据 / xlsx 表名 / OFD 附件名走的就是这条路径
        s.redactHidden("作者 张三 " + PHONE);
        String 正文 = "正文 " + PHONE;

        String out = s.redact(正文).sanitizedText();
        assertTrue(out.contains(PHONE),
                "隐形路径吃掉了序号的话，正文这处就成了第 1 次，勾第 0 次就留不住它: " + out);
    }

    @Test
    @DisplayName("redactHidden 仍然照常脱敏，并进入还原映射")
    void 隐形路径照常脱敏() {
        RedactionSession s = new RedactionSession("x", RULES);
        String out = s.redactHidden("作者 " + PHONE).sanitizedText();

        assertFalse(out.contains(PHONE), "隐形路径也必须脱敏，否则元数据里的号码会原样留着: " + out);
        assertTrue(s.mapping().containsValue(PHONE),
                "要进映射，否则还原不回来: " + s.mapping());
    }

    @Test
    @DisplayName("redactHidden 仍然服从值级排除")
    void 隐形路径服从值级排除() {
        RedactionSession s = new RedactionSession("x", RULES,
                Exclusions.of(List.of(new Exclusions.HitRef("phone", PHONE))));

        String out = s.redactHidden("作者 " + PHONE).sanitizedText();
        assertTrue(out.contains(PHONE), "用户在界面上把整条排除了，元数据里这份也该留下: " + out);
    }

    // ------------------------------------------------------ 顺序与保守方向

    @Test
    @DisplayName("changes 按位置顺序发出（界面的序号靠它对应文件里的实际位置）")
    void changes按位置顺序发出() {
        String text = "甲 " + PHONE + " 乙 " + PHONE + " 丙 " + PHONE;
        RedactionResult r = RedactionEngine.redact(text, RULES, new TokenAllocator(text));

        List<Integer> 位置 = r.changes().stream().map(Change::start).toList();
        List<Integer> 升序 = new ArrayList<>(位置);
        java.util.Collections.sort(升序);
        assertEquals(升序, 位置,
                "命中的发出顺序必须是从前到后，倒着发界面上的序号就全反了");
    }

    @Test
    @DisplayName("未编号时不认位置级排除（宁可多脱，也不因为序号对不上就放过）")
    void 未编号时不认位置级排除() {
        String text = "甲 " + PHONE;
        // 直接调 4 参重载：没有 numbering，等价于 CLI 那条不编号的路径
        RedactionResult r = RedactionEngine.redact(text, RULES, new TokenAllocator(text), nth(0));

        assertFalse(r.sanitizedText().contains(PHONE),
                "位置级排除不该在未编号的路径上生效: " + r.sanitizedText());
    }

    @Test
    @DisplayName("序号越界的排除项不生效（由 JobStore 在接口层转成 400，不靠这里兜底）")
    void 序号越界不生效() {
        String text = "甲 " + PHONE + " 乙 " + PHONE;
        String out = new RedactionSession(text, RULES, nth(7)).redact(text).sanitizedText();

        assertEquals(0, countOccurrences(out, PHONE),
                "对不上的排除项必须照常脱敏——引擎这一层不报错，报错发生在接口层: " + out);
    }

    @Test
    @DisplayName("出现序号小于 -1 是非法请求")
    void 非法序号被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> Exclusions.HitRef.nth("phone", PHONE, -2));
    }

    // ------------------------------------------------------------------ 工具

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
