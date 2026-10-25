package com.litemask.ner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滑窗与跨窗口合并的验证。
 *
 * <p>这里最容易出的错是<b>「偶尔少一个」</b>而不是「整体不对」：
 * 大部分实体都对，只有跨窗口的那几个消失了。
 * 所以判据都用<b>具体到下标的区间</b>，并专门构造跨边界场景，
 * 而不是「跑一个大文件看总数」。
 */
class SlidingWindowNerTest {

    // ------------------------------------------------------------------ 切窗规划

    @Test
    @DisplayName("短文本只切一个窗口")
    void 短文本单窗() {
        SlidingWindowNer ner = new SlidingWindowNer(20, 5);
        List<SlidingWindowNer.Window> plan = ner.plan(8);
        assertEquals(1, plan.size());
        assertEquals(0, plan.get(0).start());
        assertEquals(8, plan.get(0).end());
    }

    @Test
    @DisplayName("窗口重叠推进，既不留缝也不退回")
    void 窗口重叠推进() {
        int maxChars = 20;
        int overlap = 6;
        SlidingWindowNer ner = new SlidingWindowNer(maxChars, overlap);
        for (int len = 1; len <= 120; len++) {
            List<SlidingWindowNer.Window> plan = ner.plan(len);
            assertTrue(plan.size() >= 1, "长度 " + len + " 没有窗口");
            assertEquals(0, plan.get(0).start(), "第一个窗口必须从 0 开始");
            assertEquals(len, plan.get(plan.size() - 1).end(),
                    "最后一个窗口必须到文本末尾，长度 " + len);
            for (int i = 1; i < plan.size(); i++) {
                SlidingWindowNer.Window prev = plan.get(i - 1);
                SlidingWindowNer.Window cur = plan.get(i);
                assertTrue(cur.start() > prev.start(), "长度 " + len + " 第 " + i + " 个窗口没往前推进");
                assertTrue(cur.start() < prev.end(),
                        "长度 " + len + " 第 " + i + " 个窗口与上一个之间有缝，重叠宽度必须 ≥1");
                assertTrue(cur.start() >= prev.start() + (maxChars - overlap)
                                || cur.end() == len,
                        "长度 " + len + " 第 " + i + " 个窗口推进步长不是 maxChars-overlap");
            }
        }
    }

    @Test
    @DisplayName("空文本不切窗也不调识别器")
    void 空文本() {
        SlidingWindowNer ner = new SlidingWindowNer(20, 5);
        assertEquals(0, ner.plan(0).size());
        assertEquals(List.of(), ner.run("", w -> {
            throw new AssertionError("空文本不该调识别器");
        }));
    }

    @Test
    @DisplayName("重叠宽度非法时直接拒绝构造")
    void 参数校验() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowNer(10, 0), "重叠 0 会漏边界实体");
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowNer(10, 10), "重叠等于窗口宽会死循环");
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowNer(10, 11));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowNer(1, 0));
        SlidingWindowNer ner = new SlidingWindowNer(10, 3);
        assertThrows(IllegalArgumentException.class, () -> ner.run(null, w -> List.of()),
                "待识别文本为 null 要当场报错，而不是当成空文本返回「没有实体」");
    }

    // ------------------------------------------------------------------ 跨窗口实体

    @Test
    @DisplayName("跨越窗口边界的实体照样能识别出来")
    void 跨窗口实体() {
        // 窗口 10 / 重叠 3，推进步长 7。实体「张三丰」在全局 [8,11)。
        // 窗口 0 = [0,10) 只看得见「张三」，窗口 1 = [7,17) 才看得见全貌，
        // 且它在窗口 1 里是 [1,4)，不贴任何切边 → 采信。
        // 早期版本用「归属区投票」，归属区在窗口之间留了个空洞，
        // 这个实体两边都拿不到票，整个消失且无报错。
        String text = "甲乙丙丁戊己庚辛张三丰癸壬子丑";
        SlidingWindowNer ner = new SlidingWindowNer(10, 3);

        List<EntitySpan> got = ner.run(text, w -> 认出(w, "张三丰", EntityType.PERSON));

        assertEquals(1, got.size(), "跨边界的实体必须被找回来。实际: " + got);
        assertEquals(new EntitySpan(8, 11, EntityType.PERSON), got.get(0));
        assertEquals("张三丰", text.substring(got.get(0).start(), got.get(0).end()));
    }

    @Test
    @DisplayName("相邻窗口重复报出的同一个实体只出现一次")
    void 重复去重() {
        // 15 个填充字 + 「张三丰」[15,18) + 10 个填充字 = 28 字。
        // 窗口 [0,20) 与 [14,28) 都<b>完整</b>看得见它、都不贴切边 → 各报一次。
        //
        // 第一版这条测试用的是「实体紧贴第二个窗口左切边」的文本，
        // 那样只有一个窗口看得见全貌，去重代码根本不会执行——
        // 把去重键改坏它也照样绿。**判据必须先证明「确实产生了重复观测」**，
        // 否则测的是一条永远为真的断言。
        String text = "甲乙丙丁戊己庚辛己午未申酉戌亥张三丰子丑寅卯辰巳午未申酉戌";
        SlidingWindowNer ner = new SlidingWindowNer(20, 6);
        int at = text.indexOf("张三丰");
        java.util.concurrent.atomic.AtomicInteger 观测次数 =
                new java.util.concurrent.atomic.AtomicInteger();

        List<EntitySpan> got = ner.run(text, w -> {
            int p = w.indexOf("张三丰");
            if (p < 0) {
                return List.of();
            }
            观测次数.incrementAndGet();
            return List.of(new EntitySpan(p, p + 3, EntityType.PERSON));
        });

        assertTrue(观测次数.get() >= 2,
                "前提没成立：只有 " + 观测次数.get() + " 个窗口报了这个实体，"
                        + "去重逻辑不会被执行，这条测试就是空转");
        assertEquals(1, got.size(),
                "同一实体被多个窗口各报一次是常态，合并后只能留一条。实际: " + got);
        assertEquals(new EntitySpan(at, at + 3, EntityType.PERSON), got.get(0));
    }

    @Test
    @DisplayName("紧贴切边的观测不采信，紧贴文本末尾的采信")
    void 边缘截断不采信() {
        // 识别器对<b>每个</b>窗口都报「本窗口最后 3 个字」这个实体。
        // 对非末尾窗口来说，那 3 个字紧贴切边，多半是模型把实体截断了 → 必须丢；
        // 对末尾窗口来说，那 3 个字就是全文的最后 3 个字，是真实体 → 必须留。
        // 一条测试同时钉住这两面，只测「会丢」的那一面会把「末尾实体也丢了」放过。
        String text = "甲乙丙丁戊己庚辛午未申酉戌亥子丑寅卯";
        SlidingWindowNer ner = new SlidingWindowNer(10, 3);
        int len = text.length();

        List<EntitySpan> got = ner.run(text, w -> List.of(
                new EntitySpan(w.length() - 3, w.length(), EntityType.PERSON)));

        assertEquals(List.of(new EntitySpan(len - 3, len, EntityType.PERSON)), got,
                "只该留下全文末尾那一条；其余都紧贴切边，是被截断的半截实体");
        assertEquals("丑寅卯", text.substring(len - 3, len));
    }

    @Test
    @DisplayName("实体起点落在某个窗口的左切边上，仍然被采信")
    void 左切边上的起点不是截断() {
        // 「张三丰」[3,6) 完整落在窗口 [3,8) 里，而 3 恰好是这个窗口的<b>左切边</b>。
        //
        // 老实现左右对称地丢，理由是「贴切边就可能被截断」。但左边这个理由不成立：
        // 解码器只输出以 B- 开头的实体，起点落在窗口左边界上说明实体<b>真的从那里开始</b>，
        // 不可能是被截断的尾巴。现场是：窗口 [3,8) 丢掉它、前一个窗口因为终点贴右切边
        // 也丢掉半截，于是「张三丰」<b>整个消失且零报错</b>。
        //
        // 这条测试存在的另一个理由：老测试里凡是要用到窗口的，都是特意把实体挪开左切边的，
        // 等于这条分支从来没被当成期望行为测过——「另一面没测」就等于没测。
        String text = "甲乙丙张三丰子丑寅卯";
        assertEquals(3, text.indexOf("张三丰"), "夹具前提：张三丰 的起点必须是 3");
        SlidingWindowNer ner = new SlidingWindowNer(5, 2);

        List<SlidingWindowNer.Window> plan = ner.plan(text.length());
        boolean someWindowStartsAt3 = plan.stream().anyMatch(w -> w.start() == 3 && w.end() >= 6);
        assertTrue(someWindowStartsAt3,
                "前提不成立：没有窗口的左切边正好压在实体起点上，这条测试会变成空转。plan=" + plan);

        List<EntitySpan> got = ner.run(text, w -> {
            // w 是窗口文本，不是 Window 对象，所以要用它在全文里的位置反推窗口起点。
            // 夹具里每个字都不同，indexOf 不会有歧义。
            int start = text.indexOf(w);
            if (start <= 3 && start + w.length() >= 6) {
                return List.of(new EntitySpan(3 - start, 6 - start, EntityType.PERSON));
            }
            return List.of();
        });

        assertEquals(List.of(new EntitySpan(3, 6, EntityType.PERSON)), got,
                "起点贴左切边不代表被截断，不该丢");
    }

    @Test
    @DisplayName("文本真正的首尾不算切边，首尾实体照常采信")
    void 首尾实体采信() {
        // 「张三丰」贴到全文开头，「李四」贴到全文末尾。
        // 开头那个只被窗口 0 看见且贴着窗口 0 的左边界——但那是文本开头，不是切边；
        // 末尾那个同理。任何「切边就丢」的粗糙实现都会把这两个实体弄丢，
        // 而它们恰恰是文档第一行和最后一行，是最该脱的。
        String text = "张三丰甲乙丙丁戊己庚辛午未申酉戌亥子丑寅卯辰巳李四";
        SlidingWindowNer ner = new SlidingWindowNer(10, 3);

        List<EntitySpan> got = ner.run(text, w -> {
            List<EntitySpan> out = new java.util.ArrayList<>();
            addIfFound(out, w, "张三丰", EntityType.PERSON);
            addIfFound(out, w, "李四", EntityType.PERSON);
            return out;
        });

        assertEquals(List.of(
                new EntitySpan(0, 3, EntityType.PERSON),
                new EntitySpan(text.length() - 2, text.length(), EntityType.PERSON)),
                got, "全文首尾的实体不能因为「贴着窗口边」被丢掉");
    }

    @Test
    @DisplayName("识别器报出越界区间时立刻报错")
    void 越界报错() {
        SlidingWindowNer ner = new SlidingWindowNer(10, 3);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ner.run("甲乙丙丁戊己庚辛辛辛癸", w ->
                        List.of(new EntitySpan(0, 99, EntityType.PERSON))),
                "越界说明窗口识别器的坐标换算坏了，必须当场暴露而不是悄悄丢");
        assertTrue(e.getMessage().contains("超出该窗口范围"), e.getMessage());
    }

    @Test
    @DisplayName("同一段文字被两个窗口给出不同类型判断时两条都在，不二选一")
    void 类型冲突不合并() {
        // 实体 [15,18) 被两个窗口完整看见，但一个说是人名、一个说是机构名。
        // 合并键里带了类型，所以两条都会留下 —— 差异交给一致性检查去暴露，
        // 而不是在这里悄悄二选一。二选一会让「哪个窗口赢」变成实现细节。
        // 15 个填充字 + 「张三丰」[15,18) + 9 个填充字 = 27 字；
        // 窗口 [0,20) 与 [14,27) 都能<b>完整</b>看见它且都不贴切边，于是各报一次。
        // 填充字必须凑够 15 个：少一个实体就贴到第二个窗口的左切边，会被「边缘不可信」规则丢掉。
        String text = "甲乙丙丁戊己庚辛己午未申酉戌亥张三丰子丑寅卯辰巳午未申酉";
        SlidingWindowNer ner = new SlidingWindowNer(20, 6);
        int at = text.indexOf("张三丰");

        List<EntitySpan> got = ner.run(text, w -> {
            int p = w.indexOf("张三丰");
            if (p < 0) {
                return List.of();
            }
            EntityType type = w.indexOf("甲乙") >= 0 ? EntityType.PERSON : EntityType.ORGANIZATION;
            return List.of(new EntitySpan(p, p + 3, type));
        });

        assertEquals(2, got.size(), "两个窗口判断不同时不能悄悄丢掉其中一个。实际: " + got);
        assertEquals(EntityType.PERSON, got.get(0).type());
        assertEquals(EntityType.ORGANIZATION, got.get(1).type());
        assertEquals(at, got.get(0).start());
        assertEquals(at + 3, got.get(0).end());
    }

    @Test
    @DisplayName("结果按起点排序")
    void 结果有序() {
        String text = "张三丰甲乙丙丁戊己庚辛午未申酉北京某某科技有限公司";
        SlidingWindowNer ner = new SlidingWindowNer(60, 10);
        List<EntitySpan> got = ner.run(text, w -> {
            List<EntitySpan> out = new java.util.ArrayList<>();
            addIfFound(out, w, "北京某某科技有限公司", EntityType.ORGANIZATION);
            addIfFound(out, w, "张三丰", EntityType.PERSON);
            return out;
        });

        assertEquals(2, got.size());
        assertTrue(got.get(0).start() < got.get(1).start(),
                "必须按起点升序，界面表格与令牌编号都依赖这个顺序");

        assertThrows(IllegalArgumentException.class, () -> ner.run("甲乙", null));
    }

    @Test
    @DisplayName("识别器返回 null 条目时被跳过，返回 null 列表也跳过")
    void 宽容处理() {
        SlidingWindowNer ner = new SlidingWindowNer(8, 2);
        List<EntitySpan> got = ner.run("甲乙丙丁戊己庚辛", w -> {
            if (w.length() < 8) {
                return null;
            }
            List<EntitySpan> out = new java.util.ArrayList<>();
            out.add(null);
            addIfFound(out, w, "戊己", EntityType.PERSON);
            return out;
        });
        assertEquals(List.of(new EntitySpan(4, 6, EntityType.PERSON)), got);
    }

    // ------------------------------------------------------------------ 辅助

    private static List<EntitySpan> 认出(String window, String needle, EntityType type) {
        int at = 认出位置(window, needle);
        return at < 0 ? List.of() : List.of(new EntitySpan(at, at + needle.length(), type));
    }

    private static int 认出位置(String window, String needle) {
        return window.indexOf(needle);
    }

    private static void addIfFound(List<EntitySpan> out, String window, String needle, EntityType type) {
        int at = window.indexOf(needle);
        if (at >= 0) {
            out.add(new EntitySpan(at, at + needle.length(), type));
        }
    }
}
