package com.litemask.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开关解析的两个帮手。
 *
 * <p><b>为什么要有这个类</b>
 *   块 38 开工前的实测：{@code redact --ouput 合同.txt} 报出来的是「错误: --ouput」——
 *   那其实<b>不是</b>「工具认出了我拼错的开关」，而是「文件 {@code --ouput} 不存在」：
 *   解析层把未知开关当成了<b>文件名</b>。
 *   → 两件事必须分开：
 *     · <b>已知开关从哪来</b>：从「用法文本」里<b>现算</b>，不另抄一份清单
 *       （另抄一份 = 第三份拷贝 = 必然漂，且漂了没人喊）；
 *     · <b>拼错了给什么提示</b>：算编辑距离，给出最接近的那一个。
 *
 * <p><b>为什么只给长开关提建议</b>
 *   短开关就一个字母（{@code -o}、{@code -h}），编辑距离在这么短的串上噪声很大，
 *   乱指一个比不指更糟。长开关（{@code --output}）才有足够长度让距离有意义。
 */
final class CliHints {

    /** 长开关：{@code --max-upload-bytes}。 */
    private static final Pattern LONG_FLAG = Pattern.compile("(?<![\\w-])--[a-z][a-z0-9-]*");
    /** 短开关：{@code -o}。 */
    private static final Pattern SHORT_FLAG = Pattern.compile("(?<![\\w-])-[A-Za-z]");

    private CliHints() {
    }

    /**
     * 从一段用法文本里<b>现算</b>出全部开关（长 + 短），去重并保持首次出现的顺序。
     *
     * <p>【为什么从用法文本里算，而不是维护一份开关清单】
     *   用法文本就是「这个命令认哪些开关」的唯一真源（它同时被顶层 help、
     *   子命令自己的 --help、以及解析器的报错文案使用）。
     *   另抄一份清单 = 第三份拷贝：加了开关忘了改清单，解析器就会把用户
     *   正在用的开关报成「未知开关」——而清单自己不会喊。
     */
    static List<String> flagsIn(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = LONG_FLAG.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        m = SHORT_FLAG.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return new ArrayList<>(found);
    }

    /**
     * 未知开关的完整报错文案。
     *
     * <p>【为什么不只是一句「未知开关」】
     *   用户敲错开关绝大多数是「少打一个字母」或「顺序颠倒」。
     *   只说「未知开关: --ouput」用户得自己猜；补一句「你是不是想写 --output？」
     *   就能一步到位。
     */
    static String unknownSwitchMessage(String given, Collection<String> known) {
        String near = nearest(given, known);
        if (near == null) {
            return "未知开关: " + given;
        }
        return "未知开关: " + given + "（你是不是想写 " + near + "？）";
    }

    /**
     * 找出与 {@code given} 最接近的已知开关；没有足够接近的就返回 {@code null}。
     *
     * <p>【阈值怎么定的 —— 都是「宁可少指，不要乱指」】
     *   · 距离 1：少一个字母 / 多一个字母 / 顺序颠倒 → 一定提。
     *   · 距离 2：两个位置的差异 → 提。
     *   · 距离 3：只在<b>前 4 个字符相同</b>时提（{@code --out} → {@code --output} 提；
     *     {@code --xyz} → {@code --map} 不提，那是乱敲的，不是拼错的）。
     *   · 距离再大：不提 —— 乱指一个比不指更糟。
     */
    static String nearest(String given, Collection<String> known) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String k : known) {
            if (k.equals(given)) {
                return null;                       // 不是拼错，就是它自己
            }
            if (!k.startsWith("--")) {
                continue;                          // 只给长开关提建议（见类注释）
            }
            int d = distance(given, k);
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        if (best == null) {
            return null;
        }
        if (bestDistance <= 2) {
            return best;
        }
        if (bestDistance == 3 && commonPrefix(given, best) >= 4) {
            return best;
        }
        return null;
    }

    /**
     * Damerau-Levenshtein 距离（相邻换位算 1 次）。
     *
     * <p>用 Damerau 而不是普通 Levenshtein：用户最常见的打字错误是<b>顺序颠倒</b>
     * （{@code --ouput} ← {@code --output} 的 o/u 换位），普通 Levenshtein 算它是 2，
     * Damerau 算 1 —— 后者落在「一定提」那一档。
     */
    static int distance(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return d[n][m];
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }
}
