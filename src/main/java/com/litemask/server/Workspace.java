package com.litemask.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 上传文件的落脚点。
 *
 * <p><b>本类存在的唯一理由是「外部提供的字符串不参与路径拼接」。</b>
 * 浏览器把文件名交给服务端时，这个文件名是<b>攻击者完全可控</b>的输入。
 * 一旦写成 {@code workspace.resolve(session).resolve(clientFilename)}，
 * 一个 {@code ../../.ssh/authorized_keys} 就能落到工作目录之外。
 *
 * <p>所以这里的分工是死的：
 * <ul>
 *   <li><b>磁盘名</b>由本类自己生成（UUID + 白名单后缀），不接受任何外部输入；</li>
 *   <li><b>客户端文件名</b>只被用来做两件事：判定后缀（白名单）、生成一个<b>去掉了
 *       路径分隔符与控制字符</b>的显示名。显示名只出现在界面上，永远不进文件系统。</li>
 * </ul>
 *
 * <p>另外两层防护是纵深的，不指望上面那一条就够：
 * <ul>
 *   <li>会话 id 有格式校验，且 {@link #resolve} 末尾会做一次「规范化后仍在工作目录内」的比对；</li>
 *   <li>整个会话目录挂在 {@code <根>/sessions/<uuid>/} 下，<b>正常退出</b>时整棵删掉——
 *       这一层只管正常退出；<b>被强杀</b>（SIGKILL / 断电）时 {@code close()} 根本不会执行，
 *       那一半靠<b>下一次启动时的清扫</b>：每个会话持有一把文件锁，
 *       锁由内核持有，主人一死内核就放，于是下一个实例能认出来并清掉。</li>
 * </ul>
 *
 * <p>清扫和启动之间有一把独立的<b>启动互斥锁</b>（{@code <根>/locks/.root.lock}），
 * 原因见 {@link #open}：清扫的判据是「锁我能拿到 = 主人已死」，
 * 而主人自己也是「先建锁文件、再锁上」，这两步之间若被别的实例撞上，
 * 活着的实例会被误判成崩溃残留、连同用户文档一起被删。
 */
public final class Workspace implements AutoCloseable {

    /** 上传体积上限。256MB 足够本工具处理的任何一种文档，再大就该走文件系统直接操作了。 */
    public static final long MAX_UPLOAD_BYTES = 256L * 1024 * 1024;

    /**
     * 允许的扩展名白名单。
     *
     * <p>只认五种文档格式，外加纯文本兜底。刻意<b>不接受</b> {@code .html}：
     * 存进去再取出来用浏览器打开，等于自己发一个 XSS。也不接受 {@code .jar}/{@code .sh}
     * 这类可执行后缀——本机工具没有理由接收它们。
     */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".docx", ".xlsx", ".pptx", ".pdf", ".ofd", ".txt");

    /** 显示名最长长度。超长既没用（界面不显示）又会撑爆日志。 */
    private static final int MAX_DISPLAY_NAME = 120;

    /**
     * 一份已经落到磁盘的上传文件。
     *
     * @param sessionId   本次服务启动内的会话 id
     * @param diskName    服务端生成的磁盘文件名（不可从外部推导）
     * @param path        绝对路径，仅服务端使用
     * @param displayName 清洗后的展示名，可安全地 {@code textContent} 到界面上
     * @param extension   白名单内的扩展名，含点，小写
     */
    public record StoredFile(String sessionId, String diskName, Path path,
                             String displayName, String extension) {
    }

    /**
     * 一份<b>已经落盘、但还没被认领</b>的上传。
     *
     * <p>它和 {@link StoredFile} 的区别是「还没人认领」。暂存件必须有明确的去处：
     * 被 {@link #store(StagedFile)} 认领（变成正式件），
     * 或者被 {@link #discard(StagedFile)} 扔掉。
     * <b>没有第三条路</b>——留在盘上的暂存件就是一份没人知道它是什么的原文档。
     *
     * @param path        暂存文件的绝对路径，仅服务端使用
     * @param extension   白名单内的扩展名，含点，小写
     * @param displayName 清洗后的展示名
     */
    public record StagedFile(Path path, String extension, String displayName) {
    }

    private final Path root;
    private final String sessionId;
    private final Path sessionDir;
    private final long maxUploadBytes;
    private boolean closed;

    /** 锁文件所在目录。放在会话目录<b>之外</b>——会话目录里只该有用户的文档。 */
    private static final String LOCK_DIR = "locks";
    private static final String LOCK_SUFFIX = ".lock";

    /**
     * 启动互斥锁的文件名。
     *
     * <p>它不是某个会话的锁，而是「<b>谁可以在这棵工作目录上启动</b>」的那把锁。
     * 理由见 {@link #open} 里那段注释——没有它，清扫会把刚启动的实例误判成崩溃残留。
     */
    private static final String ROOT_LOCK_FILE = ".root.lock";

    /** 拿不到启动互斥锁时的最长等待（毫秒）。临界区只有毫秒级，这个上限只是防挂死。 */
    private static final long ROOT_LOCK_WAIT_MS = 5_000L;

    /** 同一根目录的 {@code open()} 在进程内必须串行；跨进程靠 {@link #ROOT_LOCK_FILE}。 */
    private static final Map<Path, Object> OPEN_MONITORS = new ConcurrentHashMap<>();

    /** 会话 id 的格式：32 位十六进制。清扫时只认这种名字。 */
    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{32}");

    private Path lockFile;
    private FileChannel lockChannel;
    private FileLock lock;

    private Workspace(Path root, String sessionId, Path sessionDir, long maxUploadBytes) {
        this.root = root;
        this.sessionId = sessionId;
        this.sessionDir = sessionDir;
        this.maxUploadBytes = maxUploadBytes;
    }

    /**
     * 打开一个工作区：固定根目录下建一个本次启动专属的随机会话目录。
     *
     * <p>用随机子目录而不是直接用根目录，是为了让「哪一批文件属于这次运行」一眼可辨，
     * 也是为了让 {@link #close()} 能整棵删掉而不误伤用户放在同一父目录下的东西。
     */
    public static Workspace open(Path root) throws IOException {
        return open(root, MAX_UPLOAD_BYTES);
    }

    /**
     * 打开一个工作区，并<b>指定上传体积上限</b>。
     *
     * <p><b>为什么上限要能改</b>：上限判定如果只能靠「真的传 256MB」去触发，
     * 那这条判定就没法在测试里被直接验——要么测试慢到不可接受，
     * 要么为了迁就测试把上限改小，而改小的上限就不是生产用的那个上限了，
     * 等于测了另一个东西（这条教训在 {@link #checkUploadSize} 的注释里已经记过一次）。
     * 把它变成实例参数之后，测试可以把它调到 1KB，
     * <b>测的是同一段代码、同一个判定</b>。
     */
    public static Workspace open(Path root, long maxUploadBytes) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path sessionsDir = base.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Path locksDir = base.resolve(LOCK_DIR);
        Files.createDirectories(locksDir);
        // 【为什么这三个目录要收紧到 0700】
        //   会话目录里放着**用户上传的原件**与**含原值的映射件**。
        //   默认的 0755 意味着同一台机器上的任何用户都能进去把它们读走 ——
        //   而这份工具的承诺是「文档内容零外发」，落到文件系统上却是「同机人人可读」。
        //   目录权限与文件权限（映射件 0600，见 PrivateFile）是两层，缺一不可：
        //   文件 0600 但目录 0755 时，**目录名**照样被同机其他用户看到并猜到路径。
        com.litemask.core.PrivateFile.tightenDir(base);
        com.litemask.core.PrivateFile.tightenDir(sessionsDir);
        com.litemask.core.PrivateFile.tightenDir(locksDir);

        // ── 启动临界区 ───────────────────────────────────────────────
        // 临界区里只有两件事：清扫别人留下的东西、建自己的锁和会话目录。
        // 这两件事必须和其它实例互斥，原因是<b>清扫的判据</b>：
        // 「这把锁我能锁上 = 主人已经不在了」。
        // 而主人自己也是「先建锁文件、再锁上」——在这两步之间，
        // 另一个实例的清扫会看到一把<b>还没人锁</b>的锁文件，
        // 于是把「刚启动起来的实例」当成「崩溃留下的」，
        // 把它的会话目录连同里面的用户文档一起删掉。
        //
        // 临界区把「建锁文件」和「清扫」隔开，这个窗口就没有了。
        // 进程内用 synchronized（文件锁在同一 JVM 里是抛异常而不是阻塞），
        // 跨进程用一把独立的文件锁。
        Object monitor = openMonitor(base);
        synchronized (monitor) {
            FileChannel guard = null;
            FileLock guardLock = null;
            try {
                guard = FileChannel.open(locksDir.resolve(ROOT_LOCK_FILE),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                guardLock = lockWithRetry(guard);
                if (guardLock == null) {
                    throw new IOException("工作目录正在被另一个实例使用，请稍后重试");
                }
                // 先把上一次崩溃留下的东西清掉，再开自己的。
                sweepStaleSessions(sessionsDir, locksDir);
                String sid = randomId();
                Workspace ws = new Workspace(base, sid, sessionsDir.resolve(sid), maxUploadBytes);
                ws.acquireLock(locksDir.resolve(sid + LOCK_SUFFIX));
                try {
                    Files.createDirectories(ws.sessionDir);
                    com.litemask.core.PrivateFile.tightenDir(ws.sessionDir);
                } catch (IOException e) {
                    ws.releaseLock();
                    throw e;
                }
                return ws;
            } finally {
                if (guardLock != null) {
                    try {
                        guardLock.release();
                    } catch (IOException ignored) {
                        // 放不掉锁只会让下一个实例多等一会儿
                    }
                }
                closeQuietly(guard);
            }
        }
    }

    /** 同一根目录的 {@code open()} 在进程内必须串行。 */
    private static Object openMonitor(Path base) {
        return OPEN_MONITORS.computeIfAbsent(base, p -> new Object());
    }

    /**
     * 拿启动互斥锁，拿不到就重试到超时。
     *
     * <p>跨进程时是另一把文件锁在挡着，这里要等；
     * 同一 JVM 里文件锁是<b>抛 {@link OverlappingFileLockException}</b> 而不是阻塞，
     * 但那一层已经被 {@link #OPEN_MONITORS} 的 synchronized 兜住了，
     * 真撞上了就当成「暂时拿不到」继续等（有上限，不会无限等）。
     */
    private static FileLock lockWithRetry(FileChannel channel) throws IOException {
        long deadline = System.nanoTime() + ROOT_LOCK_WAIT_MS * 1_000_000L;
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException e) {
                // 见上：正常到不了这里
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 清掉上一次崩溃留下的会话目录。
     *
     * <p><b>怎么判断「上一次」还在不在</b>：靠<b>文件锁</b>。
     * 每个会话有一个锁文件，进程活着时一直持着它；
     * 进程被强杀（SIGKILL / 断电）时，操作系统会替它把锁放开——
     * 于是「这把锁我能拿到」就等于「主人已经不在了」。
     *
     * <p><b>为什么不看时间戳</b>：进程刚崩、文件刚写完时，时间戳是分不清的；
     * 而「这个进程还活着吗」在跨平台上没有可靠答案，文件锁却有
     * （锁由内核持有，进程一死内核就放）。
     *
     * <p><b>为什么只清锁文件指向的会话</b>：用户可能把 {@code --workspace} 指到
     * 一个装着自己资料的目录。要是去扫 {@code sessions/} 下的每一个目录，
     * 就会把用户自己的东西删掉——那比留残留严重得多。
     * 所以只认「有对应锁文件、且会话名是本工具的 id 格式」的那些。
     *
     * <p>扫不动就当没扫：宁可留残留，也不要误删。
     */
    private static void sweepStaleSessions(Path sessionsDir, Path locksDir) {
        List<Path> lockFiles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(locksDir, "*" + LOCK_SUFFIX)) {
            for (Path p : ds) {
                lockFiles.add(p);
            }
        } catch (IOException ignored) {
            return;
        }
        for (Path file : lockFiles) {
            String name = file.getFileName().toString();
            if (name.equals(ROOT_LOCK_FILE)) {
                // 启动互斥锁：它不属于任何会话
                continue;
            }
            String sid = name.substring(0, name.length() - LOCK_SUFFIX.length());
            if (!SESSION_ID.matcher(sid).matches()) {
                // 不是本工具建的会话名：可能是用户自己的东西，别碰
                continue;
            }
            FileChannel channel = null;
            FileLock held = null;
            try {
                channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                held = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // 同一个 JVM 里已经有人锁着它 → 那个实例还活着
                held = null;
            } catch (IOException e) {
                // 锁不到不等于死了：Windows 上别人锁着的文件根本打不开
                held = null;
            }
            // 锁到我手上 = 没人正持着它 = 主人已经不在了
            boolean 主人已死 = held != null;
            if (!主人已死) {
                // 主人在位：它的会话目录和文档正在被使用，一个字都不能碰
                closeQuietly(channel);
                continue;
            }
            try {
                deleteRecursively(sessionsDir.resolve(sid));
            } catch (IOException ignored) {
                // 清不掉就留着：下一轮还会再试
            }
            if (held != null) {
                try {
                    held.release();
                } catch (IOException ignored) {
                }
            }
            closeQuietly(channel);
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    /** 持有一把锁：锁在，主人就在。 */
    private void acquireLock(Path path) throws IOException {
        lockFile = path;
        lockChannel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            closeQuietly(lockChannel);
            lockChannel = null;
            throw new IOException("工作目录已被另一个实例占用：" + path);
        }
        // 写上进程号：残留被清掉时，至少能看出它是谁留下的
        try {
            lockChannel.write(ByteBuffer.wrap(
                    (ProcessHandle.current().pid() + System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ignored) {
            // 写不进去也不影响：锁本身才是判据
        }
    }

    /** 放锁并删掉锁文件。顺序要紧：Windows 上文件被锁着时删不掉。 */
    private void releaseLock() {
        try {
            if (lock != null) {
                lock.release();
                lock = null;
            }
        } catch (IOException ignored) {
            // 见 close 的注释：清理是尽力而为
        }
        closeQuietly(lockChannel);
        lockChannel = null;
        if (lockFile != null) {
            try {
                Files.deleteIfExists(lockFile);
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    /** 整棵删掉一个目录。 */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 本工作区的上传体积上限（字节）。 */
    public long maxUploadBytes() {
        return maxUploadBytes;
    }

    public String sessionId() {
        return sessionId;
    }

    public Path sessionDir() {
        return sessionDir;
    }

    public Path root() {
        return root;
    }

    /**
     * 造一个还没落盘的文件名。
     *
     * <p>预览件、成品件都走这里——和 {@link #store} 一样，磁盘名完全由本类生成，
     * 外部输入只贡献一个已经过白名单的后缀。
     *
     * <p><b>映射件不走这里</b>：这条路的白名单说的是「<b>用户能传进来什么</b>」，
     * 而映射件是服务端自己生成的东西。让它由「用户能传什么」来决定叫什么，
     * 是把两件不相干的事混成一件。映射件见 {@link #newMappingName()}。
     */
    public String newDiskName(String extension) throws IOException {
        ensureOpen();
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IOException("扩展名不在白名单内: " + extension);
        }
        return randomId() + extension;
    }

    /**
     * 造一个映射件的文件名。
     *
     * <p>刻意<b>不</b>走 {@link #newDiskName}，理由见那段注释：上传白名单管的是
     * 「用户能传什么」，映射件是服务端自己产的。
     *
     * <p>后缀固定 {@code .json}。它<b>不</b>进 {@link #ALLOWED_EXTENSIONS}——
     * 那是上传白名单，把 {@code .json} 加进去等于让用户能上传 .json 文件。
     */
    public String newMappingName() throws IOException {
        ensureOpen();
        return randomId() + ".json";
    }

    /** 会话目录内的绝对路径；调用方负责确保该文件确实存在。 */
    public Path pathOf(String diskName) {
        return sessionDir.resolve(diskName);
    }

    /**
     * 删掉会话目录里的一个文件。
     *
     * <p>任务被 LRU 淘汰时用来清掉它在盘上的三份东西（原件、预览件、成品件）。
     * <b>不这么做的话，淘汰只是把内存里的条目抹掉</b>，文件还在磁盘上一直留到
     * 进程退出——对一个处理敏感文档的工具来说，那等于把用户的原始文件
     * 悄悄留在临时目录里，而界面上早就看不见它了。
     *
     * <p>删不掉不算失败：文件可能已经不在（重试），也可能被别人占着。
     * 这条路径只在没人再引用它之后跑，删不掉不该让一次新的上传失败。
     */
    public void delete(String diskName) {
        if (diskName == null || diskName.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(pathOf(diskName));
        } catch (IOException | RuntimeException ignored) {
            // 见方法注释：清理是尽力而为，不能反过来卡住主流程
        }
    }

    /**
     * 收下一份上传。
     *
     * @param bytes          文件内容
     * @param clientFilename 浏览器报上来的文件名，可为 null，可完全不可信
     * @throws UnsupportedFileTypeException 后缀不在白名单内
     * @throws FileTooLargeException        超过 {@link #MAX_UPLOAD_BYTES}
     */
    public StoredFile store(byte[] bytes, String clientFilename)
            throws IOException, UnsupportedFileTypeException, FileTooLargeException {
        Objects.requireNonNull(bytes, "bytes");
        ensureOpen();
        checkUploadSize(bytes.length);
        StagedFile staged = stage(new java.io.ByteArrayInputStream(bytes), clientFilename);
        try {
            return store(staged);
        } catch (IOException e) {
            discard(staged);
            throw e;
        }
    }

    /**
     * 把一份上传<b>流式</b>写到暂存文件上。
     *
     * <p><b>为什么要流式落盘，而不是攒成 byte[] 再写</b>：
     * 后台任务从「提交」到「被工作线程捡起来」之间，那份内容一直在某个地方等着。
     * 以前它等在<b>堆</b>里（一个 byte[]），而任务表上限是 64、上传上限是 256MB——
     * 于是「连着提交 64 份大文件」在理论上就能把内存撑爆，
     * 而那只是用户多拖了几个文件而已。
     * 改成先落盘，等的那份内容就变成了<b>磁盘空间</b>：内存里只有一个 64KB 的缓冲。
     *
     * <p><b>后缀先判、盘后写</b>：不支持的类型压根不该占磁盘，
     * 而且拒绝得越早越好——用户不用等任务跑完才发现白传了一份。
     *
     * @param in             上传内容。方法不关闭它（调用方还要用）
     * @param clientFilename 浏览器报上来的文件名，可为 null，可完全不可信
     * @throws UnsupportedFileTypeException 后缀不在白名单内（此时<b>不写盘</b>）
     * @throws FileTooLargeException        超过 {@link #maxUploadBytes()}（此时<b>不留半截文件</b>）
     */
    public StagedFile stage(InputStream in, String clientFilename)
            throws IOException, UnsupportedFileTypeException, FileTooLargeException {
        ensureOpen();
        // 先判后缀：不支持就压根别占磁盘
        String ext = extensionOf(clientFilename);
        String display = displayNameOf(clientFilename);
        Path tmp = sessionDir.resolve("upload-" + randomId() + ".tmp");
        long total = 0;
        boolean 写完 = false;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    total += n;
                    if (total > maxUploadBytes) {
                        // 立刻停：再写下去就是在替攻击者占磁盘
                        throw new FileTooLargeException(total, maxUploadBytes);
                    }
                    out.write(chunk, 0, n);
                }
            }
            写完 = true;
        } finally {
            if (!写完) {
                // 半截文件比没有文件更糟：它看起来像一份完整的原文档
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清理是尽力而为；它本来就只躺在会话目录里，进程退出时整棵删掉
                }
            }
        }
        return new StagedFile(tmp, ext, display);
    }

    /**
     * 认领一份暂存件：把它挪进会话目录并登记成正式件。
     *
     * <p>用 {@code move} 而不是再写一遍：暂存件本来就在同一个目录里，
     * 挪过去是一次改名，不是一次拷贝——大文件下这个差别是实打实的。
     *
     * @throws IOException 挪不动（此时暂存件仍在原处，调用方负责丢弃）
     */
    public StoredFile store(StagedFile staged) throws IOException {
        ensureOpen();
        if (staged == null || staged.path() == null) {
            throw new IOException("没有可登记的上传文件");
        }
        String diskName = randomId() + staged.extension();
        Path target = sessionDir.resolve(diskName);
        Files.move(staged.path(), target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile(sessionId, diskName, target, staged.displayName(), staged.extension());
    }

    /**
     * 扔掉一份没人认领的暂存件。
     *
     * <p><b>不留垃圾</b>：暂存件是用户的原始文档。留在磁盘上等于
     * 「用户以为没上传成功、文件却还在」——对一个处理敏感文档的工具来说，
     * 这不只是占空间的问题。
     *
     * <p>删不掉不算失败：它可能已经不在（被认领走了），也可能被别人占着。
     * 清理失败不该让一次新的上传失败。
     */
    public void discard(StagedFile staged) {
        if (staged == null || staged.path() == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged.path());
        } catch (IOException | RuntimeException ignored) {
            // 见方法注释：清理是尽力而为
        }
    }

    /**
     * 由「会话 id + 磁盘名」取回一份上传。
     *
     * <p>两个参数都来自后续请求（也就是来自客户端），所以这里逐项校验格式，
     * 最后再核对一次规范化后的路径确实落在会话目录里。
     * 前面的字符集校验已经足够挡住穿越，这一道是纵深——万一哪天白名单被放宽了，
     * 至少还有一层不会跟着一起失效。
     */
    public Path resolve(String session, String diskName) throws IOException {
        ensureOpen();
        if (session == null || !session.matches("[0-9a-f]{32}") || !session.equals(sessionId)) {
            throw new IOException("会话不存在或已过期，请重新上传文件");
        }
        if (diskName == null || !diskName.matches("[0-9a-f]{32}\\.[a-z0-9]{2,5}")) {
            throw new IOException("文件名不合法");
        }
        Path resolved = sessionDir.resolve(diskName).normalize();
        if (!resolved.startsWith(sessionDir.normalize())) {
            throw new IOException("文件名不合法");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("文件不存在或已过期，请重新上传文件");
        }
        return resolved;
    }

    /**
     * 上传体积判定。
     *
     * <p>单独抽出来是为了能被直接测：走 {@link #store} 的公开路径要真的分配
     * 256MB 才能触发，而那会让测试要么慢到不可接受，要么为了迁就内存把上限改小——
     * 改小的上限就不是生产用的那个上限了，等于测了另一个东西。
     *
     * <p>判定用 {@code >} 而不是 {@code >=}：正好等于上限的文件是允许的。
     *
     * <p>上限取的是<b>本工作区的</b>上限（{@link #maxUploadBytes()}），
     * 而不是那个静态常量——否则测试把上限调小之后，
     * 生产路径和测试路径就会走两套判定，测的还是别的东西。
     */
    void checkUploadSize(long bytes) throws FileTooLargeException {
        if (bytes > maxUploadBytes) {
            throw new FileTooLargeException(bytes, maxUploadBytes);
        }
    }

    /**
     * 取扩展名。
     *
     * <p>只认 {@code clientFilename} 里最后一个点之后的部分，且必须在白名单里。
     * {@code ../../x.pdf} 会被剥成 {@code x.pdf}（因为我们只取 basename），
     * {@code x.PDF} 会被小写后接受，{@code .pdf} 这种只有后缀没有主名的一律拒绝。
     */
    static String extensionOf(String clientFilename) throws UnsupportedFileTypeException {
        String name = baseOf(clientFilename);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            throw new UnsupportedFileTypeException(
                    "无法识别文件类型：" + (displayNameOf(clientFilename))
                            + "。支持 .docx / .xlsx / .pptx / .pdf / .ofd / .txt");
        }
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new UnsupportedFileTypeException(
                    "不支持的文件类型：" + ext + "。支持 .docx / .xlsx / .pptx / .pdf / .ofd / .txt");
        }
        return ext;
    }

    /**
     * 生成可安全展示的名字。
     *
     * <p>剥掉任何路径成分（{@code /} 与 {@code \\}）、去掉控制字符与引号，截断长度。
     * 剥完可能为空（客户端传的是 {@code ".."}），此时给一个占位名，
     * 免得界面上出现一个空白行让人以为还有东西没加载出来。
     */
    static String displayNameOf(String clientFilename) {
        String name = baseOf(clientFilename);
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // 控制字符、双引号、反斜杠、斜杠、以及所有分隔符一律替换成下划线
            if (c < 0x20 || c == 0x7F || c == '"' || c == '\\' || c == '/' || Character.isSurrogate(c)) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        // 结尾的点会让某些系统把扩展名藏起来（Windows 的做法），显示上也没意义
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "未命名文件";
        }
        if (cleaned.length() > MAX_DISPLAY_NAME) {
            cleaned = cleaned.substring(cleaned.length() - MAX_DISPLAY_NAME);
        }
        return cleaned;
    }

    /** 取 basename：砍掉最后一个 {@code /} 或 {@code \} 之前的全部内容。 */
    private static String baseOf(String clientFilename) {
        if (clientFilename == null) {
            return "";
        }
        String s = clientFilename.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        // 有些浏览器会把整条路径都报上来，Windows 上还可能带盘符，这里一并去掉
        if (s.length() >= 2 && s.charAt(1) == ':') {
            s = s.substring(2);
        }
        return s;
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("服务已停止");
        }
    }

    /**
     * 整棵删掉本次会话目录，并放掉锁。
     *
     * <p>删不掉也不抛——退出路径不该因为清理失败而影响用户的退出。
     *
     * <p><b>但这一条只管「正常退出」</b>：进程被强杀（SIGKILL / 断电）时
     * {@code close()} 根本不会执行，原文档会留在盘上。
     * 那一半靠的是<b>下一次启动时的清扫</b>（见 {@link #sweepStaleSessions}）：
     * 锁由内核持有，主人一死内核就放，于是下一个实例能认出来并清掉。
     */
    @Override
    public void close() {
        closed = true;
        try {
            deleteRecursively(sessionDir);
        } catch (IOException ignored) {
            // 见上：清理失败不影响关闭
        }
        releaseLock();
    }

    /** 文件类型不在白名单内。这不是「忽略」，是要明确告诉用户的错误。 */
    public static final class UnsupportedFileTypeException extends Exception {
        public UnsupportedFileTypeException(String message) {
            super(message);
        }
    }

    /** 上传体积超限。 */
    public static final class FileTooLargeException extends Exception {
        private final long size;
        private final long limit;

        /**
         * <b>必须带着「实际上限」进来。</b>
         *
         * <p>上限已经是实例参数（{@link Workspace#maxUploadBytes()}），
         * 而文案如果去读静态常量 {@link Workspace#MAX_UPLOAD_BYTES}，
         * 那么上限一旦不是 256MB，报错就会告诉用户一个错的数——
         * 用户会以为自己还有 256MB 的余量，于是反复重传同一个文件。
         *
         * <p>这也是「同一个事实只在一个地方定义」：上限的值由工作区决定，
         * 报错只是把它念出来，不自己另存一份。
         */
        public FileTooLargeException(long size, long limit) {
            super("文件超过 " + humanSize(limit) + " 上限（本次 " + humanSize(size) + "）");
            this.size = size;
            this.limit = limit;
        }

        public long size() {
            return size;
        }

        /** 实际上限（字节）。 */
        public long limit() {
            return limit;
        }
    }

    /**
     * 把字节数说成人话。
     *
     * <p>小体积也要说得准：「本次 0MB」等于没说——上限调到 1KB 时，
     * 整句话就变成「文件超过 1MB 上限（本次 0MB）」，两个数都在骗人。
     */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " 字节";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + "KB";
        }
        return (bytes / 1024 / 1024) + "MB";
    }
}
