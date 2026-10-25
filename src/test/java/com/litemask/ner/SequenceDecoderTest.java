package com.litemask.ner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解码层的验证。
 *
 * <p><b>标尺来自哪里</b>：不是「再写一遍 Viterbi 看看一不一样」——
 * 那样只是证明两份代码抄得像。这里用<b>穷举枚举</b>当标尺：
 * 长度 ≤5 的标签序列最多 5⁵=3125 条，全部枚举一遍取最高分，
 * 断言 DP 解出的路径总分与穷举一致。
 *
 * <p>两者唯一的共同点是这个数学定义（最大化所有合法路径的总分），
 * 实现方式毫无关系——这才是独立标尺。枚举慢，但只在测试里跑。
 */
class SequenceDecoderTest {

    private static final TagScheme BIO = TagScheme.bio();
    private static final int O = 0;
    private static final int B_PER = 1;
    private static final int I_PER = 2;
    private static final int B_ORG = 3;
    private static final int I_ORG = 4;

    // ------------------------------------------------------------------ 标尺

    @Test
    @DisplayName("Viterbi 解出的路径必须与穷举的最高分一致")
    void 与穷举一致() {
        Random rnd = new Random(20261016L);
        int checked = 0;
        for (int t = 2; t <= 5; t++) {
            for (int trial = 0; trial < 40; trial++) {
                double[][] emissions = new double[t][BIO.size()];
                for (double[] row : emissions) {
                    for (int i = 0; i < row.length; i++) {
                        row[i] = Math.round(rnd.nextDouble() * 60 - 30) / 10.0;
                    }
                }
                double[] trans = new double[BIO.size() * BIO.size()];
                for (int i = 0; i < trans.length; i++) {
                    // 转移分数取更窄的区间，模拟真实 CRF：模型对「能不能接」比「更像什么」更有把握
                    trans[i] = Math.round(rnd.nextDouble() * 12 - 6) / 10.0;
                }

                int[] path = SequenceDecoder.decode(emissions, trans, BIO);
                double got = SequenceDecoder.scoreOf(path, emissions, trans, BIO);
                double want = 穷举最高分(emissions, trans, BIO);

                assertEquals(want, got, 1e-9,
                        "T=" + t + " 第 " + trial + " 次：DP 与穷举不一致。\n"
                                + "发射=" + dump(emissions) + "\n转移=" + dump(trans)
                                + "\nDP 路径=" + java.util.Arrays.toString(path));
                checked++;
            }
        }
        assertEquals(160, checked, "实际跑的对账组数不对");
    }

    @Test
    @DisplayName("解码出的相邻标签必须结构合法")
    void 路径必定结构合法() {
        Random rnd = new Random(7L);
        for (int trial = 0; trial < 200; trial++) {
            int t = 3 + rnd.nextInt(8);
            double[][] emissions = new double[t][BIO.size()];
            for (double[] row : emissions) {
                for (int i = 0; i < row.length; i++) {
                    // 故意让 I- 通道的分数经常最高，逼解码器必须靠约束兜住
                    row[i] = (i == I_PER || i == I_ORG) ? rnd.nextDouble() * 3 : rnd.nextDouble();
                }
            }
            double[] trans = new double[BIO.size() * BIO.size()];
            for (int i = 0; i < trans.length; i++) {
                trans[i] = rnd.nextDouble();
            }
            int[] path = SequenceDecoder.decode(emissions, trans, BIO);
            for (int i = 1; i < path.length; i++) {
                assertTrue(BIO.allows(path[i - 1], path[i]),
                        "出现了结构非法的转移 " + path[i - 1] + "→" + path[i] + "，路径 " + java.util.Arrays.toString(path));
            }
        }
    }

    @Test
    @DisplayName("I- 接在不属于实体的标签后面不可能被解出来")
    void 孤立I不被解出() {
        // 每个位置都强烈倾向 I-人名，唯独第 0 位强烈倾向「不属于任何实体」。
        // 若没有结构约束，逐位取最大值会得到 [O, I-人名] —— 一个没有开头的实体。
        double[][] emissions = {
                {-5, 1, 9, 1, 9},
                {-5, 1, 9, 1, 9},
                {-5, 1, 9, 1, 9},
        };
        double[] trans = new double[BIO.size() * BIO.size()];
        for (int i = 0; i < trans.length; i++) {
            trans[i] = 0;
        }
        int[] path = SequenceDecoder.decode(emissions, trans, BIO);
        assertTrue(BIO.allowsStart(path[0]),
                "序列开头不能是 I- 标签，它没有任何前驱可延续。解出的是 " + path[0]);
        assertNotEquals(I_PER, path[0], "解出了 I-人名 作为序列开头");
        for (int i = 1; i < path.length; i++) {
            assertTrue(BIO.allows(path[i - 1], path[i]), "第 " + i + " 位转移非法: " + path[i - 1] + "→" + path[i]);
        }
    }

    // ------------------------------------------------------------------ 抽实体

    @Test
    @DisplayName("标签路径抽成实体区间")
    void 抽实体() {
        List<EntitySpan> got = SequenceDecoder.toEntities(
                new int[]{B_PER, I_PER, O, B_ORG, I_ORG, I_ORG}, BIO);
        assertEquals(List.of(
                        new EntitySpan(0, 2, EntityType.PERSON),
                        new EntitySpan(3, 6, EntityType.ORGANIZATION)),
                got, got.toString());
    }

    @Test
    @DisplayName("结尾没有收尾标签的实体照样要交出来")
    void 收尾实体() {
        List<EntitySpan> got = SequenceDecoder.toEntities(new int[]{O, B_PER, I_PER, I_PER}, BIO);
        assertEquals(List.of(new EntitySpan(1, 4, EntityType.PERSON)), got,
                "实体顶到文本末尾时不能因为「还没看到收尾」就不报——那等于让文档末尾的机构名逃逸");
    }

    @Test
    @DisplayName("孤立的 I- 当成实体开头，不丢弃")
    void 孤立I不丢弃() {
        // 手工构造一条非法路径（合法路径解不出来），验证兜底方向
        List<EntitySpan> got = SequenceDecoder.toEntities(new int[]{I_PER, I_PER}, BIO);
        assertEquals(List.of(new EntitySpan(0, 2, EntityType.PERSON)), got,
                "解码器理应已挡住这种路径；真出现了也不能靠丢弃来处理，"
                        + "丢弃的方向是「少脱」——本工具唯一不能犯的错");
    }

    @Test
    @DisplayName("类型不同的相邻实体不会并成一个")
    void 相邻异类不合并() {
        List<EntitySpan> got = SequenceDecoder.toEntities(
                new int[]{B_PER, B_ORG, I_ORG}, BIO);
        assertEquals(List.of(
                new EntitySpan(0, 1, EntityType.PERSON),
                new EntitySpan(1, 3, EntityType.ORGANIZATION)), got, got.toString());
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    @DisplayName("标签方案残缺时直接拒绝加载")
    void 标签方案校验() {
        EntityType[] t = new EntityType[5];
        boolean[] b = new boolean[5];
        assertThrows(IllegalArgumentException.class, () -> new TagScheme(t, b),
                "全是 null 类型 = 没有「不属于任何实体」的标签");

        EntityType[] 两个O = {null, null, EntityType.PERSON, EntityType.PERSON, EntityType.PERSON};
        boolean[] 对应 = {false, false, true, false, false};
        assertThrows(IllegalArgumentException.class, () -> new TagScheme(两个O, 对应),
                "有两个 O，O 之间可以随便跳");

        EntityType[] 缺I = {null, EntityType.PERSON, EntityType.PERSON, EntityType.ORGANIZATION, null};
        boolean[] 缺I对应 = {false, true, false, true, false};
        assertThrows(IllegalArgumentException.class, () -> new TagScheme(缺I, 缺I对应),
                "机构名只有 B- 没有 I-，四字以上的公司名会被截断");

        assertThrows(IllegalArgumentException.class,
                () -> new TagScheme(new EntityType[]{null, EntityType.PERSON}, new boolean[3]),
                "两个数组长度不一致");
        assertThrows(IllegalArgumentException.class,
                () -> new TagScheme(null, new boolean[3]), "null 数组");
        assertThrows(IllegalArgumentException.class,
                () -> new TagScheme(new EntityType[]{null}, new boolean[]{false}),
                "只有一个标签，至少要 O + 一个实体的 B-");
    }

    @Test
    @DisplayName("模型输出与标签方案对不上时报错，不猜")
    void 形状不匹配报错() {
        double[] trans = new double[BIO.size() * BIO.size()];
        assertThrows(IllegalArgumentException.class,
                () -> SequenceDecoder.decode(new double[][]{{0, 0, 0}}, new double[4], BIO),
                "转移分数长度不是 C*C");
        assertThrows(IllegalArgumentException.class,
                () -> SequenceDecoder.decode(new double[][]{{0, 0, 0}}, trans, BIO),
                "某位置的通道数不是标签数——这说明模型包与标签方案不是一套");
        assertThrows(IllegalArgumentException.class,
                () -> SequenceDecoder.decode(new double[][]{{0, 0, 0}, null}, trans, BIO), "某位置为 null");
    }

    @Test
    @DisplayName("全部得分都是负无穷时失败，不返回「没有实体」")
    void 全不可能则失败() {
        double[][] emissions = {
                {SequenceDecoder.IMPOSSIBLE, SequenceDecoder.IMPOSSIBLE, SequenceDecoder.IMPOSSIBLE,
                        SequenceDecoder.IMPOSSIBLE, SequenceDecoder.IMPOSSIBLE},
                {SequenceDecoder.IMPOSSIBLE, SequenceDecoder.IMPOSSIBLE, SequenceDecoder.IMPOSSIBLE,
                        SequenceDecoder.IMPOSSIBLE, SequenceDecoder.IMPOSSIBLE},
        };
        double[] trans = new double[BIO.size() * BIO.size()];
        java.util.Arrays.fill(trans, SequenceDecoder.IMPOSSIBLE);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SequenceDecoder.decode(emissions, trans, BIO));
        assertTrue(e.getMessage().contains("没有任何合法路径"), "报错要说清是模型包的问题: " + e.getMessage());
    }

    @Test
    @DisplayName("空文本返回空路径，不报错")
    void 空输入() {
        assertEquals(0, SequenceDecoder.decode(new double[0][], new double[25], BIO).length);
    }

    // ------------------------------------------------------------------ 穷举标尺

    /**
     * 暴力枚举所有合法标签路径，返回最高总分。
     *
     * <p>刻意写成最朴素的递归——它和 {@link SequenceDecoder} 没有任何共享代码，
     * 连 {@link TagScheme#allows} 之外的规则都不引用。
     */
    /**
     * 暴力枚举所有合法标签路径，返回最高总分。
     *
     * <p>刻意写成最朴素的递归——它和 {@link SequenceDecoder} 没有任何共享代码。
     * 两者唯一的共同点是<b>规格</b>（合法路径 = 开头合法 + 每次转移合法，总分取最大），
     * 实现方式毫无关系：一个是穷举，一个是动态规划。
     *
     * <p><b>「合法」的定义必须两边一致</b>，否则就不是对账而是对不齐：
     * 早先这一版漏了「开头不能是 I-」这条，解码器解出 [1,2]、枚举解出别的路径，
     * 红灯看起来像解码器有 bug，其实是枚举器按旧规格写的。
     */
    private static double 穷举最高分(double[][] emissions, double[] trans, TagScheme scheme) {
        double[] best = {Double.NEGATIVE_INFINITY};
        枚举(0, -1, 0.0, new int[emissions.length], emissions, trans, scheme, best);
        return best[0];
    }

    private static void 枚举(int pos, int prev, double acc, int[] current,
                             double[][] emissions, double[] trans, TagScheme scheme, double[] best) {
        int c = scheme.size();
        if (pos == emissions.length) {
            if (acc > best[0]) {
                best[0] = acc;
            }
            return;
        }
        for (int tag = 0; tag < c; tag++) {
            if (pos == 0) {
                if (!scheme.allowsStart(tag)) {
                    continue;
                }
            } else if (!scheme.allows(prev, tag)) {
                continue;
            }
            double step = emissions[pos][tag];
            if (pos > 0) {
                step += trans[prev * c + tag];
            }
            current[pos] = tag;
            枚举(pos + 1, tag, acc + step, current, emissions, trans, scheme, best);
        }
    }

    private static String dump(double[][] a) {
        List<String> rows = new ArrayList<>();
        for (double[] row : a) {
            rows.add(java.util.Arrays.toString(row));
        }
        return rows.toString();
    }

    private static String dump(double[] a) {
        return java.util.Arrays.toString(a);
    }
}
