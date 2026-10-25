package com.litemask.ner;

import com.litemask.core.EntitySource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * NER 模型在进程内的持有者：决定「用不用、用哪个」，并如实报告状态。
 *
 * <h2>为什么模型可以关掉</h2>
 * 方案 B 把模型包从核心包里拆出去，于是「用户手上没有模型包」是<b>常态</b>而不是异常。
 * 这时候工具仍然要能用——纯正则脱敏不依赖模型。
 * 但它<b>必须说出来</b>：一份人名机构名都没脱的文档如果报成「已脱干净」，
 * 用户就会直接把它传出去。这是本工具最不能犯的错，
 * 与「PDF 没有文本层时 0 命中不等于文档干净」是同一类。
 *
 * <h2>查找顺序</h2>
 * <ol>
 *   <li>{@code --ner-model <目录>} 显式指定</li>
 *   <li>环境变量 {@code LITEMASK_NER_MODEL}</li>
 *   <li>{@code <程序所在目录>/models/ner}（安装包里的默认位置）</li>
 *   <li>{@code ~/.litemask/models/ner}（用户手动导入的位置）</li>
 * </ol>
 * 顺序是「越具体越优先」。显式指定排第一，是因为排障时用户需要能覆盖一切。
 *
 * <h2>加载失败不静默</h2>
 * <b>指到了目录但加载不了</b>（摘要不对、版本不认、模型与词表不配套）
 * 与 <b>根本没找到目录</b>是两种完全不同的情况：
 * 前者是<b>故障</b>，用户以为自己装了模型、工具却当没装；
 * 后者是<b>未安装</b>，本来就该安静降级。
 * 所以只有后者返回「未启用」，前者直接抛——让用户看到真正的原因。
 */
public final class NerModelRegistry implements AutoCloseable {

    /** 环境变量名。放在这里而不是散在各处，是为了让 `--help` 与文档引用同一个常量。 */
    public static final String ENV_MODEL_DIR = "LITEMASK_NER_MODEL";

    /**
     * 用户目录下的默认模型包位置（相对 {@code user.home}）。
     *
     * <p><b>为什么定义在这里</b>：查找路径（{@link #discover}）与安装路径
     * （{@code LightMeter model install} 的默认目标）必须指向<b>同一个</b>目录。
     * 各写一份的话，改了一处就装错地方——而症状是「装完了，工具却说没装」。
     */
    public static final String USER_MODEL_SUBPATH = ".litemask/models/ner";

    /** 用户目录下的默认模型包绝对路径。 */
    public static Path defaultUserModelDir() {
        return Path.of(System.getProperty("user.home", ".")).resolve(USER_MODEL_SUBPATH);
    }

    private static volatile NerModelRegistry instance;

    private final NerModelPackage modelPackage;
    private final String reason;
    private final Path searched;

    private NerModelRegistry(NerModelPackage modelPackage, String reason, Path searched) {
        this.modelPackage = modelPackage;
        this.reason = reason;
        this.searched = searched;
    }

    /** 没装模型：正则照常工作，人名/机构名不脱。 */
    public static NerModelRegistry disabled(Path searched, String reason) {
        return new NerModelRegistry(null, Objects.requireNonNull(reason), searched);
    }

    public static NerModelRegistry enabled(NerModelPackage pkg) {
        return new NerModelRegistry(Objects.requireNonNull(pkg), null, pkg.directory());
    }

    /** 按顺序查找并加载；找不到就是「未启用」。 */
    public static NerModelRegistry discover(String explicitDir) throws IOException, OrtExceptionWrapper {
        if (explicitDir != null && !explicitDir.isBlank()) {
            return loadOrThrow(Path.of(explicitDir), "--ner-model");
        }
        String env = System.getenv(ENV_MODEL_DIR);
        if (env != null && !env.isBlank()) {
            return loadOrThrow(Path.of(env), "环境变量 " + ENV_MODEL_DIR);
        }
        List<Path> candidates = new ArrayList<>();
        Path programDir = programDirectory();
        if (programDir != null) {
            candidates.add(programDir.resolve("models").resolve("ner"));
        }
        Path home = Path.of(System.getProperty("user.home", "."));
        candidates.add(home.resolve(USER_MODEL_SUBPATH));

        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return loadOrThrow(p, "默认位置");
            }
        }
        return disabled(null, "没有找到中文人名/机构名识别模型包（依次找过："
                + String.join("、", candidates.stream().map(Path::toString).toList())
                + "）。可以用 --ner-model <目录> 或环境变量 " + ENV_MODEL_DIR + " 指定。");
    }

    private static NerModelRegistry loadOrThrow(Path dir, String origin)
            throws IOException, OrtExceptionWrapper {
        if (!Files.isDirectory(dir)) {
            // 指到了却不存在 —— 这不是「未安装」，是指示写错了。
            throw new OrtExceptionWrapper("按" + origin + "指定的模型包目录不存在: " + dir);
        }
        try {
            return enabled(NerModelPackage.load(dir));
        } catch (NerModelPackage.InvalidModelPackage e) {
            throw new OrtExceptionWrapper("按" + origin + "找到的模型包不能用（" + dir
                    + "）：" + e.getMessage(), e);
        } catch (ai.onnxruntime.OrtException e) {
            throw new OrtExceptionWrapper("按" + origin + "找到的模型包加载失败（" + dir + "）："
                    + e.getMessage(), e);
        }
    }

    /**
     * 程序所在目录（jar 的父目录）。
     *
     * <p><b>为什么暴露成 public</b>：随安装包分发的模型包要放在这个目录旁边，
     * 而「这个目录在哪」这件事<b>只能有一处定义</b>——
     * 各写一份的话，改了一处就装错地方，而症状是「装完了，工具却说没装」。
     *
     * @return 程序目录；取不到时为 {@code null}
     */
    public static Path programDirectory() {
        try {
            java.net.URI location = NerModelRegistry.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            // jar 直接跑时 location 就是那个 jar；跑 target/classes 时是目录。
            Path p = Path.of(location);
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 这个注册表当前持有的模型包。
     *
     * @return 已启用时有值；没启用时为 {@code null}
     */
    public static NerModelPackage packageOf(NerModelRegistry registry) {
        if (registry == null) {
            return null;
        }
        return registry.modelPackage;
    }

    public boolean isEnabled() {
        return modelPackage != null;
    }

    /**
     * 交给引擎的来源列表。没启用时返回空列表——引擎会照常跑纯正则。
     *
     * <p>返回空列表而不是返回 null，是为了让「没有 NER」与「NER 一个实体都没识别到」
     * 在代码里就是两回事：后者会走到来源并返回空命中列表。
     */
    public List<EntitySource> sources() {
        return modelPackage == null ? List.of() : List.of(modelPackage.entitySource());
    }

    /** 没启用时的说明；启用时返回空串。 */
    public String reason() {
        return reason == null ? "" : reason;
    }

    public Path directory() {
        return searched;
    }

    /**
     * 给用户看的一句话状态。
     *
     * <p>启用时也说清楚<b>用的是哪个版本</b>——脱敏结果依赖模型版本，
     * 出了争议时用户需要能报出「我用的是哪个」。
     */
    public String describe() {
        if (modelPackage == null) {
            return "中文人名/机构名识别：未启用。" + reason
                    + " 本次结果里**人名与机构名没有被脱敏**，只有正则覆盖的"
                    + "身份证、手机号、银行卡等标识符被处理。";
        }
        NerModelPackage.Manifest m = modelPackage.manifest();
        return "中文人名/机构名识别：已启用（模型包 " + m.id() + "，"
                + modelPackage.vocab().size() + " 字表，目录 " + modelPackage.directory() + "）。";
    }

    /** 进程级单例。关掉后置空，便于测试之间互不干扰。 */
    public static NerModelRegistry get() {
        return instance;
    }

    public static void set(NerModelRegistry registry) {
        instance = registry;
    }

    /** 已启用时关掉底层会话；没启用时什么都不做。 */
    public void close() {
        if (modelPackage != null) {
            modelPackage.close();
        }
    }

    @Override
    public String toString() {
        return isEnabled() ? "NerModelRegistry[enabled " + modelPackage.manifest().id() + "]"
                : "NerModelRegistry[disabled: " + reason + "]";
    }

    /**
     * 加载侧失败的统一包装。
     *
     * <p>存在的理由是 {@link NerModelPackage#load} 会抛三种不同家族的受检异常
     * （{@code IOException} / {@code OrtException} / 运行时异常），
     * 让每一个调用方都写三个 catch 只会让它们在某一处漏掉一个。
     */
    public static final class OrtExceptionWrapper extends Exception {
        public OrtExceptionWrapper(String message) {
            super(message);
        }

        public OrtExceptionWrapper(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 供上层决定要不要把「NER 未启用」显示给用户。 */
    public boolean needsUserNotice() {
        return !isEnabled();
    }
}
