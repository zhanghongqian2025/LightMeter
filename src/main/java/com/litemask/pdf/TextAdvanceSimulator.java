package com.litemask.pdf;

import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 内容流文本位移模拟器。
 *
 * <p>版面有没有被改坏，肉眼看截图是不可靠的（字号、缩放、字距都会影响），
 * 必须能用算式判定。这里做的事很简单：沿内容流顺序走一遍，维护文本状态，
 * 在每个定位算子（Tm/Td/TD/T*）处把累计位移清零，记录每个显示文本算子结束时的累计水平位移。
 *
 * <p>于是「版面未变」有了一个可断言的定义：
 * <b>改写前后，被同一个原算子发出的所有片段首尾相接、且末片段的停止位置与原始一致。</b>
 * 任何漏补偿、错补偿、偏移算错，都会让停止位置分叉。
 *
 * <p>这里的宽度计算刻意**不复用** {@link ShowTextOp} 的实现，而是按 PDF 32000-1
 * 9.4.4 的公式重写一遍。两者共用同一个函数的话，写错了会一起错，测试就变成空转——
 * 这是写「探针式」测试时最常见的一种自欺。
 */
final class TextAdvanceSimulator {

    /**
     * 一个显示文本算子结束时的状态。
     *
     * <p>{@code segment} 是「第几个重定位段」——两个定位算子之间的那一片。分段是能拿来做
     * 前后对比的最小单位：改写会把一个显示算子拆成好几个（令牌单独成一条 TJ），
     * 所以逐算子比对必然对不齐；但它**不会新增或删除定位算子**，
     * 于是「每段末尾的累计位移」两边一一对应，这个量才是可以断言的。
     */
    record ShowStop(int segment, double displacement, String text) {
    }

    private TextAdvanceSimulator() {
    }

    static List<ShowStop> simulate(byte[] content, ContentInterpreter.FontResolver resolver)
            throws IOException {
        List<ContentToken> tokens = ContentScanner.scan(content);
        List<ShowStop> stops = new ArrayList<>();

        org.apache.pdfbox.pdmodel.font.PDFont font = null;
        float size = 0f;
        float charSpacing = 0f;
        float wordSpacing = 0f;
        float hPercent = 100f;
        float accumulated = 0f;
        int segment = 0;
        List<ContentToken> operands = new ArrayList<>();

        for (ContentToken t : tokens) {
            if (!t.isBoundary()) {
                operands.add(t);
                continue;
            }
            if (!t.isOperator()) {
                operands.clear();
                continue;
            }
            switch (t.text()) {
                case "Tf" -> {
                    if (operands.size() >= 2
                            && operands.get(operands.size() - 2).kind() == ContentToken.Kind.NAME) {
                        font = resolver.font(operands.get(operands.size() - 2).text());
                        size = parse(operands.get(operands.size() - 1));
                    }
                }
                case "Tc" -> charSpacing = parse(requireOperand(operands, "Tc"));
                case "Tw" -> wordSpacing = parse(requireOperand(operands, "Tw"));
                case "Tz" -> hPercent = parse(requireOperand(operands, "Tz"));
                case "BT", "ET", "Tm", "Td", "TD", "T*" -> {
                    accumulated = 0f;
                    segment++;
                }
                case "Tj", "'", "\"" -> {
                    accumulated += (float) advanceOfLast(font, operands, size, charSpacing,
                            wordSpacing, hPercent);
                    stops.add(new ShowStop(segment, accumulated, textOfLast(font, operands)));
                }
                case "TJ" -> {
                    accumulated += (float) arrayAdvance(font, operands, size, charSpacing,
                            wordSpacing, hPercent);
                    stops.add(new ShowStop(segment, accumulated, arrayText(font, operands)));
                }
                default -> {
                }
            }
            operands.clear();
        }
        return stops;
    }

    /**
     * 取每段**最后一个**显示算子的累计位移。
     *
     * <p>段内中间位置不给断言用：令牌夹在中间时，它自己的宽度与原文字宽度本就不同，
     * 中间位置理应不同——那不是缺陷。而段末尾是「这一段整体挪动了多少」，
     * 任何漏补、错补都会让它分叉。
     *
     * <p><b>不含没有任何显示算子的段</b>（{@code BT} 与 {@code ET} 各自开一段，
     * 再加上夹在中间的定位算子，首尾恒有两段空的）。留着它们，下标就凭空多出两位，
     * 两边对不齐时只能靠 NaN 兜底——而 NaN 一旦漏判，比较就悄悄放过了一个真实差异。
     * 直接不返回，问题就不存在了。
     */
    static double[] segmentEnds(List<ShowStop> stops) {
        List<Double> out = new ArrayList<>();
        int current = Integer.MIN_VALUE;
        for (ShowStop s : stops) {
            if (s.segment() != current) {
                out.add(s.displacement());
                current = s.segment();
            } else {
                out.set(out.size() - 1, s.displacement());
            }
        }
        double[] ends = new double[out.size()];
        for (int i = 0; i < ends.length; i++) {
            ends[i] = out.get(i);
        }
        return ends;
    }

    /**
     * 前后两段内容流的版面差异。空数组 = 版面一致。
     *
     * <p>返回的是**人话**，直接拼进告警：只报差在哪一段、差多少点。
     * 一个没有位置的数字（「位移不一致」）会让人无从判断是缺陷还是浮点噪声。
     */
    static List<String> compareDisplacement(List<ShowStop> before, List<ShowStop> after,
                                            double tolerance) {
        double[] a = segmentEnds(before);
        double[] b = segmentEnds(after);
        List<String> out = new ArrayList<>();
        if (a.length != b.length) {
            out.add("重定位段数变了：" + a.length + " → " + b.length
                    + "（回写不该动 Tm/Td/TD/T*/BT/ET）");
            return out;
        }
        for (int i = 0; i < a.length; i++) {
            double d = Math.abs(a[i] - b[i]);
            if (d > tolerance) {
                out.add("第 " + i + " 段末尾位移差 " + fmt(d) + " pt（原 " + fmt(a[i])
                        + " → 新 " + fmt(b[i]) + "）");
            }
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /**
     * 规范 9.4.4：单个字符串的水平位移 = Σ(字宽/1000×字号 + 字间距 [+ 词间距]) × 水平缩放。
     *
     * <p>遍历用的是 {@link PdfCodes} 的公共遍历器，**宽度公式仍是自己按规范重写的**。
     * 分工是刻意的：遍历器解决的是「怎么停下来」（PDFBox 在输入耗尽时返回 0 而非 -1，
     * 按码位正负判断会死循环），公式要保持独立，两边共用同一个函数就等于测试自证。
     */
    private static double showAdvance(PDFont font, byte[] codes, float size, float charSpacing,
                                      float wordSpacing, float hPercent) throws IOException {
        if (font == null) {
            return 0;
        }
        double[] total = {0};
        PdfCodes.forEachCode(font, codes, (code, consumed) -> {
            total[0] += font.getWidth(code) * 0.001 * size + charSpacing;
            // 词间距只作用于单字节码 32；Type0 的码是多字节，不吃词间距
            if (code == 32 && !ShowTextOp.isMultiByte(font)) {
                total[0] += wordSpacing;
            }
        });
        return total[0] * (hPercent / 100.0);
    }

    private static double advanceOfLast(PDFont font, List<ContentToken> operands, float size,
                                        float charSpacing, float wordSpacing, float hPercent)
            throws IOException {
        double total = 0;
        for (int i = operands.size() - 1; i >= 0; i--) {
            ContentToken e = operands.get(i);
            if (e.kind() == ContentToken.Kind.STRING) {
                return showAdvance(font, e.data(), size, charSpacing, wordSpacing, hPercent);
            }
            if (e.kind() == ContentToken.Kind.OPERATOR) {
                break;
            }
        }
        return total;
    }

    private static double arrayAdvance(PDFont font, List<ContentToken> operands, float size,
                                       float charSpacing, float wordSpacing, float hPercent)
            throws IOException {
        double total = 0;
        for (ContentToken e : operands) {
            if (e.kind() == ContentToken.Kind.STRING) {
                total += showAdvance(font, e.data(), size, charSpacing, wordSpacing, hPercent);
            } else if (e.kind() == ContentToken.Kind.NUMBER) {
                // TJ 里的数字是往左移：-n/1000 × 字号 × 水平缩放
                total -= Double.parseDouble(e.text()) / 1000.0 * size * (hPercent / 100.0);
            }
        }
        return total;
    }

    private static String textOfLast(PDFont font, List<ContentToken> operands) throws IOException {
        for (int i = operands.size() - 1; i >= 0; i--) {
            if (operands.get(i).kind() == ContentToken.Kind.STRING) {
                return ShowTextOp.decodeString(font, operands.get(i).data());
            }
        }
        return "";
    }

    private static String arrayText(PDFont font, List<ContentToken> operands) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (ContentToken e : operands) {
            if (e.kind() == ContentToken.Kind.STRING) {
                sb.append(ShowTextOp.decodeString(font, e.data()));
            }
        }
        return sb.toString();
    }

    /**
     * 取算子紧邻的那个操作数；<b>缺操作数时明确报错</b>。
     *
     * <p>缺操作数几乎总是「算子与它的操作数粘在了一起」：内容流里写着
     * 「68.2317 Tz100 Tz」时，{@code Tz100} 被当成一个未知算子，
     * 紧跟着那个 {@code Tz} 就没有操作数了。
     *
     * <p>原来这里直接 {@code get(operands.size() - 1)}，越界抛的是
     * {@code IndexOutOfBoundsException: Index -1 out of bounds for length 0}——
     * 读告警的人看不出「内容流里有个坏算子」，只看到一句天书。
     * 而这正是版面自检<b>整个死掉</b>的方式：自检一崩，
     * 「版面是否保持原样」就再没人核对了（实测 2026-10-20）。
     *
     * <p>这里仍然是<b>报错</b>而不是跳过：跳过等于假装这个坏算子不存在，
     * 模拟出来的版面就与真实阅读器的行为分叉了，而自检最怕的就是这个。
     */
    private static ContentToken requireOperand(List<ContentToken> operands, String op)
            throws IOException {
        if (operands.isEmpty()) {
            throw new IOException("内容流里的算子 " + op + " 没有操作数"
                    + "（多半是算子与操作数之间少了空白，"
                    + "例如「Tz100」被读成了一个未知算子）");
        }
        return operands.get(operands.size() - 1);
    }

    private static float parse(ContentToken t) {
        if (t == null || t.kind() != ContentToken.Kind.NUMBER) {
            return 0f;
        }
        return Float.parseFloat(t.text());
    }
}
