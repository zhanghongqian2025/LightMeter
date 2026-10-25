package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CharVocab} 的测试。
 *
 * <p>重点是<b>码点坐标与 UTF-16 坐标的换算</b>：它是这一层唯一会「静默脱错一个字」的地方。
 */
class CharVocabTest {

    /** 造一个「。=1 张=2 三=3 丰=4」的字符表，与测试夹具无关，用于隔离换算逻辑。 */
    private static CharVocab vocab() {
        return CharVocab.ofListedChars(List.of("。", "张", "三", "丰"));
    }

    private static final String THUMBS = "👍";   // U+1F44D，占两个 UTF-16 单元

    @Test
    @DisplayName("代理对只产生一个 id，而 UTF-16 长度是二")
    void surrogatePairIsOneCodePoint() {
        CharVocab.Encoding enc = vocab().encode(THUMBS);
        assertEquals(1, enc.size(), "一个表情是一个码点，应产生一个 id");
        assertEquals(2, enc.utf16Length(), "但它在原文里占两个 UTF-16 单元");
        assertArrayEquals(new int[] {0}, enc.offsets());
    }

    @Test
    @DisplayName("表外字符落到 UNK，且不改变后续偏移")
    void unknownFallsBackToUnk() {
        CharVocab v = vocab();
        CharVocab.Encoding enc = v.encode("在张三");
        assertArrayEquals(new long[] {v.unkId(), 2, 3}, enc.ids());
        // 关键：「在」虽然没进表，但它仍然占了一个 UTF-16 位置，
        // 偏移不能因此塌掉——否则后面所有实体的位置都会往前挪。
        assertArrayEquals(new int[] {0, 1, 2}, enc.offsets());
    }

    @Test
    @DisplayName("纯 ASCII 也按码点，一个字符一个 id")
    void asciiIsOneCodePointEach() {
        CharVocab v = CharVocab.ofListedChars(List.of("a", "b"));
        assertArrayEquals(new long[] {1, 2, 1, 0}, v.encode("abax").ids());
    }

    @Test
    @DisplayName("实体前面有表情时，起点落在表情之后")
    void startSkipsSurrogatePair() {
        CharVocab v = vocab();
        String text = THUMBS + "张三丰";
        CharVocab.Encoding enc = v.encode(text);
        // 码点 1..4 是「张三丰」
        int[] r = v.toUtf16Range(enc, 1, 4);
        assertEquals(2, r[0], "起点应是表情之后的 UTF-16 下标");
        assertEquals(5, r[1], "终点是原文长度");
        assertEquals("张三丰", text.substring(r[0], r[1]));
    }

    @Test
    @DisplayName("实体后面紧跟表情时，终点不把表情吞进去")
    void endDoesNotSwallowSurrogatePair() {
        CharVocab v = vocab();
        String text = "张三丰" + THUMBS;
        CharVocab.Encoding enc = v.encode(text);
        // 码点 0..3 是「张三丰」，码点 3 是表情
        int[] r = v.toUtf16Range(enc, 0, 3);
        assertEquals("张三丰", text.substring(r[0], r[1]),
                "终点必须落在表情之前；取 offsets[3] 而不是 +1 正是为了这个");
    }

    @Test
    @DisplayName("中间夹表情时区间仍精确")
    void emojiInsideRange() {
        CharVocab v = vocab();
        String text = "张" + THUMBS + "三丰";
        CharVocab.Encoding enc = v.encode(text);
        int[] r = v.toUtf16Range(enc, 0, 4);
        assertEquals(text, text.substring(r[0], r[1]),
                "覆盖整个原文时首尾必须严丝合缝");
    }

    @Test
    @DisplayName("单码点实体：表情本身")
    void singleCodePointEntityIsTheWholePair() {
        CharVocab v = vocab();
        String text = THUMBS + "张";
        CharVocab.Encoding enc = v.encode(text);
        int[] r = v.toUtf16Range(enc, 0, 1);
        assertEquals(THUMBS, text.substring(r[0], r[1]),
                "代理对是一个整体，不能只切出半个");
    }

    @Test
    @DisplayName("空文本得到空编码")
    void emptyText() {
        CharVocab.Encoding enc = vocab().encode("");
        assertEquals(0, enc.size());
        assertEquals(0, enc.utf16Length());
    }

    @Test
    @DisplayName("区间越界立即报错，并打印实际长度")
    void outOfRangeFails() {
        CharVocab.Encoding enc = vocab().encode("张三");
        var e = assertThrows(IllegalArgumentException.class, () -> vocab().toUtf16Range(enc, 0, 9));
        assertTrue(e.getMessage().contains("只有 2"), "错误信息要给出实际编码长度，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("空区间被拒")
    void emptyRangeFails() {
        CharVocab.Encoding enc = vocab().encode("张三");
        assertThrows(IllegalArgumentException.class, () -> vocab().toUtf16Range(enc, 1, 1));
    }

    @Test
    @DisplayName("字符表里的一项必须恰好是一个码点")
    void listedItemMustBeSingleCodePoint() {
        assertThrows(IllegalArgumentException.class, () -> CharVocab.ofListedChars(List.of("张三")));
        assertThrows(IllegalArgumentException.class,
                () -> CharVocab.ofListedChars(java.util.Collections.singletonList("")));
    }

    @Test
    @DisplayName("字符表里有重复字符时报错")
    void duplicateCharRejected() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CharVocab.ofListedChars(List.of("张", "张")));
        assertTrue(e.getMessage().contains("重复"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("字符 id 不能与 UNK id 相同，否则无法区分")
    void unkIdCollisionRejected() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> new CharVocab(0, Map.of((int) '张', 0)));
        assertTrue(e.getMessage().contains("UNK"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("idCount 是最大 id +1，不是表长")
    void idCountIsMaxPlusOne() {
        CharVocab v = new CharVocab(0, Map.of((int) '张', 5));
        assertEquals(1, v.size());
        assertEquals(6, v.idCount(), "输入通道要按最大 id 校准，不是按表长");
    }

    @Test
    @DisplayName("负的 UNK id 被拒")
    void negativeUnkRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CharVocab(-1, Map.of()));
    }

    @Test
    @DisplayName("Encoding 自身也校验两个数组等长")
    void encodingValidatesLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> new CharVocab.Encoding(new long[] {1, 2}, new int[] {0}, 2));
    }
}
