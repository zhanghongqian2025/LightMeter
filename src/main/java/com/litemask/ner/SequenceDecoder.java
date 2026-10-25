package com.litemask.ner;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型逐位置的通道分数解码成标签序列，再从标签序列抽出实体区间。
 *
 * <p><b>为什么用 Viterbi 而不是逐位置取最大值</b>：逐位置贪心会在某一字上选出
 * {@code I-机构名}，而它前面一个字既不是 {@code B-机构名} 也不是 {@code I-机构名}——
 * 这个实体压根没有开头。贪心不管三七二十一照样把它当成实体，界面上的公司名
 * 于是凭空多出或少掉一个字。
 *
 * <p>解码分两层：
 * <ol>
 *   <li>模型的转移分数决定「这条路径总分有多高」；</li>
 *   <li>{@link TagScheme#allows} 的结构约束决定「这条路径<b>可能</b>不成立」。</li>
 * </ol>
 * 两层都不可省：只有转移分数，解码器仍可能选出一条结构上非法的路径；
 * 只有结构约束，模型在整段文本上的置信度比较就被丢掉了。
 */
public final class SequenceDecoder {

    /** 模型给出的不可能取到的分数。与它相加的结果仍是它。 */
    public static final double IMPOSSIBLE = Double.NEGATIVE_INFINITY;

    private SequenceDecoder() {
    }

    /**
     * Viterbi 解码。
     *
     * @param emissions   逐位置的通道分数 {@code [T][C]}，通常是对数概率
     * @param transitions 转移分数 {@code [C*C]}，{@code transitions[from * C + to]}
     * @param scheme      标签方案
     * @return 最优标签路径，长度与 {@code emissions} 相同
     */
    public static int[] decode(double[][] emissions, double[] transitions, TagScheme scheme) {
        int c = scheme.size();
        if (emissions == null || emissions.length == 0) {
            return new int[0];
        }
        if (transitions == null || transitions.length != c * c) {
            throw new IllegalArgumentException(
                    "转移分数必须是 C*C 的扁平数组，实际长度 " + (transitions == null ? "null" : transitions.length)
                            + "，期望 " + (c * c));
        }
        int t = emissions.length;
        for (int i = 0; i < t; i++) {
            if (emissions[i] == null || emissions[i].length != c) {
                throw new IllegalArgumentException(
                        "第 " + i + " 个位置的通道数不是 " + c
                                + "，模型输出与标签方案对不上（这是模型包本身的问题，不是输入文本的问题）");
            }
        }

        int[] path = new int[t];

        // 不为 t==1 单开一条分支：下面的 DP 对 t==1 天然正确（回溯循环不执行），
        // 单开分支就会绕过开头的合法性检查。
        // dp[t][tag]：前 t+1 个位置都以 tag 结尾的最优总分
        double[][] dp = new double[t][c];
        int[][] back = new int[t][c];
        for (int tag = 0; tag < c; tag++) {
            if (!scheme.allowsStart(tag)) {
                // I- 不能开头：直接压到负无穷，而不是靠后面的转移约束去救。
                // 靠转移救会留下「首字是 I-、解码器自己却不知道自己违约」的状态。
                dp[0][tag] = IMPOSSIBLE;
                back[0][tag] = -1;
                continue;
            }
            dp[0][tag] = emissions[0][tag];
            back[0][tag] = -1;
        }
        for (int pos = 1; pos < t; pos++) {
            for (int to = 0; to < c; to++) {
                double best = IMPOSSIBLE;
                int bestFrom = -1;
                for (int from = 0; from < c; from++) {
                    if (!scheme.allows(from, to)) {
                        continue;
                    }
                    double score = dp[pos - 1][from] + transitions[from * c + to];
                    // 用 `>` 而不是 `>=`：from 是从小到大遍历的，等分时先到的就是通道号小的，
                    // 于是解码结果可复现——同一段文本两次处理必须得到同一串标签，
                    // 否则用户会拿到两份令牌编号不同的脱敏文件，却看不出任何区别。
                    if (score > best) {
                        best = score;
                        bestFrom = from;
                    }
                }
                // 全局无合法前驱时退回到「任意前驱」，宁可给一条结构非法的路径也不返回空——
                // 空路径会在下游变成「这段文本没有实体」，那是**静默漏脱**。
                if (bestFrom < 0) {
                    for (int from = 0; from < c; from++) {
                        double score = dp[pos - 1][from] + transitions[from * c + to];
                        if (score > best) {
                            best = score;
                            bestFrom = from;
                        }
                    }
                }
                dp[pos][to] = best + emissions[pos][to];
                back[pos][to] = bestFrom;
            }
        }

        int last = argmax(dp[t - 1]);
        if (Double.isInfinite(dp[t - 1][last]) && dp[t - 1][last] < 0) {
            // 全路径得分都是 -Infinity：模型的分数全被标成了「不可能」。
            // 此时任何回溯都是在编一个答案，而编出来的答案必然是「没有实体」——
            // 那是**静默漏脱**。直接失败，让调用方知道模型包有问题。
            throw new IllegalStateException(
                    "模型输出与标签方案之间没有任何合法路径（所有路径得分都是负无穷）。"
                            + "通常是模型包的标签方案与实际模型不匹配——这是模型包的问题，不是输入文本的问题。");
        }
        path[t - 1] = last;
        for (int pos = t - 1; pos > 0; pos--) {
            int prev = back[pos][path[pos]];
            path[pos - 1] = prev < 0 ? 0 : prev;
        }
        return path;
    }

    /** 解码并直接抽出实体区间。 */
    public static List<EntitySpan> decodeToEntities(double[][] emissions, double[] transitions,
                                                     TagScheme scheme) {
        return toEntities(decode(emissions, transitions, scheme), scheme);
    }

    /**
     * 从标签路径抽实体。
     *
     * <p>路径经过 {@link TagScheme} 的结构约束，正常情况下不会出现「I- 没有 B- 打头」。
     * 真出现了（手工构造的路径、或未来的解码实现变了），
     * 这里的处理是<b>当作一个新的实体开头</b>，而不是丢弃——
     * 丢弃等于少脱，而少脱是这个工具唯一不能犯的错。
     */
    public static List<EntitySpan> toEntities(int[] path, TagScheme scheme) {
        List<EntitySpan> out = new ArrayList<>();
        int start = -1;
        EntityType openType = null;
        for (int i = 0; i < path.length; i++) {
            int tag = path[i];
            EntityType type = scheme.typeOf(tag);
            if (type == null) {
                // 不属于任何实体：有开着的就先收口
                if (openType != null) {
                    out.add(new EntitySpan(start, i, openType));
                    openType = null;
                    start = -1;
                }
                continue;
            }
            if (scheme.isBegin(tag)) {
                if (openType != null) {
                    out.add(new EntitySpan(start, i, openType));
                }
                start = i;
                openType = type;
            } else {
                // I- 且当前没有开着的实体：当作新实体开头（宁可多脱）
                if (openType == null || openType != type) {
                    if (openType != null) {
                        out.add(new EntitySpan(start, i, openType));
                    }
                    start = i;
                    openType = type;
                }
            }
        }
        if (openType != null) {
            out.add(new EntitySpan(start, path.length, openType));
        }
        return out;
    }

    /** 该标签路径的总分。用于测试里与穷举结果对账。 */
    public static double scoreOf(int[] path, double[][] emissions, double[] transitions, TagScheme scheme) {
        if (path.length == 0) {
            return 0.0;
        }
        int c = scheme.size();
        double total = emissions[0][path[0]];
        for (int i = 1; i < path.length; i++) {
            total += transitions[path[i - 1] * c + path[i]] + emissions[i][path[i]];
        }
        return total;
    }

    private static int argmax(double[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }
}
