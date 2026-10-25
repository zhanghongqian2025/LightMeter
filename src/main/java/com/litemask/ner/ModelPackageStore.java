package com.litemask.ner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 模型包的<b>多版本仓库</b>：装、列、回退、删。
 *
 * <h2>为什么要有它</h2>
 * 模型包是<b>独立分发</b>的产物（方案 B 把模型从核心包里拆了出去）。
 * 独立分发意味着它会<b>迭代</b>：v2 修了个漏识别的毛病，v3 又换了训练集。
 * 于是「装上新的」是常态，而「新版本不如旧版本」也是常态——
 * 新版可能漏掉某些名字，可能对某类文书效果更差。
 *
 * <p>只保留一份的话，用户装新版本就是一次<b>不可逆</b>的赌博：
 * 旧的那份被删掉之后，他既回不去，也没法对比。
 * 而模型包一旦没了，<b>中文人名与机构名就不会被脱敏</b>——
 * 那不是「少一个功能」，是一份没脱干净的文档。
 * 所以「回退」不是锦上添花，是这条链上的<b>安全带</b>。
 *
 * <h2>布局</h2>
 * <pre>
 *   ~/.litemask/models/ner            ← 生效中的那一份（查找路径读它，路径不变）
 *   ~/.litemask/models/ner.versions/  ← 保留下来的历史版本，一版一个子目录
 * </pre>
 * <p>历史目录<b>必须</b>与生效目录<b>同级</b>，不能塞进生效目录里：
 * 生效目录<b>就是</b>模型包目录（清单 + 模型 + 词表），
 * 往里塞别的东西会污染它，「这个目录里只有模型包该有的东西」这条不变量一旦破了，
 * 以后谁往里放一个 {@code .DS_Store} 都要重新判断一遍。
 *
 * <h2>版本键：内容寻址</h2>
 * 版本键 = {@code <清单里的 id 洗白>-<目录内容摘要前 12 位>}。
 * <ul>
 *   <li><b>为什么带摘要</b>：同一个 id 可以有不同内容（改了训练集重训），
 *       只用 id 会两版相撞、后者覆盖前者。</li>
 *   <li><b>为什么要洗白 id</b>：id 来自模型包里的清单，是<b>不受信任的输入</b>。
 *       直接拿它当目录名，一个 {@code ../../} 就能把文件写到目录外面去。
 *       洗白后只留下 {@code [A-Za-z0-9._-]}，其余一律换成下划线，
 *       并且 {@code .} 与 {@code ..} 单独处理成 {@code model}。</li>
 *   <li>于是<b>同一个内容永远得到同一个键</b>：重复装同一份不会多出历史条目，
 *       而用户拿旧键回退时，键也还是那个键（内容没变，摘要就没变）。</li>
 * </ul>
 *
 * <h2>换装是原子的，失败要把旧的放回去</h2>
 * 装新版本的动作是两次改名：{@code 生效目录 → 历史目录}、{@code 新版本 → 生效目录}。
 * 第二步失败时第一步要撤销——用户原来那份是<b>能用的</b>，
 * 装失败顺手毁掉它等于「想升级反而降级」。
 * 连撤销都失败时（极少见，但会发生：文件被别的进程占着），
 * 异常消息里必须写清<b>旧的那份现在在哪</b>：
 * 失败时没有返回值可言，异常消息是唯一能让用户知道的出口。
 *
 * <h2>为什么有锁</h2>
 * 两次改名之间，生效目录<b>短暂不存在</b>。这时候另一个装模型包的动作若插进来，
 * 它会看到「生效目录不存在」，于是不挪旧的、直接装自己的——
 * 结果是<b>两份版本都被弄丢</b>（先装的那份不在历史里，也没生效）。
 * 锁把这件事挡在门外：第二个动作明确失败，并告诉用户「有人在装」。
 *
 * <p><b>锁不保护读者</b>：应用在换装的那一瞬间启动，仍然可能看到「没有模型包」。
 * 那种情况工具会<b>明说</b>「人名与机构名没有被脱敏」——
 * 降级是<b>出声</b>的，不是静默的，这是本项目最不能犯的错的红线。
 */
public final class ModelPackageStore {

    /**
     * 最多保留多少个历史版本。
     *
     * <p>每份模型包 14MB 出头，8 份约 110MB——再往上涨，
     * 「装了很多次」就会变成「磁盘被吃掉了」。超出的部分按<b>最旧</b>清理，
     * 并把清理了哪几个如实说出来（不静默删用户的数据）。
     */
    public static final int MAX_HISTORY = 8;

    /** 内容摘要取前多少位十六进制。48 位对「一个人手边几个版本」绰绰有余。 */
    private static final int DIGEST_CHARS = 12;

    /** 洗白后的 id 最多留多少字符（目录名不能太长）。 */
    private static final int MAX_ID_CHARS = 40;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path activeDir;
    private final Path versionsDir;
    private final Path receiptsDir;
    private final Path lockFile;
    private final ModelPackageInstaller installer;
    private final ModelPackageInstaller.Mover mover;
    private final ModelTrust.Checker trustChecker;

    private FileChannel lockChannel;
    private FileLock lock;

    /** 本进程内单调递增的换装时间戳（见 {@link #nextStamp()}）。 */
    private long lastStamp;

    /**
     * 生产用的构造：真的把模型加载起来校验。
     *
     * @param activeDir 生效中的那份放在哪（它的历史版本放在同名目录 + {@code .versions}）
     */
    public ModelPackageStore(Path activeDir) {
        this(activeDir, ModelPackageInstaller::verifyWithModelLoader, ModelPackageStore::moveWithRetry);
    }

    /**
     * 测试用的构造：校验器与搬目录的动作都可替换。
     *
     * <p><b>验签默认仍然是打开的</b>：这一层是「吃外部文件」的唯一入口，
     * 默认放行等于把「模型包是不是我们发布的」这件事交给调用方记得记得。
     *
     * @param activeDir 生效目录
     * @param validator 校验器（生产用真的加载模型）
     * @param mover     搬目录的动作（生产用 Files.move）
     */
    public ModelPackageStore(Path activeDir,
                             ModelPackageInstaller.Validator validator,
                             ModelPackageInstaller.Mover mover) {
        this(activeDir, validator, mover, ModelTrust::verifyPackage);
    }

    /**
     * 全可替换的构造（单元测试用）。
     *
     * @param activeDir   生效目录
     * @param validator   校验器
     * @param mover       搬目录的动作
     * @param trustChecker 验签动作（生产用真的验）
     */
    public ModelPackageStore(Path activeDir,
                             ModelPackageInstaller.Validator validator,
                             ModelPackageInstaller.Mover mover,
                             ModelTrust.Checker trustChecker) {
        this.activeDir = Objects.requireNonNull(activeDir, "activeDir")
                .toAbsolutePath().normalize();
        if (this.activeDir.getFileName() == null || this.activeDir.getParent() == null) {
            throw new IllegalArgumentException("模型包目录必须是一个带父目录的普通路径: " + activeDir);
        }
        this.versionsDir = this.activeDir.resolveSibling(this.activeDir.getFileName() + ".versions");
        this.receiptsDir = this.activeDir.resolveSibling(this.activeDir.getFileName() + ".receipts");
        Path parent = this.activeDir.getParent();
        this.lockFile = parent.resolve("." + this.activeDir.getFileName() + ".锁");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.trustChecker = Objects.requireNonNull(trustChecker, "trustChecker");
        this.installer = new ModelPackageInstaller(validator, mover);
    }

    // ------------------------------------------------------------------ 记录

    /**
     * 搬目录的默认动作：<b>Windows 上被占用的文件要等一会儿才搬得动</b>。
     *
     * <h2>为什么需要重试</h2>
     * 换模型时，模型文件（{@code ner.onnx}）刚刚被推理引擎映射过。
     * 换装的第一步是「把生效目录挪到历史目录」——
     * 而 Windows 上<b>被打开的文件挪不动</b>（{@code ERROR_SHARING_VIOLATION}）。
     *
     * <p>调用方（{@link ModelManager}）会先把推理会话关掉再调用这里，
     * 绝大多数情况下这时手柄已经放开了。但「刚放开」与「真的放开」之间
     * 隔着操作系统的缓存与延迟，<b>偶发地</b>还会撞上一次。
     *
     * <p>这一层重试的意义就是接住那<b>偶发</b>的一次：
     * 用户看到的是「更新失败，请重试」，而实际上它本来能成。
     * 重试期间不打断任何任务，也不留半个模型在盘上。
     *
     * <p><b>为什么只重试「像是文件被占用」的失败</b>：
     * 「磁盘满了」「路径非法」这类失败重试多少次都一样，
     * 白白让用户多等几秒才看到真正的错误。
     *
     * @param from 从哪
     * @param to   到哪
     * @throws IOException 搬不动
     */
    static void moveWithRetry(Path from, Path to) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                Files.move(from, to);
                return;
            } catch (IOException e) {
                last = e;
                if (!looksLikeFileInUse(e)) {
                    throw e;
                }
                // 退避：第一次等久一点（操作系统释放映射通常在几十毫秒内），
                // 后面逐步拉长，总等待封顶在一秒多。
                sleep(120L * (attempt + 1));
            }
        }
        throw last == null ? new IOException("搬不动：" + from) : last;
    }

    /**
     * 这个失败像不像「文件正被占用」。
     *
     * <p><b>为什么要靠消息文本判断</b>：Java 的 {@code FileSystemException}
     * 没有专门表达「共享冲突」的错误码（{@code AccessDeniedException} 在
     * Windows 上既可能是权限问题、也可能是共享冲突），
     * 而共享冲突的可靠信号只出现在操作系统的原始消息里。
     *
     * <p>所以这里<b>宁可多试</b>（多试几次最多多花一秒），
     * 也不要漏判——漏判的后果是「用户点了更新，一直失败」，
     * 而多试的后果只是「极小概率下失败得晚一点」。
     */
    private static boolean looksLikeFileInUse(IOException e) {
        if (!isWindows()) {
            return false;
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.contains("正在使用") || m.contains("being used by another process")
                || m.contains("另一个程序") || m.contains("used by another process")
                || m.contains("进程无法访问");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("搬动模型目录时被中断", e);
        }
    }

    /**
     * 一个版本。
     *
     * @param key     版本键（也是历史目录名）
     * @param modelId 清单里的模型 id；清单读不出来时为 {@code null}
     * @param dir     这一份所在目录
     * @param active  是否是当前生效的那一份
     */
    public record Version(String key, String modelId, Path dir, boolean active) {
    }

    /**
     * 装完的结果。
     *
     * @param key        装上的版本键
     * @param modelId    装上的模型 id
     * @param dir        生效目录
     * @param files      装进去的文件名（相对目录）
     * @param previousKey 被保留下来的旧版本键；此前没有版本时为 {@code null}
     * @param warnings   需要让用户知道的收尾动作
     */
    public record InstallResult(String key, String modelId, Path dir, List<String> files,
                                String previousKey, List<String> warnings) {
    }

    /**
     * 回退的结果。
     *
     * @param key        回退后生效的版本键
     * @param modelId    回退后生效的模型 id
     * @param dir        生效目录
     * @param previousKey 被换下来的那个版本的键
     * @param warnings   需要让用户知道的收尾动作
     */
    public record RollbackResult(String key, String modelId, Path dir,
                                  String previousKey, List<String> warnings) {
    }

    public Path activeDir() {
        return activeDir;
    }

    public Path versionsDir() {
        return versionsDir;
    }

    // ------------------------------------------------------------------ 装

    /**
     * 装一个模型包，并把它切成生效的那一份。
     *
     * <p>原来生效的那份**不会**被删掉，而是进历史目录——这样用户能回退。
     *
     * <p><b>装之前先验签</b>：模型包是独立分发的产物，来源可能不可信。
     * 一个「自洽的坏包」（清单合法、模型合法、但故意漏识别某些名字）
     * 能通过现有的全部校验，装上去之后工具照常跑、照常说「脱敏完成」，
     * 而人名就留在输出文件里了。签名是唯一能挡住这种包的东西。
     *
     * @param zip 模型包压缩文件
     * @throws IOException 包坏了、签名不可信、校验不通过、换装失败
     */
    public InstallResult install(Path zip) throws IOException {
        return install(zip, false);
    }

    /**
     * 装一个模型包，可以显式跳过验签。
     *
     * <p><b>跳过验签必须由用户明确要求</b>，而且会留下一条明确的提醒。
     * 默认不跳：把一条「来源不明」的模型包装上去，等于把
     * 「人名会不会漏脱敏」这件事交给运气。
     *
     * @param zip          模型包压缩文件
     * @param allowUnsigned 是否允许跳过验签
     * @throws IOException 包坏了、签名不可信、校验不通过、换装失败
     */
    public InstallResult install(Path zip, boolean allowUnsigned) throws IOException {
        if (!allowUnsigned) {
            // 先验签再动磁盘：验签不过时，这台机器上还没有多出任何东西。
            trustChecker.check(zip);
        }
        lock();
        try {
            Path parent = activeDir.getParent();
            if (parent == null) {
                throw new IOException("安装位置没有父目录，装不了：" + activeDir);
            }
            Files.createDirectories(parent);
            Files.createDirectories(versionsDir);

            // 先装到暂存目录：校验不过的话，到这一步为止磁盘上还没多出任何东西。
            Path staging = versionsDir.resolve(".暂存-" + UUID.randomUUID());
            ModelPackageInstaller.Result installed;
            boolean installedOk = false;
            try {
                installed = installer.install(zip, staging);
                installedOk = true;
            } finally {
                // **只在失败时**清暂存：成功时暂存目录里就是刚装好的那份内容，
                // 无条件删会把刚装的东西删掉（而后面还要把它挪到生效目录去）。
                if (!installedOk) {
                    ModelPackageInstaller.deleteRecursively(staging);
                }
            }

            // 版本键用**权威**的 id（校验器给的），不用再读一遍清单。
            String key = key(installed.modelId(), staging);
            Path dest = versionsDir.resolve(key);
            if (Files.exists(dest)) {
                // 同一个内容重复装：内容一样，键也一样。把旧的清掉再放新的。
                if (!ModelPackageInstaller.deleteRecursively(dest)) {
                    throw new IOException("同名的历史版本删不掉（可能正被别的进程占用）：" + dest);
                }
            }
            mover.move(staging, dest);

            if (!allowUnsigned) {
                try {
                    persistReceipt(zip, key);
                } catch (IOException e) {
                    ModelPackageInstaller.deleteRecursively(dest);
                    throw new IOException("已校验模型，但保存签名收据失败，未启用这份模型：" + e.getMessage(), e);
                }
            }

            List<String> warnings = new ArrayList<>();
            if (allowUnsigned) {
                // 跳过验签是用户明确要求的，但**必须说出来**：
                // 一个来源不明的模型包装上去，工具不会知道它会不会漏脱敏人名。
                warnings.add("这个模型包**没有验签**（你用了 --allow-unsigned）："
                        + "工具没法确认它来自发布方。如果它被人改过，"
                        + "「中文人名与机构名」可能漏脱敏。");
            }
            String previousKey;
            try {
                previousKey = activate(dest);
            } catch (IOException e) {
                // 装失败就该等于「什么都没变」：新装的那一份不留下来冒充「保留的版本」。
                ModelPackageInstaller.deleteRecursively(dest);
                throw e;
            }
            pruneHistory(warnings);
            return new InstallResult(key, installed.modelId(), activeDir, installed.files(),
                    previousKey, warnings);
        } finally {
            unlock();
        }
    }

    /**
     * 生产界面在加载已经展开的模型前，重新核对它来自一份仍可验签的发布包。
     * 只校验展开目录自身不够：攻击者可以同时替换模型、清单与清单内摘要。
     */
    public Provenance verifyActiveProvenance() throws IOException {
        if (!Files.isDirectory(activeDir)) {
            throw new IOException("没有生效中的模型目录");
        }
        String key = keyOf(activeDir);
        Path zip = receiptsDir.resolve(key + ".zip");
        Path sig = ModelTrust.signatureFileFor(zip);
        if (!Files.isRegularFile(zip) || !Files.isRegularFile(sig)) {
            throw new IOException("当前模型没有可复验的签名收据；请从已签名模型包重新安装");
        }
        ModelTrust.verifyPackage(zip);
        Path work = Files.createTempDirectory("litemask-model-receipt-");
        Path extracted = work.resolve("package");
        try {
            installer.install(zip, extracted);
            String expected = digest(extracted);
            String actual = digest(activeDir);
            if (!expected.equals(actual)) {
                throw new IOException("当前展开模型与已签名发布包内容不一致");
            }
            ModelPackageSignature.Envelope env = ModelPackageSignature.parseEnvelope(Files.readAllBytes(sig));
            return new Provenance(key, env.sha256(), env.keyId());
        } finally {
            ModelPackageInstaller.deleteRecursively(work);
        }
    }

    /** 已验签发布包对当前展开版本的可复验身份。 */
    public record Provenance(String versionKey, String packageSha256, String signingKeyId) {
    }

    private void persistReceipt(Path zip, String key) throws IOException {
        Path sig = ModelTrust.signatureFileFor(zip);
        Files.createDirectories(receiptsDir);
        Path saved = receiptsDir.resolve(key + ".zip");
        Files.copy(zip, saved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.copy(sig, ModelTrust.signatureFileFor(saved),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(saved);
            throw e;
        }
    }

    /**
     * 把 {@code newDir} 切成当前生效的那一份；原来生效的那份进历史。
     *
     * <p><b>为什么抽出来</b>：「装」与「回退」要做的是<b>同一件事</b>——
     * 把历史目录里的某一份换成当前生效的那一份。两处各写一份的话，
     * 改了一处就分叉：而分叉出来的那个分叉正是「装失败毁掉原来那份」那类事故的温床。
     *
     * @param newDir 要切成生效的那一份（位于历史目录里）
     * @return 原来生效的那份的版本键；此前没有生效版本时为 {@code null}
     */
    private String activate(Path newDir) throws IOException {
        if (!Files.exists(activeDir)) {
            mover.move(newDir, activeDir);
            return null;
        }
        String previousKey = keyOf(activeDir);
        Path previousDest = uniquePath(versionsDir.resolve(previousKey));
        mover.move(activeDir, previousDest);
        // 挪进历史的那一刻就是它「被换下来」的时刻：把这个时刻记在目录的修改时间上，
        // 排序才有依据（否则只能靠目录名，而目录名里没有时间）。
        Files.setLastModifiedTime(previousDest, FileTime.fromMillis(nextStamp()));
        try {
            mover.move(newDir, activeDir);
        } catch (IOException e) {
            // 第二步失败要把旧的放回去：用户原来那份是<b>能用的</b>，
            // 装失败顺手毁掉它等于「想升级反而降级」。
            try {
                mover.move(previousDest, activeDir);
            } catch (IOException restoreFailure) {
                // 放不回去是**最坏**的一种：用户原来那份模型包现在躺在 previousDest 里，
                // 不在原来的位置了。这件事必须让用户知道——
                // 失败时没有 Result 可言，所以它必须进异常消息（消息会直接显示给用户）。
                throw new IOException(e.getMessage()
                        + "；而且原来那份模型包没能放回原位，它现在在 " + previousDest
                        + "，请手动把它挪回 " + activeDir, e);
            }
            throw e;
        }
        return previousKey;
    }

    /**
     * 本进程内单调递增的时间戳。
     *
     * <p><b>为什么要单调</b>：两次换装可能发生在同一毫秒里（装模型包很快），
     * 而文件系统记的修改时间精度不一定到毫秒——同刻的两次换装排序就说不清了。
     * 这里保证每次都比上一次大 1 毫秒，排序就<b>确定</b>了。
     */
    private long nextStamp() {
        long now = System.currentTimeMillis();
        lastStamp = Math.max(now, lastStamp + 1);
        return lastStamp;
    }

    // ------------------------------------------------------------------ 列

    /**
     * 列出当前生效的那一份与保留着的历史版本。
     *
     * <p>历史版本按<b>最近换下来的在前</b>排——回退默认就是回退到最近的那个。
     */
    public List<Version> list() throws IOException {
        List<Version> out = new ArrayList<>();
        if (Files.isDirectory(activeDir)) {
            out.add(new Version(keyOf(activeDir), manifestId(activeDir), activeDir, true));
        }
        out.addAll(historyVersions());
        return out;
    }

    // ------------------------------------------------------------------ 回退

    /**
     * 回退到历史版本。
     *
     * @param selector 要回退到哪个版本；{@code null} 表示「最近换下来的那个」
     * @throws IOException 没有历史版本、版本名对不上、换装失败
     */
    public RollbackResult rollback(String selector) throws IOException {
        lock();
        try {
            List<Version> history = historyVersions();
            if (history.isEmpty()) {
                throw new IOException("没有可回退的历史版本（当前生效的是唯一一份）。"
                        + "以后每次装新版本都会把旧版本留下来，那时就能回退了。");
            }
            Version target;
            if (selector == null || selector.isBlank()) {
                target = history.get(0);
            } else {
                // 解析时把**当前生效的那一份**也算进去：
                // 否则「回退到当前这一份」会被报成「没有这个版本」，
                // 而真实情况是「它已经在生效了」——两句话指向完全不同的下一步。
                List<Version> candidates = new ArrayList<>(history);
                if (Files.isDirectory(activeDir)) {
                    candidates.add(new Version(keyOf(activeDir), manifestId(activeDir), activeDir, true));
                }
                target = resolve(selector, candidates);
                if (target.active()) {
                    throw new IOException("它已经是当前生效的版本了，不用回退：" + target.key());
                }
            }

            List<String> warnings = new ArrayList<>();
            String previousKey = activate(target.dir());
            return new RollbackResult(target.key(), target.modelId(), activeDir, previousKey, warnings);
        } finally {
            unlock();
        }
    }

    // ------------------------------------------------------------------ 删

    /**
     * 删掉一个版本。
     *
     * <p>删<b>当前生效</b>的那一份 = 把模型包卸载掉（工具退回「只做正则」的状态，
     * 并会明说人名与机构名没被脱敏）。删历史版本 = 腾磁盘。
     *
     * @param selector 要删的版本
     * @throws IOException 版本名对不上、删不掉
     */
    public void remove(String selector) throws IOException {
        lock();
        try {
            // 解析时把**当前生效的那一份**也算进去：
            // 只在历史里找的话，「删掉当前生效的那份」（= 卸载模型）会被报成
            // 「没有这个版本」，而那恰恰是用户最可能想做的事之一。
            List<Version> candidates = new ArrayList<>();
            if (Files.isDirectory(activeDir)) {
                candidates.add(new Version(keyOf(activeDir), manifestId(activeDir), activeDir, true));
            }
            candidates.addAll(historyVersions());
            Version target = resolve(selector, candidates);
            if (!ModelPackageInstaller.deleteRecursively(target.dir())) {
                throw new IOException("删不掉这个版本（可能正被别的进程占用）：" + target.dir());
            }
            deleteReceipt(target.key());
        } finally {
            unlock();
        }
    }

    // ------------------------------------------------------------ 版本的键

    /**
     * 版本键：洗白后的 id + 内容摘要前 12 位。
     *
     * <p>摘要覆盖目录里<b>每一个普通文件</b>（相对路径 + 大小 + 内容），
     * 所以「同样的内容」必然得到同样的键，而「改了一个字节」必然得到不同的键。
     */
    static String key(String modelId, Path dir) throws IOException {
        return sanitize(modelId) + "-" + digest(dir).substring(0, DIGEST_CHARS);
    }

    /** 现有目录的键。清单读不出来也不影响——键里的 id 部分只是给人看的。 */
    static String keyOf(Path dir) throws IOException {
        return key(manifestId(dir), dir);
    }

    /**
     * 目录内容摘要。
     *
     * <p>符号链接<b>不算</b>普通文件（{@code NOFOLLOW_LINKS}）：
     * 跟着链接读会把目录外面的内容读进来，摘要就掺进了不属于这一份的东西。
     */
    static String digest(Path dir) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("这台机器没有 SHA-256？", e);
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> dir.relativize(p).toString()));
        for (Path f : files) {
            md.update(dir.relativize(f).toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Long.toString(Files.size(f)).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * 把不受信任的 id 洗成能当目录名的一串。
     *
     * <p>只留 {@code [A-Za-z0-9._-]}，其余换成下划线；{@code .} 与 {@code ..} 换成
     * {@code model}（它们在路径里是「上一级」，不是普通名字）。
     */
    static String sanitize(String id) {
        if (id == null || id.isBlank()) {
            return "model";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length() && sb.length() < MAX_ID_CHARS; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String s = sb.toString();
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) {
            return "model";
        }
        return s;
    }

    /**
     * 尽力从清单里读出模型 id，**只给人看**。
     *
     * <p><b>这里不做任何校验</b>：校验的唯一权威是 {@link NerModelPackage#load}。
     * 这一段存在的理由是「列版本」时得给用户显示一个名字，
     * 而目录里的东西可能本来就是坏的——那时读不出来就显示「读不出来」，
     * 而不是把整个列举操作变成一次加载尝试（那会在坏目录上抛异常）。
     */
    static String manifestId(Path dir) {
        Path m = dir.resolve("manifest.json");
        if (!Files.isRegularFile(m, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(m));
            JsonNode id = root.get("id");
            return id != null && id.isTextual() ? id.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 历史目录

    /**
     * 历史版本，按<b>最近换下来的在前</b>。
     *
     * <p>排序依据是目录的修改时间——它被显式写成「被换下来的那一刻」
     * （见 {@link #activate}），所以它就是「被换下来的先后」，
     * 而不是「文件内容最后一次被写」这两件不同的事。
     * 同刻时按目录名定序，保证结果稳定（不会这次一个样、下次另一个样）。
     */
    private List<Version> historyVersions() throws IOException {
        if (!Files.isDirectory(versionsDir)) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(versionsDir)) {
            s.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(dirs::add);
        }
        dirs.sort(Comparator.comparingLong(ModelPackageStore::modifiedMillis).reversed()
                .thenComparing(p -> p.getFileName().toString()));
        List<Version> out = new ArrayList<>();
        for (Path d : dirs) {
            out.add(new Version(d.getFileName().toString(), manifestId(d), d, false));
        }
        return out;
    }

    private static long modifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 找一个不存在的目标路径（同名的就加个短后缀）。
     *
     * <p>「加后缀」而不是「覆盖」：两个不同内容的版本键不会相同，
     * 但键相同时内容也一样（同键 = 同内容），此时旧的直接删掉即可。
     * 这里处理的是「手工塞进来的同名目录」这种脏状态——不覆盖它，
     * 但也别让装模型包这件事直接崩掉。
     */
    private Path uniquePath(Path wanted) {
        if (!Files.exists(wanted)) {
            return wanted;
        }
        return wanted.resolveSibling(wanted.getFileName() + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 删掉超出上限的历史版本（最旧的先删），并如实报告删了哪些。
     */
    private void pruneHistory(List<String> warnings) throws IOException {
        List<Version> history = historyVersions();
        for (int i = MAX_HISTORY; i < history.size(); i++) {
            Version v = history.get(i);
            if (ModelPackageInstaller.deleteRecursively(v.dir())) {
                deleteReceipt(v.key());
                warnings.add("历史版本太多（上限 " + MAX_HISTORY + " 个），已删掉最旧的 " + v.key());
            } else {
                warnings.add("历史版本太多（上限 " + MAX_HISTORY + " 个），但最旧的 " + v.key()
                        + " 删不掉（可能正被别的进程占用），请用 model remove 手动清理");
            }
        }
    }

    /** 版本内容已经删除时，同步清掉它的签名发布包收据，避免模型更新长期堆积。 */
    private void deleteReceipt(String key) throws IOException {
        Path zip = receiptsDir.resolve(key + ".zip");
        Files.deleteIfExists(ModelTrust.signatureFileFor(zip));
        Files.deleteIfExists(zip);
    }

    // ------------------------------------------------------------ 版本名解析

    /**
     * 把用户给的名字解析成唯一的一个版本。
     *
     * <p>允许三种写法：完整目录名、摘要那 12 位、目录名的前缀。
     * 匹配到多个就说清楚是哪些——<b>不猜</b>。
     *
     * <p><b>注意这里从不拿用户输入拼路径</b>：路径一律从磁盘上真实存在的
     * 目录里取。用户输入只用来做<b>比较</b>，不用来做<b>定位</b>——
     * 于是 {@code ../../etc} 这类输入最多「匹配不到」，不可能「匹配到别的地方」。
     */
    private Version resolve(String selector, List<Version> candidates) throws IOException {
        String s = selector == null ? "" : selector.trim();
        if (s.isEmpty()) {
            // 这里只写子命令、不写程序名：这一层不知道用户是怎么调起工具的，
            // 而写死一个程序名（哪怕它看起来很合理）很可能在这台机器上并不存在——
            // 用户照着敲就是 command not found。
            throw new IOException("要指定一个版本，但没给版本名。"
                    + "用 `model list` 看有哪些版本。");
        }
        if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0) {
            throw new IOException("版本名里不能带路径分隔符：" + s);
        }
        List<Version> hits = new ArrayList<>();
        for (Version v : candidates) {
            if (matches(v.key(), s)) {
                hits.add(v);
            }
        }
        if (hits.isEmpty()) {
            throw new IOException("没有这个版本：" + s
                    + (candidates.isEmpty() ? "（现在一个历史版本都没有）"
                    : "。现有版本：" + String.join("、", candidates.stream().map(Version::key).toList())));
        }
        if (hits.size() > 1) {
            throw new IOException("「" + s + "」对上了 " + hits.size() + " 个版本，请写得更完整："
                    + String.join("、", hits.stream().map(Version::key).toList()));
        }
        return hits.get(0);
    }

    /**
     * 版本名是否对得上。
     *
     * <p>三种匹配：完全相等、摘要那 12 位相等、目录名以它开头。
     * 摘要相等这一条是给用户省事的——键里那串 id 通常是同一个，
     * 真正能区分版本的是后面 12 位十六进制。
     */
    private static boolean matches(String key, String selector) {
        if (key == null || selector == null || selector.isEmpty()) {
            return false;
        }
        if (key.equals(selector)) {
            return true;
        }
        String digest = digestPart(key);
        if (digest != null && digest.equals(selector)) {
            return true;
        }
        return key.startsWith(selector);
    }

    /** 从版本键里取出摘要那 12 位（键的最后一个 {@code -} 之后）。 */
    private static String digestPart(String key) {
        int i = key.lastIndexOf('-');
        if (i < 0 || i + 1 >= key.length()) {
            return null;
        }
        String tail = key.substring(i + 1);
        for (int k = 0; k < tail.length(); k++) {
            char c = tail.charAt(k);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return tail;
    }

    // ---------------------------------------------------------------- 锁

    /**
     * 拿锁。
     *
     * <p>拿不到就明确失败，并说清是谁在装——「静默等着」比「明确失败」更坏，
     * 因为用户会以为程序卡死了。
     */
    private void lock() throws IOException {
        Path parent = lockFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock l = channel.tryLock();
            if (l == null) {
                channel.close();
                throw new IOException("另一个进程正在装模型包（锁文件 " + lockFile
                        + "），请等它装完再试。");
            }
            this.lockChannel = channel;
            this.lock = l;
        } catch (OverlappingFileLockException e) {
            // 同一个 JVM 里已经有一个在装了。这不是 bug，是并发。
            channel.close();
            throw new IOException("另一个装模型包的操作正在进行中（同一个进程内），请等它结束。");
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    private void unlock() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException e) {
                // 解锁失败没什么可补救的：文件描述符关闭会一并释放锁。
            }
            lock = null;
        }
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException e) {
                // 同上。
            }
            lockChannel = null;
        }
    }
}
