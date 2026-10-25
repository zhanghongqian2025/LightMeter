package com.litemask.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「跨文本槽」替换：把一组文本槽拼起来跑引擎，再把结果按偏移映射回各槽。
 *
 * <p>OOXML 会把一句话拆进多个文本节点。docx 里是 {@code <w:t>}（每个 run 一个），
 * xlsx 里是共享字符串的 {@code <si>} 富文本片段。「公民身份号码：11010519491231002X」
 * 看着连续，实际可能横跨三四个节点。**逐槽跑正则必然漏**：单个槽里只有「1101051949」
 * 这种碎片，任何规则都匹配不上，而工具照样报告「脱敏成功」。这是本类存在的唯一理由。
 *
 * <p>做法是把各槽拼成一个逻辑字符串跑引擎，再把替换结果按偏移映射回去：
 * 命中的首个槽写入「前缀 + 令牌 + （同槽则）后缀」，中间槽清空，末槽保留后缀。
 * 从文末向前处理，每次重算槽偏移，因此前面命中的位置不会被前面的替换顶偏。
 *
 * <p>刻意做成不碰 XML 的纯函数：这段逻辑最容易写错，而带 XML 的测试很难构造出
 * 「恰好横跨三个节点」这种输入。纯函数让刁钻输入可以手写出来。
 */
public final class SlotTextMapper {

    private SlotTextMapper() {
    }

    /**
     * 替换结果。{@code texts} 长度与入参一致；{@code changes} 是段内偏移，
     * 调用方需要定位到具体位置时可以直接用。
     */
    public record Mapped(List<String> texts, List<Change> changes) {
        public boolean changed() {
            return !changes.isEmpty();
        }
    }

    /**
     * 把各槽拼成一个逻辑字符串——<b>拼接规则只此一处定义</b>。
     *
     * <p>调用方（OFD 侧要收集「实际喂进引擎的那段文本」）必须用这个方法，
     * 不能自己再写一遍 {@code String.join("", …)}：
     * 一旦这里改了拼接规则（比如中间插分隔符），调用方那份手写的就会错位，
     * 而错位的后果是「续接的覆盖落到别的单元上」——把 A 的碎片按 B 的类型脱掉。
     */
    public static String join(List<String> slotTexts) {
        if (slotTexts == null || slotTexts.isEmpty()) {
            return "";
        }
        return String.join("", slotTexts);
    }

    /**
     * @param slotTexts 段落内各文本槽的原文，按文档顺序
     * @param session   会话（共用分配器，保证同值同符）
     */
    public static Mapped map(List<String> slotTexts, RedactionSession session) {
        if (slotTexts == null || slotTexts.isEmpty() || session == null) {
            return new Mapped(slotTexts == null ? List.of() : List.copyOf(slotTexts), List.of());
        }
        String full = join(slotTexts);
        if (full.isEmpty()) {
            return new Mapped(List.copyOf(slotTexts), List.of());
        }
        RedactionResult result = session.redact(full);
        if (!result.isChanged()) {
            return new Mapped(List.copyOf(slotTexts), List.of());
        }

        List<String> out = new ArrayList<>(slotTexts);
        List<Change> changes = new ArrayList<>(result.changes());
        // 从文末向前应用：每次都会改变后续槽的长度，倒着做才不会让前面的偏移失效
        changes.sort(Comparator.comparingInt(Change::start).reversed());
        for (Change change : changes) {
            applyEdit(out, change.start(), change.end(), change.token());
        }
        return new Mapped(List.copyOf(out), List.copyOf(result.changes()));
    }

    /**
     * 把 [start, end) 区间整体替换为 token，其余槽内容保留。
     *
     * <p>每次都从头重算槽起始偏移——前面的替换会改变后面槽的位置，只有重算才对得上。
     */
    private static void applyEdit(List<String> texts, int start, int end, String token) {
        if (start < 0 || end > totalLength(texts) || start >= end) {
            throw new IllegalArgumentException(
                    "替换区间越界 [" + start + ", " + end + ")，全文长度 " + totalLength(texts));
        }
        int[] offsets = offsets(texts);
        int first = slotAt(offsets, start);
        int last = slotAt(offsets, end - 1);

        String head = texts.get(first).substring(0, start - offsets[first]);
        String tail = texts.get(last).substring(end - offsets[last]);

        if (first == last) {
            texts.set(first, head + token + tail);
            return;
        }
        texts.set(first, head + token);
        for (int i = first + 1; i < last; i++) {
            texts.set(i, "");
        }
        texts.set(last, tail);
    }

    /** offsets[i] 是第 i 个槽在拼接串中的起始位置，长度为槽数 + 1。 */
    private static int[] offsets(List<String> texts) {
        int[] offsets = new int[texts.size() + 1];
        int acc = 0;
        for (int i = 0; i < texts.size(); i++) {
            offsets[i] = acc;
            acc += texts.get(i).length();
        }
        offsets[texts.size()] = acc;
        return offsets;
    }

    private static int totalLength(List<String> texts) {
        int acc = 0;
        for (String t : texts) {
            acc += t.length();
        }
        return acc;
    }

    /** 找到承载绝对下标 {@code index} 的槽；空槽不承载任何字符，会被跳过。 */
    private static int slotAt(int[] offsets, int index) {
        for (int i = 0; i < offsets.length - 1; i++) {
            if (index >= offsets[i] && index < offsets[i + 1]) {
                return i;
            }
        }
        throw new IllegalArgumentException("下标 " + index + " 不在任何槽内");
    }
}
