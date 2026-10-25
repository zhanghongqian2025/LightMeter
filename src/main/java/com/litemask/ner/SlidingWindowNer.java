package com.litemask.ner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 滑窗识别 + 跨窗口合并。
 *
 * <p><b>为什么要重叠窗口</b>：模型一次只看有限长度的文本。切窗会让跨界的实体被截断——
 * 「北京市朝阳区人民法」落在窗口末尾、「院」落在下一个窗口开头，
 * 两个窗口各自都只看得见半截，于是这个实体在结果里<b>整个消失</b>，
 * 而且不报错，只是少了。
 *
 * <p><b>一条规则：边缘观测不可信</b>。一条实体区间只要<b>贴着窗口被切开的那条边</b>，
 * 就有可能是被截断的半截（前半截或后半截）。
 * 因此采信条件是：
 *
 * <ol>
 *   <li>实体区间<b>完全落在某个窗口内</b>（这个窗口确实看得见全貌）；</li>
 *   <li>它<b>不触碰该窗口被切开的那条边</b>——首窗的右边界、尾窗的左边界、
 *       以及所有中间窗口的两条边都是被切开的边；
 *       只有<b>真正的文本首尾</b>不算被切开。</li>
 * </ol>
 *
 * <p>这条规则让「谁负责报这个实体」变成自动的：跨界的实体总有一个窗口能完整看见它
 * 且不贴着切边，于是被采信；而每个窗口紧贴切边的那几个字，由下一个/上一个窗口负责。
 * 同一实体被多个窗口各报一次是常态，按 {@code (类型, 起点, 终点)} 去重即可。
 *
 * <p><b>只检查右边，不检查左边</b>——这条是踩过坑之后改的。
 * 起初左右两边对称地丢，理由是「贴切边就可能被截断」。但左边那个理由<b>不成立</b>：
 * 解码器只会输出以 {@code B-} 开头的实体（孤立 {@code I-} 被开头约束挡住），
 * 所以一个起点落在窗口左边界上的实体，<b>是真的从那里开始的</b>，不可能是被截断的尾巴。
 *
 * <p>现场是实体<b>整个消失且零报错</b>：「张三丰北京大学」配 5/2 的窗口，
 * 「北京大学」完整落在窗口 {@code [3,7)} 里，却因为起点恰好等于左切边被丢掉；
 * 而前一个窗口看见的半截「北京」又因为终点贴着右切边被丢掉——两边都不认领。
 *
 * <p><b>为什么原来的设计没能发现</b>：既有测试里贴左切边的场景全都被绕开了
 * （填字数量是特意凑的），等于这条规则<b>从来没被当成期望行为测过</b>。
 * 这就是「只测会丢的那一面」的另一个形态：另一面根本没测，就等于没测。
 *
 * <p><b>为什么不用「归属区」投票</b>：先试过那个方案，它是错的——
 * 归属区必须在窗口两端各让出一个重叠宽度，于是相邻窗口的归属区之间
 * 会留下一段谁都不认领的空洞，落在空洞里的实体<b>永远拿不到票</b>。
 * 那个 bug 的现场也是「大部分实体都对，只有跨窗口的那几个消失」，极难归因。
 */
public final class SlidingWindowNer {

    /** 一个窗口，坐标是<b>全文</b>坐标系。 */
    public record Window(int index, int start, int end) {
    }

    /**
     * 在单个窗口内做识别的回调，返回的区间是<b>窗口局部</b>坐标。
     *
     * <p>刻意用窗口局部坐标：实现方不用关心滑窗怎么切，
     * 免得有人在坐标换算上出错——那种错的现场是「有的文档对、有的文档整体偏移」。
     */
    public interface WindowRecognizer {
        List<EntitySpan> recognize(String windowText);
    }

    private final int maxChars;
    private final int overlap;

    /**
     * @param maxChars 单窗口最多看多少字
     * @param overlap  相邻窗口重叠多少字；必须 ≥ 1 且 &lt; maxChars
     */
    public SlidingWindowNer(int maxChars, int overlap) {
        if (maxChars < 2) {
            throw new IllegalArgumentException("窗口太小: " + maxChars);
        }
        if (overlap < 1 || overlap >= maxChars) {
            throw new IllegalArgumentException(
                    "重叠宽度必须 ≥1 且小于窗口宽度，收到 overlap=" + overlap + ", maxChars=" + maxChars);
        }
        this.maxChars = maxChars;
        this.overlap = overlap;
    }

    /** 规划窗口切分。相邻窗口重叠 {@link #overlap()} 个字。 */
    public List<Window> plan(int textLength) {
        List<Window> windows = new ArrayList<>();
        if (textLength <= 0) {
            return windows;
        }
        int step = maxChars - overlap;
        int start = 0;
        int index = 0;
        while (true) {
            int end = Math.min(start + maxChars, textLength);
            windows.add(new Window(index++, start, end));
            if (end >= textLength) {
                break;
            }
            start += step;
        }
        return windows;
    }

    /**
     * 跑一遍全文识别并合并各窗口的结果。
     *
     * @param text       全文
     * @param recognizer 单窗口识别器
     * @return 全文坐标系下的实体区间，按起点升序
     */
    public List<EntitySpan> run(String text, WindowRecognizer recognizer) {
        if (text == null) {
            throw new IllegalArgumentException("待识别文本不能为 null");
        }
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        if (recognizer == null) {
            throw new IllegalArgumentException("识别器不能为 null");
        }
        int textLength = text.length();

        Map<String, EntitySpan> collected = new LinkedHashMap<>();
        for (Window w : plan(textLength)) {
            String windowText = text.substring(w.start(), w.end());
            List<EntitySpan> local = recognizer.recognize(windowText);
            if (local == null) {
                continue;
            }
            for (EntitySpan span : local) {
                if (span == null) {
                    // 空元素直接丢弃：窗口识别器是插件式的（不同模型、不同参数），
                    // 一条 null 不该让整篇文档失败。
                    continue;
                }
                int gStart = w.start() + span.start();
                int gEnd = w.start() + span.end();
                if (gStart < w.start() || gEnd > w.end() || gEnd <= gStart) {
                    throw new IllegalArgumentException(
                            "窗口 " + w.index() + " 报出的区间 " + span
                                    + " 超出该窗口范围 [0," + (w.end() - w.start()) + ")，"
                                    + "说明窗口识别器的坐标换算有问题");
                }
                if (贴着切边(w, gStart, gEnd, textLength)) {
                    continue;
                }
                EntitySpan global = new EntitySpan(gStart, gEnd, span.type());
                collected.putIfAbsent(global.type().code() + '@' + gStart + '-' + gEnd, global);
            }
        }
        List<EntitySpan> merged = new ArrayList<>(collected.values());
        merged.sort((a, b) -> a.start() != b.start()
                ? Integer.compare(a.start(), b.start())
                : Integer.compare(a.end(), b.end()));
        return merged;
    }

    /**
     * 这条观测是否紧贴「窗口被切开的那条边」。
     *
     * <p>文本真正的首尾<b>不算</b>被切开：那里的实体被截断不了，
     * 反而是整篇文档开头/结尾的机构名最需要被采信。
     *
     * <p><b>只判右边</b>：左边的理由不成立——解码器只输出以 {@code B-} 开头的实体，
     * 起点落在窗口左边界上的实体是真的从那里开始的，不是被截断的尾巴。
     * 详见类注释里那段「只检查右边，不检查左边」。
     */
    /** 这条观测是否紧贴「窗口被切开的那条边」。 */
    static boolean 贴着切边(Window w, int gStart, int gEnd, int textLength) {
        boolean touchesEndCut = gEnd == w.end() && w.end() < textLength;
        return touchesEndCut;
    }

    public int maxChars() {
        return maxChars;
    }

    public int overlap() {
        return overlap;
    }
}
