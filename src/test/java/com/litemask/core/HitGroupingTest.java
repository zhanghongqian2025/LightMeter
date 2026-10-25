package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HitGrouping} 的归组规则。
 *
 * <p>归组只动<b>呈现</b>，不动映射：合并意味着要往映射里写一个
 * 「从未作为整体出现在源文件里」的原值，那是把工具自己编出来的字符串
 * 当成源文件的事实（见 {@link HitGrouping} 的类注释）。
 *
 * <p>这组用例盯住的是<b>三条硬条件</b>：严格相邻且同单元、每个成员的值全文档唯一、
 * 拼接结果不撞已有的原值。少一条，用户就会在一个其实不相干的地方做决定——
 * 那比不归组更糟。
 */
class HitGroupingTest {

    /** 同一单元里的一段命中。序号传 0：归组本身不关心序号。 */
    private static HitGrouping.PositionedHit 在(String unit, String ruleKey, String original, int start, int end) {
        return new HitGrouping.PositionedHit(ruleKey, original, "<" + ruleKey + ">", "定位", unit, start, end, 0);
    }

    @Test
    @DisplayName("原文里严格相邻的三个命中归成一条，原值是它们的拼接")
    void 相邻的归成一条() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "person", "a.b", 3, 6),
                在("U1", "email", "@x.", 6, 9),
                在("U1", "person", "cn", 9, 11)));

        assertEquals(1, groups.size(), "三段紧挨着且同单元，应当归成一条");
        HitGrouping.Group g = groups.get(0);
        assertTrue(g.merged());
        assertEquals(3, g.valueCount());
        assertEquals("a.b@x.cn", g.original(), "拼接值应当正好是原文里连续的那一段");
        assertEquals("person + email", g.ruleKey(), "规则列要列出全部规则，不能只报第一条");
        assertEquals("<person><email><person>", g.token(), "令牌原样相连，不加分隔符");
    }

    @Test
    @DisplayName("中间隔着别的字就不归组——拼起来的串在原文里不是连续的一段")
    void 有间隔的不归组() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "person", "a.b", 3, 6),
                在("U1", "email", "@x.", 7, 10),
                在("U1", "person", "cn", 11, 13)));

        assertEquals(3, groups.size(), "三段两两之间都隔着字符，一个都不该拼起来");
        assertFalse(groups.get(0).merged());
        assertEquals("a.b", groups.get(0).original());
    }

    @Test
    @DisplayName("只有后半段紧挨着时，只归后半段")
    void 只归紧挨着的那一段() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "person", "a.b", 3, 6),
                在("U1", "email", "@x.", 7, 10),
                在("U1", "person", "cn", 10, 12)));

        assertEquals(2, groups.size());
        assertFalse(groups.get(0).merged(), "a.b 与 @x. 之间隔着字符，不该归");
        assertTrue(groups.get(1).merged(), "@x. 与 cn 紧挨着，该归成一条");
        assertEquals("@x.cn", groups.get(1).original());
    }

    @Test
    @DisplayName("跨文本单元不归组——两段不相干的内容不能拼成一个标识符")
    void 跨单元不归组() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "person", "a.b", 3, 6),
                在("U2", "email", "@x.", 6, 9),
                在("U2", "person", "cn", 9, 11)));

        assertEquals(2, groups.size(), "前一段在 U1、后两段在 U2，只能归后两段");
        assertEquals(2, groups.get(1).valueCount());
        assertEquals("@x.cn", groups.get(1).original());
    }

    @Test
    @DisplayName("成员的值在别处还出现过就不归组——勾选粒度会骗人")
    void 值不唯一不归组() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "person", "a.b", 3, 6),
                在("U1", "email", "@x.", 6, 9),
                在("U1", "person", "cn", 9, 11),
                // 同一个值在另一处又出现了一次
                在("U2", "person", "a.b", 0, 3)));

        assertEquals(4, groups.size(), "a.b 出现两次，整段都不该归组");
        for (HitGrouping.Group g : groups) {
            assertFalse(g.merged(), "含非唯一成员的那一段不能归组");
        }
    }

    @Test
    @DisplayName("三段里只有中间那段值不唯一时，前后两段也不拼——不能拆开挑")
    void 局部不唯一则整段不归组() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "person", "a.b", 3, 6),
                在("U1", "email", "@x.", 6, 9),
                在("U1", "person", "cn", 9, 11),
                在("U2", "email", "@x.", 0, 3)));

        assertEquals(4, groups.size(),
                "@x. 出现两次，[a.b,@x.] 与 [@x.,cn] 都不能成立——否则拼出来的值只覆盖了一部分");
    }

    @Test
    @DisplayName("拼接结果撞上文档里已有的原值时整段不归组——界面上会出现两行一样的值")
    void 拼接撞值不归组() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "person", "a.b", 3, 6),
                在("U1", "email", "@x.", 6, 9),
                在("U1", "person", "cn", 9, 11),
                // 文档别处有一个完整命中的 "a.b@x.cn"
                在("U2", "email", "a.b@x.cn", 0, 8)));

        assertEquals(4, groups.size(),
                "拼出来的串已经是一个独立命中的原值；连续相邻的一段要么整段归要么都不归");
        for (HitGrouping.Group g : groups) {
            assertFalse(g.merged());
        }
    }

    @Test
    @DisplayName("位置未知（PDF 那样摊平了单元 id）的命中永不归组")
    void 位置未知不归组() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                new HitGrouping.PositionedHit("person", "a.b", "<p1>", null, null, -1, -1, 0),
                new HitGrouping.PositionedHit("email", "@x.", "<e1>", null, null, -1, -1, 0)));

        assertEquals(2, groups.size());
        assertFalse(groups.get(0).merged());
        assertFalse(groups.get(1).merged());
    }

    @Test
    @DisplayName("只有相邻的两段、且两段都唯一时，归成一条")
    void 两段也归组() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "email", "@x.", 0, 3),
                在("U1", "person", "cn", 3, 5)));

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).valueCount());
        assertEquals("@x.cn", groups.get(0).original());
    }

    @Test
    @DisplayName("输出的行序与输入一致（界面刷新后复选框不会跑到别的行去）")
    void 行序稳定() {
        List<HitGrouping.Group> groups = HitGrouping.group(List.of(
                在("U1", "phone", "13800000000", 0, 11),
                在("U1", "person", "a.b", 20, 23),
                在("U1", "email", "@x.", 23, 26),
                在("U1", "person", "cn", 26, 28)));

        assertEquals(2, groups.size());
        assertEquals("13800000000", groups.get(0).original());
        assertEquals("a.b@x.cn", groups.get(1).original());
        assertTrue(groups.get(1).merged());
    }

    @Test
    @DisplayName("空输入不炸")
    void 空输入() {
        assertEquals(List.of(), HitGrouping.group(List.of()));
        assertEquals(List.of(), HitGrouping.group(null));
    }
}
