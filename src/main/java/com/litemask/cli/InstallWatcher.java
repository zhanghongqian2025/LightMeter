package com.litemask.cli;

import com.litemask.ner.NerModelRegistry;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * 「安装目录被动过 → 自己让位」的看守。
 *
 * <h2>为什么需要它</h2>
 * Windows 上装着 LightMeter 时，程序目录里的 jar 被本进程<b>打开着</b>。
 * 用户双击新版安装包升级时，Windows Installer 要覆盖那个 jar ——
 * 而<b>被打开的文件在 Windows 上覆盖不了</b>。
 *
 * <p>Windows Installer 自己会尝试处理这件事：它启用 Restart Manager，
 * 去找「谁占着这些文件」，让那个程序退出，再重试覆盖。
 * 但 Restart Manager 只能通过<b>窗口消息</b>请程序退出（{@code WM_QUERYENDSESSION}）。
 * 而本产品是<b>控制台程序</b>（jpackage 的 {@code --win-console} 启动器 + 一个 Java 进程），
 * <b>没有窗口</b> → Restart Manager 判定它是「critical application」，
 * <b>拒绝关它</b>，只留下那句要用户自己去处理的话：
 * <pre>
 *   The file ...\app\desens-core.jar is being held in use.  Close that application and retry.
 * </pre>
 * 升级于是失败（MSI 报 1603），而那条提示把「找进程、结束进程」这件事
 * <b>推给了用户</b>——那正是本项目要消除的那种要求。
 *
 * <h2>那怎么办：让程序自己让位</h2>
 * Installer 覆盖文件时会在安装目录里产生动静（删旧文件、写新文件、建临时文件）。
 * 本看守盯着自己的安装目录，<b>一看到动静就自己退出</b>：
 * 先停掉服务（不再接新请求）、关掉模型（释放模型文件）、再退出进程。
 *
 * <p>时序上够不够：Windows Installer 覆盖被占用的文件时<b>会重试大约 90 秒</b>
 * （本机实测：安装 11:01:38 开始，报错 11:03:08，正好 90 秒）。
 * 所以只要本程序在安装开始后的头几秒内退出，覆盖就会在重试中成功。
 *
 * <h2>为什么这样算是「程序自己协调」</h2>
 * 用户什么都不用做：不找进程、不执行命令、不改设置。
 * 程序自己判断「有人要换掉我」，自己把资源交还，自己退出。
 * 安装器随后完成升级，下次启动就是新版本。
 *
 * <h2>为什么不用 WatchService 之外的东西</h2>
 * 目录监视是操作系统提供的能力，不需要额外依赖、不需要轮询、
 * 也不需要去猜「是不是有 msiexec 在跑」。
 */
final class InstallWatcher {

    /**
     * 触发退出后，等多久再真的退出。
     *
     * <p><b>为什么要等</b>：让安装器先动起来再退出，
     * 避免它在「刚删掉旧文件、还没写新文件」的中间态里看到一个半装的目录。
     * 1 秒足够；而 Installer 覆盖被占用文件时**只会重试约 90 秒**，
     * 所以也不能等太久。
     */
    private static final long GRACE_MILLIS = 1000L;

    private InstallWatcher() {
    }

    /**
     * 装上看守（守护线程，不挡着进程退出）。
     *
     * @param onRelease 被触发时要做的事：交还资源（停服务、关模型）
     */
    static void start(Runnable onRelease) {
        Path dir = watchDir();
        if (dir == null) {
            // 找不到自己的安装目录（开发期从 target/classes 跑就是这样）→ 不装看守。
            return;
        }
        Thread t = new Thread(() -> watch(dir, onRelease), "litemask-install-watch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 要盯着哪个目录。
     *
     * <p><b>为什么盯 jar 所在的目录</b>：那是 Installer 唯一会改的目录
     * （{@code <安装目录>/app/}）。盯它就够了，而且它一定存在。
     *
     * <p><b>为什么只在「装好的程序」里才盯</b>：开发期是从
     * {@code target/classes} 跑的，而 {@code mvn package} 会往那儿写文件——
     * 守着它就等于「每次重新构建，服务自己退出了」。
     * 装好的程序有一个开发期没有的标记：jpackage 写在 jar 旁边的
     * <b>{@code *.cfg}</b>（启动器配置）。有它才说明「我住在安装目录里」。
     *
     * @return 目录；不是安装形态时为 {@code null}
     */
    private static Path watchDir() {
        Path programDir = NerModelRegistry.programDirectory();
        if (programDir == null || !Files.isDirectory(programDir)) {
            return null;
        }
        if (!isInstalledLayout(programDir)) {
            return null;
        }
        return programDir;
    }

    /**
     * 这个目录是不是「装好的程序」的目录。
     *
     * <p>判据：旁边有 jpackage 写的启动器配置 {@code *.cfg}。
     * 那是 jpackage 独有的产物，开发期的 {@code target/classes} 里没有。
     */
    private static boolean isInstalledLayout(Path programDir) {
        try (var entries = Files.list(programDir)) {
            return entries.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".cfg");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static void watch(Path dir, Runnable onRelease) {
        WatchService service;
        try {
            service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            return;
        }
        try (WatchService ws = service) {
            register(ws, dir);
            // 连**上一层**也盯着：安装器换装时会把旧产品的文件与目录删掉，
            // 那些动作落在上一层（安装根目录）上。
            // 只盯着 jar 那一层的话，「先删旧目录」这类动作可能落在视野之外。
            Path parent = dir.getParent();
            if (parent != null) {
                register(ws, parent);
            }
            while (true) {
                // 【为什么是 poll(超时) 而不是 await】
                //   await 会**先**睡满这段时间，醒来才去 poll ——
                //   那样第一条事件要等一个小时才被看见，
                //   而安装器只重试 90 秒 → 永远等不到。
                //   poll 带超时才是「事件一来就醒」。
                WatchKey key = ws.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (key == null) {
                    if (installerRunning()) {
                        // 第二个信号：安装器进程在跑。
                        break;
                    }
                    continue;
                }
                boolean interesting = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        interesting = true;
                        break;
                    }
                    interesting = true;
                }
                key.reset();
                if (!interesting) {
                    continue;
                }
                // 安装目录里出现了「创建/删除/修改」——
                // 而**读**文件不会产生这三种事件（读是 ENTRY_ACCESS，这里没订阅）。
                // 也就是说：正常使用时这个目录是不动的，
                // 一旦有动静，就是有人（安装器）在改这份安装。
                break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ClosedWatchServiceException ignored) {
            // 服务被关掉了，正常收工
        } catch (IOException ignored) {
            // 监视不了就算了：升级失败时用户仍可手动退出程序
            return;
        }
        // 【为什么要先等一小会儿】
        //   让安装器先动起来再退出，避免它在「刚删掉旧文件、还没写新文件」
        //   的中间态里就看到一个半装的目录而报错。
        //   Installer 覆盖被占用文件时**会重试约 90 秒**
        //   （本机实测：安装 11:01:38 开始，报错 11:03:08，正好 90 秒），
        //   所以退出得足够早，重试就能成功。
        try {
            Thread.sleep(GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            onRelease.run();
        } catch (RuntimeException ignored) {
            // 让位失败也要退出：留着进程只会让安装继续失败
        }
        // 交还资源后退出。退出码 0：这不是错误，是「安装器来了，我让位」。
        Runtime.getRuntime().halt(0);
    }

    private static void register(WatchService ws, Path dir) {
        try {
            dir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException | RuntimeException ignored) {
            // 某个目录盯不上就算了，另一个还盯着
        }
    }

    /**
     * 有没有安装器正在跑。
     *
     * <p><b>为什么要有这个信号</b>：目录监视依赖「安装器确实动了那个目录」。
     * 而安装器在**第一个文件就被占用**时会直接失败（此时它还没来得及改任何东西），
     * 目录里一个字节都没变 → 目录监视**不会触发**。
     *
     * <p>而「有安装器正在跑」这件事是**直接可观察**的：
     * 安装器的命令行里带着 MSI 的路径，而那个路径里有产品名。
     *
     * <p><b>为什么只认带产品名的那个</b>：别的安装器在跑时把我们关掉，
     * 是用户没要求的事。只认带「LightMeter」的，就只会在**升级本程序**时触发。
     *
     * @return 在跑就 true
     */
    private static boolean installerRunning() {
        try (var stream = ProcessHandle.allProcesses()) {
            for (ProcessHandle ph : stream.toList()) {
                // ProcessHandle.Info 没有 name()，可执行文件路径在 command() 里。
                String exe = ph.info().command().orElse("");
                if (!exe.toLowerCase(java.util.Locale.ROOT).contains("msiexec")) {
                    continue;
                }
                String cmd = ph.info().commandLine().orElse("");
                if (cmd.toLowerCase(java.util.Locale.ROOT).contains("lightmeter")) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 看不了进程就当没有
        }
        return false;
    }
}
