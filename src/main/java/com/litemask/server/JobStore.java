package com.litemask.server;

import com.litemask.core.Exclusions;
import com.litemask.core.ProcessingIdentity;
import com.litemask.core.Rule;
import com.litemask.core.TaskContext;
import com.litemask.core.ToolIdentity;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.litemask.server.LocalServer.Http;

/**
 * 一次脱敏任务的状态。
 *
 * <p>为什么要有状态：{@code scan} 与 {@code apply} 是两次独立请求，
 * 中间隔着用户看命中清单、做取舍的那段时间。状态放在内存而不是文件里，
 * 是因为它<b>本来就不该活过这次进程</b>——里面装着原文与命中明细，
 * 落盘就等于给会话目录多加了一份必须被清理的敏感数据。
 * 进程退出时 {@link Workspace#close()} 整棵删掉，与内存里的这张表同时消失。
 *
 * <p><b>唯一的例外是映射件</b>（{@link #writeMapping}）：它必须落盘，
 * 否则用户拿到的脱敏件是不可逆的，而工具却显示「脱敏成功」。
 * 它落在会话目录里、随任务淘汰一起删、下载要走令牌——
 * 敏感数据的处置规则与原件完全一致，只是不再是「只活一会儿」，
 * 因为用户需要把它带走。
 *
 * <p>并发：表是 {@link ConcurrentHashMap}，但单个 job 的状态用 {@code synchronized}
 * 保护——一个 job 只该被一个界面上的一条流程推进，重复提交 apply 是要报错的，不是要并行的。
 */
public final class JobStore {

    /** 单次会话内最多保留多少个任务。超了拒绝新的，而不是无限吃内存。 */
    public static final int MAX_JOBS = 64;

    /** 上传体类型。刻意只用一种：引 multipart 解析器就是多一份攻击面。 */
    private static final String UPLOAD_TYPE = "application/octet-stream";

    private final Workspace workspace;
    /**
     * 按「最近一次被访问」排序，而不是按插入顺序。
     *
     * <p>淘汰最久没人碰的那个：用户刚扫完、正在挑排除项的那份文件，
     * 不该因为他又传了两份新文件就被踢掉。为此必须用 access-order 的
     * {@link LinkedHashMap}——插入序会淘汰掉最早传的那份，
     * 而那恰恰可能是他还在处理的那份。
     *
     * <p>所有读写都在 {@code synchronized} 方法里，所以不需要并发容器。
     */
    private final Map<String, Job> jobs =
            new LinkedHashMap<>(16, 0.75f, true);
    private final List<Rule> rules;
    /**
     * AI 来源（NER）。由构造方注入而不是在方法里现取——
     * 在方法里读一个进程级单例会让「这次脱敏用了哪个模型」变得无法从参数上看出来，
     * 测试里也就没法固定住它。
     */
    private final List<com.litemask.core.EntitySource> sources;
    /**
     * 扫描期间的独占闸门。
     *
     * <p><b>为什么需要它</b>：模型可以在服务运行期间被换掉（用户在界面上点「更新模型」）。
     * 换的时候要挪走模型文件，而 Windows 上被打开的文件挪不动 →
     * 必须先确保<b>此刻没有任务正在用这个模型</b>。
     * 这把闸门就是那件事的落点：扫描期间持锁，换模型要拿写锁。
     */
    private final com.litemask.core.ExclusiveGate gate;
    /** 在扫描持有模型闸门时读取，保证身份与实际使用的推理来源是同一时刻。 */
    private final ProcessingIdentity.Provider identityProvider;
    private final MappingWriter mappingWriter;

    @FunctionalInterface
    interface MappingWriter {
        void write(Path path, byte[] content) throws IOException;
    }

    public JobStore(Workspace workspace, List<Rule> rules) {
        this(workspace, rules, List.of());
    }

    /**
     * 本作业库用的工作区。
     *
     * <p>暴露它是为了让<b>上传的落盘</b>发生在同一个地方：
     * 暂存件要落在会话目录里（进程退出时整棵删掉），
     * 而认领它的工作区必须是同一个——不然暂存件会落在工作区管不到的地方，
     * 于是「没人认领的暂存件就留在盘上」这种泄漏没人能兜底。
     */
    public Workspace workspace() {
        return workspace;
    }

    public JobStore(Workspace workspace, List<Rule> rules,
                    List<com.litemask.core.EntitySource> sources) {
        this(workspace, rules, sources, com.litemask.core.ExclusiveGate.NONE);
    }

    /**
     * 带独占闸门的构造。
     *
     * @param workspace 工作区
     * @param rules     规则
     * @param sources   AI 来源
     * @param gate      扫描期间的独占闸门
     */
    public JobStore(Workspace workspace, List<Rule> rules,
                    List<com.litemask.core.EntitySource> sources,
                    com.litemask.core.ExclusiveGate gate) {
        this(workspace, rules, sources, gate, null);
    }

    public JobStore(Workspace workspace, List<Rule> rules,
                    List<com.litemask.core.EntitySource> sources,
                    com.litemask.core.ExclusiveGate gate,
                    ProcessingIdentity.Provider identityProvider) {
        this(workspace, rules, sources, gate, identityProvider,
                com.litemask.core.PrivateFile::write);
    }

    JobStore(Workspace workspace, List<Rule> rules,
             List<com.litemask.core.EntitySource> sources,
             com.litemask.core.ExclusiveGate gate,
             ProcessingIdentity.Provider identityProvider,
             MappingWriter mappingWriter) {
        this.workspace = workspace;
        this.rules = rules;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.gate = gate == null ? com.litemask.core.ExclusiveGate.NONE : gate;
        this.identityProvider = identityProvider;
        this.mappingWriter = mappingWriter == null
                ? com.litemask.core.PrivateFile::write : mappingWriter;
    }

    /** 一个任务的全部状态；所有跨字段变更都在该 job 的监视器内完成。 */
    public static final class Job {
        final String jobId;
        final String sourceName;
        final String displayName;
        final String extension;
        final DocumentService.Format format;
        /** 预览文件、命中、身份与修订号一次性发布，避免读到半新半旧的组合。 */
        private volatile ScanSnapshot scanSnapshot;
        /**
         * 用户在这个任务上手工补充的敏感词。
         *
         * <p><b>只在本次任务内有效</b>：它不是全局规则、不写进规则文件、
         * 也不影响别的文件。下一次处理别的文件时这些词不在 ——
         * 这是有意的：用户补的词多半是「这一份文件里的事」，
         * 悄悄变成全局规则会让后面每一份文件都被它影响。
         */
        final List<String> userTerms = new ArrayList<>();
        /** 成品实际使用的身份；无排除项时与 scanIdentity 是同一个快照。 */
        volatile ProcessingIdentity appliedIdentity;
        /** 成品基于哪个经人工复核的扫描修订。 */
        volatile long appliedRevision;
        volatile String outputName;
        /**
         * 映射件的磁盘名。apply 之后才有。
         *
         * <p>映射原先只活在 {@code applied.mapping()} 这块内存里——进程一退出就没了。
         * 那意味着界面上的「脱敏成功」实际上是<b>不可逆</b>的，而本工具最重要的承诺
         * 就是可逆。落盘是必须的（见 {@link JobStore#writeMapping}）。
         */
        volatile String mappingName;
        /**
         * 映射件是不是被口令封装过。
         *
         * <p>界面靠它决定要不要提示「还原时需要同一个口令」——
         * 界面不能靠猜：用户填了口令却拿到明文映射件（或者反过来），
         * 都会让他对「这份文件能不能还原」做出错误判断，而界面上看不出任何异常。
         *
         * <p>只记「封没封」，<b>不记口令本身</b>：口令留在任务对象里，
         * 等于把它延长到整个会话的生命周期，而它只需要活到写完映射件那一刻。
         */
        volatile boolean mappingSealed;
        volatile DocumentService.Result applied;

        Job(String jobId, String sourceName, String previewName, String displayName,
            String extension, DocumentService.Format format, DocumentService.Result scan,
            boolean nerEnabled) {
            this(jobId, sourceName, previewName, displayName, extension, format, scan,
                    new ProcessingIdentity(ToolIdentity.version(), ToolIdentity.artifactSha256(),
                            nerEnabled, nerEnabled ? "unverified-entity-source" : null,
                            null, null, null, false));
        }

        Job(String jobId, String sourceName, String previewName, String displayName,
            String extension, DocumentService.Format format, DocumentService.Result scan,
            ProcessingIdentity scanIdentity) {
            this.jobId = jobId;
            this.sourceName = sourceName;
            this.displayName = displayName;
            this.extension = extension;
            this.format = format;
            this.scanSnapshot = new ScanSnapshot(previewName, scan, scanIdentity, 1L);
        }

        public String jobId() {
            return jobId;
        }

        public String displayName() {
            return displayName;
        }

        DocumentService.Result scan() {
            return scanSnapshot.result();
        }

        ProcessingIdentity scanIdentity() {
            return scanSnapshot.identity();
        }

        long scanRevision() {
            return scanSnapshot.revision();
        }

        public String downloadName() {
            String base = displayName;
            int dot = base.lastIndexOf('.');
            String stem = dot <= 0 ? base : base.substring(0, dot);
            String ext = dot <= 0 ? extension : base.substring(dot);
            return stem + ".redacted" + ext;
        }

        /**
         * 映射件给用户下载时的文件名。
         *
         * <p>刻意从<b>原文件名</b>派生（{@code 合同.docx.redacted.map.json}），
         * 而不是从输出名派生：同一份文件脱两次时，输出是
         * {@code 合同.redacted.docx} 与 {@code 合同.redacted.redacted.docx}，
         * 映射则是 {@code 合同.docx.redacted.map.json} 与
         * {@code 合同.redacted.docx.redacted.map.json}——两次互不覆盖。
         * CLI 侧用的是同一条规则，两边的产物名才对得上。
         */
        public String mappingDownloadName() {
            return displayName + ".redacted.map.json";
        }

        /**
         * 还原件给用户下载时的文件名。
         *
         * <p>{@code 合同.redacted.docx} → {@code 合同.redacted.restored.docx}，
         * 与 CLI 的 {@code restoredName} 同一条规则（保住目录、只换文件名、原扩展名留在最后）。
         */
        public String restoreDownloadName() {
            String base = downloadName();
            int dot = base.lastIndexOf('.');
            String stem = dot <= 0 ? base : base.substring(0, dot);
            String ext = dot <= 0 ? extension : base.substring(dot);
            return stem + ".restored" + ext;
        }

        // 磁盘名：测试要断言「淘汰时这三个文件都被删了」。
        // 只给测试看，且只读——不该有任何生产代码走这两个方法去拼路径。
        String sourceNameForTest() {
            return sourceName;
        }

        String previewNameForTest() {
            return scanSnapshot.previewName();
        }

        String outputNameForTest() {
            return outputName;
        }

        String mappingNameForTest() {
            return mappingName;
        }

        private record ScanSnapshot(String previewName, DocumentService.Result result,
                                    ProcessingIdentity identity, long revision) {
        }
    }

    // ------------------------------------------------------------------ scan

    /**
     * 收上传、跑一遍脱敏、把命中清单交回去。
     *
     * <p>这一步<b>会真的生成一份脱敏预览件</b>，不是干跑。用户若一条都不排除，
     * {@code apply} 直接把它转正，不必再跑一遍整条管线。
     */
    public Job scan(HttpExchange exchange) throws IOException {
        requireContentType(exchange, UPLOAD_TYPE);
        // 流式落盘：这份内容不该在堆里等着被写出去。
        // （以前是 Http.readBody 攒成一个 byte[]，256MB 上限意味着它能占满半台机器。）
        return scanStaged(stage(exchange), null);
    }

    /**
     * 把上传流落成暂存件。
     *
     * <p>后缀与体积都在<b>这里</b>判：判完再交出去，
     * 于是「不支持的文件类型」与「文件太大」都是同步 400，
     * 而不是先收下再让任务跑完才失败。
     */
    private Workspace.StagedFile stage(HttpExchange exchange)
            throws IOException, BadRequestException {
        try {
            return workspace.stage(exchange.getRequestBody(), clientFileName(exchange));
        } catch (Workspace.UnsupportedFileTypeException e) {
            throw new BadRequestException(e.getMessage());
        } catch (Workspace.FileTooLargeException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    /**
     * 收一份上传并脱敏。
     *
     * <p>HTTP 与「脱一份文件」在这里分开：超上限淘汰、落盘、清盘这些行为与 HTTP 无关，
     * 而要连续传 65 份文件验淘汰行为时，每份都起一次真实 socket 会把测试拖慢十倍。
     * 拆开之后淘汰逻辑可以用普通单测直接压，HTTP 那层只验「解析出来的文件名和字节对不对」。
     *
     * @param clientName 客户端报来的文件名；仅用于判定后缀与显示名清洗，不参与路径拼接
     */
    public Job scan(String clientName, byte[] body) throws IOException {
        return scanWith(clientName, body, null);
    }

    /**
     * 扫描的共同实现（输入是字节）。
     *
     * <p>先落盘再脱敏：即使调用方手里已经有字节了，也不走「攒在堆里 → 再写出去」那条路。
     * 两条路最后汇到同一个 {@link #scanStaged}，于是「怎么收文件」只有一处实现。
     */
    private Job scanWith(String clientName, byte[] body, TaskContext ctx) throws IOException {
        Workspace.StagedFile staged;
        try {
            staged = workspace.stage(new java.io.ByteArrayInputStream(body), clientName);
        } catch (Workspace.UnsupportedFileTypeException e) {
            throw new BadRequestException(e.getMessage());
        } catch (Workspace.FileTooLargeException e) {
            throw new BadRequestException(e.getMessage());
        }
        return scanStaged(staged, ctx);
    }

    /**
     * 扫描：输入是<b>已落盘</b>的上传。
     *
     * <p>后台任务走这条：提交时内容就已经在磁盘上了，
     * 所以任务在队列里等的那段时间，<b>内存里没有那份内容</b>
     * （见 {@link Workspace#stage} 的注释：以前是 64 份 256MB 的 byte[] 一起等着）。
     *
     * <p>暂存件在这里被「认领」成正式件（原件），所以它不会以暂存件的形态留在盘上；
     * 万一在认领之前就失败，{@link #discard} 会把它清掉。
     */
    Job scanStaged(Workspace.StagedFile staged, TaskContext ctx) throws IOException {
        Workspace.StoredFile stored;
        try {
            stored = workspace.store(staged);
        } catch (IOException e) {
            // 认领失败 = 没人接手这份上传，它不能留在盘上
            workspace.discard(staged);
            throw e;
        }
        // 认领之后暂存件已经不在原处了（被挪成了正式件），
        // 它的去留由作业的生命周期管：取消会删，淘汰会删。
        return scanStored(stored, ctx);
    }

    /**
     * 扫描的共同实现。
     *
     * <p><b>重活（脱敏）刻意跑在监视器外面。</b>本类的其它方法——查任务、取成品件、
     * 淘汰——都靠同一把监视器互斥。如果脱敏也拿着它，一份 200 页的 PDF 就会把
     * <b>整个服务</b>卡住：连「再上传一个文件」都得排在它后面等它跑完。
     * 短操作（淘汰、登记）仍然在监视器里，长操作在外面。
     */
    private Job scanStored(Workspace.StoredFile stored, TaskContext ctx) throws IOException {
        synchronized (this) {
            if (jobs.size() >= MAX_JOBS) {
                evictOldest();
            }
        }
        String previewName = null;
        try {
            DocumentService.Format format;
            try {
                format = DocumentService.Format.of(stored.extension());
            } catch (DocumentService.UnsupportedFormatException e) {
                throw new BadRequestException(e.getMessage());
            }

            previewName = workspace.newDiskName(stored.extension());
            DocumentService.Result result;
            ProcessingIdentity identity;
            String finalPreviewName = previewName;
            try (com.litemask.core.ExclusiveGate.Release release = gate.acquire()) {
                identity = captureIdentity();
                result = TaskContext.in(ctx, () -> DocumentService.redact(
                        workspace.pathOf(stored.diskName()), workspace.pathOf(finalPreviewName),
                        format, rules, Exclusions.NONE, sources));
            }

            Job job = new Job(UUID.randomUUID().toString().replace("-", ""),
                    stored.diskName(), previewName, stored.displayName(),
                    stored.extension(), format, result, identity);
            synchronized (this) {
                jobs.put(job.jobId, job);
            }
            return job;
        } catch (RuntimeException | IOException e) {
            // 登记成 job 之前的任何失败都没有后续生命周期接管，原件和半成品必须清掉。
            workspace.delete(stored.diskName());
            if (previewName != null) {
                workspace.delete(previewName);
            }
            throw e;
        }
    }

    // ----------------------------------------------------------------- 补充敏感词

    /**
     * 用户手工补充敏感词，然后<b>重扫</b>。
     *
     * <p><b>为什么要重扫</b>：补充的词必须出现在**复核清单**里 ——
     * 用户补了「李某」之后，要能在清单上看见它、并且能把它排除掉
     * （那可能是个误伤，比如「张某」出现在法律条文引用里）。
     * 不重扫的话，词虽然会在 apply 时被脱敏，但用户**没机会排除它**，
     * 那就不是「人工复核」，是「用户被迫接受」。
     *
     * <p><b>为什么接进现有引擎而不是另开一条路</b>：
     * 走规则才能自动获得同值同符、位置、出现序号、整值/逐处排除、进入映射。
     * 另开一条旁路的话这些全要重做，而且大概率做不全。
     *
     * @param jobId 任务号
     * @param terms 用户补的词
     * @return 重扫后的任务
     * @throws IOException 重扫失败
     */
    public Job addUserTerms(String jobId, List<String> terms) throws IOException {
        Job job = require(jobId);
        synchronized (job) {
            if (job.outputName != null) {
                throw new BadRequestException("这个文件已经应用过了；要改补充词请重新上传。");
            }
            List<String> cleaned;
            try {
                cleaned = com.litemask.core.UserTerms.validate(terms);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(e.getMessage());
            }
            List<String> nextTerms = new ArrayList<>(job.userTerms);
            boolean changed = false;
            for (String t : cleaned) {
                if (!nextTerms.contains(t)) {
                    nextTerms.add(t);
                    changed = true;
                }
            }
            if (!changed) {
                throw new BadRequestException("这些词已经在本次处理里了。");
            }
            rescan(job, nextTerms);
            job.userTerms.clear();
            job.userTerms.addAll(nextTerms);
            return job;
        }
    }

    /**
     * 这次脱敏里，<b>AI 识别是不是真的参与了</b>。
     *
     * <p><b>为什么不能只看「来源列表非空」</b>：模型可以在服务运行期间被装上，
     * 于是来源列表里那个来源可能正因为<b>模型还没准备好</b>而什么都没认出来。
     * 「模型参与了但没认出」与「模型压根没参与」，
     * 前者是这份文档确实没有人名，后者是<b>这份文档没脱干净</b>——
     * 这两件事必须能在结果里区分开。
     *
     * @return 参与了就 true
     */
    private boolean nerParticipated() {
        for (com.litemask.core.EntitySource s : sources) {
            if (s.isActive()) {
                return true;
            }
        }
        return false;
    }

    private ProcessingIdentity captureIdentity() {
        if (identityProvider != null) {
            return java.util.Objects.requireNonNull(identityProvider.snapshot(),
                    "身份提供器不能返回 null");
        }
        boolean usedModel = nerParticipated();
        return new ProcessingIdentity(ToolIdentity.version(), ToolIdentity.artifactSha256(),
                usedModel, usedModel ? unverifiedSourceId() : null,
                null, null, null, false);
    }

    private String unverifiedSourceId() {
        String names = sources.stream()
                .filter(com.litemask.core.EntitySource::isActive)
                .map(com.litemask.core.EntitySource::name)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .sorted()
                .distinct()
                .collect(java.util.stream.Collectors.joining("+"));
        return names.isEmpty() ? "unverified-entity-source" : "unverified-source:" + names;
    }

    /**
     * 用（含补充词的）规则重扫一遍，换掉预览件。
     *
     * <p><b>旧预览件必须删掉</b>：它里面是**没脱敏干净**的那一版
     * （补的词没生效），留在会话目录里就是一份含原值的文件没人认领。
     */
    private void rescan(Job job, List<String> terms) throws IOException {
        Job.ScanSnapshot previous = job.scanSnapshot;
        String newPreview = workspace.newDiskName(job.extension);
        DocumentService.Result result;
        ProcessingIdentity identity;
        try (com.litemask.core.ExclusiveGate.Release release = gate.acquire()) {
            identity = captureIdentity();
            result = DocumentService.redact(workspace.pathOf(job.sourceName),
                    workspace.pathOf(newPreview), job.format, rulesWithUserTerms(terms),
                    Exclusions.NONE, sources);
        } catch (RuntimeException | IOException e) {
            // 半成品不能留在盘上
            workspace.delete(newPreview);
            throw e;
        }
        job.scanSnapshot = new Job.ScanSnapshot(
                newPreview, result, identity, previous.revision() + 1);
        if (previous.previewName() != null) {
            workspace.delete(previous.previewName());
        }
    }

    /**
     * 这个任务要用的规则：内置规则 + 用户补充的词。
     *
     * <p>没有补充词时**原样返回**内置那份 —— 不复制、不包装，
     * 免得每次扫描都多分配一份一模一样的列表。
     */
    private List<Rule> rulesWithUserTerms(Job job) {
        return rulesWithUserTerms(job.userTerms);
    }

    private List<Rule> rulesWithUserTerms(List<String> terms) {
        if (terms.isEmpty()) {
            return rules;
        }
        List<Rule> all = new ArrayList<>(rules);
        all.addAll(com.litemask.core.UserTerms.toRules(terms));
        return all;
    }

    /**
     * 这份文件是不是<b>没有可提取的文字层</b>。
     *
     * <p><b>为什么要有这个判断</b>：扫描件（纯图片）在文字层里**一个字都没有**，
     * 于是脱敏跑完是「零命中」—— 而零命中看上去与「这份文件很干净」一模一样。
     * 用户拿到的却是一份**图片里原封不动**的文档。
     * 这正是「脱敏结果掩盖了未处理内容」。
     *
     * <p><b>为什么只对 PDF 与 TXT 这么判</b>：这两种格式的「文字」就是文件的全部内容
     * （PDF 的内容流、TXT 的正文），为 0 就等于**整个文件没有可处理的内容**。
     * 而 docx/xlsx/pptx/ofd 的 0 只说明「正文是空的」，
     * 它们还可能有表格、备注、附件 —— 那些位置被处理过了，不该说「没处理」。
     *
     * @param job 任务
     * @param result 该次扫描的结果
     * @return 没有文字层时给出一句人话；否则返回 null
     */
    private static String noTextLayerReason(Job job, DocumentService.Result result) {
        if (result.hits() != null && !result.hits().isEmpty()) {
            return null;
        }
        Object readable = result.stats().values().get("可读字符");
        if (readable == null) {
            readable = result.stats().values().get("字符数");
        }
        if (!(readable instanceof Number n) || n.intValue() != 0) {
            return null;
        }
        if (job.format == DocumentService.Format.PDF) {
            return "这份 PDF 里没有可提取的文字（像是扫描件或纯图片）。"
                    + "文字层里没有可脱敏的内容，但**图片里的内容没有被处理** —— "
                    + "请先在外部做 OCR 或图像脱敏，再拿处理过的文件来脱敏。";
        }
        if (job.format == DocumentService.Format.TXT) {
            return "这个文本文件是空的，没有任何内容需要脱敏。";
        }
        return null;
    }

    /**
     * 零命中时**必须说清是哪一种零**。
     *
     * <p><b>为什么</b>：「零命中」这四个字对用户至少有三种完全不同的含义 ——
     * <ul>
     *   <li>文件里**没有文字层**（扫描件）→ 图片里的东西**没被处理**；</li>
     *   <li>规则没命中，且<b>人名识别没开</b> → 人名**根本没被找过**；</li>
     *   <li>规则没命中（识别已开）→ 真的没命中，但**没有固定形态的名字仍可能漏**。</li>
     * </ul>
     * 三种都显示成「零命中」，用户只能自己猜是哪一种 ——
     * 而猜错的后果是「以为干净就发出去了」。
     *
     * @param job    任务
     * @param result 扫描结果
     * @return 要写进 notes 的说明；不是零命中时返回空列表
     */
    private static List<String> zeroHitNotes(Job job, DocumentService.Result result) {
        List<String> out = new ArrayList<>();
        if (result.hits() != null && !result.hits().isEmpty()) {
            return out;
        }
        String noText = noTextLayerReason(job, result);
        if (noText != null) {
            out.add(noText);
            return out;
        }
        if (!job.scanIdentity().usedModel()) {
            out.add("规则没有命中。**人名与机构名识别未启用** —— "
                    + "文档里的人名与机构名**没有被找过**，"
                    + "不能据此认为这份文件里没有敏感信息。");
            return out;
        }
        out.add("规则没有命中（人名与机构名识别已启用）。"
                + "本工具只认**有固定形态**的信息，没有固定形态的名字、代号仍可能漏 —— "
                + "请人工复核；也可以在下面补充你知道的敏感词再重扫。");
        return out;
    }

    // ----------------------------------------------------------------- apply

    /**
     * 应用排除项，产出成品。
     *
     * <p>排除项为空时<b>不重跑</b>：直接把预览件转正。重跑能得到同样的文件，
     * 但对一份 200 页的 PDF 来说是白等几秒，而且两次运行之间文件没变、
     * 结果理应一致——与其重述这条不变式，不如直接复用。
     */
    public Job apply(String jobId, Exclusions exclusions) throws IOException {
        return apply(jobId, exclusions, null);
    }

    /**
     * 应用排除项，产出成品；映射件按给定口令封装。
     *
     * @param jobId      任务号
     * @param exclusions 用户要保留的命中
     * @param passphrase 映射件口令；{@code null} 表示不封装（写明文，与从前一致）
     */
    public Job apply(String jobId, Exclusions exclusions, char[] passphrase) throws IOException {
        Job job = require(jobId);
        Exclusions excl = exclusions == null ? Exclusions.NONE : exclusions;
        synchronized (job) {
            if (job.outputName != null) {
                throw new BadRequestException("这个文件已经应用过了；要改排除项请重新上传");
            }
            return applyClaimed(job, excl, passphrase);
        }
    }

    /**
     * 可取消的应用：给后台任务用。
     *
     * <p>取消时**不产出任何文件**：半成品成品件会被删掉，映射件也不会写。
     */
    Job applyCancellable(String jobId, Exclusions exclusions, TaskContext ctx) throws IOException {
        return applyCancellable(jobId, exclusions, ctx, null);
    }

    /**
     * 可取消的应用，带映射件口令。
     *
     * @param passphrase 映射件口令；{@code null} 表示不封装
     */
    Job applyCancellable(String jobId, Exclusions exclusions, TaskContext ctx, char[] passphrase)
            throws IOException {
        Job job = require(jobId);
        Exclusions excl = exclusions == null ? Exclusions.NONE : exclusions;
        synchronized (job) {
            if (job.outputName != null) {
                throw new BadRequestException("这个文件已经应用过了；要改排除项请重新上传");
            }
            return TaskContext.in(ctx, () -> applyClaimed(job, excl, passphrase));
        }
    }

    /** 已持有该 job 监视器后的应用逻辑。 */
    private Job applyClaimed(Job job, Exclusions excl, char[] passphrase) throws IOException {
        verifyExclusionsMatch(job, excl);
        verifyGroupClosure(job, excl);
        verifyNoPositionExclusionInMergedRow(job, excl);
        // 【没有文字层的 PDF：阻止导出成「脱敏件」】
        //   扫描件在文字层里一个字都没有 → 脱敏跑完是零命中 →
        //   而产出的那份文件与原件**逐字相同**（图片里原封不动）。
        //   把它当「脱敏件」交给用户，就是在**掩盖未处理的内容**。
        //   → 直接拒掉，并说清要用户先做什么。
        if (job.format == DocumentService.Format.PDF) {
            String noText = noTextLayerReason(job, job.scan());
            if (noText != null) {
                throw new BadRequestException(noText);
            }
        }
        Job.ScanSnapshot reviewed = job.scanSnapshot;
        String outputName;
        DocumentService.Result applied;
        ProcessingIdentity appliedIdentity;
        boolean generatedOutput = false;
        if (excl.isEmpty()) {
            outputName = reviewed.previewName();
            applied = reviewed.result();
            appliedIdentity = reviewed.identity();
        } else {
            outputName = workspace.newDiskName(job.extension);
            generatedOutput = true;
            try (com.litemask.core.ExclusiveGate.Release release = gate.acquire()) {
                appliedIdentity = captureIdentity();
                applied = DocumentService.redact(workspace.pathOf(job.sourceName),
                        workspace.pathOf(outputName), job.format,
                        rulesWithUserTerms(job), excl, sources);
            } catch (RuntimeException | IOException e) {
                // 半成品：内容不完整，绝不能当成品交出去，也不能留在磁盘上。
                workspace.delete(outputName);
                throw e;
            }
        }
        // 映射件必须**跟着应用一起产出**。
        // 脱敏件已经能下载了，而映射只在内存里的话，用户拿到的是一份「不可逆」的文件——
        // 那等于把本工具最重要的承诺在界面上悄悄收走，而界面上看不出任何异常。
        MappingArtifact mapping;
        try {
            mapping = writeMapping(job, applied, appliedIdentity, passphrase);
        } catch (RuntimeException | IOException e) {
            if (generatedOutput) {
                workspace.delete(outputName);
            }
            throw e;
        }
        // 成品和映射都已安全落盘后，才一次性发布可下载状态。
        job.applied = applied;
        job.appliedIdentity = appliedIdentity;
        job.appliedRevision = reviewed.revision();
        job.mappingName = mapping.name();
        job.mappingSealed = mapping.sealed();
        // outputName 是所有下载/重试路径的发布标志，必须最后写。
        job.outputName = outputName;
        return job;
    }

    /**
     * 把这次的映射写成一件<b>映射件</b>，落在会话目录里。
     *
     * <p><b>为什么必须落盘</b>：映射原先只活在 {@code job.applied.mapping()} 这块内存里，
     * 进程一退出就没了。用户下载了脱敏件却拿不到映射，就再也无法还原——
     * 界面上显示「脱敏成功」而实际上不可逆，这比不做脱敏更糟。
     *
     * <p><b>落盘的是敏感数据</b>：映射里全是原值。所以它
     * <ul>
     *   <li>只落在会话目录里，进程退出时随整棵目录一起删掉（{@link Workspace#close()}）；</li>
     *   <li>任务被 LRU 淘汰时<b>连它一起删</b>（{@link #evictOldest()}）——
     *       留着它等于把用户文档里的原值留在磁盘上，而界面上已经看不见它了；</li>
     *   <li>下载要走令牌校验（{@code GET /api/mapping} 与 {@code /api/download} 同一条纪律）。</li>
     * </ul>
     *
     * <p><b>格式与 CLI 的映射文件兼容</b>：顶层有 {@code "mapping"}，因此 GUI 产出的文件
     * 可以直接喂给 {@code LightMeter restore --map}。{@code changes} 那一段按<b>值</b>聚合，
     * 与 CLI 的按<b>处</b>结构不同——而还原只依赖 {@code mapping}，不受影响。
     *
     * <p><b>给了口令就封装</b>：封装格式只有 {@link com.litemask.core.SecretMap} 一处定义
     * （CLI 侧也是它），所以 GUI 封装出来的文件能被命令行的
     * {@code restore --map --map-pass-env} 直接还原，两边不分裂。
     * 没给口令就写明文——与本工具从前的行为一致，不给口令的用户不该被静默改掉流程。
     */
    private MappingArtifact writeMapping(Job job, DocumentService.Result applied,
                                         ProcessingIdentity appliedIdentity,
                                         char[] passphrase) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mapping", applied.mapping());
        payload.put("format", job.format.name());
        payload.put("source", job.displayName);
        payload.put("toolIdentity", identityMap(appliedIdentity));
        List<Map<String, Object>> changes = new ArrayList<>();
        for (DocumentService.HitItem h : applied.hits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleKey", h.ruleKey());
            item.put("original", h.original());
            item.put("token", h.token());
            item.put("occurrences", h.occurrences());
            // 映射件里只写位置说明本身：它是给人核对用的，序号是界面的事。
            // 结构与改动前保持一致（字符串数组），CLI 侧读这个文件不受影响。
            item.put("locators", h.locators().stream()
                    .map(DocumentService.HitItem.Loc::locator).toList());
            changes.add(item);
        }
        payload.put("changes", changes);
        payload.put("notes", List.of(
                "mapping 的方向是「令牌 → 原值」，与命令行产出的映射文件一致，"
                        + "可以直接用本工具的 `restore --map <本文件>` 还原。",
                "changes 段按**值**聚合（命令行侧按处），结构不同；还原只依赖 mapping 段。"));
        byte[] json = Json.toPrettyBytes(payload);
        byte[] content = json;
        boolean sealed = false;
        if (passphrase != null) {
            try {
                content = com.litemask.core.SecretMap.seal(json, passphrase);
                sealed = true;
            } catch (com.litemask.core.SecretMap.SecretMapException e) {
                // 【为什么不许降级成明文】封装失败就写明文，等于「用户以为加密了，
                // 文件里却全是原值」，而界面上看不出任何异常。
                // 这与 fail-closed 是同一条纪律：宁可这次请求失败，也不能交出一份
                // 名不副实的「已加密」映射件。
                throw new IOException("封装映射件失败：" + e.getMessage(), e);
            }
        }
        String name = workspace.newMappingName();
        // 映射件是本工具唯一含原值的产物 → 走 PrivateFile（权限 0600）。
        // 原来用 Files.writeString，权限取决于 umask（默认 0644），
        // 于是同一台机器上的其他用户能直接读到原值。
        try {
            mappingWriter.write(workspace.pathOf(name), content);
            return new MappingArtifact(name, sealed);
        } catch (RuntimeException | IOException e) {
            workspace.delete(name);
            throw e;
        }
    }

    private record MappingArtifact(String name, boolean sealed) {
    }

    /** 取映射件的字节，供下载。 */
    public byte[] mappingBytes(Job job) throws IOException {
        if (job.mappingName == null) {
            throw new BadRequestException("还没有应用，请先调用 /api/apply");
        }
        return Files.readAllBytes(workspace.pathOf(job.mappingName));
    }

    /**
     * 还原这个任务的成品件。
     *
     * <p>还原的是<b>服务端自己那份成品</b>，而不是用户传回来的文件：
     * 用户手上的脱敏件可能已经改过，而这里的映射是这次任务的那一份，
     * 两者对不上时会把不该替换的地方替换掉。用户想还原自己改过的文件，
     * 走命令行（{@code LightMeter restore --map <映射件>}）——那条路是通的。
     *
     * @param job 已应用过的任务
     */
    public Restored restoreBytes(Job job) throws IOException {
        if (job.outputName == null || job.applied == null) {
            throw new BadRequestException("还没有应用，请先调用 /api/apply");
        }
        // 读出这三样再干活：还原本身可能很慢（一份 200 页的 PDF），
        // 不该拿着监视器跑——那会把整个服务卡住。
        Path in = workspace.pathOf(job.outputName);
        DocumentService.Format format = job.format;
        Map<String, String> mapping = job.applied.mapping();
        String outName = workspace.newDiskName(job.extension);
        Path out = workspace.pathOf(outName);
        try {
            DocumentService.RestoreResult r = DocumentService.restore(in, out, format, mapping);
            return new Restored(Files.readAllBytes(out), r.count(), r.warnings());
        } finally {
            // 还原件是中间产物，用完即删：它含原值，留在会话目录里就是多一份敏感数据。
            workspace.delete(outName);
        }
    }

    /** 还原的结果。 */
    public record Restored(byte[] bytes, int count, List<String> warnings) {
    }

    /**
     * 排除项必须真的对得上扫描结果，否则让请求失败。
     *
     * <p><b>为什么对不上要报错，而不是照样跑完</b>：一条对不上的排除项在引擎里
     * 匹配不到任何命中，于是原样被脱掉——文件是对的，但用户以为「我留住了这一处」，
     * 实际没留住。界面不会显示任何异常，他只会拿到一份「按我要求处理过」的文档，
     * 而那份文档里他要保留的东西还在。
     *
     * <p>这与 M2-b 拒绝残缺排除项是同一条纪律：宁可让请求当场失败，
     * 也不要一份「看起来成功、实则没按要求处理」的结果。
     *
     * <p>对不上的两种情况：原值压根没被命中（规则键写错、服务端规则改名、
     * 界面上传的是上一份文件的命中清单），或序号超出实际出现次数
     * （界面拿的是旧清单，而文件已经变了）。
     */
    private static void verifyExclusionsMatch(Job job, Exclusions exclusions) throws BadRequestException {
        for (Exclusions.HitRef ref : exclusions.hits()) {
            boolean known = false;
            for (DocumentService.HitItem hit : job.scan().hits()) {
                if (命中这个值(hit, ref)) {
                    if (ref.occurrence() == Exclusions.HitRef.ANY
                            || ref.occurrence() < hit.occurrences()) {
                        known = true;
                    }
                    // 值对得上但序号越界：往下找也找不到了
                    break;
                }
            }
            if (!known) {
                throw new BadRequestException("排除项对不上这份文件的扫描结果: "
                        + ref.ruleKey() + " / " + 描述序号(ref.occurrence())
                        + "。可能是规则名变了，或你手上是上一份文件的命中清单——请重新上传再试");
            }
        }
    }

    /**
     * 归组行必须<b>整行</b>排除，否则让请求失败。
     *
     * <p>界面上归成一行的是若干处命中（一个标识符被切成 N 段），
     * 勾这一行发回来的是 N 个 {@code (规则键, 原值)} 对。
     * 只排除其中一处的话，文档里会留下「保留了一半的标识符」——
     * 用户要的要么是「这个地址别动」，要么是「这个地址脱掉」，没有第三种。
     *
     * <p>所以这里让请求失败，而不是照做。照做出来的是一份
     * 「看起来成功、实则没按要求处理」的文件：这与 {@link #verifyExclusionsMatch}
     * 是同一条纪律，只是触发条件不同（那边是「排除项对不上」，这边是「只排除了半行」）。
     *
     * <p>只看<b>值级</b>排除（{@link Exclusions.HitRef#ANY}）：位置级排除
     * 「只保留第 N 次出现」针对的是同一个值的不同出现处，与归组行无关。
     */
    private static void verifyGroupClosure(Job job, Exclusions exclusions) throws BadRequestException {
        for (DocumentService.HitItem hit : job.scan().hits()) {
            if (hit.valueCount() < 2) {
                continue;
            }
            int 排除了几处 = 0;
            for (DocumentService.HitItem.Member m : hit.members()) {
                if (exclusions.excludes(m.ruleKey(), m.original())) {
                    排除了几处++;
                }
            }
            if (排除了几处 != 0 && 排除了几处 != hit.members().size()) {
                throw new BadRequestException("有一行命中只排除了 " + 排除了几处 + " / "
                        + hit.members().size() + " 处：这一行是原文里紧挨着的若干处命中拼成的，"
                        + "要保留请整行勾选（否则会留下保留了一半的标识符）。");
            }
        }
    }

    /**
     * 位置级排除不能落在归组行的成员上，否则让请求失败。
     *
     * <p><b>为什么</b>：归组行是「原文里紧挨着、被不同规则分别认领的若干处命中」
     * 拼成的一个标识符（见 {@link com.litemask.core.HitGrouping}）。
     * 引擎是<b>逐段命中</b>脱敏的，它不认识「这一行是个整体」。
     * 所以只排除其中一个成员时，文档里会留下<b>保留了一半的标识符</b>——
     * 比如身份证的地址段留着、出生日期段被换成 {@code <身份证1>}。
     *
     * <p>值级排除已经由 {@link #verifyGroupClosure} 管住了：那一行要么整行保留、要么整行脱。
     * <b>位置级排除是这一轮才开放的</b>，它绕过了那道闸——所以这里必须单独补一道。
     * 不补的话，请求会「成功」，产出一份看起来正常、实则标识符被拆成两半的文件。
     *
     * <p><b>为什么是拒绝而不是照做</b>：与本类其余两条校验同一条纪律——
     * 宁可让请求当场失败，也不要一份「看起来成功、实则没按要求处理」的结果。
     * 界面上本来也不给归组行开单处入口（归组成员的值在全文只出现一次，
     * 没有「第 N 次」可谈），这条校验防的是绕过界面手工构造的报文。
     */
    private static void verifyNoPositionExclusionInMergedRow(Job job, Exclusions exclusions)
            throws BadRequestException {
        for (Exclusions.HitRef ref : exclusions.hits()) {
            if (ref.occurrence() == Exclusions.HitRef.ANY) {
                continue;                       // 值级：verifyGroupClosure 已经管了
            }
            for (DocumentService.HitItem hit : job.scan().hits()) {
                if (hit.valueCount() < 2) {
                    continue;
                }
                for (DocumentService.HitItem.Member m : hit.members()) {
                    if (m.ruleKey().equals(ref.ruleKey()) && m.original().equals(ref.original())) {
                        throw new BadRequestException(
                                "「" + m.original() + "」是原文里紧挨着的若干处命中拼成的一个标识符，"
                                        + "不能只保留其中一次出现——那会在文档里留下保留了一半的标识符。"
                                        + "要保留请整行保留（不写序号即整条不脱敏）。");
                    }
                }
            }
        }
    }

    /**
     * 排除项是否命中清单里的某一行。
     *
     * <p><b>归组行要按成员逐个对</b>：界面上归组成一行的是若干处命中
     * （一个标识符被切成 N 段），勾选它发回来的是 N 个 {@code (规则键, 原值)} 对。
     * 只拿行本身的 {@code ruleKey/original}（那是拼接出来的）去对，
     * 每一个成员都会被判成「对不上」，用户勾了一行却被整份拒掉。
     */
    private static boolean 命中这个值(DocumentService.HitItem hit, Exclusions.HitRef ref) {
        if (hit.ruleKey().equals(ref.ruleKey()) && hit.original().equals(ref.original())) {
            return true;
        }
        for (DocumentService.HitItem.Member m : hit.members()) {
            if (m.ruleKey().equals(ref.ruleKey()) && m.original().equals(ref.original())) {
                return true;
            }
        }
        return false;
    }

    private static String 描述序号(int occurrence) {
        return occurrence == Exclusions.HitRef.ANY
                ? "「整条不脱敏」"
                : "第 " + occurrence + " 次出现";
    }

    /** 取成品字节，供下载。 */
    public byte[] outputBytes(Job job) throws IOException {
        if (job.outputName == null) {
            throw new BadRequestException("还没有应用，请先调用 /api/apply");
        }
        return java.nio.file.Files.readAllBytes(workspace.pathOf(job.outputName));
    }

    public synchronized Job job(String jobId) throws IOException {
        return require(jobId);
    }

    /**
     * 查一个任务。
     *
     * <p><b>这里必须有锁，而且是以前就缺的那把锁。</b>{@code jobs} 是
     * <b>access-order</b> 的 {@link LinkedHashMap}：它的 {@code get()} 不是只读——
     * 它会把命中的条目挪到链表末尾（这正是 LRU 淘汰要的行为）。
     * 不加锁的话，四个 HTTP 线程同时查任务就会在改同一条链表，
     * 轻则淘汰顺序错乱，重则链表结构损坏。
     *
     * <p>查任务只是一次 map 查找，锁的持有时间是纳秒级；
     * 真正慢的脱敏已经挪到监视器外面去了（见 {@link #scanWith}）。
     */
    private synchronized Job require(String jobId) throws IOException {
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null) {
            // 不区分「不存在」与「已过期」：两者对调用方是同一件事，
            // 报得越细，越像在给人一个可以枚举的接口。
            throw new BadRequestException("文件不存在或已过期，请重新上传");
        }
        return job;
    }

    public synchronized int size() {
        return jobs.size();
    }

    /**
     * 淘汰最久没被访问的任务，并把它的文件从磁盘上删掉。
     *
     * <p><b>为什么是淘汰而不是直接报错</b>：M2-b 的做法是「满了就拒绝新的」。
     * 那意味着用户传到第 65 份文件时，除了关掉服务重来没有别的办法——
     * 而他很可能只是想接着传第 65 份而已。淘汰掉一份他早就下载完的老文件，
     * 比让他重做一遍要合理得多。
     *
     * <p><b>为什么必须连磁盘一起清</b>：只在内存里抹掉条目的话，
     * 原件、预览件、成品件三份文件会一直留在临时目录里直到进程退出。
     * 这是一个处理敏感文档的工具，用户以为「处理完就清掉了」，
     * 实际上原始文档还在磁盘上——那是最不能出现的落差。
     */
    private void evictOldest() {
        Iterator<Map.Entry<String, Job>> it = jobs.entrySet().iterator();
        if (!it.hasNext()) {
            return;
        }
        Map.Entry<String, Job> oldest = it.next();
        it.remove();
        Job job = oldest.getValue();
        evicted++;
        workspace.delete(job.sourceName);
        workspace.delete(job.scanSnapshot.previewName());
        workspace.delete(job.outputName);
        // 映射件同样含原值：留着它等于把用户文档里的原值留在磁盘上，
        // 而界面上早就看不见这个任务了。
        workspace.delete(job.mappingName);
    }

    /** 因超上限而被淘汰掉的任务数，供 /api/health 如实报出。 */
    public int evicted() {
        return evicted;
    }

    /** volatile：淘汰发生在监视器里，读发生在任何线程上。 */
    private volatile int evicted;

    /**
     * 读出客户端报上来的文件名。
     *
     * <p><b>必须百分号解码。</b>HTTP 头是 ISO-8859-1 的，中文文件名直接塞进去会变成问号
     * ——实测「合同.docx」过去就是以 {@code ??.docx} 回来的。一个把用户文件显示成
     * {@code ??.docx} 的界面，会让人以为是文件坏了而不是工具坏了。
     * 客户端用 {@code encodeURIComponent} 发，服务端在这里解，解不动就按原样用。
     */
    static String clientFileName(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst(ApiHandlers.FILENAME_HEADER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法转义序列：不是我们约定的客户端发的，按原样用，
            // 后面的白名单与清洗会照常处理它
            return raw;
        }
    }

    // ------------------------------------------------------------- 请求校验

    /**
     * 写接口的 {@code Content-Type} 必须是指定的那一种。
     *
     * <p>为什么不能只靠令牌：令牌走自定义头，HTML 表单设不了自定义头——这确实挡住了表单。
     * 但把防线全部押在「浏览器一定会先发预检」上是不对的：
     * 一旦将来有人为了加载项加了 CORS 头，这道门就自动开了。所以类型也要卡。
     */
    static void requireContentType(HttpExchange exchange, String expected) throws BadRequestException {
        String actual = exchange.getRequestHeaders().getFirst("Content-Type");
        if (actual == null || !actual.toLowerCase(java.util.Locale.ROOT).startsWith(expected)) {
            throw new BadRequestException("请求类型必须是 " + expected + "，收到: " + actual);
        }
    }

    /** 请求本身不合法。 */
    public static class BadRequestException extends IOException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    // -------------------------------------------------------------- 响应

    /**
     * {@code POST /api/apply} 的请求体。
     *
     * <p><b>{@code mapPassphrase} 缺省就是「不加密」</b>：老版本的客户端
     * （以及本工具自己的旧脚本）发过来的报文里没有这个字段，
     * 那时的行为是写明文映射件——现在也必须一样，否则升级一下就把用户的产物格式变了。
     *
     * <p>它<b>不会</b>被存进任务对象：口令只需要活到映射件写完那一刻，
     * 留在 job 上等于把它延长到整个会话的生命周期。
     */
    public record ApplyRequest(String jobId, List<Exclusions.HitRef> excluded, String mapPassphrase) {
    }

    /**
     * {@code POST /api/terms} 的请求体。
     *
     * @param jobId 任务号
     * @param terms 用户补的敏感词
     */
    public record TermsRequest(String jobId, List<String> terms) {
    }

    /**
     * 把命中与结果摊平成给界面的 JSON。
     *
     * @param excludedCount 本次被排除的命中条数，如实报出来——
     *                      界面上「我排除了 3 条」和报告里的数字必须是同一个数
     */
    public static Map<String, Object> describe(Job job, DocumentService.Result result,
                                               String stage, int excludedCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("stage", stage);
        out.put("jobId", job.jobId);
        out.put("format", job.format.name());
        out.put("formatLabel", job.format.label());
        out.put("fileName", job.displayName());
        out.put("downloadName", job.downloadName());
        boolean appliedStage = "applied".equals(stage);
        ProcessingIdentity identity = appliedStage ? job.appliedIdentity : job.scanIdentity();
        out.put("toolIdentity", identityMap(identity));
        out.put("processingRevision", appliedStage ? job.appliedRevision : job.scanRevision());
        if (job.mappingName != null) {
            // 只有应用过才有映射件与还原件。scan 阶段报这两个名字会让界面
            // 以为「现在就能下载映射」——那时候点下去只会拿到一个 400。
            out.put("mappingName", job.mappingDownloadName());
            out.put("restoreName", job.restoreDownloadName());
            // 映射件封没封：界面据此决定要不要提示「还原时需要同一个口令」。
            // 少了这个字段，界面只能按「一定是明文」或「一定加密」二选一，
            // 两个都会让用户对这份文件能不能还原做出错误判断。
            out.put("mappingSealed", job.mappingSealed);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        // 计数一律按「值」而不是「行」：归组后一行可能代表 N 个值，
        // 用户勾的是值、脱敏的也是值，报告里写「保留 3 个值」才与实际相符。
        int values = 0;
        for (DocumentService.HitItem h : result.hits()) {
            values += h.valueCount();
        }
        summary.put("distinctHits", values);
        summary.put("rows", result.hits().size());
        summary.put("occurrences", result.occurrences());
        summary.put("excluded", excludedCount);
        summary.put("redacted", values - excludedCount);
        out.put("summary", summary);
        out.put("stats", result.stats().values());
        // notes：扫描阶段本来就有的说明 + **零命中的四种区分**。
        // 少了后者，「零命中」会被读成「这份文件很干净」。
        List<String> notes = new ArrayList<>(result.notes());
        notes.addAll(zeroHitNotes(job, result));
        out.put("notes", notes);
        // 用户补的词要回给界面：它得**看得见自己补了什么**，
        // 否则用户会以为「补了没反应」。
        if (!job.userTerms.isEmpty()) {
            out.put("userTerms", List.copyOf(job.userTerms));
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        for (DocumentService.HitItem h : result.hits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleKey", h.ruleKey());
            item.put("original", h.original());
            item.put("token", h.token());
            item.put("occurrences", h.occurrences());
            // 位置说明带着自己的出现序号。界面上「只保留第 N 处」勾的就是这个序号，
            // 引擎判「第 N 次」用的也必须是同一个数——中间不许有人重数一遍。
            List<Map<String, Object>> locators = new ArrayList<>();
            for (DocumentService.HitItem.Loc l : h.locators()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("occurrence", l.occurrence());
                one.put("locator", l.locator());
                locators.add(one);
            }
            item.put("locators", locators);
            item.put("hasLocators", h.hasLocators());
            // 归组信息：这一行由哪几处命中拼成。界面据此显示
            // 「原文里紧挨着的 N 处命中」，勾选时按 N 个 (规则键, 原值) 发回排除项。
            item.put("valueCount", h.valueCount());
            List<Map<String, Object>> members = new ArrayList<>();
            for (DocumentService.HitItem.Member m : h.members()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("ruleKey", m.ruleKey());
                one.put("original", m.original());
                one.put("token", m.token());
                members.add(one);
            }
            item.put("members", members);
            hits.add(item);
        }
        out.put("hits", hits);
        return out;
    }

    private static Map<String, Object> identityMap(ProcessingIdentity identity) {
        ProcessingIdentity actual = java.util.Objects.requireNonNull(identity,
                "作业缺少处理身份快照，拒绝用当前状态替代旧作业身份");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolVersion", actual.toolVersion());
        out.put("toolArtifactSha256", actual.toolArtifactSha256());
        out.put("usedModel", actual.usedModel());
        out.put("modelId", actual.modelId());
        out.put("modelVersionKey", actual.modelVersionKey());
        out.put("modelPackageSha256", actual.modelPackageSha256());
        out.put("modelSigningKeyId", actual.modelSigningKeyId());
        out.put("signatureVerified", actual.signatureVerified());
        out.put("strictProductionEligible", actual.strictProductionEligible());
        return out;
    }
}
