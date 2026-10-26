package com.litemask.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 界面的<b>静态接线</b>测试。
 *
 * <p>浏览器端那半边（拖拽、CSP 实际生效、点击真的走对接口）在这个项目里
 * <b>没有真人验收</b>——没有自动化浏览器，也不要求用户接管浏览器。
 * 那是个明确的验证缺口，记在阶段文档里。能在本地真正验的部分只有一件：
 * <b>页面与脚本里的元素 id、接口路径、令牌用法，和服务端实际提供的是否对得上</b>。
 *
 * <p>这条接线的失配是最容易发生又最难发现的：页面上有个按钮，服务端路由改了名字，
 * 双方各自编译各自测试全绿，用户点下去才发现没反应。文本层面的对账能在
 * 部署前把它抓出来。
 *
 * <p>标尺独立于被测实现：这里读的是 jar 里真实发给浏览器的三个静态资源文件，
 * 不复用任何 Java 侧的服务端代码去「推导」它应该长什么样。
 */
class WebUiTest {

    static String 页面() {
        return 资源("/web/index.html");
    }

    static String 脚本() {
        return 资源("/web/app.js");
    }

    static String 样式() {
        return 资源("/web/app.css");
    }

    /**
     * 数一个子串在文本里出现了几次（不重叠）。
     *
     * <p>用来判断「某个收口点是不是被调用了多处」：
     * 只出现一次，说明它只在定义处出现（或者只在一个地方被调用），
     * 那么「另一条路径忘了调它」这种缺陷就查不出来。
     */
    static int 计数(String src, String needle) {
        int n = 0;
        int i = src.indexOf(needle);
        while (i >= 0) {
            n++;
            i = src.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /**
     * 取一条 CSS 规则的内容（从选择器到第一个右花括号）。
     *
     * <p>刻意不做完整 CSS 解析：这里只需要「某个选择器那一段里有没有某条声明」，
     * 而完整的解析器要引依赖，不值当。
     *
     * @return 规则文本；找不到时返回空串
     */
    static String css块(String css, String 选择器) {
        int i = css.indexOf(选择器);
        if (i < 0) {
            return "";
        }
        int j = css.indexOf('}', i);
        return j < 0 ? "" : css.substring(i, j);
    }

    /**
     * 取脚本里从某个标记起、到它那个 {@code forEach} 回调<b>括号配平</b>处结束的那一段。
     *
     * <p><b>为什么需要它</b>：判据要落在「<b>这一处</b>」而不是「这一类」。
     * 整份文件里搜一个子串，判不出「是<b>哪一段</b>代码在用它」——
     * 本轮就踩了：逐处排除那段用的是 {@code ob.dataset.original}，
     * 而整份文件里搜 {@code "b.dataset.original"} 照样命中（{@code ob} 的尾巴就是 {@code b}），
     * 于是「整行那条路不许用一行的原值」这条判据被<b>另一段代码</b>误伤。
     * 判据必须指到具体那一段，才分得清谁犯了事。
     *
     * <p>为什么按括号配平而不是按第一个分号切：那一段是<b>多条语句</b>，
     * 第一个分号落在回调的第一条 return 上，切出来只有半句。
     */
    static String 脚本块(String js, String 标记) {
        int i = js.indexOf(标记);
        if (i < 0) {
            return "";
        }
        int open = js.indexOf(".forEach(function", i);
        if (open < 0) {
            return "";
        }
        int brace = js.indexOf('{', open);
        if (brace < 0) {
            return "";
        }
        int depth = 0;
        for (int k = brace; k < js.length(); k++) {
            char c = js.charAt(k);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return js.substring(i, k + 1);
                }
            }
        }
        return "";
    }

    static String 资源(String name) {
        try (var in = WebUiTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new AssertionError("资源不存在: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读不到资源: " + name, e);
        }
    }

    // ------------------------------------------------------------ 元素 id

    @Test
    @DisplayName("脚本引用的每个 id 在页面里都存在（改一个名字不会静默失效）")
    void 脚本引用的id都存在() {
        String html = 页面();
        Matcher m = Pattern.compile("\\$\\(\"([A-Za-z0-9_-]+)\"\\)").matcher(脚本());
        int 查过 = 0;
        while (m.find()) {
            String id = m.group(1);
            查过++;
            assertTrue(html.contains("id=\"" + id + "\""),
                    "脚本用了 #" + id + "，但页面里没有这个元素");
        }
        assertTrue(查过 >= 15, "应至少查到 15 处 id 引用，实际 " + 查过
                + "——正则多半没匹配上，那这条测试就等于什么都没测");
    }

    @Test
    @DisplayName("流程三步在页面上都存在，且脚本能推动它们")
    void 三步齐备() {
        String html = 页面();
        for (String step : List.of("stepPick", "stepReview", "stepDownload")) {
            assertTrue(html.contains("id=\"" + step + "\""), "缺少步骤节点 " + step);
        }
        assertTrue(脚本().contains("markStep"), "脚本应有推进步骤的函数");
        // 每个步骤节点都至少被 markStep 覆盖一次，否则它永远停在初始态
        for (String step : List.of("Pick", "Review", "Download")) {
            assertTrue(脚本().contains("\"" + step + "\""),
                    "markStep 未覆盖步骤 " + step + "，它会永远停在初始样式");
        }
    }

    @Test
    @DisplayName("三个阶段的面板都存在：选文件 / 命中清单 / 下载")
    void 三个面板齐备() {
        String html = 页面();
        for (String id : List.of("pickCard", "reviewCard", "downloadCard")) {
            assertTrue(html.contains("id=\"" + id + "\""), "缺少面板 " + id);
        }
    }

    // ------------------------------------------------------------ 接口路径

    @Test
    @DisplayName("脚本调用的接口与服务端注册的路由逐个对得上")
    void 接口路径对得上() {
        String js = 去注释(脚本());
        for (String path : List.of("/api/task-scan", "/api/task-apply", "/api/task",
                "/api/task-cancel", "/api/download", "/api/mapping", "/api/restore",
                "/api/restore-import", "/api/restore-import-cancel")) {
            assertTrue(js.contains(path), "脚本未调用 " + path);
        }
        // 令牌必须走自定义头，不能拼进 URL
        assertTrue(js.contains("X-Litemask-Token"),
                "脚本应通过自定义头带令牌——放 URL 会进浏览器历史记录");
        assertFalse(js.contains("?jobId=") && js.contains("token="),
                "令牌不得拼进任何 URL");
    }

    /**
     * 服务端实际注册的写接口，脚本都要用到。
     *
     * <p>方向反过来查：接口在服务端存在、脚本却不调用，说明这个功能做了一半。
     * 这条断言在本轮<b>改过两次方向</b>：{@code /api/restore} 原本是 501 占位，
     * 那时正确的断言是「界面上不该有它的入口」——给一个点不动的按钮比没有按钮更糟。
     * 端点真做了之后，同一条断言必须反过来：现在缺了入口就是「做了一半」。
     *
     * <p>第二次改方向是「长任务进度反馈与取消」这一轮：界面改走异步那套
     * （{@code /api/task-scan} 等四个），同步的 {@code /api/scan} 与 {@code /api/apply}
     * 留给脚本与命令行式调用。所以这里查的是异步那四个——
     * 界面必须能报进度、能取消，才不会在长任务上装死。
     *
     * <p>判据的依据是「服务端已交付」这件事本身，而不是脚本里有没有那个字符串。
     */
    @Test
    @DisplayName("已接入的写接口都有界面入口（没有做一半的功能）")
    void 写接口都有入口() {
        String js = 去注释(脚本());
        for (String path : List.of("/api/task-scan", "/api/task-apply", "/api/task",
                "/api/task-cancel", "/api/download", "/api/mapping", "/api/restore",
                "/api/restore-import", "/api/restore-import-cancel")) {
            assertTrue(js.contains(path), path + " 已在服务端交付，界面上却没有入口");
        }
    }

    /**
     * 「补充敏感词」（人工补漏）必须**真的有界面入口**。
     *
     * <p><b>为什么单独一条</b>：它是一条**新的能力**——
     * 让用户能补上规则认不出来的名字与代号，接进引擎、复核清单与映射。
     * 它要是只做了一半（服务端有接口、界面上没有入口），
     * 用户永远发现不了这个功能，那等于没做。
     *
     * <p>三条判据：脚本调了接口、页面有输入框、脚本给按钮接上了事件。
     * 少任何一条，这条能力就是「碰不到」的。
     */
    @Test
    @DisplayName("补充敏感词：接口、输入框、按钮事件三样都在")
    void 补充敏感词有入口() {
        String js = 去注释(脚本());
        String html = 页面();
        assertTrue(js.contains("/api/terms"), "脚本未调用 /api/terms");
        assertTrue(html.contains("id=\"termInput\""), "页面里没有补充敏感词的输入框");
        assertTrue(html.contains("id=\"termAdd\""), "页面里没有「加入并重扫」按钮");
        assertTrue(js.contains("termAdd"), "脚本没有给「加入并重扫」接上事件");
        // 补的词要看得见：用户得知道自己补了什么
        assertTrue(js.contains("userTerms"), "脚本没有把用户补的词显示出来");
    }

    /**
     * 界面必须走异步那条路。
     *
     * <p>同步的 {@code /api/scan} / {@code /api/apply} 在服务端仍然留着
     * （脚本与命令行式调用需要「发过去、拿结果」的一个来回），但**界面不能用它**：
     * 同步接口要等整份文档脱敏完才回，界面在这期间只能显示一个不动的按钮，
     * 用户既看不到进度也放弃不了——那正是这一轮要解决的问题。
     *
     * <p>所以这条断言是双向的：异步那套必须在，界面里也<b>不许</b>再调同步那两个。
     */
    @Test
    @DisplayName("界面走异步任务接口，不走同步的 scan/apply（否则没有进度也没有取消）")
    void 界面走异步() {
        String js = 去注释(脚本());
        assertTrue(js.contains("/api/task-scan"), "界面应通过异步任务接口提交扫描");
        assertTrue(js.contains("/api/task-apply"), "界面应通过异步任务接口提交应用");
        assertFalse(js.contains("/api/scan"),
                "界面不应再调用同步的 /api/scan：那会让界面在长任务上装死");
        assertFalse(js.contains("/api/apply"),
                "界面不应再调用同步的 /api/apply：那会让界面在长任务上装死");
    }

    /**
     * 进度条与取消按钮要真的接上。
     *
     * <p>页面上摆一个进度条而脚本不更新它，用户看到的就是一条永远不动的死条——
     * 比没有进度条更糟，因为它让人以为程序死了。
     *
     * <p><b>断言卡的是「接上了事件」，不是「引用过这个 id」</b>：
     * 脚本里还有别处会引用 {@code cancelBtn}（比如设置它的禁用态），
     * 只查「出现过 {@code $("cancelBtn")}」的话，
     * 把监听器整段删掉也能过——而那正是按钮点了没反应的形状。
     * （这条断言的强度是被对照实验逼出来的：照原样写时，
     * 「删掉取消按钮的监听器」这一条变异<b>没被咬住</b>。）
     */
    @Test
    @DisplayName("进度与取消有入口且接上事件（不接上的进度条是死条）")
    void 进度与取消有入口() {
        String html = 页面();
        String js = 去注释(脚本());
        for (String id : List.of("taskCard", "progressFill", "progressText", "cancelBtn")) {
            assertTrue(html.contains("id=\"" + id + "\""), "缺少进度组件节点 " + id);
        }
        assertTrue(js.contains("$(\"cancelBtn\").addEventListener"),
                "取消按钮在页面上，却没有接上事件");
        assertTrue(js.contains("updateProgress"), "应有更新进度的函数");
        assertTrue(js.contains("pollTask"), "应有轮询任务状态的函数");
        assertTrue(js.contains("/api/task?"), "轮询应走 GET /api/task");
        assertTrue(js.contains("/api/task-cancel"), "取消应走 POST /api/task-cancel");
    }

    /**
     * 「生成脱敏文件」按钮的可用状态必须由「有没有 jobId」决定。
     *
     * <p><b>这条是被一个真实缺陷逼出来的</b>：脚本里 {@code showTask} 把这个按钮禁用了，
     * 而 {@code hideTask} 只在「应用」那一种任务结束时才放开它——
     * 于是<b>扫完之后那个按钮永远是禁用的</b>：界面能扫、不能脱。
     *
     * <p>那几处代码单看都合理，所以服务端几十条单测全绿也没人发现；
     * 真正抓到它的是浏览器端到端（{@code tools/e2e-browser.sh}）。
     * 这里补一条静态断言，让它在快速回归里也能被看见——
     * 但它只是<b>代理判据</b>（字符串匹配），真正的牙仍然是浏览器那条。
     */
    @Test
    @DisplayName("应用按钮的可用状态由 jobId 决定（扫完必须能点「生成脱敏文件」）")
    void 应用按钮由JobId决定() {
        String js = 去注释(脚本());
        assertTrue(js.contains("$(\"applyBtn\").disabled = !state.jobId;"),
                "「生成脱敏文件」按钮的可用状态应由「有没有 jobId」决定："
                        + "否则扫描任务一结束它就可能一直是禁用的（界面能扫、不能脱）");
        int 调用点 = 计数(js, "syncButtons();");
        assertTrue(调用点 >= 2,
                "按钮状态的收口点至少要有两个调用点（任务结束时、复位时），现在只有 " + 调用点 + " 个");
    }

    /**
     * 步骤条必须允许折行。
     *
     * <p>实测：{@code .steps} 是 {@code display:flex} 却没写 {@code flex-wrap}，
     * 三个步骤被撑成一行（527px），窄屏（420px）下整页横向溢出 173px——
     * 步骤条把别的内容都顶没了。
     *
     * <p>这条同样只是代理判据：真正量「有没有横向溢出」的是浏览器端到端，
     * 它把视口压到 420px 再量 {@code scrollWidth - clientWidth}。
     */
    @Test
    @DisplayName("步骤条在窄屏能折行（不折行实测把整页顶宽 173px）")
    void 步骤条能折行() {
        String css = 样式();
        String 块 = css块(css, ".steps {");
        assertFalse(块.isEmpty(), "样式表里找不到 .steps 规则");
        assertTrue(块.contains("flex-wrap"),
                ".steps 必须允许折行：否则三步被撑成一行，窄屏下整页横向溢出");
    }

    /**
     * 位置信息必须真的<b>显示</b>出来。
     *
     * <p><b>为什么这条要紧</b>：服务端一直在回传 {@code locators}，
     * 界面却只显示「出现 N 次」——位置只下发、不显示，对用户就等于没有。
     * 用户拿到清单只知道「这个值出现了 3 次」，仍然不知道 3 次分别在哪；
     * 而清单是用来核对的，核对不了位置的清单等于把判断甩回给用户。
     * PDF 那一侧连位置本身都是这一轮才补上的。
     *
     * <p>这条是<b>代理判据</b>（字符串匹配）：真正验「页码出现在屏幕上」的是
     * 浏览器端到端（{@code tools/e2e-browser.sh}）。
     * 它守的是「渲染位置的那段代码不许被拆掉」——
     * 那段代码曾经整段不存在过，而那时服务端照样在回传位置。
     */
    @Test
    @DisplayName("命中清单把位置显示出来（只下发不显示等于没有）")
    void 位置显示在清单里() {
        String js = 去注释(脚本());
        assertTrue(js.contains("hit.locators"),
                "脚本应读取服务端回传的 locators 并渲染出来");
        // 【口径改了，判据没放松】块 22 之前这里守的是「最多列几条位置」；
        // 块 22 之后是「**默认**显示几条，其余折叠起来、点一下能展开」——
        // 上限守的是**默认显示**的条数，不是「能选到第几处」。
        // 名字跟着实现走（MAX_LOCS_SHOWN → LOCS_COLLAPSED），
        // 守的还是同一件事：默认显示的条数不能没有上限。
        assertTrue(js.contains("LOCS_COLLAPSED"),
                "默认显示的位置条数应当有上限：二十条位置会把一列撑成长文");
        // 样式也要有：位置文字是左对齐的一串字，
        // 跟右对齐的次数挤在同一格里会很难看（而「难看」没人会写进测试）。
        assertFalse(css块(样式(), "table.hits td.num .locs {").isEmpty(),
                "位置文字应当有自己的样式（左对齐、小一号、淡一点）");
    }

    /**
     * 「只保留这一处」必须真的发得出序号，而且走的是<b>另一条路</b>。
     *
     * <p>这是<b>代理判据</b>（字符串匹配）：真正验「勾了以后文件里只留下那一处」的是
     * 浏览器端到端。它守的是三件一旦改坏就<b>静默</b>的事：
     * <ol>
     *   <li><b>occurrence 必须显式写出来</b>。省略它，服务端按「整条不脱敏」处理——
     *       「只保留这一处」就变成了「这个值全部保留」，比用户勾的宽松得多，
     *       而且界面上看不出任何异常。</li>
     *   <li><b>整行与逐处不能共用一个选择器</b>。共用的话，
     *       「已保留 N 个值」会把「保留 1 处出现」也算进去，报出来的数就是假的。</li>
     *   <li><b>逐处的勾选框要有自己的标记</b>，否则没法单独取出来、也没法单独置灰。</li>
     * </ol>
     */
    @Test
    @DisplayName("逐处排除显式带 occurrence，且与整行勾选分开走")
    void 逐处排除带序号() {
        String js = 去注释(脚本());
        // 判据同样落在「逐处提交那一段」上：occurrence 必须出现在**发出去的那一段**里。
        String 逐处提交 = 脚本块(js, "occBoxes().filter");
        assertFalse(逐处提交.isEmpty(),
                "逐处的排除项应当单独成段提交（找不到这段说明写法变了，判据要跟着改）");
        assertTrue(逐处提交.contains("occurrence: Number("),
                "逐处排除必须显式带上 occurrence：省略它等于「整条不脱敏」，比用户勾的宽松");
        assertTrue(逐处提交.contains("dataset.original"),
                "逐处那条路要发原值回去（用的是**成员**的原值，不是归组行拼接出来的那个）");
        assertTrue(js.contains("occ-pick"),
                "逐处的勾选框要有自己的标记，否则没法单独取、也没法单独置灰");
        assertTrue(js.contains("rowBoxes"),
                "整行的勾选框要单独取");
        assertTrue(js.contains("occBoxes"),
                "逐处的勾选框要单独取");
        // 互斥：整行保留与「只保留这一处」是对立的，两种排除项一起发回去，
        // 结果是「整个值都留下了」——比用户勾的宽松，方向还是错的那一边。
        assertTrue(js.contains("disabled"),
                "整行勾上后，逐处的勾选框应当被置灰（两者互斥）");
    }

    /**
     * 「识别模型」这一块必须真的有界面入口。
     *
     * <p><b>为什么单独一条</b>：模型从「命令行装」改成「随程序分发、界面自动准备」
     * 之后，模型这一块就成了<b>产品交付的主线</b>——
     * 用户拿到安装包之后，全程不碰终端就能把模型用起来。
     * 它要是只做了一半（服务端有接口、界面上没有入口），
     * 用户永远卡在「程序说没有模型」这一步，那等于没交付。
     *
     * <p>四条判据：脚本调了接口、页面有状态区与按钮、脚本给按钮接上了事件、
     * 以及<b>未就绪时的常驻提示</b>确实在页面上——
     * 最后这条是红线：没启用识别时，界面不许装作一切正常。
     */
    @Test
    @DisplayName("识别模型：状态区、按钮、事件、以及未就绪时的常驻提示都在")
    void 识别模型有入口() {
        String js = 去注释(脚本());
        String html = 页面();
        // 接口
        for (String path : List.of("/api/model", "/api/model/install",
                "/api/model/import", "/api/model/rollback")) {
            assertTrue(js.contains(path), "脚本未调用 " + path);
        }
        // 页面上的状态区与四个按钮
        for (String id : List.of("modelCard", "modelStateBadge", "modelMessage",
                "modelInstallBtn", "modelImportBtn", "modelUpdateBtn", "modelRollbackBtn",
                "modelFile", "modelError")) {
            assertTrue(html.contains("id=\"" + id + "\""), "缺少识别模型相关节点 " + id);
        }
        // 按钮接上了事件
        for (String id : List.of("modelInstallBtn", "modelUpdateBtn",
                "modelRollbackBtn", "modelImportBtn")) {
            assertTrue(js.contains("$(\"" + id + "\").addEventListener"),
                    id + " 在页面上，却没有接上事件");
        }
        // 红线：未就绪时的常驻提示与「必须由用户明确选择」的那一勾
        assertTrue(html.contains("id=\"nerWarn\""), "缺少未就绪时的常驻提示");
        assertTrue(html.contains("id=\"nerAck\""), "缺少「明确知晓结果需人工处理」的那一勾");
        assertTrue(html.contains("当前未启用人名与机构名识别，结果需要人工处理"),
                "常驻提示必须用这句用户能看懂的话");
        // 未就绪时不能直接放行扫描：必须先勾那一行
        assertTrue(js.contains("modelGateSatisfied"), "脚本应当有一处统一判断「模型那一关过没过」");
        assertTrue(js.contains("modelAcknowledged"), "脚本应当记住用户的明确选择");
        // 「安装」与「重试」是**两个动作**：同一个按钮在两种情形下该说不同的话。
        // 标签不对，用户看完「装坏了，请重试」却只看到一个写着「安装」的按钮，
        // 下一步就不明确了。
        assertTrue(js.contains("\"BROKEN\"") && js.contains("\"UPDATE_FAILED\""),
                "脚本应当区分「坏了/更新失败」与「还没装」两种情形");
        assertTrue(js.contains("重试"), "坏了/更新失败时按钮应当改说「重试」");
        assertTrue(js.contains("安装识别模型"), "还没装时按钮应当说「安装识别模型」");
    }

    /**
     * 脚本的括号必须配平。
     *
     * <p><b>为什么要有这条</b>：浏览器那半边没有自动化测试（明确的验证缺口），
     * 唯一守着它的是这里的静态断言。而静态断言全是<b>字符串匹配</b>——
     * 它们看不见「这段代码根本不是合法的 JavaScript」。
     * 一旦脚本少写一个右括号，浏览器会把整个文件丢掉，界面变成一片空白，
     * 而服务端那六十多条单测<b>全绿</b>：没有一个人会收到任何信号。
     *
     * <p><b>它不是 JavaScript 解析器</b>，只做一件事：剔掉注释与字符串之后，
     * 看三对括号配不配平。它抓的是最常见也最难发现的那一类破坏。
     * 更细的语法错误（少个逗号、写成 {@code const x = ;}）仍然抓不到——
     * 要真解析就得引一个 JS 引擎，那会给核心包加进几十 MB 的依赖，
     * 与「核心包 ≤90MB」直接冲突。这条抓不到的部分写进了已知限制。
     */
    @Test
    @DisplayName("脚本括号配平（少一个右括号 → 浏览器丢掉整个脚本 → 界面空白而服务端全绿）")
    void 脚本括号配平() {
        String js = 脚本();
        String 问题 = 括号问题(js);
        assertNull(问题, "脚本括号不配平：" + 问题);
    }

    /**
     * 括号配平检查（只做括号，刻意不做完整语法分析）。
     *
     * @return 有问题时的说明；没问题返回 null
     */
    private static String 括号问题(String js) {
        java.util.ArrayDeque<Character> 栈 = new java.util.ArrayDeque<>();
        int i = 0;
        int n = js.length();
        while (i < n) {
            char c = js.charAt(i);
            char 下一个 = (i + 1 < n) ? js.charAt(i + 1) : '\0';
            // 行注释
            if (c == '/' && 下一个 == '/') {
                while (i < n && js.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // 块注释
            if (c == '/' && 下一个 == '*') {
                i += 2;
                while (i + 1 < n && !(js.charAt(i) == '*' && js.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            // 字符串（单双引号同理）
            if (c == '"' || c == '\'') {
                char 引号 = c;
                i++;
                while (i < n) {
                    char d = js.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == 引号) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            // 正则字面量：按「前一个非空白字符是 ( , = : [ ! & | ? 之一」来认。
            // 认错的后果是括号数对不上（误报），所以这条只在
            // 「除法两边是标识符/数字」时不认——那是除法，不是正则。
            if (c == '/' && 是正则开头(js, i)) {
                i++;
                boolean 在字符类 = false;
                while (i < n) {
                    char d = js.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == '[') {
                        在字符类 = true;
                    } else if (d == ']') {
                        在字符类 = false;
                    } else if (d == '/' && !在字符类) {
                        i++;
                        break;
                    } else if (d == '\n') {
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                栈.addLast(c);
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                Character 开 = 栈.pollLast();
                char 要 = c == ')' ? '(' : (c == ']' ? '[' : '{');
                if (开 == null || 开 != 要) {
                    return "第 " + i + " 个字符处不配平：遇到 " + c
                            + "，栈顶是 " + (开 == null ? "空" : String.valueOf(开));
                }
                i++;
                continue;
            }
            i++;
        }
        if (!栈.isEmpty()) {
            return "还有 " + 栈.size() + " 个括号没闭合：" + 栈;
        }
        return null;
    }

    /** 这个斜杠是正则字面量的开头吗？ */
    private static boolean 是正则开头(String js, int i) {
        int j = i - 1;
        while (j >= 0 && Character.isWhitespace(js.charAt(j))) {
            j--;
        }
        if (j < 0) {
            return true;
        }
        char 前 = js.charAt(j);
        return "(,=:[!&|?{};".indexOf(前) >= 0;
    }

    /**
     * 总量未知时的不确定态样式必须存在。
     *
     * <p>各格式的文本单元总数事先都算不出来，{@code total} 恒为 -1，
     * 所以界面上跑的是<b>不确定态</b>那条路。如果只写了确定态的样式，
     * 进度条会永远停在 0% 且纹丝不动——用户会以为程序卡死了。
     */
    @Test
    @DisplayName("不确定态进度条有样式（总量恒为 -1，只写确定态就成了一根死条）")
    void 不确定态有样式() {
        String css = 样式();
        String js = 去注释(脚本());
        assertTrue(css.contains(".progress"), "应有进度条样式");
        // 断言的是**类选择器本身**，不是「文件里出现过 indeterminate 这几个字母」——
        // 后者会被 `.progress-fill.indeterminate-x` 这种改名的变异体蒙混过关。
        assertTrue(css.contains(".progress-fill.indeterminate"),
                "应有不确定态样式：各格式的单元总数事先算不出来，total 恒为 -1");
        // 而且界面加的类名必须与样式里的选择器**对得上**：
        // 类名对不上，那段动画根本不生效，进度条会是一根不动的死条。
        assertTrue(js.contains("progress-fill indeterminate"),
                "界面应给进度条加上不确定态类");
    }

    /**
     * 进度不许伪造百分比。
     *
     * <p>只有服务端报了真实总量（{@code total > 0}）才画百分比；
     * 总量未知时显示「已处理 N 个文本单元」。
     * 拿一个估算值糊出「47%」比说「不知道」更糟——用户会以为它可信。
     */
    @Test
    @DisplayName("总量未知时不画百分比（估算出来的百分比比「不知道」更糟）")
    void 不伪造百分比() {
        String js = 去注释(脚本());
        assertTrue(js.contains("t.total > 0"),
                "只有服务端报了真实总量才画百分比");
        // 总量未知的那条路必须切到不确定态，而不是把 done 当分子凑一个百分比出来。
        assertTrue(js.contains("indeterminate"),
                "总量未知时应切到不确定态，而不是硬凑一个百分比");
    }

    /**
     * 映射是还原的唯一凭据，界面上必须把这句话说出来。
     *
     * <p>脱敏件里只有令牌、没有原值。用户若以为「文件还在就还能还原」，
     * 丢了映射之后那份文件就<b>永久</b>不可逆，而界面上看不出任何异常。
     */
    @Test
    @DisplayName("界面明说「映射是还原的唯一凭据」，且有独立的映射下载入口")
    void 映射提示与入口() {
        String html = 页面();
        String js = 去注释(脚本());
        assertTrue(html.contains("id=\"mappingBtn\""), "应有独立的映射下载按钮");
        assertTrue(html.contains("id=\"keepNote\""), "应有承载「映射是唯一凭据」的提示");
        assertTrue(html.contains("唯一凭据"),
                "提示必须说清映射丢了就还原不回来: " + html);
        // 按钮必须**真的接上**：页面里摆个按钮而脚本不监听它，
        // 用户点下去毫无反应——这正是「做了一半」的形状。
        assertTrue(js.contains("$(\"mappingBtn\")"),
                "映射下载按钮在页面上，却没有接上事件");
        assertTrue(js.contains("$(\"restoreBtn\")"),
                "还原按钮在页面上，却没有接上事件");
        assertTrue(js.contains("state.mappingName"),
                "映射文件名应由服务端给出并存进 state，而不是脚本自己拼");
    }

    /**
     * 映射件口令的<b>接线</b>：输入框在页面上、事件接上了、口令随应用请求发出去了。
     *
     * <p><b>为什么要有这一条</b>：口令这条链有三处可以「看起来做了」——
     * 输入框摆在页面上、脚本里有个变量、请求体里有个字段。
     * 少接一处，用户填的口令就<b>静默地不生效</b>：界面照样显示「已加密」，
     * 而文件里全是原值。这类缺陷在服务端单测里<b>完全看不见</b>
     * （服务端永远是收到什么口令就封什么）。
     *
     * <p>「留空就不发字段」这条语义也在这里守着：服务端把「字段缺失」当成
     * 「不加密」，把「字段存在但为空串」当成错误。界面若把空串也发过去，
     * 用户留空就会得到一句莫名其妙的失败。
     */
    @Test
    @DisplayName("界面有映射件口令输入框，口令随应用请求发出去，且用完就清掉")
    void 口令入口接线() {
        String html = 页面();
        String js = 去注释(脚本());
        assertTrue(html.contains("id=\"mapPass\""), "应有映射件口令输入框");
        assertTrue(html.contains("type=\"password\""),
                "口令框必须是 password 类型：明文显示等于把口令摆在屏幕上");
        assertTrue(js.contains("$(\"mapPass\")"), "口令框在页面上，却没有接上");
        assertTrue(js.contains("mapPassphrase"), "口令必须随应用请求发出去");
        assertTrue(js.contains("if (passphrase)"),
                "只有填了口令才把字段放进请求体：留空时字段缺失，服务端才认作「不加密」");
        assertTrue(html.contains("id=\"sealedNote\""), "应有「映射件已加密」的提示位");
        assertTrue(js.contains("mappingSealed"),
                "界面必须按服务端说的「封没封」来显示提示，而不是自己猜");
        assertTrue(js.contains("passInput.value = \"\""),
                "口令用完要从界面上清掉：留着会被下一个文件无声地沿用");
    }

    /**
     * 还原的处数要显示出来。
     *
     * <p>还原一份没被脱过的东西，文件会<b>一模一样</b>。用户看到「没变化」会以为还原没生效，
     * 而事实是「这份文档本来就没有可还原的内容」——这两件事必须能被区分开。
     */
    @Test
    @DisplayName("还原处数要读出来并显示（响应头 X-Litemask-Restored）")
    void 还原处数要显示() {
        String js = 去注释(脚本());
        assertTrue(js.contains("X-Litemask-Restored"),
                "应从响应头读出还原了几处");
        assertTrue(js.contains("已还原 "),
                "界面上要显示还原处数");
    }

    @Test
    @DisplayName("重启后独立还原：两个文件+口令+手动按钮的接线完整")
    void 独立还原有完整入口() {
        String html = 页面();
        String js = 去注释(脚本());
        for (String id : List.of("restoreImportCard", "restoreProductFile", "restoreMappingFile",
                "restorePass", "restoreImportBtn", "restoreImportCancelBtn",
                "restoreImportStatus", "restoreImportError")) {
            assertTrue(html.contains("id=\"" + id + "\""), "独立还原缺少 " + id);
        }
        assertTrue(js.contains("fetch(\"/api/restore-import\""), "按钮必须调独立还原端点");
        assertTrue(js.contains("application/octet-stream"), "应使用有界二进制信封");
        assertTrue(js.contains("fetch(\"/api/restore-import-cancel\""),
                "同步还原必须能用另一条 HTTP 线程请求协作取消");
        assertTrue(js.contains("new AbortController()") && js.contains("signal: op.controller.signal"),
                "取消必须中止本次页面持有的原上传，不能只给服务端打取消标记");
        assertTrue(js.contains("addEventListener(\"click\""),
                "选文件时不得自动上传，只有点按钮才发请求");
        assertTrue(js.contains("restorePass\").value = \"\""), "口令用完应从输入框清掉");
        assertTrue(html.contains("重启程序后") && html.contains("还原已保存的脱敏文件"),
                "加密映射的完成提示应指向现有 GUI 还原入口");
        assertFalse(html.contains("restore --map"),
                "普通用户已有 GUI 还原，不应再被引导去命令行");
    }

    @Test
    @DisplayName("GUI 退出有保存提醒、下载防护、取消可继续和成功停轮询")
    void guiShutdownIsGuardedAndTruthful() {
        String html = 页面();
        String js = 去注释(脚本());
        for (String id : List.of("shutdownCard", "shutdownBtn", "shutdownStatus", "shutdownError")) {
            assertTrue(html.contains("id=\"" + id + "\""), "GUI 退出缺少 " + id);
        }
        assertTrue(html.contains("脱敏文件和映射文件都已保存"),
                "退出前必须明确提醒同时保存成品与映射");
        assertTrue(js.contains("state.pendingDownloads - activeImportUpload > 0"),
                "普通文件正在下载时必须阻止退出；本页持有的还原上传应走取消后退出");
        assertTrue(js.contains("cancelRestoreImport(state.restoreImportOperation, true)"),
                "退出时应中止本页持有的独立还原上传并通知服务端取消");
        assertTrue(js.contains("window.confirm"), "退出必须二次确认");
        assertTrue(js.contains("已取消退出，本机服务仍可继续使用"),
                "用户取消后应明说服务仍可用");
        assertTrue(js.contains("fetch(\"/api/shutdown\""), "退出按钮必须调受保护的停服端点");
        assertTrue(js.contains("stopAllPolling()"), "服务受理退出后必须停止任务与模型轮询");
        assertTrue(js.contains("本机服务正在停止"),
                "响应只能说正在停止，不得提前宣称已停止");
    }

    @Test
    @DisplayName("下载不用 <a download> 直连（导航请求带不上令牌自定义头）")
    void 下载走fetch() {
        String js = 脚本();
        assertTrue(js.contains("URL.createObjectURL"), "下载应走 fetch + Blob 才能带上令牌头");
        assertTrue(js.contains("revokeObjectURL"), "用完应立刻 revokeObjectURL，别把文件字节一直挂在页面上");
        assertFalse(页面().contains("downloadLink"),
                "已改成 button + fetch，页面里不该再有指向下载接口的 <a>");
    }

    // ------------------------------------------------------------ 安全纪律

    @Test
    @DisplayName("全程不碰 innerHTML（服务端来的文本一律 textContent）")
    void 不用innerHtml() {
        String js = 去注释(脚本());
        assertFalse(js.contains("innerHTML"),
                "出现了 innerHTML：用户文件名与文档原值都是攻击者可控的字符串，"
                        + "拼进 innerHTML 就是存储型 XSS");
        assertFalse(js.contains("outerHTML"), "出现了 outerHTML，同样是拼接注入面");
        assertFalse(js.contains("document.write"), "出现了 document.write");
        assertTrue(js.contains("textContent"), "应能看到 textContent 的用法");
    }

    /**
     * 去掉块注释与行注释，只留真正会执行的代码。
     *
     * <p>不这么做的话，「文件头注释里提了一句 innerHTML」就会让测试变红，
     * 而那条注释恰恰是在讲「不要用 innerHTML」。为了让断言盯住代码而不是注释，
     * 这里先把注释剥掉——也免得后来人为解释一句就不得不改这条测试。
     */
    static String 去注释(String src) {
        return src
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//[^\\n]*", " ");
    }

    @Test
    @DisplayName("文件名与原值都放进 dataset 而不是拼进 HTML 属性")
    void 用户数据走dataset() {
        String js = 脚本();
        assertTrue(js.contains("box.dataset.members"),
                "命中成员应放 dataset（归组后一行可能代表多个值，成员列表是唯一完整的载体）");
        assertTrue(js.contains("JSON.stringify(hit.members"),
                "成员应以 JSON 放进 dataset，值仍由属性赋值，不进 HTML 字符串");
        // setAttribute 只用于我们自己的固定属性名，值仍由属性赋值，不进 HTML 字符串
        assertFalse(js.contains("setAttribute(\"on"),
                "出现了内联事件属性设置");
    }

    /**
     * 归组行勾选后必须<b>按成员逐个</b>发排除项。
     *
     * <p>归组出来的一行代表 N 个值（一个标识符被切成 N 段）。只发一条的话，
     * 被勾的那几段里只有一段被保留，其余照常被脱——用户勾了一行、
     * 看到文件里还留着其中一段的原文，界面上却没有任何异常提示。
     */
    @Test
    @DisplayName("归组行勾选后按成员逐个发排除项（少发一个，那一处照样会被脱掉）")
    void 归组行展开成多个排除项() {
        String js = 去注释(脚本());
        assertTrue(js.contains("JSON.parse(b.dataset.members"),
                "应从 dataset 读回这一行的成员");
        assertTrue(js.contains("members.forEach"),
                "应逐个成员展开成多条排除项");
        assertTrue(js.contains("excluded.push"),
                "每个成员都要进排除项数组");
        // 「一行发一条」的老写法必须已经没有了：归组行的 original 是拼接出来的，
        // 它根本不在映射里，发过去会被服务端判成「对不上」而整份拒绝。
        //
        // **判据落在「整行提交那一段」上**，不是整份文件：
        // 逐处排除那一段也会读 dataset（它要的是**成员**的原值，不是这一行的），
        // 在整份文件里搜子串分不清是哪一段在用——本轮就被自己人误伤过一回。
        String 整行提交 = 脚本块(js, "rowBoxes().filter");
        assertFalse(整行提交.isEmpty(),
                "整行的排除项应当单独成段提交（找不到这段说明写法变了，判据要跟着改）");
        assertTrue(整行提交.contains("dataset.members"),
                "整行那条路必须按成员展开");
        assertFalse(整行提交.contains("dataset.original"),
                "整行的排除项不能再按「一行的原值」发——归组行的原值是拼接出来的，不在映射里");
    }

    @Test
    @DisplayName("文件名走百分号编码（HTTP 头是 ISO-8859-1，中文直发会变问号）")
    void 文件名百分号编码() {
        String js = 脚本();
        assertTrue(js.contains("TextEncoder"), "应先用 TextEncoder 取 UTF-8 字节");
        assertTrue(js.contains("encodeName"), "应有 encodeName");
        assertTrue(js.contains("X-Filename"), "文件名应放在 X-Filename 头里");
    }

    // ------------------------------------------------------------ 样式与文案

    @Test
    @DisplayName("提示语与 notes 容器都有配套样式（否则像脚注一样被忽略）")
    void 提示语有样式() {
        String css = 样式();
        assertTrue(css.contains(".notes"), "notes 容器应有样式：它是「必须让用户看到的话」");
        assertTrue(css.contains(".error"), "错误提示应有样式");
        assertTrue(样式().contains(".steps"), "流程步骤应有样式");
        assertTrue(页面().contains("id=\"notes\"") && 页面().contains("id=\"applyError\""),
                "页面里应有这两个容器");
    }

    @Test
    @DisplayName("M2-a 的占位文案已撤掉（不能一边接上流程一边说「尚未接入」）")
    void 占位文案已撤() {
        String html = 页面();
        assertFalse(html.contains("尚未接入"), "脱敏流程已接入，不该还留着 M2-a 的占位说明");
        assertFalse(html.contains("里程碑 M2-b"), "过时文案");
    }

    @Test
    @DisplayName("令牌注入占位符仍在（服务端靠它把当次令牌写进页面）")
    void 令牌占位符仍在() {
        assertEquals("__LITEMASK_TOKEN__",
                页面().replaceAll("(?s).*<meta name=\"litemask-token\" content=\"([^\"]*)\".*", "$1"),
                "占位符被改掉了，服务端就找不到注入点");
        assertTrue(脚本().contains("litemask-token"), "脚本应从该 meta 读令牌");
    }
}
