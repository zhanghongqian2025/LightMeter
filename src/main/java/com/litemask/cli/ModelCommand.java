package com.litemask.cli;

import com.litemask.ner.ModelPackageInstaller;
import com.litemask.ner.ModelPackageStore;
import com.litemask.ner.NerModelPackage;
import com.litemask.ner.NerModelRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code LightMeter model} 子命令：装模型包、看当前用的哪个模型包、回退与清理。
 *
 * <p><b>为什么要有这个命令</b>：方案 B 把模型包从核心包里拆了出去，
 * 「用户手上没有模型包」是常态。而没有模型包时，
 * 中文人名与机构名<b>不会</b>被脱敏——那不是少一个功能，
 * 是一份没脱干净的文档。所以模型包必须有一条不靠手工复制目录的安装路径。
 *
 * <p><b>为什么要有 list / rollback / remove</b>：模型包是<b>独立迭代</b>的产物，
 * 新版本不一定比旧版本好（新版本可能漏识别人名、可能对某类文书效果更差）。
 * 只保留一份的话，「装新版本」就是一次不可逆的赌博：
 * 旧的那份被删掉之后用户既回不去，也没法对比。
 * 所以装的时候把旧的留下来，并且给一条<b>回退</b>的路。
 */
public final class ModelCommand {

    /**
     * {@code download} 专有的带值参数。
     *
     * <p>放在这里而不是公共解析器里，是因为**只有** {@code download} 认它：
     * 别的子命令收到 {@code --sig-url} 必须拒掉，不能静默忽略。
     */
    private static final Set<String> DOWNLOAD_VALUE_OPTIONS = Set.of("--sig-url");

    private ModelCommand() {
    }

    /**
     * 跑 {@code model} 子命令。
     *
     * @param args   子命令及其参数（不含 {@code model} 这一层）
     * @param prefix 调用前缀（用户是怎么调起这个工具的），空串表示取不到。
     *               <b>建议给用户的命令要用它</b>：写死一个程序名，
     *               而那个名字在这台机器上并不存在，用户照着敲就是 command not found。
     */
    public static int run(String[] args, String prefix, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            usage(err);
            return 2;
        }
        String sub = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return switch (sub) {
            case "install" -> install(rest, prefix, out, err);
            case "download" -> download(rest, prefix, out, err);
            case "list" -> list(rest, prefix, out, err);
            case "rollback" -> rollback(rest, prefix, out, err);
            case "remove" -> remove(rest, out, err);
            case "info" -> info(rest, out, err);
            case "verify" -> verify(rest, out, err);
            case "trust" -> trust(rest, out, err);
            case "-h", "--help" -> {
                usage(out);
                yield 0;
            }
            default -> {
                err.println("未知子命令: model " + sub);
                usage(err);
                yield 2;
            }
        };
    }

    /**
     * 公共参数的解析结果。
     *
     * @param dir           {@code --to} 指定的目录；没给时为 {@code null}（用默认位置）
     * @param rest          摘掉公共参数之后剩下的参数
     * @param bad           参数写错了（{@code --to} 后面没跟目录）
     * @param allowUnsigned 是否带了 {@code --allow-unsigned}
     * @param options       本子命令**自己**声明认的带值参数（如 {@code --sig-url}）
     */
    private record Spec(Path dir, List<String> rest, boolean bad, boolean allowUnsigned,
                        Map<String, String> options) {

        /** 兼容旧写法：只带四个老字段的解析结果。 */
        Spec(Path dir, List<String> rest, boolean bad, boolean allowUnsigned) {
            this(dir, rest, bad, allowUnsigned, Map.of());
        }

        /** 取一个带值参数；没给就是 {@code null}。 */
        String option(String name) {
            return options.get(name);
        }
    }

    /**
     * 把 {@code --to <目录>} 与 {@code --allow-unsigned} 从参数里摘出来。
     *
     * <p><b>为什么要有 --to</b>：{@code install --to <目录>} 装的那份，
     * 它的历史版本也放在那个目录旁边。而 {@code list} / {@code rollback} / {@code remove}
     * 若不认 {@code --to}，就只管默认位置——于是「用 {@code --to} 装的版本」永远回退不了。
     * 解析只写一份，几个子命令才不会各认各的。
     *
     * <p><b>为什么 --allow-unsigned 也在这里摘</b>：摘出来是为了让每个子命令
     * 自己决定收不收——多余的参数一律拒掉，不静默忽略。
     *
     * <p><b>为什么带值参数要由各子命令自己声明</b>：{@code --sig-url} 只有
     * {@code download} 认。要是公共解析器无条件把它吃掉，{@code model list --sig-url x}
     * 就会**静默忽略**这个参数——用户以为它生效了，其实没有。
     * 所以这里只认「公共的几个」+「本子命令声明的」，其余照旧落到 {@code rest}，
     * 由各子命令自己拒掉。
     *
     * @param args         参数
     * @param err          错误输出
     * @param valueOptions 本子命令认的带值参数
     * @return 解析结果
     */
    private static Spec parseArgs(String[] args, PrintStream err) {
        return parseArgs(args, err, Set.of());
    }

    /**
     * 见 {@link #parseArgs(String[], PrintStream)}：多带了本子命令自己声明的参数。
     *
     * @param args         参数
     * @param err          错误输出
     * @param valueOptions 本子命令认的带值参数
     * @return 解析结果
     */
    private static Spec parseArgs(String[] args, PrintStream err, Set<String> valueOptions) {
        Path dir = null;
        boolean allowUnsigned = false;
        List<String> rest = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if ("--to".equals(args[i])) {
                if (++i >= args.length) {
                    err.println("--to 需要一个目录");
                    return new Spec(null, rest, true, allowUnsigned);
                }
                dir = Path.of(args[i]);
            } else if ("--allow-unsigned".equals(args[i])) {
                allowUnsigned = true;
            } else if (valueOptions.contains(args[i])) {
                String name = args[i];
                if (++i >= args.length) {
                    err.println(name + " 需要一个值");
                    return new Spec(null, rest, true, allowUnsigned, Map.of());
                }
                options.put(name, args[i]);
            } else {
                rest.add(args[i]);
            }
        }
        return new Spec(dir, rest, false, allowUnsigned, options);
    }

    /** 生效目录：{@code --to} 指定的那个，或默认位置。 */
    private static Path storeDir(Spec spec) {
        return spec.dir() != null ? spec.dir() : NerModelRegistry.defaultUserModelDir();
    }

    /**
     * 不接受 {@code --allow-unsigned} 的子命令用它：多余参数一律拒掉，不静默忽略。
     *
     * @param spec 解析结果
     * @param err  错误输出
     * @return 有不接受的东西就返回 true
     */
    private static boolean rejectsAllowUnsigned(Spec spec, PrintStream err) {
        if (spec.allowUnsigned()) {
            err.println("这个子命令不接受 --allow-unsigned（只有 install 认它）。");
            return true;
        }
        return false;
    }

    /**
     * 校验一个模型包目录能不能用（不安装、不动任何文件）。
     *
     * <p><b>为什么要有这个</b>：它和 {@code model info} 问的是<b>两个不同</b>的问题——
     * {@code info} 问「工具现在会用哪个模型包」，{@code verify} 问「这个目录本身是不是一个
     * 能用的模型包」。用户拿到一份别人给的模型包、想在装之前先验一下，用的是后者。
     *
     * <p>它也给了打包流程一条真闸门：模型包要独立分发，
     * 分发出去的东西必须先被真的加载过一次，才算数。
     */
    private static int verify(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("用法: model verify <模型包目录>");
            return 2;
        }
        Path dir = Path.of(args[0]);
        try (NerModelPackage pkg = NerModelPackage.load(dir)) {
            out.println("模型包可用：" + pkg.manifest().id()
                    + "（字表 " + pkg.vocab().size() + " 字，目录 " + pkg.directory() + "）");
            return 0;
        } catch (NerModelPackage.InvalidModelPackage e) {
            err.println("模型包不能用：" + e.getMessage());
            return 1;
        } catch (ai.onnxruntime.OrtException e) {
            err.println("模型包加载失败：" + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("模型包目录读不了：" + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------ 信任锚

    /**
     * 管理「我们信任哪些公钥」。
     *
     * <p><b>为什么用户要能自己加公钥</b>：程序内置的信任锚是发布方填的
     * （见 {@code src/main/resources/model-trust-anchors.json}）。
     * 但用户也可能从别处拿到发布方的公钥——那把公钥必须经过<b>带外</b>的渠道核对
     * （官网、公告、面对面），核对过再加进来。
     *
     * <p><b>标识不由人来写</b>：标识由公钥本身派生（见
     * {@link com.litemask.ner.ModelPackageSignature#keyIdOf(java.security.PublicKey)}），
     * 于是「标识打错一个字」这个失败模式不存在。
     */
    private static int trust(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            trustUsage(err);
            return 2;
        }
        String sub = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return switch (sub) {
            case "add" -> trustAdd(rest, out, err);
            case "list" -> trustList(rest, out, err);
            case "remove" -> trustRemove(rest, out, err);
            case "-h", "--help" -> {
                trustUsage(out);
                yield 0;
            }
            default -> {
                err.println("未知子命令: model trust " + sub);
                trustUsage(err);
                yield 2;
            }
        };
    }

    private static int trustAdd(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("用法: model trust add <公钥.pem>");
            return 2;
        }
        Path pemFile = Path.of(args[0]);
        String pem;
        try {
            pem = Files.readString(pemFile);
        } catch (IOException e) {
            err.println("公钥文件读不了：" + e.getMessage());
            return 1;
        }
        String keyId;
        try {
            java.security.PublicKey key = com.litemask.ner.ModelPackageSignature.publicKeyFromPem(pem);
            keyId = com.litemask.ner.ModelTrust.addAnchor(
                    com.litemask.ner.ModelPackageSignature.publicKeyBase64(key));
        } catch (IOException e) {
            err.println("加不进去：" + e.getMessage());
            return 1;
        }
        out.println("已信任这把公钥：" + keyId);
        out.println("（文件 " + com.litemask.ner.ModelTrust.userAnchorsFile() + "）");
        out.println("注意：信任一把公钥等于信任「用它的私钥签的所有模型包」。");
        out.println("加之前请先通过带外的渠道核对这把公钥确实来自发布方。");
        return 0;
    }

    private static int trustList(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 0) {
            err.println("用法: model trust list");
            return 2;
        }
        List<com.litemask.ner.ModelTrust.Anchored> anchors;
        try {
            anchors = com.litemask.ner.ModelTrust.anchored();
        } catch (IOException e) {
            err.println("信任锚读不出来：" + e.getMessage());
            return 1;
        }
        if (anchors.isEmpty()) {
            out.println("这台机器上还没有信任任何公钥。");
            out.println("模型包必须由受信任的密钥签名才能装。");
            return 0;
        }
        // 报**去重后**的条数，并标出每一条的来源。
        //
        // 为什么不能直接报条数：同一把公钥可能既在内置锚里、又在用户级
        // （发布方内置了它，用户又从官网拿到一份加了一遍）——
        // 按条数报就会说「已信任 2 把公钥」而其实是 1 把。
        out.println("已信任 " + anchors.size() + " 把公钥：");
        for (com.litemask.ner.ModelTrust.Anchored a : anchors) {
            out.println("  " + a.anchor().keyId() + "（" + a.source() + "）");
        }
        return 0;
    }

    private static int trustRemove(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("用法: model trust remove <密钥标识>");
            return 2;
        }
        try {
            boolean removed = com.litemask.ner.ModelTrust.removeAnchor(args[0]);
            if (!removed) {
                out.println("用户信任锚里本来就没有 " + args[0] + "（内置信任锚不受这个命令影响）");
                return 0;
            }
            // 删掉的只是**用户级**那一份。
            // 同一把公钥若还在内置锚里，它照样受信——
            // 这时候说「已不再信任」是**一句不实的陈述**，必须说真话。
            if (com.litemask.ner.ModelTrust.isTrusted(args[0])) {
                out.println("已从用户信任锚里删掉 " + args[0]);
                out.println("注意：它仍在**内置**信任锚里，所以模型包照样会被接受。");
            } else {
                out.println("已不再信任 " + args[0]);
            }
            return 0;
        } catch (IOException e) {
            err.println("改不了信任锚：" + e.getMessage());
            return 1;
        }
    }

    private static void trustUsage(PrintStream out) {
        out.println("""
                用法:
                  model trust add <公钥.pem>     信任一把发布方的公钥
                  model trust list                列出已信任的公钥
                  model trust remove <密钥标识>    不再信任一把公钥

                说明:
                  模型包必须由受信任的密钥签名才能装。加一把公钥等于信任
                  「用它的私钥签的所有模型包」——加之前请先通过带外的渠道
                  （官网、公告、面对面）核对这把公钥确实来自发布方。
                  密钥标识由公钥本身派生，不用手写。
                """);
    }

    private static int install(String[] args, String prefix, PrintStream out, PrintStream err) {
        Spec spec = parseArgs(args, err);
        if (spec.bad()) {
            return 2;
        }
        if (spec.rest().size() != 1) {
            err.println("用法: model install <模型包.zip> [--to 目录] [--allow-unsigned]");
            return 2;
        }
        String packageFile = spec.rest().get(0);
        Path dir = storeDir(spec);
        ModelPackageStore store = new ModelPackageStore(dir);
        try {
            ModelPackageStore.InstallResult r = store.install(Path.of(packageFile), spec.allowUnsigned());
            reportInstalled(r, prefix, out, err);
            return 0;
        } catch (IOException e) {
            return reportInstallFailure(e, err);
        }
    }

    /**
     * 安装失败时的统一说法。
     *
     * <p>{@code install} 与 {@code download} 都走这一步：一个事实只在一处定义，
     * 免得两边的措辞慢慢长成两个样子。
     *
     * @param e   失败原因
     * @param err 错误输出
     * @return 退出码
     */
    private static int reportInstallFailure(IOException e, PrintStream err) {
        err.println("安装失败: " + e.getMessage());
        return 1;
    }

    /**
     * 装成功之后的统一汇报。
     *
     * <p>同样是一处定义：{@code install} 与 {@code download} 装完都要说同样那几句话，
     * 而这些话里有一句是不能省的——「旧版本还留着，可以退回」。
     * 用户不知道这件事，就不会去试新版本。
     *
     * @param r      安装结果
     * @param prefix 调用前缀
     * @param out    标准输出
     * @param err    错误输出
     */
    private static void reportInstalled(ModelPackageStore.InstallResult r, String prefix,
                                        PrintStream out, PrintStream err) {
        out.println("已安装模型包 " + r.modelId() + " 到 " + r.dir());
        out.println("版本键: " + r.key());
        for (String f : r.files()) {
            out.println("  " + f);
        }
        if (r.previousKey() != null) {
            // 旧版本**没被删掉**——这是这一块存在的全部理由，必须说出来，
            // 否则用户以为「装新的 = 旧的没了」，下次就不敢试新版本了。
            out.println("已保留原来的版本 " + r.previousKey()
                    + "，要退回用 "
                    + CliInvocation.command(prefix, "model rollback " + r.previousKey()));
        } else {
            out.println("（这是第一份模型包，暂时没有可回退的旧版本）");
        }
        for (String w : r.warnings()) {
            err.println("提醒: " + w);
        }
        out.println();
        reportWhatWillBeUsed(out, r.dir());
    }

    /**
     * 造一个模型包仓库的工厂。
     *
     * <p><b>为什么留这个缝</b>：安装模型包要真的把 ONNX 模型加载起来验一遍，
     * 而单元测试里没有那个 15MB 的真模型——于是「下载 + 装上」这条主路径
     * 在单测里就**只能测到下载为止**。
     * 做成可替换的动作后，测的是同一段代码、同一个判定，
     * 只是把「模型能不能加载」这一步换成一个只认目录的桩。
     * 真模型上的那条路径由端到端脚本覆盖（那里有真模型）。
     *
     * <p>注意：<b>验签不在这个缝里</b>——验签始终是真的
     * （{@link com.litemask.ner.ModelTrust#verifyPackage}），
     * 因为那才是这一块最要紧的判定，桩掉它等于把要测的东西测没了。
     */
    @FunctionalInterface
    interface StoreFactory {
        /**
         * 造一个模型包仓库。
         *
         * @param activeDir 生效目录
         * @return 仓库
         */
        ModelPackageStore create(Path activeDir);
    }

    private static int download(String[] args, String prefix, PrintStream out, PrintStream err) {
        return download(args, prefix, out, err, ModelPackageStore::new);
    }

    /**
     * 从网上把模型包取回来并装上。
     *
     * <p><b>为什么要有这个子命令</b>：模型包是独立分发的产物，
     * 而「用户从哪儿拿到它」一直只能靠人手递一个 zip 过去。
     * 这一条命令把「到达路径」补上：给一个地址，工具自己取、自己验、自己装。
     *
     * <p><b>它不接受 --allow-unsigned</b>：下载来的东西恰恰是最需要验签的
     * ——它的来源不由用户掌握。真的没有签名时，请显式用
     * {@code model install --allow-unsigned} 手动装，那会留下「来源不明」的提醒。
     *
     * @param args   子命令及其参数
     * @param prefix 调用前缀
     * @param out    标准输出
     * @param err    错误输出
     * @param stores 模型包仓库的工厂（测试可替换）
     * @return 退出码
     */
    static int download(String[] args, String prefix, PrintStream out, PrintStream err, StoreFactory stores) {
        Spec spec = parseArgs(args, err, DOWNLOAD_VALUE_OPTIONS);
        if (spec.bad()) {
            return 2;
        }
        if (rejectsAllowUnsigned(spec, err)) {
            return 2;
        }
        if (spec.rest().size() != 1) {
            err.println("用法: model download <模型包地址> [--to 目录] [--sig-url 签名地址]");
            return 2;
        }
        String packageUrl = spec.rest().get(0);
        // 没给 --sig-url 就传 null：让取包的那一层按「包实际落在哪儿」去找签名。
        // 这里自己拼「<地址>.sig」是不对的——地址有重定向时，签名的位置会跟着跑偏。
        String signatureUrl = spec.option("--sig-url");
        Path dir = storeDir(spec);
        Path tmp;
        try {
            tmp = Files.createTempDirectory("litemask-model-download-");
        } catch (IOException e) {
            err.println("建不了临时目录: " + e.getMessage());
            return 1;
        }
        try {
            ModelFetcher.Fetched fetched;
            try {
                fetched = ModelFetcher.fetch(packageUrl, signatureUrl, tmp);
            } catch (IOException e) {
                err.println("下载失败: " + e.getMessage());
                return 1;
            }
            out.println("已下载模型包 " + fetched.bytes() + " 字节（来自 " + fetched.finalUrl() + "）");
            ModelPackageStore.InstallResult r;
            try {
                // allowUnsigned 传 false：下载来的东西必须验签。
                // 验签在 install 内部做（ModelTrust.verifyPackage），
                // 这里是**同一处**判据，不是第二份实现。
                r = stores.create(dir).install(fetched.packageFile(), false);
            } catch (IOException e) {
                return reportInstallFailure(e, err);
            }
            reportInstalled(r, prefix, out, err);
            return 0;
        } finally {
            // 临时目录里是刚下载的 zip 与签名。装完就删——
            // 它们已经进了版本目录，留着只是白占磁盘。
            ModelPackageInstaller.deleteRecursively(tmp);
        }
    }

    /**
     * 列出当前生效的那一份与保留着的历史版本。
     *
     * <p>这是一个<b>列举</b>动作，不是状态查询：
     * 「一个版本都没有」是合法状态（用户还没装），不是错误，所以退出码是 0。
     * 要问「工具现在会不会脱人名」，那是 {@code model info} 的事（它用退出码 3 表示没装）。
     */
    private static int list(String[] args, String prefix, PrintStream out, PrintStream err) {
        Spec spec = parseArgs(args, err);
        if (spec.bad()) {
            return 2;
        }
        if (rejectsAllowUnsigned(spec, err)) {
            return 2;
        }
        if (!spec.rest().isEmpty()) {
            err.println("用法: model list [--to 目录]（它不接受别的参数）");
            return 2;
        }
        ModelPackageStore store = new ModelPackageStore(storeDir(spec));
        List<ModelPackageStore.Version> versions;
        try {
            versions = store.list();
        } catch (IOException e) {
            err.println("读模型包目录失败：" + e.getMessage());
            return 1;
        }
        if (versions.isEmpty()) {
            out.println("还没有装任何模型包。");
            out.println("装一份: " + CliInvocation.command(prefix, "model install <模型包.zip>"));
            out.println("不装的话，中文人名与机构名**不会**被脱敏。");
            return 0;
        }
        out.println("模型包位置: " + store.activeDir());
        for (ModelPackageStore.Version v : versions) {
            out.println((v.active() ? "* 当前生效: " : "  保留的历史版本: ")
                    + v.key()
                    + "（模型 id: " + (v.modelId() == null ? "（清单读不出来）" : v.modelId()) + "）");
            out.println("    目录: " + v.dir());
        }
        out.println();
        out.println("回退到上一个: " + CliInvocation.command(prefix, "model rollback"));
        out.println("回退到指定版本: " + CliInvocation.command(prefix, "model rollback <版本键>"));
        out.println("删掉一个版本: " + CliInvocation.command(prefix, "model remove <版本键>"));
        return 0;
    }

    private static int rollback(String[] args, String prefix, PrintStream out, PrintStream err) {
        Spec spec = parseArgs(args, err);
        if (spec.bad()) {
            return 2;
        }
        if (rejectsAllowUnsigned(spec, err)) {
            return 2;
        }
        if (spec.rest().size() > 1) {
            err.println("用法: model rollback [版本键] [--to 目录]");
            return 2;
        }
        String selector = spec.rest().isEmpty() ? null : spec.rest().get(0);
        ModelPackageStore store = new ModelPackageStore(storeDir(spec));
        try {
            ModelPackageStore.RollbackResult r = store.rollback(selector);
            out.println("已回退到模型包 " + (r.modelId() == null ? "（清单读不出来）" : r.modelId())
                    + "（版本键 " + r.key() + "），位置 " + r.dir());
            if (r.previousKey() != null) {
                out.println("换下来的那一版保留着（版本键 " + r.previousKey()
                        + "），要切回去用 "
                        + CliInvocation.command(prefix, "model rollback " + r.previousKey()));
            }
            for (String w : r.warnings()) {
                err.println("提醒: " + w);
            }
            out.println();
            reportWhatWillBeUsed(out, r.dir());
            return 0;
        } catch (IOException e) {
            err.println("回退失败: " + e.getMessage());
            return 1;
        }
    }

    private static int remove(String[] args, PrintStream out, PrintStream err) {
        Spec spec = parseArgs(args, err);
        if (spec.bad()) {
            return 2;
        }
        if (rejectsAllowUnsigned(spec, err)) {
            return 2;
        }
        if (spec.rest().size() != 1) {
            err.println("用法: model remove <版本键> [--to 目录]");
            return 2;
        }
        ModelPackageStore store = new ModelPackageStore(storeDir(spec));
        try {
            store.remove(spec.rest().get(0));
            out.println("已删掉版本 " + spec.rest().get(0));
            Path active = store.activeDir();
            if (!Files.isDirectory(active)) {
                // 删掉的正是当前生效的那一份 → 模型包被卸载了。
                // 这时工具退回「只做正则」的状态，而那个状态**不会**脱人名。
                // 不说清楚的话，用户会以为「工具坏了」，或者更糟——
                // 以为脱敏照常进行。
                out.println("当前生效的那份已经被删掉了：工具现在退回只做正则脱敏的状态，"
                        + "**中文人名与机构名不会被脱敏**。");
            } else {
                out.println("当前生效的模型包没动，还在 " + active);
            }
            return 0;
        } catch (IOException e) {
            err.println("删除失败: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 装完之后，**真的查一遍**工具下一次会用的是哪一份模型包，并如实报出来。
     *
     * <p><b>为什么不能假设「装上了就会被用上」</b>：查找顺序里排第一的
     * 是「程序所在目录/models/ner」，第二才是「用户目录」。
     * 于是在一个**已经带模型的 .app** 里，用户新装的模型包排在第二位——
     * 工具下一次跑的仍然是 .app 里那份。
     *
     * <p>这不是理论问题：{@code .app} 形态就带着模型（75.54 MiB 那份），
     * 而 {@code .pkg} 形态不带（43.28 MiB 那份）。
     *
     * <p><b>所以这里不替用户假设</b>：跑一遍真正的查找，
     * 把结论说出来。对用户说「装好了，下次自动用上」而实际上不是，
     * 后果是用户以为换了个更新的模型、其实一直没换——
     * 而脱敏结果依赖模型版本，这件事不能含糊。
     */
    private static void reportWhatWillBeUsed(PrintStream out, Path installedAt) {
        NerModelRegistry registry = null;
        try {
            registry = NerModelRegistry.discover(null);
            Path inUse = registry.directory();
            if (inUse == null) {
                out.println("注意：工具现在仍然找不到任何模型包（没装的位置不对），"
                        + "下一次运行**不会**脱敏人名与机构名。");
                return;
            }
            if (inUse.equals(installedAt)) {
                out.println("下一次运行就会自动用上这一份。");
            } else {
                out.println("注意：工具实际会用的是 " + inUse
                        + "，不是你刚装的这一份（" + installedAt + "）——"
                        + "查找顺序里排在前面的那份会赢。");
                out.println("要用你刚装的这份，请加 --ner-model " + installedAt
                        + " 或设环境变量 " + NerModelRegistry.ENV_MODEL_DIR + "。");
            }
        } catch (Exception e) {
            // 确认环节失败**不能**让整次安装变成失败：包已经装好了。
            // 这里如实说一句「没确认成」就够了——
            // 报一个假的失败比不报更糟（用户会以为装失败了，去重装一遍）。
            out.println("（没能确认下一次会用的是哪一份模型包：" + e.getMessage() + "）");
        } finally {
            if (registry != null) {
                registry.close();
            }
        }
    }

    private static int info(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0) {
            // 多余的参数一律拒掉，不静默忽略——
            // 静默忽略的话，用户以为自己在查某个指定的模型包，
            // 拿到的却是「工具实际会用的那个」，两边对不上却看不出来。
            if ("--to".equals(args[0])) {
                err.println("model info 不接受 --to；它报的是工具实际会用哪个模型包。");
            } else {
                err.println("用法: model info（它报的是工具实际会用哪个模型包，不接受参数）");
            }
            return 2;
        }
        NerModelRegistry registry;
        try {
            registry = NerModelRegistry.discover(null);
        } catch (NerModelRegistry.OrtExceptionWrapper e) {
            err.println("错误: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("错误: 查找模型包时读文件系统失败：" + e.getMessage());
            return 1;
        }
        try {
            out.println(registry.describe());
            Path dir = registry.directory();
            if (dir != null && Files.isDirectory(dir)) {
                out.println("  目录: " + dir);
            }
            return registry.isEnabled() ? 0 : 3;
        } finally {
            registry.close();
        }
    }

    /**
     * {@code model} 的命令行清单（不带「用法:」抬头）。
     *
     * <p><b>为什么要拆出来</b>：顶层 {@code --help} 以前是**手抄**这一段的，
     * 抄漏了 {@code download} 与 {@code trust} 两块（用户照 README 敲会得到
     * 「未知子命令」）。手抄的清单必然会漂 → 改成顶层直接调这里，
     * <b>这一份就是唯一真源</b>，加子命令只改这一处。
     *
     * <p>顶层 help 把这段嵌在「用法:」块里，所以**这里不能有空行**
     * （空行会被用来切分用法块）。
     *
     * @param out 输出流
     */
    static void usageLines(PrintStream out) {
        out.print(usageText());
    }

    /**
     * {@code model} 的命令行（唯一真源）。
     *
     * <p>顶层 help 与 {@code model --help} 都从这里取。
     */
    static String usageText() {
        return """
                  model install <模型包.zip> [--to 目录] [--allow-unsigned]  装一个模型包（旧的会保留，可回退）
                  model download <模型包地址> [--to 目录] [--sig-url 签名地址]  从网上把模型包取回来并装上
                  model list [--to 目录]                 列出当前生效的与保留的历史版本
                  model rollback [版本键] [--to 目录]      回退到上一个 / 指定的历史版本
                  model remove <版本键> [--to 目录]        删掉一个版本（删当前生效的那份=卸载）
                  model info                              报当前会用的是哪个模型包
                  model verify <模型包目录>                验一个模型包能不能用（不安装）
                  model trust add <公钥.pem>              信任一把发布方的公钥
                  model trust list                        列出已信任的公钥
                  model trust remove <密钥标识>            不再信任一把公钥
                """;
    }

    /**
     * {@code model} 的说明段（不含命令行，也不含抬头）。
     *
     * <p>顶层 help 也要讲这些（尤其是「download 不接受 --allow-unsigned」，
     * 用户不看到就会以为下载来的包可以强装），所以与命令行分开给。
     *
     * @param out 输出流
     */
    static void usageNotes(PrintStream out) {
        out.print(usageNotesText());
    }

    /** {@code model} 的说明段（唯一真源）。 */
    static String usageNotesText() {
        return """
                  模型包是独立于核心程序的一份产物（核心包不含模型）。
                  不装模型时工具照常工作，但**中文人名与机构名不会被脱敏**——
                  只有身份证、手机号、银行卡这类正则覆盖的标识符会被处理。
                  install 会先校验再换装：校验不通过就原样不动，
                  装失败也不会毁掉你原来那份能用的模型包；
                  换下来的旧版本会留在历史里，新版本不合适时可以 rollback 退回去。
                  download 是「取回来 + 装上」：给一个模型包地址就行，
                  签名默认从 <地址>.sig 取（也可以用 --sig-url 另外指定）。
                  它只走 http/https，https 降到 http 的重定向会被拒绝，
                  而且**不接受** --allow-unsigned——下载来的东西恰恰最需要验签。
                  模型包还必须**由受信任的公钥签过名**才装得上（签名文件是
                  <模型包.zip>.sig 旁挂一份）。真的没有签名时可以用
                  --allow-unsigned 强装，但工具会明确提醒你来源不明。
                """;
    }

    private static void usage(PrintStream out) {
        CliHelp.print(out, usageText(), usageNotesText());
    }
}
