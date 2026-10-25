package com.litemask.server;

import com.litemask.core.Exclusions;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import com.litemask.core.ServiceIdentity;
import com.litemask.server.LocalServer.Http;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 接口。
 *
 * <p>M2-a 只铺「服务能起来、能被正确地挡在外面、规则可查」这三件事；
 * 脱敏相关的 {@code scan / apply / restore} 在 M2-b 接上。
 *
 * <p>接口约定里有两个刻意的地方：
 * <ul>
 *   <li><b>写操作一律带 {@code X-Litemask-Token}</b>。自定义头会触发 CORS 预检，
 *       而我们不答预检——所以外部网页<b>根本无法</b>伪造出这样一个请求，
 *       令牌是在此之上的第二道，不是唯一一道。</li>
 *   <li><b>上传是 {@code application/octet-stream} + {@code X-Filename} 头</b>，
 *       不是 multipart。见 {@link LocalServer} 的类注释。</li>
 * </ul>
 */
public final class ApiHandlers {

    /** 令牌在请求头里的名字。 */
    public static final String TOKEN_HEADER = "X-Litemask-Token";

    /** 文件名在请求头里的名字。 */
    public static final String FILENAME_HEADER = "X-Filename";

    private static final String INDEX_RESOURCE = "/web/index.html";

    private ApiHandlers() {
    }

    /** 装配全部路由。 */
    public static void register(LocalServer server, Workspace workspace, List<Rule> rules) {
        register(server, workspace, rules, List.of());
    }

    /**
     * 装配路由，并指定<b>任务时限</b>（毫秒；<= 0 表示不设时限）。
     *
     * <p>时限是**实例参数**而不是写死的常量：它得能被用户调（有的文档就是很大），
     * 也得能被端到端在几秒内造出一次真实的超时。
     */
    public static void register(LocalServer server, Workspace workspace, List<Rule> rules,
                                List<com.litemask.core.EntitySource> sources,
                                long taskTimeoutMs) {
        JobStore jobs = new JobStore(workspace, rules, sources);
        TaskRunner tasks = new TaskRunner(jobs, taskTimeoutMs);
        server.onClose(tasks);
        registerRoutes(server, workspace, rules, sources, jobs, tasks, null,
                com.litemask.core.ExclusiveGate.NONE);
    }

    /**
     * 装配路由，并挂上 AI 来源。
     *
     * <p>{@code sources} 为空就是「纯正则模式」，这与「NER 一个实体都没识别到」
     * 在响应里长得不一样——后者会出现在 {@code notes} 里说明用的是哪个来源。
     */
    public static void register(LocalServer server, Workspace workspace, List<Rule> rules,
                                List<com.litemask.core.EntitySource> sources) {
        register(server, workspace, rules, sources, TaskRunner.DEFAULT_TIMEOUT_MS);
    }

    /**
     * 装配路由：带模型管理与独占闸门。
     *
     * <p><b>为什么模型管理要挂在服务这一层</b>：用户点「安装模型」是一次
     * <b>真实的动作</b>——验签、换装、加载、报告结果。
     * 它必须走底层服务（{@link com.litemask.ner.ModelManager}），
     * <b>不能</b>由界面去拼一条 shell 命令再解析它的输出：
     * 那条路要么需要用户有终端，要么会把「失败原因」退化成一行看不懂的报错。
     *
     * @param models 模型管理器；为 {@code null} 时不装模型相关的路由
     * @param gate   扫描期间的独占闸门（换模型时要拿写锁）
     */
    public static void register(LocalServer server, Workspace workspace, List<Rule> rules,
                                List<com.litemask.core.EntitySource> sources,
                                com.litemask.core.ExclusiveGate gate,
                                com.litemask.ner.ModelManager models) {
        JobStore jobs = new JobStore(workspace, rules, sources, gate);
        TaskRunner tasks = new TaskRunner(jobs, TaskRunner.DEFAULT_TIMEOUT_MS);
        server.onClose(tasks);
        registerRoutes(server, workspace, rules, sources, jobs, tasks, models, gate);
    }

    /** 装配路由（共用实现）。 */
    private static void registerRoutes(LocalServer server, Workspace workspace, List<Rule> rules,
                                       List<com.litemask.core.EntitySource> sources,
                                       JobStore jobs, TaskRunner tasks,
                                       com.litemask.ner.ModelManager models,
                                       com.litemask.core.ExclusiveGate gate) {
        server.route("GET", "/", false, ex -> serveIndex(ex, server));
        server.route("GET", "/app.js", false, ex -> serveAsset(ex, "/web/app.js", "application/javascript; charset=utf-8"));
        server.route("GET", "/app.css", false, ex -> serveAsset(ex, "/web/app.css", "text/css; charset=utf-8"));
        server.route("GET", "/api/health", false, ex -> health(ex, server, workspace, rules, jobs, tasks));
        server.route("GET", "/api/rules", false, ex -> ruleList(ex, rules));
        server.route("POST", "/api/scan", true, ex -> scan(ex, jobs));
        server.route("POST", "/api/apply", true, ex -> apply(ex, jobs));
        // 补充敏感词：用户在复核界面上补几个「规则认不出来」的词，
        // 服务端把它们接进引擎**重扫**一遍 —— 补的词会出现在命中清单里，
        // 用户能看见、能排除、能进映射。
        server.route("POST", "/api/terms", true, ex -> terms(ex, jobs));
        server.route("GET", "/api/download", true, ex -> download(ex, jobs));
        // 映射件与还原。两者都要令牌：映射里全是原值，还原件里也是。
        server.route("GET", "/api/mapping", true, ex -> mapping(ex, jobs));
        server.route("POST", "/api/restore", true, ex -> restore(ex, jobs));
        // 异步任务。长文档不该把界面卡在「上传中」——它要能报进度、能放弃。
        // 查任务也要令牌：任务结果里是命中清单，清单里全是原值。
        server.route("POST", "/api/task-scan", true, ex -> taskScan(ex, tasks));
        server.route("POST", "/api/task-apply", true, ex -> taskApply(ex, tasks));
        server.route("GET", "/api/task", true, ex -> taskStatus(ex, tasks));
        server.route("POST", "/api/task-cancel", true, ex -> taskCancel(ex, tasks));
        // ---- 模型管理 ----
        // 状态查询不设令牌：里面没有任何用户数据（版本号、能力、状态），
        // 而界面在模型还没准备好时也要能问出「现在到哪一步了」。
        // 真正会动磁盘的动作一律要令牌。
        if (models != null) {
            server.route("GET", "/api/model", false, ex -> modelStatus(ex, models));
            server.route("POST", "/api/model/install", true, ex -> modelInstall(ex, models));
            server.route("POST", "/api/model/import", true, ex -> modelImport(ex, models));
            server.route("POST", "/api/model/rollback", true, ex -> modelRollback(ex, models));
        }
    }

    // ------------------------------------------------------------------ 模型管理

    /**
     * 模型状态。
     *
     * <p><b>刻意不返回文件路径</b>：目标用户是普通人，
     * {@code ~/.litemask/models/ner} 对他没有意义，只会让他以为自己得管这个目录。
     * 技术细节进日志文件，不进界面。
     */
    private static void modelStatus(HttpExchange ex, com.litemask.ner.ModelManager models)
            throws IOException {
        Http.json(ex, 200, modelJson(models.status()));
    }

    /** 安装随程序分发的模型。 */
    private static void modelInstall(HttpExchange ex, com.litemask.ner.ModelManager models)
            throws IOException {
        try {
            Http.json(ex, 200, modelJson(models.installBundled()));
        } catch (RuntimeException e) {
            Http.json(ex, 500, Map.of("ok", false, "error", "安装模型时出错：" + e.getMessage()));
        }
    }

    /**
     * 导入一个离线模型包（用户选的那一个文件）。
     *
     * <p><b>上传是 {@code application/octet-stream} + {@code X-Filename}</b>，
     * 与文档上传同一套约定——不引 multipart 解析器就是少一份攻击面。
     */
    private static void modelImport(HttpExchange ex, com.litemask.ner.ModelManager models)
            throws IOException {
        Path tmp = null;
        try {
            JobStore.requireContentType(ex, "application/octet-stream");
            String name = JobStore.clientFileName(ex);
            tmp = Files.createTempFile("litemask-model-upload-", ".zip");
            try (InputStream in = ex.getRequestBody();
                 java.io.OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            Http.json(ex, 200, modelJson(models.importOffline(tmp)));
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        } catch (IOException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", "这个模型包装不上：" + e.getMessage()));
        } finally {
            // 上传件是用户给的文件（可能很大）：用完即删，不留在盘上。
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 删不掉不算失败
                }
            }
        }
    }

    /** 回退到上一个模型版本。 */
    private static void modelRollback(HttpExchange ex, com.litemask.ner.ModelManager models)
            throws IOException {
        try {
            Http.json(ex, 200, modelJson(models.rollback()));
        } catch (RuntimeException e) {
            Http.json(ex, 500, Map.of("ok", false, "error", "回退时出错：" + e.getMessage()));
        }
    }

    /**
     * 把模型状态摆成界面能直接用的形状。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：
     * 后者不接受 null 值，而「当前没有模型版本」正是 null。
     */
    private static Map<String, Object> modelJson(com.litemask.ner.ModelManager.Status s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", s.usable());
        m.put("state", s.state().name());
        m.put("stateLabel", s.state().label());
        m.put("usable", s.usable());
        m.put("modelId", s.modelId());
        m.put("personName", s.personName());
        m.put("organizationName", s.organizationName());
        m.put("message", s.message());
        m.put("nextStep", s.nextStep());
        m.put("actions", s.actions());
        m.put("bundledAvailable", s.bundledAvailable());
        m.put("bundledModelId", s.bundledModelId());
        m.put("canRollback", s.canRollback());
        m.put("failure", s.failure());
        return m;
    }

    /**
     * 上传并扫描：请求体就是文件本身，文件名在 {@code X-Filename} 头里。
     *
     * <p>刻意<b>不</b>用 multipart。引一个 multipart 解析器就是多一份攻击面，
     * 而「拖一个文件进来」并不需要它——浏览器直接
     * {@code fetch(url, {method:'POST', body: file, headers:{...}})} 就够了。
     */
    private static void scan(HttpExchange ex, JobStore jobs) throws IOException {
        try {
            JobStore.Job job = jobs.scan(ex);
            Http.json(ex, 200, JobStore.describe(job, job.scan, "scanned", 0));
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /**
     * 用户补充敏感词，然后重扫。
     *
     * <p><b>为什么要带令牌</b>：请求体里有用户自己写的敏感词，
     * 那也是敏感信息 —— 与「下载映射」同级（映射里全是原值）。
     */
    private static void terms(HttpExchange ex, JobStore jobs) throws IOException {
        try {
            JobStore.requireContentType(ex, "application/json");
            byte[] body = Http.readBody(ex, 1L * 1024 * 1024);
            JobStore.TermsRequest req = Json.read(body, JobStore.TermsRequest.class);
            JobStore.Job job = jobs.addUserTerms(req.jobId(), req.terms());
            // 重扫之后，命中清单变了 → 界面要整体重画，
            // 所以回的是**完整的 scan 形状**（与 /api/scan 同一个 describe）。
            Http.json(ex, 200, JobStore.describe(job, job.scan, "scanned", 0));
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", "请求体不是合法的 JSON: " + e.getOriginalMessage()));
        }
    }

    /** 应用排除项并产出成品。 */    private static void apply(HttpExchange ex, JobStore jobs) throws IOException {
        try {
            // 必须在 try 内：类型不对是请求本身的问题（400），
            // 漏出去会被 dispatch 的兜底 catch 接成 500——那会把「你发错了」
            // 说成「服务坏了」，用户会去重启程序而不是改请求。
            JobStore.requireContentType(ex, "application/json");
            byte[] body = Http.readBody(ex, 1L * 1024 * 1024);
            JobStore.ApplyRequest req = Json.read(body, JobStore.ApplyRequest.class);
            Exclusions exclusions = Exclusions.ofList(req.excluded());
            char[] passphrase = passphraseOf(req.mapPassphrase());
            try {
                JobStore.Job job = jobs.apply(req.jobId(), exclusions, passphrase);
                int excluded = exclusions.size();
                Http.json(ex, 200, JobStore.describe(job, job.applied, "applied", excluded));
            } finally {
                // 口令用完即擦。请求体里那个 String 擦不掉（String 不可变），
                // 那条限制写在阶段文档里；char[] 这一份至少不留。
                com.litemask.core.SecretMap.wipe(passphrase);
            }
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", "请求体不是合法的 JSON: " + e.getOriginalMessage()));
        }
    }

    /**
     * 把请求里的口令字段变成 {@code char[]}。
     *
     * <p><b>三种情形语义不同，不能混为一谈</b>：
     * <ul>
     *   <li><b>字段缺失</b>（{@code null}）→ 没给口令 → 映射件写明文。
     *       老版本客户端发来的报文就是这样，行为必须与从前一致；</li>
     *   <li><b>字段存在但是空串</b> → 用户明确要求加密却没给出口令 → <b>报错</b>。
     *       静默改成明文是 fail-open：用户以为加密了，文件里却全是原值，
     *       而界面上看不出任何异常；</li>
     *   <li><b>非空</b> → 正常口令。</li>
     * </ul>
     *
     * <p>空串判成错误而不是「不加密」，是因为这两种请求在<b>报文上可区分</b>：
     * 界面留空时整个字段都不发（走第一条），而发一个空串是另一回事。
     */
    private static char[] passphraseOf(String raw) throws JobStore.BadRequestException {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            throw new JobStore.BadRequestException(
                    "映射件口令是空的：请填一个口令，或把这个字段整个去掉（表示不加密）");
        }
        return raw.toCharArray();
    }

    /**
     * 下载成品。
     *
     * <p>按 {@code jobId} 取，不接受调用方给的文件名——文件名是我们自己根据上传时的名字
     * 生成的，让客户端指定下载文件名就等于给了一条往任意路径写内容的路。
     */
    private static void download(HttpExchange ex, JobStore jobs) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            JobStore.Job job = jobs.job(query.get("jobId"));
            byte[] content = jobs.outputBytes(job);
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + urlEncode(job.downloadName()));
            Http.send(ex, 200, contentTypeOf(job), content);
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /**
     * 下载映射件。
     *
     * <p><b>为什么映射也要走令牌</b>：映射里全是原值。它与 {@code /api/download}
     * 是同一条纪律——「这个文件是敏感数据，取它要证明你是本机页面」。
     *
     * <p>文件名由服务端定（{@link JobStore.Job#mappingDownloadName()}），
     * 与 {@code /api/download} 同理：让客户端指定下载文件名等于给了一条往任意路径写内容的路。
     */
    private static void mapping(HttpExchange ex, JobStore jobs) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            JobStore.Job job = jobs.job(query.get("jobId"));
            byte[] content = jobs.mappingBytes(job);
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + urlEncode(job.mappingDownloadName()));
            Http.send(ex, 200, "application/json; charset=utf-8", content);
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /**
     * 还原成品件并回传。
     *
     * <p><b>响应体是文件而不是 JSON</b>：用户要的就是那份还原出来的文档，
     * 让他再走一次「先拿个 id、再下载」反而多一道会断的环节。
     * 还原处数与必须知道的话放在响应头里：
     * <ul>
     *   <li>{@code X-Litemask-Restored} —— 实际替换了几处；</li>
     *   <li>{@code X-Litemask-Notice} —— 百分号编码的提醒（如「还原的是文本内容，不是文件字节」）。</li>
     * </ul>
     * 头里放不下正文，是因为正文已经是文件了；而「还原了 0 处」这种事实
     * 必须能被调用方读到，否则它会拿着一份没变的文件以为还原成功。
     */
    private static void restore(HttpExchange ex, JobStore jobs) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            JobStore.Job job = jobs.job(query.get("jobId"));
            JobStore.Restored r = jobs.restoreBytes(job);
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + urlEncode(job.restoreDownloadName()));
            ex.getResponseHeaders().set("X-Litemask-Restored", String.valueOf(r.count()));
            ex.getResponseHeaders().set("X-Litemask-Notice", urlEncode(String.join(" | ", r.warnings())));
            Http.send(ex, 200, contentTypeOf(job), r.bytes());
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(decodeUrl(pair.substring(0, eq)), decodeUrl(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String decodeUrl(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法转义：原样返回，让下游的格式校验去拒，而不是在这里抛一个看不懂的异常
            return s;
        }
    }

    private static String urlEncode(String s) {
        // filename* 用的是 RFC 5987：只允许 token 字符，中文与空格一律百分号编码。
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static String contentTypeOf(JobStore.Job job) {
        return switch (job.format) {
            case PDF -> "application/pdf";
            case OFD -> "application/ofd";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case TXT -> "text/plain; charset=utf-8";
        };
    }

    /**
     * 首页。
     *
     * <p>令牌在这一步注入页面。这是它交给浏览器的唯一途径——
     * 不放在 URL 里（会进历史记录、也可能从 Referer 泄出去）。
     */
    private static void serveIndex(HttpExchange ex, LocalServer server) throws IOException {
        String html = readResource(INDEX_RESOURCE);
        if (html == null) {
            Http.text(ex, 500, "首页资源缺失: " + INDEX_RESOURCE);
            return;
        }
        String token = server.policy().token();
        // 令牌是 48 位十六进制，不含引号反斜杠等任何需要转义的字符。
        // 即便将来换成了别的字符集，这里也走的是文本替换而非脚本拼接，
        // 不会出现「令牌内容被当成 HTML 解析」的情况。
        String page = html.replace("__LITEMASK_TOKEN__", token);
        LocalServer.Http.send(ex, 200, "text/html; charset=utf-8",
                page.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 静态资源。
     *
     * <p>路径是<b>常量</b>，不是从请求里来的——白名单式路由。
     * 请求里的路径只用来和这张常量表比对，比不上就是 404，
     * 所以不存在「拿请求拼文件路径」这种代码。
     */
    private static void serveAsset(HttpExchange ex, String resource, String contentType) throws IOException {
        String body = readResource(resource);
        if (body == null) {
            Http.text(ex, 404, "资源不存在");
            return;
        }
        LocalServer.Http.send(ex, 200, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /** 健康检查。除了「活着吗」，还把「活在哪、怎么被保护」一起报出来，便于诊断。 */
    /**
     * 提交一个扫描任务，**立刻返回**，不等它跑完。
     *
     * <p>同步的 {@code /api/scan} 留着：脚本与命令行式调用要的是一个
     * 「发过去、拿结果」的接口，拆成两段反而更麻烦。界面走异步那条。
     *
     * <p><b>上传是流式落盘的</b>：请求体边收边写进会话目录，
     * 内存里只有一个 64KB 的缓冲。任务在队列里等的时候，
     * 等的是磁盘而不是堆——见 {@link Workspace#stage}。
     *
     * <p><b>「不支持的文件类型」与「文件太大」在提交时就回 400</b>：
     * 用户不必等任务跑完才发现白传了一份。界面上那句
     * 「不支持 .exe」出现在上传那一刻，而不是几秒后。
     */
    private static void taskScan(HttpExchange ex, TaskRunner tasks) throws IOException {
        try {
            JobStore.requireContentType(ex, "application/octet-stream");
            String name = JobStore.clientFileName(ex);
            // 流式落盘：内容在提交那一刻就离开堆了。
            // 落盘之后它归任务管——任务表满时没人认领的那份，由 TaskRunner 负责清掉，
            // 清理责任只有一处（这里再清一遍是冗余的，删了它自测仍然全绿）。
            Workspace.StagedFile staged = tasks.workspace().stage(ex.getRequestBody(), name);
            TaskRunner.Task task = tasks.submitScan(staged);
            Http.json(ex, 202, Map.of(
                    "ok", true,
                    "taskId", task.taskId(),
                    "status", TaskRunner.Status.RUNNING.wire()));
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        } catch (Workspace.UnsupportedFileTypeException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        } catch (Workspace.FileTooLargeException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        } catch (IOException e) {
            // 任务表满了之类。报错要说清楚，不能吞成 500。
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /** 提交一个应用任务，同样立刻返回。 */
    private static void taskApply(HttpExchange ex, TaskRunner tasks) throws IOException {
        try {
            JobStore.requireContentType(ex, "application/json");
            byte[] body = Http.readBody(ex, 1L * 1024 * 1024);
            JobStore.ApplyRequest req = Json.read(body, JobStore.ApplyRequest.class);
            Exclusions exclusions = Exclusions.ofList(req.excluded());
            char[] passphrase = passphraseOf(req.mapPassphrase());
            TaskRunner.Task task;
            try {
                // 口令数组的所有权随任务转移：提交成功后由执行线程擦掉。
                task = tasks.submitApply(req.jobId(), exclusions, passphrase);
            } catch (IOException | RuntimeException e) {
                // 提交没成 → 没人擦 → 这里擦。
                com.litemask.core.SecretMap.wipe(passphrase);
                throw e;
            }
            Http.json(ex, 202, Map.of(
                    "ok", true,
                    "taskId", task.taskId(),
                    "status", TaskRunner.Status.RUNNING.wire()));
        } catch (JobStore.BadRequestException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        } catch (IOException e) {
            Http.json(ex, 400, Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /** 查任务状态。 */
    private static void taskStatus(HttpExchange ex, TaskRunner tasks) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        TaskRunner.Task task = tasks.task(query.get("taskId"));
        if (task == null) {
            Http.json(ex, 400, Map.of("ok", false, "error", "任务不存在或已过期"));
            return;
        }
        Http.json(ex, 200, tasks.snapshot(task));
    }

    /**
     * 请求取消一个任务。
     *
     * <p>它只是<b>请求</b>取消：工作线程跑到下一个检查点才会真的停。
     * 所以这里回的是「已受理取消」，真正的终态要看 {@code GET /api/task}。
     */
    private static void taskCancel(HttpExchange ex, TaskRunner tasks) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        String taskId = query.get("taskId");
        if (!tasks.cancel(taskId)) {
            Http.json(ex, 400, Map.of("ok", false, "error", "任务不存在或已过期"));
            return;
        }
        Http.json(ex, 200, Map.of("ok", true, "taskId", taskId, "status", "canceling"));
    }

    private static void health(HttpExchange ex, LocalServer server, Workspace workspace,
                               List<Rule> rules, JobStore jobs, TaskRunner tasks)
            throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        // 【块 34】身份与协议：这一段是「服务在不在」这个判据的**依据**。
        //   没有它，探活只能靠「HTTP 200」判断在线 —— 端口上被别的服务占着
        //   也会说「在线」（§十九那条老缺口）。
        //   → 报出服务名与协议版本，探活拿它们核对；
        //   → 探活发来的挑战原样回显（形态受限，见 ServiceIdentity）。
        //   挑战回显这一条是「当场握手」：不相干的服务不会照着这个协议应答。
        out.put(ServiceIdentity.FIELD_SERVICE, ServiceIdentity.NAME);
        out.put(ServiceIdentity.FIELD_PROTOCOL, ServiceIdentity.PROTOCOL);
        String challenge = ex.getRequestHeaders().getFirst(ServiceIdentity.CHALLENGE_HEADER);
        if (ServiceIdentity.isEchoableChallenge(challenge)) {
            out.put(ServiceIdentity.FIELD_CHALLENGE, challenge);
        }
        out.put("url", server.url());
        out.put("port", server.port());
        out.put("sessionId", workspace.sessionId());
        out.put("ruleCount", rules.size());
        out.put("activeRuleCount", rules.stream().filter(Rule::active).count());
        out.put("routes", server.routeSummary());
        // 任务表：超上限时会淘汰最久没碰的那份，所以「在用几个 / 淘汰过几个」是
        // 用户能核对的事实，不报的话，一份上传的文件悄悄消失就无从查起。
        out.put("activeJobs", jobs.size());
        out.put("maxJobs", JobStore.MAX_JOBS);
        out.put("evictedJobs", jobs.evicted());
        // 正在跑的后台任务。报出来是为了让「界面卡住」这件事可核对：
        // 用户看得见「还有 1 个任务在跑」，而不是只能猜。
        out.put("runningTasks", tasks.running());
        out.put("maxTasks", TaskRunner.MAX_TASKS);
        // 超时：报出「时限是多少」「有几个任务被超时中止过」。
        // 这两项是**可核对的事实**——用户看到任务莫名其妙没了，
        // 可以来这里确认是超时而不是崩溃。
        out.put("taskTimeoutMs", tasks.timeoutMs());
        out.put("timedOutTasks", tasks.timedOutCount());
        // 以下三项是「安全姿态」而不是「配置」：报出来是为了让用户能自己核对，
        // 而不是一个「已加固」的空头承诺。
        //
        // hostHeaderChecked 报 true **不是**写死的常量——请求能走到这个处理器，
        // 本身就说明 Host 头已经过了校验（dispatch 在读请求体之前就判了）。
        // bindAddress 从 socket 读，不是绑定常量：常量改了而这里没改，就是报告在骗人。
        // corsHeaders 是配置声明，它的守门人是单测「任何响应都不得带 CORS 头」，
        // 以及对照实验——那条断言会因为有人加了 CORS 头而变红。
        Map<String, Object> posture = new LinkedHashMap<>();
        posture.put("bindAddress", server.boundAddress());
        posture.put("loopbackVerified", server.loopbackVerified());
        posture.put("hostHeaderChecked", true);
        posture.put("corsHeaders", false);
        out.put("securityPosture", posture);
        Http.json(ex, 200, out);
    }

    /** 规则清单。带 pattern 是有意的：用户要能自己判断某条规则为什么误报。 */
    private static void ruleList(HttpExchange ex, List<Rule> rules) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Rule r : rules) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", r.key());
            item.put("name", r.name());
            item.put("description", r.description());
            item.put("pattern", r.pattern());
            item.put("tokenPrefix", r.tokenPrefix());
            item.put("sortOrder", r.sortOrder());
            item.put("active", r.active());
            item.put("category", r.category().name());
            item.put("validator", r.validator().name());
            items.add(item);
        }
        Http.json(ex, 200, Map.of("ok", true, "count", items.size(), "rules", items));
    }

    /** 载入内置规则库。 */
    public static List<Rule> defaultRules() {
        return RuleLoader.loadFromClasspath("rules/core-rules.json");
    }

    static String readResource(String name) throws IOException {
        try (InputStream in = ApiHandlers.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
