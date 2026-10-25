package com.litemask.ner;

import com.litemask.core.EntitySource;
import com.litemask.core.ExclusiveGate;
import com.litemask.core.Match;
import com.litemask.core.ProcessingIdentity;
import com.litemask.core.ToolIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * 模型的生命周期管理：<b>装、验、载、回退、报状态</b>。
 *
 * <h2>为什么要有它</h2>
 * 过去模型是<b>与程序分开分发</b>的产物，用户拿到程序之后还要自己去装：
 * {@code model install}、{@code model info}——都得敲命令。
 * 那不是「少一个功能」，是<b>产品没交付完</b>：
 * 一个普通人拿到安装包，双击装好，然后发现自己还要开终端——
 * 他多半就不会继续用了，而没脱干净的文档比没脱敏更危险。
 *
 * <p>所以模型必须<b>随安装包分发</b>，并由界面自动完成准备。
 * 而「准备」不是「把文件复制过去」——
 * 它必须真的<b>验签、算摘要、原子换装、真的把模型加载起来</b>，
 * 否则界面上的「已就绪」就是一句谎话。
 *
 * <h2>复用既有代码，不重写</h2>
 * 验签、摘要、逐文件校验、原子换装、历史版本与回退，
 * <b>全都已经在</b> {@link ModelTrust} / {@link ModelPackageInstaller} /
 * {@link ModelPackageStore} / {@link NerModelPackage} 里了，而且都带测试。
 * 本类<b>只做编排</b>（什么时候做、做成什么样的话给用户），
 * <b>不重新实现任何一条判定</b>——重写的那份迟早与原件分叉，
 * 而分叉出来的那份正是「装了个坏模型还说就绪」这类事故的温床。
 *
 * <h2>状态机</h2>
 * <pre>
 *   未安装 ──装──▶ 正在校验 ──▶ 正在安装 ──▶ 可用
 *                       │            │
 *                       └── 失败 ◀───┘
 * </pre>
 * <ul>
 *   <li><b>正在校验</b> = 正在验签名与摘要（模型包是不是发布方签的）；</li>
 *   <li><b>正在安装</b> = 正在解压、换装、加载；</li>
 *   <li><b>可用</b> = 模型真的加载起来了，推理引擎真的能用；</li>
 *   <li><b>损坏</b> = 装了一半/文件损坏/加载不了；</li>
 *   <li><b>更新失败</b> = 更新没成功，<b>但原来那份还能用</b>。</li>
 * </ul>
 *
 * <h2>「可用」不是「文件在」</h2>
 * 只有<b>模型真的被 ONNX Runtime 加载起来</b>才叫可用。
 * 文件在、目录对、清单合法——这些都还不够：
 * 模型与词表不配套、初始化张量不在、推理引擎起不来，
 * 都会让「文件在」与「能用」分道扬镳。
 * 而用户在这两件事上看到的东西长得一模一样（界面都显示绿字），
 * 所以判定必须落在<b>真的加载一次</b>上。
 *
 * <h2>换模型时不让用户去杀进程</h2>
 * Windows 上模型文件被本进程打开时挪不动。
 * 本类用一把<b>公平读写锁</b>协调：
 * 正在跑的任务跑完当前一步（不丢用户的文档），
 * 新任务排队（不在换到一半时开扫），
 * 换完自动恢复（用户不用重开程序）。
 * 详见 {@link ExclusiveGate}。
 */
public final class ModelManager implements ExclusiveGate, AutoCloseable {

    /**
     * 随安装包分发的模型包在程序目录下的位置。
     *
     * <p><b>为什么是 {@code models/ner-package}</b> 而不是 {@code models/ner}：
     * {@code models/ner} 是<b>装好之后</b>模型所在的位置（加载器直接看它），
     * 而这里放的是<b>还没装</b>的模型包（一个 zip 加它的签名）——
     * 两者是不同阶段的东西，混在一个目录里会让人以为「放进去了就算装好了」。
     */
    public static final String BUNDLED_SUBDIR = "models/ner-package";

    /** 离线模型包（外层 zip）允许的条目数上限。 */
    private static final int MAX_BUNDLE_ENTRIES = 64;

    /** 离线模型包（外层 zip）解压后的总字节上限。 */
    private static final long MAX_BUNDLE_BYTES = 512L * 1024 * 1024;

    /**
     * 模型的状态。
     *
     * <p><b>为什么是这六个</b>：这是普通用户能理解、并且能对应到「我现在该点什么」的
     * 全集。多一个「待确认」之类，用户就不知道该做什么了。
     */
    public enum State {
        /** 未安装。 */
        NOT_INSTALLED("未安装"),
        /** 正在安装（解压、换装、加载）。 */
        INSTALLING("正在安装"),
        /** 正在校验（验签名与摘要）。 */
        VERIFYING("正在校验"),
        /** 可用：模型真的加载起来了。 */
        READY("可用"),
        /** 损坏：装了一半、文件坏了、或加载不了。 */
        BROKEN("损坏"),
        /** 更新失败：<b>原来那份仍可使用</b>。 */
        UPDATE_FAILED("更新失败");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /** 给用户看的名字。 */
        public String label() {
            return label;
        }
    }

    /**
     * 模型状态的对外视图。
     *
     * <p><b>刻意不包含文件路径</b>：目标用户是普通人，
     * {@code C:\Users\xxx\.litemask\models\ner} 这种字符串对他没有意义，
     * 只会让他以为自己得管这个目录。
     * 需要排障时看<b>日志</b>，不看界面。
     *
     * @param state                状态
     * @param usable               模型<b>真的</b>可用（已加载、推理引擎能跑）
     * @param modelId              当前模型版本（清单里的 id）
     * @param personName           是否支持人名识别
     * @param organizationName     是否支持机构名识别
     * @param message              给普通用户看的一句话
     * @param nextStep             出错后用户可以点的那一步
     * @param actions              当前可用的操作（界面据此显示按钮）
     * @param bundledAvailable     安装包里是否带了随附模型
     * @param bundledModelId       随附模型的版本（没带则为 null）
     * @param canRollback          有没有可回退的历史版本
     * @param failure              失败原因（人话）；没有失败则为 null
     */
    public record Status(State state,
                         boolean usable,
                         String modelId,
                         String modelVersionKey,
                         String modelPackageSha256,
                         String modelSigningKeyId,
                         boolean signatureVerified,
                         boolean personName,
                         boolean organizationName,
                         String message,
                         String nextStep,
                         List<String> actions,
                         boolean bundledAvailable,
                         String bundledModelId,
                         boolean canRollback,
                         String failure) {
    }

    /**
     * 安装包里随附的模型包。
     *
     * @param zip 模型包 zip
     * @param sig 它的签名文件
     * @param modelId 清单里的 id（读不出来时为 null，仅用于显示）
     */
    public record BundledPackage(Path zip, Path sig, String modelId) {
    }

    private final Path storeDir;
    private final ModelPackageStore store;
    private final Path explicitDir;
    /**
     * 随附模型包的目录；非 null 时<b>只看这一个</b>。
     *
     * <p><b>为什么要有这个缝</b>：随附模型包的位置在生产里是「程序目录旁边」，
     * 而那个位置是从代码来源推导出来的——测试里没法自然地摆一个模型包进去。
     * 做成实例参数后，测的是<b>同一段代码、同一个判定</b>，只是把「包在哪」指到了别处。
     */
    private final Path bundleDir;

    /**
     * 公平读写锁：扫描拿读锁，换模型拿写锁。
     *
     * <p><b>为什么必须公平</b>：非公平模式下，排队等写锁的线程可能被源源不断的
     * 新读者<b>插队</b>（reader barging）——于是「用户点了更新」这件事
     * 可能一直排在不断到来的新任务后面，永远轮不到。
     * 公平模式下一个等待中的写者会<b>挡住后来的读者</b>：
     * 新任务排队（用户看到的是「正在更新，稍等」），
     * 写者拿到锁后完成换装，然后一切恢复。
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private final AtomicReference<NerModelRegistry> registry = new AtomicReference<>();

    private volatile State state = State.NOT_INSTALLED;
    private volatile String failure;
    private volatile String nextStep;
    private volatile boolean installedFromBundle;
    private volatile ModelPackageStore.Provenance activeProvenance;

    /** 生产构造：装到当前用户的数据目录。 */
    public ModelManager() {
        this(NerModelRegistry.defaultUserModelDir(), null, null);
    }

    /**
     * 全参构造。
     *
     * @param storeDir    模型包仓库（生效目录 + 历史版本）
     * @param explicitDir 用户显式指定的模型目录；非 null 时用它，且<b>不</b>自动装随附模型
     */
    public ModelManager(Path storeDir, Path explicitDir) {
        this(storeDir, explicitDir, null);
    }

    /**
     * 全参构造（含随附模型包的位置）。
     *
     * @param storeDir    模型包仓库（生效目录 + 历史版本）
     * @param explicitDir 用户显式指定的模型目录；非 null 时用它，且<b>不</b>自动装随附模型
     * @param bundleDir   随附模型包所在目录；非 null 时<b>只看这一个</b>
     */
    public ModelManager(Path storeDir, Path explicitDir, Path bundleDir) {
        this.storeDir = Objects.requireNonNull(storeDir, "storeDir").toAbsolutePath().normalize();
        this.explicitDir = explicitDir == null ? null : explicitDir.toAbsolutePath().normalize();
        this.bundleDir = bundleDir == null ? null : bundleDir.toAbsolutePath().normalize();
        this.store = new ModelPackageStore(this.storeDir);
    }

    // ------------------------------------------------------------------ 闸门

    @Override
    public Release acquire() {
        lock.readLock().lock();
        return lock.readLock()::unlock;
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 启动时同步做的那部分：<b>恢复中断的换装</b> + <b>载入生效中的模型</b>。
     *
     * <p><b>为什么要恢复中断的换装</b>：换装是两次改名
     * （生效目录 → 历史目录、新目录 → 生效目录）。
     * 进程若恰好死在两次改名之间，生效目录就<b>不存在</b>了，
     * 而原来那份还在历史里躺着。这时候直接说「未安装」是失实的——
     * 用户明明有一份能用的模型。于是这里把它换回来。
     *
     * <p>这一步必须在服务起来<b>之前</b>做完：界面第一眼看到的状态必须是真的。
     */
    public void bootstrapSync() {
        try {
            recoverInterruptedInstall();
        } catch (IOException e) {
            log("恢复中断的安装失败：" + e.getMessage());
        }
        loadActive();
    }

    /**
     * 首次启动的自动准备：没有可用模型时，把<b>随安装包分发</b>的模型装上。
     *
     * <p><b>为什么放在后台线程</b>：装模型要解压 13MB 并把 ONNX 模型加载起来，
     * 大约几秒。让用户等这几秒才看到界面，体验很差；
     * 而界面本就可以显示「正在准备模型…」，进度是<b>真实操作结果</b>，
     * 不是假的百分比条。
     *
     * <p><b>为什么失败不致命</b>：模型装不上时程序仍然能脱敏（只是人名不脱），
     * 所以这里<b>不抛异常</b>，而是把失败如实记进状态里，让界面显示出来。
     */
    public void bootstrapAsync() {
        if (explicitDir != null) {
            // 用户显式指定了模型目录：那是他的选择，不该被随附模型覆盖。
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (isModelUsable()) {
                    return;
                }
                Optional<BundledPackage> bundled = findBundledPackage();
                if (bundled.isEmpty()) {
                    return;
                }
                log("首次启动：自动安装随附模型 " + bundled.get().modelId());
                install(bundled.get().zip(), false);
                installedFromBundle = true;
            } catch (RuntimeException e) {
                log("自动安装随附模型失败：" + e);
            }
        }, "litemask-model-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把「上次换装被中断」留下的状态收拾干净。
     *
     * <p>判据：生效目录<b>不存在</b>，而历史目录里<b>有</b>版本。
     */
    private void recoverInterruptedInstall() throws IOException {
        if (explicitDir != null) {
            return;
        }
        if (Files.isDirectory(storeDir)) {
            return;
        }
        // 注意：这里**不**因为「别处还有个能用的模型」就放弃恢复。
        // 仓库自己的状态（生效目录没了、历史里有）就该收拾干净，
        // 否则这份仓库一直处于半装状态 —— 而下一次安装会以它为前提。
        List<ModelPackageStore.Version> versions = store.list();
        boolean hasHistory = versions.stream().anyMatch(v -> !v.active());
        if (!hasHistory) {
            return;
        }
        log("检测到上一次换装被中断，把历史里的最近一版换回生效位置");
        ModelPackageStore.RollbackResult r = store.rollback(null);
        log("已恢复：" + r.modelId());
    }

    // ------------------------------------------------------------------ 装

    /**
     * 装随安装包分发的模型。
     *
     * @return 装完后的状态
     */
    public Status installBundled() {
        Optional<BundledPackage> bundled = findBundledPackage();
        if (bundled.isEmpty()) {
            return markFailure(State.NOT_INSTALLED, false,
                    "安装包里没有随附的识别模型。",
                    "请用「选择离线模型包」从文件安装。");
        }
        return install(bundled.get().zip(), true);
    }

    /**
     * 装一个离线模型包（用户选的那个文件）。
     *
     * <p><b>离线包是「一个文件」</b>：里面已经装着模型包与它的签名。
     * 用户只需要选一次，不需要分别去找 {@code .sha256}、{@code .sig} 或公钥——
     * 「让用户自己去凑齐验证材料」是普通用户最常见的中途放弃点。
     *
     * @param bundle 用户选中的那个文件
     * @return 装完后的状态
     */
    public Status importOffline(Path bundle) {
        Path work = null;
        try {
            work = Files.createTempDirectory("litemask-model-import-");
            List<String> names = ModelPackageInstaller.extractArchive(
                    bundle, work, MAX_BUNDLE_ENTRIES, MAX_BUNDLE_BYTES);
            Path inner = null;
            Path sig = null;
            for (String name : names) {
                Path p = work.resolve(name).normalize();
                if (!p.startsWith(work)) {
                    continue;
                }
                String base = p.getFileName().toString();
                if (base.endsWith(".zip.sig")) {
                    sig = p;
                } else if (base.endsWith(".zip")) {
                    inner = p;
                }
            }
            if (inner == null) {
                throw new IOException("离线包里没有找到模型包文件。");
            }
            if (sig == null) {
                throw new IOException("离线包里没有找到验证文件（模型包必须带签名才能安装）。");
            }
            // 签名文件必须与模型包**并排**放在同一个目录里：
            // 验签那一步是按「同名 + .sig」找它的（见 ModelTrust.signatureFileFor）。
            return install(inner, true);
        } catch (IOException e) {
            log("离线模型包安装失败：" + e.getMessage());
            return markFailure(currentStateAfterFailure(), currentlyUsable(),
                    "这个模型包装不上：" + plainLanguage(e),
                    nextStepFor(e));
        } finally {
            if (work != null) {
                ModelPackageInstaller.deleteRecursively(work);
            }
        }
    }

    /**
     * 装一个模型包 zip（签名必须与它并排）。
     *
     * @param zip       模型包
     * @param userInitiated 用户是不是主动点的（决定失败后算「更新失败」还是「未安装」）
     * @return 装完后的状态
     */
    private Status install(Path zip, boolean userInitiated) {
        lock.writeLock().lock();
        try {
            boolean hadUsable = currentlyUsable();
            state = State.VERIFYING;
            failure = null;
            nextStep = null;

            // ---- 第 1 步：验签与摘要 ----
            // 先验签再动磁盘：验不过时，这台机器上还没有多出任何东西。
            try {
                ModelTrust.verifyPackage(zip);
            } catch (IOException e) {
                log("模型包验签没通过：" + e.getMessage());
                return markFailure(hadUsable ? State.UPDATE_FAILED : State.NOT_INSTALLED,
                        hadUsable,
                        hadUsable
                                ? "新模型没装上，但当前这一版仍然可以正常使用。"
                                : "这个模型包装不上：" + plainLanguage(e),
                        nextStepFor(e));
            }

            // ---- 第 2 步：释放旧模型 ----
            // Windows 上模型文件被打开时挪不动 → 先把会话关掉、把手柄放开。
            // 这一步由程序自己做，用户不需要去杀进程、也不需要执行命令。
            releaseCurrentModel();

            // ---- 第 3 步：原子换装 ----
            state = State.INSTALLING;
            try {
                ModelPackageStore.InstallResult r = store.install(zip);
                log("已安装模型包 " + r.modelId() + "（版本键 " + r.key() + "）");
            } catch (IOException e) {
                log("模型包装不上：" + e.getMessage());
                // 装失败 = 原来那份还在（store 的换装是原子的）。
                // 这里必须把它重新载起来，否则用户会从「能用」掉到「不能用」——
                // 明明失败的那次安装压根没碰坏任何东西。
                loadActive();
                return markFailure(hadUsable ? State.UPDATE_FAILED : State.BROKEN,
                        hadUsable,
                        hadUsable
                                ? "新模型没装上，但当前这一版仍然可以正常使用。"
                                : "模型装不上：" + plainLanguage(e),
                        nextStepFor(e));
            }

            // ---- 第 4 步：真的加载一次，确认推理引擎可用 ----
            loadActive();
            if (!isModelUsable()) {
                // 装得上但加载不了：这是**损坏**，不是「可用」。
                return markFailure(State.BROKEN, false,
                        "模型装上了，但没能加载成功，识别功能暂不可用。",
                        "请点「重试」重新安装；仍不行就点「选择离线模型包」换一个文件。");
            }
            state = State.READY;
            failure = null;
            nextStep = null;
            return status();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 回退到上一个版本。
     *
     * @return 回退后的状态
     */
    public Status rollback() {
        lock.writeLock().lock();
        try {
            releaseCurrentModel();
            ModelPackageStore.RollbackResult r;
            try {
                r = store.rollback(null);
            } catch (IOException e) {
                log("回退失败：" + e.getMessage());
                loadActive();
                return markFailure(currentStateAfterFailure(), currentlyUsable(),
                        "回退失败：" + plainLanguage(e),
                        "请点「重试」再试一次。");
            }
            log("已回退到版本 " + r.key());
            loadActive();
            if (!isModelUsable()) {
                return markFailure(State.BROKEN, false,
                        "回退后模型没能加载成功。",
                        "请点「重试」重新安装随附模型。");
            }
            state = State.READY;
            failure = null;
            nextStep = null;
            return status();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------ 状态

    /**
     * 当前状态。
     *
     * @return 状态快照
     */
    public Status status() {
        NerModelRegistry r = registry.get();
        boolean usable = r != null && r.isEnabled();
        String modelId = null;
        boolean person = false;
        boolean org = false;
        if (usable) {
            NerModelPackage.Manifest m = manifestOf(r);
            if (m != null) {
                modelId = m.id();
                person = supports(m, "person");
                org = supports(m, "organization");
            }
        }
        List<String> actions = new ArrayList<>();
        if (state == State.READY) {
            actions.add("update");
            if (canRollback()) {
                actions.add("rollback");
            }
        } else {
            actions.add("install");
        }
        actions.add("import");
        Optional<BundledPackage> bundled = findBundledPackage();
        ModelPackageStore.Provenance provenance = activeProvenance;
        return new Status(state, usable, modelId,
                provenance == null ? null : provenance.versionKey(),
                provenance == null ? null : provenance.packageSha256(),
                provenance == null ? null : provenance.signingKeyId(),
                provenance != null,
                person, org,
                currentMessage(usable),
                nextStep,
                actions,
                bundled.isPresent(),
                bundled.map(BundledPackage::modelId).orElse(null),
                canRollback(),
                failure);
    }

    /**
     * 取得与当前推理来源一致的不可变身份快照。
     *
     * <p>读锁把「读注册表/来源」与模型换装隔开；扫描方在同一把闸门内取得本快照并运行
     * 推理，因此不会出现“命中由 A 产生、身份却在结束后读成 B”的串版。
     */
    public ProcessingIdentity processingIdentity() {
        lock.readLock().lock();
        try {
            NerModelRegistry current = registry.get();
            boolean usedModel = current != null && current.isEnabled();
            if (!usedModel) {
                return ProcessingIdentity.withoutModel();
            }
            NerModelPackage.Manifest manifest = requireManifestOf(current);
            ModelPackageStore.Provenance provenance = activeProvenance;
            return new ProcessingIdentity(
                    ToolIdentity.version(),
                    ToolIdentity.artifactSha256(),
                    true,
                    manifest.id(),
                    provenance == null ? null : provenance.versionKey(),
                    provenance == null ? null : provenance.packageSha256(),
                    provenance == null ? null : provenance.signingKeyId(),
                    provenance != null);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 现在的模型是不是真的能用。
     *
     * @return 能用就 true
     */
    public boolean isModelUsable() {
        NerModelRegistry r = registry.get();
        return r != null && r.isEnabled();
    }

    /** 当前的模型注册表（供引擎与命令行取用）。 */
    public NerModelRegistry registry() {
        return registry.get();
    }

    /**
     * 给引擎用的<b>活</b>来源。
     *
     * <p><b>为什么不是直接给 {@code registry.sources()}</b>：那样的话，
     * 模型在服务运行期间装上之后，引擎仍然看不到它——
     * 来源列表在服务启动那一刻就定下来了。
     * 而「装完模型立刻能用」正是这个界面的核心承诺。
     *
     * <p>这个来源每次被问到都去看<b>当前</b>的注册表，
     * 所以装好模型的下一秒钟，下一份文档就会用上它。
     *
     * @return 活来源
     */
    public EntitySource source() {
        return new LiveNerSource(this);
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            releaseCurrentModel();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 模型可能待在哪几个目录，<b>按优先级</b>排。
     *
     * <ol>
     *   <li>用户显式指定的目录（给了就<b>只看这一个</b>）；</li>
     *   <li><b>用户数据目录</b>（{@code ~/.litemask/models/ner}）——
     *       这是「装好了」的正式位置，也是安装的目标；</li>
     *   <li>程序目录旁的 {@code models/ner}——
     *       旧的「随包分发已解开模型」布局，留着是为了不破坏既有用法。</li>
     * </ol>
     *
     * <p><b>为什么用户数据目录排在程序目录前面</b>：装好的模型必须能<b>盖过</b>
     * 程序自带的那份，否则「更新了模型却还在用旧的那份」会成为最难查的一类问题。
     */
    private List<Path> candidateDirs() {
        if (explicitDir != null) {
            return List.of(explicitDir);
        }
        List<Path> out = new ArrayList<>();
        out.add(storeDir);
        Path programDir = NerModelRegistry.programDirectory();
        if (programDir != null) {
            Path legacy = programDir.resolve("models").resolve("ner");
            if (!legacy.equals(storeDir)) {
                out.add(legacy);
            }
        }
        return out;
    }

    /**
     * 把生效目录里的模型载入注册表。
     *
     * <p><b>载入失败要如实记成「损坏」</b>，不能当成「没装」：
     * 前者是故障（用户装了模型、工具说能用、实际不能用），
     * 后者是正常状态。两者的用户下一步完全不同。
     */
    private void loadActive() {
        Path dir = null;
        for (Path candidate : candidateDirs()) {
            if (Files.isDirectory(candidate)) {
                dir = candidate;
                break;
            }
        }
        if (dir == null) {
            activeProvenance = null;
            registry.set(NerModelRegistry.disabled(storeDir, "没有找到识别模型"));
            if (explicitDir == null) {
                state = State.NOT_INSTALLED;
                failure = null;
                nextStep = null;
            } else {
                state = State.BROKEN;
                failure = "指定的模型目录不存在。";
                nextStep = "请检查该目录，或改用「安装随附模型」。";
            }
            return;
        }
        if (explicitDir == null) {
            try {
                activeProvenance = store.verifyActiveProvenance();
            } catch (IOException e) {
                activeProvenance = null;
                registry.set(NerModelRegistry.disabled(dir,
                        "模型签名来源无法复验：" + e.getMessage()));
                state = State.BROKEN;
                failure = "识别模型存在，但签名来源无法复验。";
                nextStep = "请从已签名模型包重新安装。";
                log("模型签名来源无法复验：" + e.getMessage());
                return;
            }
        } else {
            // --ner-model 是显式开发/排障入口：允许目录直载，但身份明确标成未验签。
            activeProvenance = null;
        }
        NerModelPackage pkg;
        try {
            pkg = NerModelPackage.load(dir);
        } catch (NerModelPackage.InvalidModelPackage e) {
            registry.set(NerModelRegistry.disabled(dir, "模型包校验不通过：" + e.getMessage()));
            state = State.BROKEN;
            failure = "识别模型已安装，但内容不完整或已损坏。";
            nextStep = "请点「重试」重新安装。";
            log("模型包校验不通过：" + e.getMessage());
            return;
        } catch (ai.onnxruntime.OrtException e) {
            registry.set(NerModelRegistry.disabled(dir, "模型加载失败：" + e.getMessage()));
            state = State.BROKEN;
            failure = "识别模型已安装，但推理引擎没能加载它。";
            nextStep = "请点「重试」重新安装；仍不行就点「选择离线模型包」换一个文件。";
            log("模型加载失败：" + e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            registry.set(NerModelRegistry.disabled(dir, "模型加载失败：" + e.getMessage()));
            state = State.BROKEN;
            failure = "识别模型已安装，但没能加载成功。";
            nextStep = "请点「重试」重新安装。";
            log("模型加载失败：" + e);
            return;
        }
        // 载入成功：注册表接管这份包，它随本对象一起关闭。
        registry.set(NerModelRegistry.enabled(pkg));
        state = State.READY;
        failure = null;
        nextStep = null;
    }

    /**
     * 关掉当前模型，释放它对文件的占用。
     *
     * <p><b>不做这一步就换不了模型</b>：Windows 上被打开的文件挪不动。
     * 这一步是「程序自己协调释放资源」的关键一环——
     * 用户不需要去找进程、更不需要执行命令。
     */
    private void releaseCurrentModel() {
        NerModelRegistry r = registry.get();
        if (r == null) {
            return;
        }
        try {
            r.close();
        } catch (RuntimeException e) {
            log("关闭旧模型时出错：" + e);
        }
        registry.set(NerModelRegistry.disabled(storeDir, "模型正在更换"));
    }

    /**
     * 找到随安装包分发的模型包。
     *
     * @return 找到了就有值
     */
    public Optional<BundledPackage> findBundledPackage() {
        for (Path dir : bundledSearchDirs(bundleDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                List<Path> zips = s
                        .filter(p -> p.getFileName().toString().endsWith(".zip"))
                        .filter(p -> Files.isRegularFile(ModelTrust.signatureFileFor(p)))
                        .sorted(Comparator
                                .comparingLong(ModelManager::lastModified).reversed()
                                .thenComparing(p -> p.getFileName().toString()))
                        .toList();
                if (zips.isEmpty()) {
                    continue;
                }
                Path zip = zips.get(0);
                return Optional.of(new BundledPackage(zip,
                        ModelTrust.signatureFileFor(zip), readManifestId(zip)));
            } catch (IOException e) {
                log("看随附模型目录时出错：" + e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * 随附模型包可能在哪几个目录。
     *
     * <p><b>为什么是两个候选</b>：jpackage 的 app-image 里，jar 放在
     * {@code <app>/app/} 下，而启动器在 {@code <app>/} 下。
     * 两个布局都试一遍，比写死一个「以后布局变了就装错地方」要稳。
     */
    private static List<Path> bundledSearchDirs(Path bundleDir) {
        List<Path> out = new ArrayList<>();
        if (bundleDir != null) {
            out.add(bundleDir);
            return out;
        }
        Path programDir = NerModelRegistry.programDirectory();
        if (programDir != null) {
            out.add(programDir.resolve(BUNDLED_SUBDIR));
            Path parent = programDir.getParent();
            if (parent != null) {
                out.add(parent.resolve(BUNDLED_SUBDIR));
            }
        }
        return out;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 读模型包清单里的 id（只用于显示）。
     *
     * <p>读不出来就返回 {@code null}——这<b>不是</b>错误：
     * 显示用的东西拿不到，不该挡住「能不能装」这件正事。
     */
    private String readManifestId(Path zip) {
        try (InputStream in = Files.newInputStream(zip);
             java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!"manifest.json".equals(e.getName())) {
                    continue;
                }
                byte[] bytes = zin.readAllBytes();
                com.fasterxml.jackson.databind.JsonNode root =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
                com.fasterxml.jackson.databind.JsonNode id = root.get("id");
                return id == null || !id.isTextual() ? null : id.textValue();
            }
        } catch (IOException | RuntimeException e) {
            log("读随附模型的清单失败（只影响显示）：" + e.getMessage());
        }
        return null;
    }

    private static NerModelPackage.Manifest manifestOf(NerModelRegistry r) {
        try {
            NerModelPackage pkg = NerModelRegistry.packageOf(r);
            return pkg == null ? null : pkg.manifest();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 推理已启用时，清单与其稳定 id 都是处理身份的必需组成，缺失必须停止扫描。 */
    static NerModelPackage.Manifest requireManifestOf(NerModelRegistry r) {
        try {
            NerModelPackage pkg = NerModelRegistry.packageOf(r);
            NerModelPackage.Manifest manifest = pkg == null ? null : pkg.manifest();
            if (manifest == null || manifest.id() == null || manifest.id().isBlank()) {
                throw new IllegalStateException("已启用模型缺少有效清单或非空 modelId，拒绝生成处理身份");
            }
            return manifest;
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("读取已启用模型清单失败，拒绝生成处理身份", e);
        }
    }

    private static boolean supports(NerModelPackage.Manifest m, String type) {
        List<String> types = m.entityTypes();
        return types != null && types.contains(type);
    }

    /**
     * 有没有可回退的历史版本。
     *
     * @return 有就 true
     */
    public boolean canRollback() {
        try {
            for (ModelPackageStore.Version v : store.list()) {
                if (!v.active()) {
                    return true;
                }
            }
        } catch (IOException e) {
            log("看历史版本时出错：" + e.getMessage());
        }
        return false;
    }

    private boolean currentlyUsable() {
        NerModelRegistry r = registry.get();
        return r != null && r.isEnabled();
    }

    private State currentStateAfterFailure() {
        return currentlyUsable() ? State.UPDATE_FAILED : State.BROKEN;
    }

    /**
     * 记一次失败，并把状态摆成用户能看懂的样子。
     */
    private Status markFailure(State s, boolean usable, String message, String next) {
        state = s;
        failure = message;
        nextStep = next;
        return status();
    }

    /**
     * 现在给用户看的那句话。
     */
    private String currentMessage(boolean usable) {
        if (usable) {
            return "人名与机构名识别已就绪。";
        }
        return switch (state) {
            case INSTALLING -> "正在安装识别模型，请稍候…";
            case VERIFYING -> "正在校验识别模型，请稍候…";
            case BROKEN -> failure != null ? failure : "识别模型不可用。";
            case UPDATE_FAILED -> failure != null ? failure : "更新失败。";
            case READY -> "识别模型不可用。";
            case NOT_INSTALLED -> "尚未安装识别模型：中文人名与机构名不会被脱敏。";
        };
    }

    /**
     * 把技术错误翻成普通用户能懂的话。
     *
     * <p><b>为什么不能直接显示原始异常消息</b>：原始消息里常有
     * {@code --ner-model}、{@code model trust add}、文件路径——
     * 普通用户既看不懂，也没有条件照做。
     * 照做不了的话，用户只会卡在原地。
     */
    static String plainLanguage(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("没有签名文件") || m.contains("签名文件")) {
            return "缺少验证材料，无法确认它来自发布方。";
        }
        if (m.contains("算法")) {
            return "验证方式不兼容，无法确认这个文件的来源。";
        }
        if (m.contains("另一个包")) {
            return "文件与验证信息对不上，可能没有下载完整。";
        }
        if (m.contains("不被信任")) {
            return "不是由本产品发布方签发的，来源不明或已损坏。";
        }
        if (m.contains("验不过")) {
            return "验证没通过，文件可能已经损坏。";
        }
        if (m.contains("越出安装目录") || m.contains("反斜杠") || m.contains("非法的条目名")) {
            return "文件结构异常，无法安全解压。";
        }
        if (m.contains("上限")) {
            return "文件体积超出限制，无法安全解压。";
        }
        if (m.contains("磁盘") || m.contains("space")) {
            return "磁盘空间不足。";
        }
        if (m.contains("被别的进程占用") || m.contains("另一个程序正在使用")) {
            return "文件正被占用，请稍后重试。";
        }
        if (m.contains("校验不通过") || m.contains("摘要") || m.contains("sha256")) {
            return "文件内容与清单对不上，可能已损坏。";
        }
        return "文件有问题，无法安装。";
    }

    /**
     * 出错之后，用户可以点的下一步。
     */
    private static String nextStepFor(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("磁盘") || m.contains("space")) {
            return "请清理磁盘空间后点「重试」。";
        }
        if (m.contains("被别的进程占用") || m.contains("另一个程序正在使用")) {
            return "请稍候几秒后点「重试」。";
        }
        return "请点「重试」重新安装；仍不行就点「选择离线模型包」换一个文件。";
    }

    /**
     * 写日志。
     *
     * <p><b>为什么要有文件日志</b>：装好的产品在 Windows 上是图形程序，
     * stdout 没有去处。排障时能看到的只有「界面上的那句话」，
     * 而那句话是<b>刻意写给普通人看的</b>——它不含技术细节。
     * → 细节进日志文件，出问题时看那里。
     */
    private void log(String message) {
        String line = Instant.now() + " " + message;
        System.out.println("[model] " + message);
        try {
            Path file = Path.of(System.getProperty("user.home", "."))
                    .resolve(".litemask").resolve("logs").resolve("litemask.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // 写不进去日志不该影响正事
        }
    }

    /**
     * 随注册表一起活着的来源。
     *
     * <p>它不持有模型，只在<b>被问到的时候</b>去看当前注册表——
     * 这就是「装完模型立刻生效」的实现方式。
     */
    private static final class LiveNerSource implements EntitySource {

        private final ModelManager manager;

        LiveNerSource(ModelManager manager) {
            this.manager = manager;
        }

        @Override
        public String name() {
            return "本地人名与机构名识别模型";
        }

        @Override
        public List<Match> collect(String text) {
            NerModelRegistry r = manager.registry();
            if (r == null || !r.isEnabled()) {
                // 模型还没装好 / 正在换 → 这次不认任何实体。
                // 返回空列表而不是抛异常：换模型是**计划内**的短暂状态，
                // 不该让用户的文档处理失败。
                return List.of();
            }
            List<EntitySource> sources = r.sources();
            if (sources.isEmpty()) {
                return List.of();
            }
            List<Match> found = sources.get(0).collect(text);
            return found == null ? List.of() : found;
        }

        @Override
        public boolean isActive() {
            return manager.isModelUsable();
        }
    }
}
