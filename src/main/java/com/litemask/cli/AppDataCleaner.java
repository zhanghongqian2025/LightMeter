package com.litemask.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 「这个工具在磁盘上留下了什么」——<b>一处定义，全项目共用</b>。
 *
 * <p><b>为什么要有这个类</b>：卸载时最要命的事不是「删不掉」，
 * 而是<b>删漏了</b>。本工具会在磁盘上留下三类东西：
 * <ol>
 *   <li>模型包（{@code ~/.litemask/models/ner}）——含历史版本；</li>
 *   <li>信任锚（{@code ~/.litemask/trust/anchors.json}）——用户加的公钥；</li>
 *   <li>任务工作区（默认 {@code $TMPDIR/litemask-workspace}）——
 *       <b>里面是脱敏映射文件</b>。</li>
 * </ol>
 * 第 3 类最要紧：映射文件里是<b>可还原的原始信息</b>，
 * 落在临时目录里没人清理，等于把「脱敏前的东西」留在了磁盘上。
 *
 * <p><b>为什么用「一处定义」而不是各处各写一遍</b>：
 * 只要清单分散在不同文件里，漏一个就会变成
 * 「卸载说清了，但那个目录还在」——
 * 而这种漏<em>不会</em>自己喊出来。
 * 所以清单只在这里列一次，别的代码（CLI、文档、单测）都从这里取。
 *
 * <p><b>分两级的原因</b>：模型包和信任锚是「重新装一次就有了」的东西，
 * 删掉通常无害；而工作区里可能有用户<b>还没取走</b>的脱敏结果与映射。
 * 所以默认只清<b>应用数据</b>（模型包 + 信任锚），
 * 碰用户数据必须显式说（{@code --all}），并且<b>先列出来再问</b>。
 */
public final class AppDataCleaner {

    /** 一个待清理的位置，以及它属于哪一级。 */
    public enum Scope {
        /** 应用数据：重新装一次就有，删掉通常无害。 */
        APP,
        /** 用户数据：可能有还没取走的产物与映射，删了就没了。 */
        USER
    }

    /**
     * 一个待清理的位置。
     *
     * @param path  要清理的路径（文件或目录）
     * @param scope 属于哪一级
     * @param what  给人看的一句话说明（<b>不是路径本身</b>——
     *                路径里可能有用户自己的文件名）
     */
    public record Target(Path path, Scope scope, String what) {
    }

    /**
     * 本工具在磁盘上留下的<b>全部</b>位置。
     *
     * <p><b>这是唯一真源</b>。别的地方（CLI 输出、单测、文档）都从这里取，
     * 不要在别处再抄一遍——抄一遍就多一个会漂移的地方。
     *
     * @param userHome     「用户主目录」，用 {@code user.home} 推
     * @param tmpDir       「临时目录」，用 {@code java.io.tmpdir} 推
     * @param workspaceDir 工作区目录；<b>用户自己指定过的话以用户为准</b>，
     *                      否则用默认的 {@code $TMPDIR/litemask-workspace}
     */
    public static List<Target> inventory(Path userHome, Path tmpDir, Path workspaceDir) {
        List<Target> out = new ArrayList<>();
        // 模型包：生效中的那一份 + 历史版本都在这个目录下。
        out.add(new Target(userHome.resolve(com.litemask.ner.NerModelRegistry.USER_MODEL_SUBPATH),
                Scope.APP, "模型包（当前生效的那份与历史版本）"));
        // 信任锚：用户自己加的公钥。
        out.add(new Target(userHome.resolve(com.litemask.ner.ModelTrust.USER_ANCHORS_SUBPATH),
                Scope.APP, "信任锚（你加过的公钥）"));
        // 开机自启的 LaunchAgent 配置：它在 launchd 里注册着一个常驻服务，
        // 只删文件不卸载服务的话，重启后它还会自己起来 —— 卸载必须两件事都做。
        // 路径由 LaunchAgentPlist 统一给出（安装/删除/清单三处共用同一个定义）。
        out.add(new Target(LaunchAgentPlist.plistPath(userHome, LaunchAgentPlist.DEFAULT_LABEL),
                Scope.APP, "开机自启配置（launchd 的 LaunchAgent plist）"));
        // 工作区：脱敏产物与映射文件。
        out.add(new Target(workspaceDir, Scope.USER, "任务工作区（脱敏产物与映射文件）"));
        return out;
    }

    /**
     * 默认工作区目录（服务端 {@code --workspace} 没指定时用的那个）。
     *
     * <p><b>为什么不自己再写一遍目录名</b>：这个路径服务端那边已经有一份定义
     * （{@link ServeCommand#defaultWorkspaceDir()}）。
     * 两边各写一遍，迟早会有一边改了、另一边没改——
     * 而那会导致**卸载清错了地方**（或者该清的没清）。
     * 所以这里**引用**服务端的定义，不复制。
     *
     * <p>保留可传参的版本，是为了**能测**：测试里会改 {@code java.io.tmpdir}
     * 系统属性，直接调服务端那个方法会拿到测试环境之外的路径。
     */
    public static Path defaultWorkspaceDir(Path tmpDir) {
        if (tmpDir == null) {
            return ServeCommand.defaultWorkspaceDir();
        }
        // 目录名取自服务端的定义，不在这里再抄一遍。
        Path serverDefault = ServeCommand.defaultWorkspaceDir();
        return tmpDir.resolve(serverDefault.getFileName());
    }

    /**
     * 清理。
     *
     * @param targets  要处理的位置清单（来自 {@link #inventory}）
     * @param scope    清到哪一级
     * @param dryRun   只列不删
     * @param out      正常输出
     * @param err      错误输出
     * @return 退出码；<b>有位置没清掉时返回 1</b>（不能假装成功）
     */
    public static int clean(List<Target> targets, Scope scope, boolean dryRun, PrintStream out, PrintStream err) {
        int removed = 0;
        int missing = 0;
        List<String> failures = new ArrayList<>();

        for (Target t : targets) {
            if (t.scope() == Scope.USER && scope != Scope.USER) {
                // 跳过的也要**说**，否则用户以为磁盘干净了。
                out.println("保留（用户数据，要清请加 --all）：" + t.what());
                continue;
            }
            if (!Files.exists(t.path())) {
                missing++;
                continue;
            }
            if (dryRun) {
                out.println("将要删除：" + t.what() + " —— " + t.path());
                continue;
            }
            try {
                deleteRecursively(t.path());
                out.println("已删除：" + t.what() + " —— " + t.path());
                removed++;
            } catch (IOException e) {
                // 失败要留痕，且**不能算成功**。
                failures.add(t.path() + "（" + e.getMessage() + "）");
                err.println("删除失败：" + t.what() + " —— " + t.path() + "：" + e.getMessage());
            }
        }

        if (dryRun) {
            out.println();
            out.println("以上只是**将要**删除的内容，没有真的删（--dry-run）。");
            return 0;
        }
        if (!failures.isEmpty()) {
            err.println();
            err.println("有 " + failures.size() + " 处没能删干净：");
            for (String f : failures) {
                err.println("  " + f);
            }
            err.println("多半是权限不够（文件属于别的用户，或目录不可写）。");
            return 1;
        }
        out.println();
        out.println("清理完成：删掉 " + removed + " 处" + (missing > 0 ? ("，另有 " + missing + " 处本来就不存在") : "") + "。");
        return 0;
    }

    /**
     * 递归删除。
     *
     * <p><b>为什么不用 {@code Files.walkFileTree} 的 SIMPLE_VISIT_OPTION</b>：
     * 那个选项在遇到符号链接时<em>不跟随</em>，是对的；
     * 但它对<b>目录不可读</b>会直接抛。
     * 这里手写一遍，是为了能逐个文件删、且把「删不掉」当成失败报出来，
     * 而不是静默跳过——<b>卸载说「删干净了」但其实没删干净</b>，
     * 是这里最不能出现的结局。
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            List<Path> children = new ArrayList<>();
            try (Stream<Path> s = Files.list(root)) {
                s.forEach(children::add);
            }
            for (Path c : children) {
                deleteRecursively(c);
            }
        }
        Files.deleteIfExists(root);
    }

    /**
     * 列出某个目录下还剩什么（给「删干净了吗」一个自证手段）。
     *
     * @param dir 要看的目录
     * @return 里面的条目名；目录不存在时返回空列表
     */
    public static List<String> listRemaining(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .forEach(out::add);
        } catch (IOException e) {
            // 读不了也是「还剩东西」——不能当成空的。
            out.add("<读不到：" + e.getMessage() + ">");
        }
        return out;
    }

    private AppDataCleaner() {
    }
}
