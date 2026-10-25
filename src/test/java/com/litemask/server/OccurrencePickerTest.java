package com.litemask.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块 22：「只保留第 N 处」在界面上要能选到<b>每一次</b>出现。
 *
 * <p><b>这一块补的是什么洞</b>：服务端早就支持位置级排除，
 * 序号校验用的也是<b>全量出现次数</b>（{@code JobStore.verifyExclusionsMatch}
 * 比的是 {@code hit.occurrences()}，不是那份被截断的位置清单）。
 * 但界面上「出现处」那一格默认只列 3 条位置 → 第 4 次及以后的出现
 * <b>在界面上根本没有入口</b>：用户看得见「这个值出现 10 次」，
 * 却没法只保留第 7 次。
 *
 * <p><b>判据落在「这一处」</b>：全部落在
 * {@code app.js} 里<b>渲染「出现处」那一格的那一段</b>上，
 * 而不是「整份脚本里出现过某个字符串」。后者会被同一份文件里
 * <b>另一段</b>代码误伤（本项目踩过：整份文件里搜
 * {@code "b.dataset.original"} 照样命中 {@code ob.dataset.original}）。
 *
 * <p><b>标尺独立于被测实现</b>：这里读的是 jar 里真实发给浏览器的
 * {@code app.js} / {@code app.css}，不复用任何 Java 侧代码去「推导」它应该长什么样。
 * 真正验「勾了第 4 次以后文件里只留下那一处」的是浏览器端到端
 * （{@code tools/e2e-browser.sh}）；这里守的是「那段渲染不许被拆掉」。
 */
class OccurrencePickerTest {

    /**
     * 渲染「出现处」那一格的那一段。
     *
     * <p>从 {@code perOcc} 那一行到 {@code tdWhere} 挂上清单为止——
     * 这一段里既有「怎么铺行」，也有「每行挂不挂勾选框」。
     *
     * <p>刻意不用 {@code WebUiTest.脚本块}：那个提取器按
     * 「{@code .forEach(function} 的括号配平」收尾，
     * 而这一段用的是 {@code for} 循环（按出现序号铺行），
     * 用它收会一路收到下一个 {@code forEach} 里去。
     */
    private static String 位置渲染段(String js) {
        int i = js.indexOf("var perOcc = canPickOccurrence(hit);");
        if (i < 0) {
            return "";
        }
        int j = js.indexOf("tr.appendChild(tdWhere);", i);
        if (j < 0) {
            return "";
        }
        return js.substring(i, j);
    }

    /** {@code occBoxes()} 那个函数的全部内容。 */
    private static String occBoxes函数(String js) {
        int i = js.indexOf("function occBoxes()");
        if (i < 0) {
            return "";
        }
        int j = js.indexOf("}", i);
        return j < 0 ? "" : js.substring(i, j + 1);
    }

    /** 位置渲染段；取不到就当场报错（前提不成立不许静默跳过）。 */
    private static String 渲染段() {
        String js = WebUiTest.去注释(WebUiTest.脚本());
        String 段 = 位置渲染段(js);
        assertFalse(段.isEmpty(),
                "在 app.js 里找不到渲染「出现处」那一格的代码"
                        + "（找的是 perOcc 那一行到 tdWhere 挂上清单为止）"
                        + "——写法变了，判据要跟着改，但**不许**因此把这条判据删掉");
        return 段;
    }

    // ------------------------------------------------------------ 铺行

    @Test
    @DisplayName("位置清单按「出现序号」铺行，不按「位置条数」铺行")
    void 按出现序号铺行() {
        String 段 = 渲染段();

        // 总数来自服务端给的 occurrences，不是本地数出来的位置条数。
        assertTrue(段.contains("hit.occurrences"),
                "铺行的总数必须用服务端下发的 occurrences（引擎分配、透传下来的那个数）");
        assertTrue(段.contains("for (var oi = 0; oi < total"),
                "铺行必须按总数循环，而不是按位置条数循环");

        // 旧写法（按位置条数截断）不许回来：
        // 它正是「第 4 次以后没有入口」的成因。
        assertFalse(段.contains("locs.slice("),
                "不许再按位置条数截断位置清单"
                        + "（服务端的位置说明最多 20 条，按它截断就等于第 21 次以后选不到）");
    }

    @Test
    @DisplayName("每一行都挂逐处勾选框，出现序号直接取行号")
    void 每行都挂勾选框() {
        String 段 = 渲染段();

        assertTrue(段.contains("occ-pick"),
                "每一处出现都要挂「只保留这一处」的勾选框");
        // 序号 = 行号。行号就是引擎按 (规则键, 原值) 从 0 连续编的那个号。
        assertTrue(段.contains("ob.dataset.occurrence = String(oi)"),
                "勾选框的序号必须直接取行号（oi）："
                        + "自己重数、或取模，都会让界面上「第 4 次」指到别的位置上去");
    }

    @Test
    @DisplayName("服务端没给位置说明的那几次照样占一行、照样能勾")
    void 没位置说明的也占一行() {
        String 段 = 渲染段();

        assertTrue(段.contains("次出现（没有位置说明）"),
                "没有位置说明的那几次必须有一句明说（不能空着、也不能假装有位置）");
        // 勾选框的挂载条件里**不许**掺进「有没有位置说明」：
        // 服务端只认序号，位置说明是给人看的。
        assertTrue(段.contains("if (perOcc)"),
                "逐处勾选框的挂载条件应当只看「这一行能不能逐处选」");
        assertFalse(段.contains("if (perOcc &&"),
                "勾选框的挂载条件里不许掺进「有没有位置说明」——"
                        + "那会让服务端截断掉的那些次在界面上选不到");
    }

    // ------------------------------------------------------------ 折叠与展开

    @Test
    @DisplayName("默认只显示前几条，但折叠起来的那几处照样能勾")
    void 默认折叠() {
        String js = WebUiTest.去注释(WebUiTest.脚本());
        String 段 = 渲染段();

        assertTrue(js.contains("var LOCS_COLLAPSED"),
                "默认显示几条位置要有一个明确的上限（二十条位置会把一列撑成长文）");
        assertTrue(段.contains("LOCS_COLLAPSED"),
                "折叠的条数必须真的用在那段渲染上");
        // 折叠到 1 条就退化成「只看得到第一处」了，那比原来还差。
        int n = -1;
        int i = js.indexOf("var LOCS_COLLAPSED");
        if (i >= 0) {
            int eq = js.indexOf("=", i);
            int semi = js.indexOf(";", eq);
            try {
                n = Integer.parseInt(js.substring(eq + 1, semi).trim());
            } catch (NumberFormatException e) {
                n = -1;
            }
        }
        assertTrue(n >= 2, "默认显示的条数至少要 2（折叠到 1 条等于只看得见第一处）：实际 " + n);
    }

    @Test
    @DisplayName("折叠的行默认不显示，展开后显示（样式与开关都要在）")
    void 展开入口与样式() {
        String js = WebUiTest.去注释(WebUiTest.脚本());
        String css = WebUiTest.样式();

        assertTrue(js.contains("locMore"),
                "要有「展开全部」的入口（位置可能有二十条，默认只显示前几条）");
        assertTrue(js.contains("locsExpanded"),
                "展开状态要有个类名挂在容器上（展开与收起是同一段代码切出来的）");

        String 折叠 = WebUiTest.css块(css, "table.hits td.num .locs .loc.locHidden {");
        assertFalse(折叠.isEmpty(), "折叠起来的行要有「默认不显示」的样式");
        assertTrue(折叠.contains("display: none"),
                "折叠起来的行默认应当不显示（它还在文档里，只是先收起来）：" + 折叠);

        String 展开 = WebUiTest.css块(css, "table.hits td.num .locs.locsExpanded .loc.locHidden {");
        assertFalse(展开.isEmpty(), "展开后那些行要能显示出来");
        assertTrue(展开.contains("display: flex"),
                "展开后的行应当恢复成与其它位置行一样的排版（display: flex）：" + 展开);
    }

    // ------------------------------------------------------------ 收集

    @Test
    @DisplayName("逐处排除按标记收，不按位置收（折叠的行也要收得到）")
    void 按标记收集() {
        String js = WebUiTest.去注释(WebUiTest.脚本());
        String fn = occBoxes函数(js);
        assertFalse(fn.isEmpty(),
                "在 app.js 里找不到 occBoxes() 这个函数（写法变了，判据要跟着改）");

        assertTrue(fn.contains("input.occ-pick"),
                "逐处的勾选框要按自己的标记取（occ-pick），"
                        + "按位置取的话折叠起来的那几处就收不到");
        assertFalse(fn.contains("nth-child"),
                "不许按「第几个」取逐处勾选框（那会漏掉折叠起来的那几处）");
        assertFalse(fn.contains(":first"),
                "不许只取第一个逐处勾选框");
    }
}
