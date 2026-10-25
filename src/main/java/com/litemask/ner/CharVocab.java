package com.litemask.ner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 字符表：把字符（码点）映射成模型输入的整数 id。
 *
 * <p><b>为什么按码点而不是按 char</b>：中文在 BMP 里，一个汉字恰好占一个 UTF-16 单元，
 * 所以按 {@code char} 处理在纯中文文档上看不出问题。但真实的合同、邮件正文里
 * 会有表情符号，它们在 UTF-16 里是<b>两个</b> {@code char}（一个高位代理 + 一个低位代理）。
 * 按 char 切分会让一个表情变成两个 id，于是：
 *
 * <ul>
 *   <li>token 数比字符数多，后面所有实体的偏移<b>整体错位</b>；</li>
 *   <li>现场是「有的文档对、有的文档错」，因为纯中文文档里一个代理对都没有——
 *       极难归因。</li>
 * </ul>
 *
 * <p>所以本类一律按<b>码点</b>切分，并在 {@link Encoding} 里同时给出每个码点在原文里的
 * UTF-16 偏移，交给上层把实体坐标换算回去。
 *
 * <p>字符表是<b>模型包提供的</b>，不写死在代码里。换模型包时字符表跟着换，
 * 而「换模型包忘了换字符表」的后果是所有字符都变成 UNK——
 * 结果是「一个实体都识别不出来」，报告却显示模型正常工作。
 */
public final class CharVocab {

    /** 编码结果：id 序列 + 每个 id 在原文里的 UTF-16 偏移 + 原文总长度。 */
    public record Encoding(long[] ids, int[] offsets, int utf16Length) {

        public Encoding {
            Objects.requireNonNull(ids, "ids");
            Objects.requireNonNull(offsets, "offsets");
            if (ids.length != offsets.length) {
                throw new IllegalArgumentException(
                        "ids 与 offsets 长度必须一致，实际 " + ids.length + " / " + offsets.length);
            }
            if (utf16Length < 0) {
                throw new IllegalArgumentException("utf16Length 不能为负: " + utf16Length);
            }
        }

        public int size() {
            return ids.length;
        }
    }

    private final int unkId;
    private final Map<Integer, Integer> idByCodePoint;

    /**
     * @param unkId       未登录字符的 id，必须 ≥ 0
     * @param idByCodePoint 已知字符 → id
     */
    public CharVocab(int unkId, Map<Integer, Integer> idByCodePoint) {
        if (unkId < 0) {
            throw new IllegalArgumentException("UNK id 不能为负: " + unkId);
        }
        this.unkId = unkId;
        this.idByCodePoint = Map.copyOf(Objects.requireNonNull(idByCodePoint, "idByCodePoint"));
        for (Map.Entry<Integer, Integer> e : this.idByCodePoint.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) {
                throw new IllegalArgumentException("字符 0x" + Integer.toHexString(e.getKey())
                        + " 的 id 非法: " + e.getValue());
            }
            if (e.getValue() == unkId) {
                throw new IllegalArgumentException("字符 0x" + Integer.toHexString(e.getKey())
                        + " 的 id 与 UNK id 相同（" + unkId + "），无法区分");
            }
        }
    }

    /**
     * 从「字符列表」构造，表内字符的 id 按下标递增，UNK 占 0。
     *
     * <p>这是模型包字符表的常见形态：第 0 行是 UNK 占位，后面每行一个字符。
     *
     * @param chars 字符列表，<b>不含</b> UNK 占位项
     */
    public static CharVocab ofListedChars(List<String> chars) {
        Objects.requireNonNull(chars, "chars");
        Map<Integer, Integer> m = new HashMap<>();
        int next = 1;
        for (String c : chars) {
            if (c == null) {
                throw new IllegalArgumentException("字符列表里不能有 null");
            }
            int[] cps = c.codePoints().toArray();
            if (cps.length != 1) {
                throw new IllegalArgumentException(
                        "字符表的每一项必须恰好是一个码点，实际这一项有 " + cps.length + " 个: " + c);
            }
            if (m.put(cps[0], next) != null) {
                throw new IllegalArgumentException("字符表里有重复字符: " + c);
            }
            next++;
        }
        return new CharVocab(0, m);
    }

    /** 该码点的 id；不在表里则返回 UNK id。 */
    public int idOf(int codePoint) {
        Integer id = idByCodePoint.get(codePoint);
        return id == null ? unkId : id;
    }

    /** 表内已知字符的个数（不含 UNK）。 */
    public int size() {
        return idByCodePoint.size();
    }

    public int unkId() {
        return unkId;
    }

    /** 表内出现过的最大 id +1。模型输入通道或输出维度通常要按它校准。 */
    public int idCount() {
        int max = unkId;
        for (Integer v : idByCodePoint.values()) {
            max = Math.max(max, v);
        }
        return max + 1;
    }

    /**
     * 把文本编码成 id 序列。
     *
     * <p>返回的 {@code offsets[i]} 是第 i 个 id 对应的字符在原文里的 UTF-16 起始下标，
     * {@code utf16Length} 是原文长度。用它们可以把码点坐标换算回 UTF-16 坐标。
     */
    public Encoding encode(String text) {
        Objects.requireNonNull(text, "text");
        List<Long> ids = new ArrayList<>(text.length());
        List<Integer> offs = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            ids.add((long) idOf(cp));
            offs.add(i);
            i += Character.charCount(cp);
        }
        long[] idArr = new long[ids.size()];
        int[] offArr = new int[offs.size()];
        for (int i = 0; i < idArr.length; i++) {
            idArr[i] = ids.get(i);
            offArr[i] = offs.get(i);
        }
        return new Encoding(idArr, offArr, text.length());
    }

    /**
     * 把码点坐标下的区间换算成 UTF-16 坐标。
     *
     * @param start 码点坐标下的起点（闭）
     * @param end   码点坐标下的终点（开）
     * @return 一个长度为 2 的数组 {@code {utf16Start, utf16End}}
     */
    public int[] toUtf16Range(Encoding enc, int start, int end) {
        if (start < 0 || end > enc.size() || end <= start) {
            throw new IllegalArgumentException("码点区间非法: [" + start + "," + end + ")，"
                    + "而编码长度只有 " + enc.size());
        }
        int utfStart = enc.offsets()[start];
        // 终点要取「下一个码点的偏移」，而不是本码点的偏移 + 1——
        // 后者对代理对字符（一个码点占两个 UTF-16 单元）会截掉半个表情。
        int utfEnd = end < enc.size() ? enc.offsets()[end] : enc.utf16Length();
        return new int[] {utfStart, utfEnd};
    }

    @Override
    public String toString() {
        return "CharVocab{size=" + size() + ", unkId=" + unkId + ", idCount=" + idCount() + '}';
    }
}
