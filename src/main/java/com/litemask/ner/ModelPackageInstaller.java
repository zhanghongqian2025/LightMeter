package com.litemask.ner;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把一个「模型包压缩文件」装到指定目录。
 *
 * <p><b>为什么要有它</b>：方案 B 把模型包从核心包里拆了出去（核心包 ≤90MiB 的硬指标
 * 就是这么腾出来的），于是「用户手上没有模型包」是<b>常态</b>。
 * 但没有模型包时，中文人名与机构名<b>不会</b>被脱敏——那不是「少一个功能」，
 * 是「一份没脱干净的文档」。所以模型包必须有一条<b>不依赖手工复制目录</b>的安装路径。
 *
 * <p><b>装进去的必须是「能用的」模型包</b>：解压完不直接就位，
 * 而是先过一遍 {@link NerModelPackage} 的完整校验（清单格式、逐文件 SHA-256、
 * 模型自包含、模型与词表配套），通过了才换装。
 * 校验不过就<b>原样不动</b>——宁可让用户看到「装不上」，也不要留下一个
 * 「装了一半、工具却当它能用」的目录。
 *
 * <p><b>换装是原子的</b>：先解压到旁边的暂存目录，校验通过后，
 * 把旧目录挪开、把新目录挪到位；挪不动就把旧的放回去。
 * 于是「装失败」不会毁掉用户原来那份能用的模型包。
 *
 * <p><b>解压按「不受信任的输入」对待</b>：条目路径必须落在目标目录内
 * （否则一个 {@code ../../} 条目就能把文件写到目标之外），
 * 条目数与解压后的总字节数都有上限（防 zip 炸弹）。
 */
public final class ModelPackageInstaller {

    /** 最多允许多少个条目。模型包只有 3 个文件（清单、模型、词表）。 */
    public static final int MAX_ENTRIES = 64;

    /**
     * 解压后总字节数上限。
     *
     * <p>模型包现在是 14MB 出头，512MiB 是一个宽松到不像限制、
     * 但又能挡住 zip 炸弹的数。解压前不限制的话，一个几百 KB 的压缩包
     * 能把磁盘写满。
     */
    public static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /**
     * 包内容校验。
     *
     * <p><b>为什么留这个缝</b>：默认实现要真的把 ONNX 模型加载起来
     * （那才是「这个包能不能用」的权威答案），而模型文件有 14MB、
     * 仓库里也没有——单元测试没法自带一份。
     * 于是把「校验」这一步做成可替换的：生产用完整的，
     * 测试用一份自己能造出来的假校验（造出来的东西是假的，
     * 但<b>被检验的那段逻辑</b>——解压、越界防护、换装、失败回退——是真的）。
     *
     * <p>真实模型包上的那条路径由端到端脚本覆盖（那里有真模型）。
     */
    @FunctionalInterface
    public interface Validator {
        /**
         * 校验解压出来的模型包目录。
         *
         * @param dir 解压后的目录
         * @return 包的身份标识（模型 id）
         * @throws Exception 校验不通过。消息要写清「哪里不对」，
         *                   因为它会直接显示给用户。
         */
        String validate(Path dir) throws Exception;
    }

    /**
     * 一次安装的结果。
     *
     * @param modelId  模型包 id
     * @param directory 装好的目录
     * @param files    装进去的文件名（相对目录）
     * @param warnings 没能做完的收尾动作（不装成功的部分，只是提醒）
     */
    public record Result(String modelId, Path directory, List<String> files, List<String> warnings) {
    }

    /**
     * 换装时搬目录的动作。
     *
     * <p><b>为什么留这个缝</b>：换装是「把旧目录挪开、把新目录挪到位」，
     * 而<b>挪失败时要把旧的放回去</b>——那是防「装失败顺手毁掉用户原来那份」
     * 的关键一步。但「挪失败」在正常机器上几乎不会发生，
     * 于是这条关键路径<b>没法被直接测</b>。
     * 做成可替换的动作后，测的是同一段代码、同一个判定，只是让搬的动作失败。
     * 生产默认仍然是 {@link Files#move}。
     */
    @FunctionalInterface
    public interface Mover {
        /**
         * 把 {@code from} 搬到 {@code to}。
         *
         * @throws IOException 搬不动
         */
        void move(Path from, Path to) throws IOException;
    }

    private final Validator validator;
    private final Mover mover;

    /**
     * 条目数上限（实例可配）。
     *
     * <p><b>为什么做成实例参数</b>：上限类判定如果只能靠「真的造一个 65 个条目的包」
     * 才能触发，那它就没法被直接测——造得起，但测起来别扭；更糟的是造出一个
     * 几百 MB 的包来测字节上限，那是在烧磁盘。
     * 做成实例参数后，测的是<b>同一段代码、同一个判定</b>，只是把阈值调小。
     * 生产默认值仍然是上面的常量。
     */
    private int maxEntries = MAX_ENTRIES;

    /**
     * 解压后总字节数上限（实例可配）。
     *
     * <p>同样是为了能被直接测，理由见 {@link #maxEntries}。
     */
    private long maxTotalBytes = MAX_TOTAL_BYTES;

    /** 用默认校验（真的把模型加载起来）。 */
    public ModelPackageInstaller() {
        this(ModelPackageInstaller::verifyWithModelLoader);
    }

    public ModelPackageInstaller(Validator validator) {
        this(validator, Files::move);
    }

    public ModelPackageInstaller(Validator validator, Mover mover) {
        this.validator = Objects.requireNonNull(validator);
        this.mover = Objects.requireNonNull(mover);
    }

    /**
     * 把条目数上限调小（测试用）。
     *
     * @return this，便于链式调用
     */
    public ModelPackageInstaller limitEntries(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("条目数上限至少是 1");
        }
        this.maxEntries = limit;
        return this;
    }

    /**
     * 把解压总字节上限调小（测试用）。
     *
     * @return this，便于链式调用
     */
    public ModelPackageInstaller limitTotalBytes(long limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("字节上限至少是 1");
        }
        this.maxTotalBytes = limit;
        return this;
    }

    /**
     * 默认校验：走 {@link NerModelPackage#load}。
     *
     * <p>它已经做了该做的全部检查（清单格式版本、必填字段、
     * 逐文件 SHA-256、模型自包含、模型与词表配套、初始化张量在不在），
     * 所以这里<b>不重复实现任何一条</b>——重复的那份迟早会与它分叉。
     */
    public static String verifyWithModelLoader(Path dir) throws Exception {
        try (NerModelPackage pkg = NerModelPackage.load(dir)) {
            return pkg.manifest().id();
        } catch (NerModelPackage.InvalidModelPackage e) {
            throw new IOException("模型包校验不通过：" + e.getMessage(), e);
        } catch (ai.onnxruntime.OrtException e) {
            throw new IOException("模型包加载失败：" + e.getMessage(), e);
        }
    }

    /**
     * 装一个模型包。
     *
     * @param packageFile 模型包压缩文件（zip）
     * @param targetDir   装到哪个目录
     * @throws IOException 包不存在、解压越界、校验不通过、换装失败
     */
    public Result install(Path packageFile, Path targetDir) throws IOException {
        Objects.requireNonNull(packageFile, "packageFile");
        Objects.requireNonNull(targetDir, "targetDir");
        if (!Files.isRegularFile(packageFile)) {
            throw new IOException("模型包文件不存在或不是普通文件：" + packageFile);
        }
        requireZip(packageFile);
        Path target = targetDir.toAbsolutePath().normalize();
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IOException("安装目标已存在，但不是目录：" + target);
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("安装目标没有父目录，装不了：" + target);
        }
        Files.createDirectories(parent);

        // 暂存目录放在目标旁边：最后那一步是「改名」，
        // 同一文件系统上的改名是原子的，跨文件系统的「复制」不是。
        Path staging = parent.resolve(target.getFileName() + ".安装中-" + UUID.randomUUID());
        Path backup = parent.resolve(target.getFileName() + ".旧-" + UUID.randomUUID());
        List<String> warnings = new ArrayList<>();
        try {
            Files.createDirectories(staging);
            List<String> files = extract(packageFile, staging);
            String modelId;
            try {
                modelId = validator.validate(staging);
            } catch (IOException e) {
                // 默认校验器已经把消息写成人话了，原样带走。
                throw e;
            } catch (Exception e) {
                throw new IOException("模型包校验不通过：" + e.getMessage(), e);
            }

            boolean hadOld = Files.exists(target);
            if (hadOld) {
                mover.move(target, backup);
            }
            try {
                mover.move(staging, target);
            } catch (IOException e) {
                // 换装失败要把旧的放回去——用户原来那份是能用的，
                // 装失败不该顺手把它也弄没了。
                if (hadOld) {
                    try {
                        mover.move(backup, target);
                    } catch (IOException restoreFailure) {
                        // 放不回去是**最坏**的一种：用户原来那份模型包现在躺在 backup 里，
                        // 不在原来的位置了。这件事必须让用户知道——
                        // 失败时没有 Result 可言，所以它必须进异常消息（消息会直接显示给用户）。
                        // 不说的话，用户会以为「装失败 = 什么都没变」，
                        // 实际上他原来那份已经不在原位了。
                        throw new IOException(e.getMessage()
                                + "；而且原来那份模型包没能放回原位，它现在在 " + backup
                                + "，请手动把它挪回 " + target, e);
                    }
                }
                throw e;
            }
            if (hadOld && !deleteRecursively(backup)) {
                warnings.add("旧的模型包没能删掉（可能正被别的进程占用）：" + backup);
            }
            return new Result(modelId, target, files, warnings);
        } finally {
            // 换装成功后这里是空操作；失败时它把暂存目录清掉，
            // 不留半个模型在盘上。
            deleteRecursively(staging);
        }
    }

    /**
     * 看一眼文件头是不是 zip。
     *
     * <p><b>为什么要有这一步</b>：用户第一次用多半会传错文件
     * （把 {@code .onnx}、{@code .txt} 或者下载下来的网页当成模型包）。
     * 不查的话，错误消息是「模型包是空的（里面一个文件都没有）」——
     * 听着像**包**的问题，其实是**文件根本不是 zip**。方向指错了，用户会一直找错原因。
     *
     * <p>只查前两个字节 {@code PK}：那是所有 zip 变体（普通 / 空 / 分卷）的共同签名。
     * 查得更细（区分本地文件头与中央目录结束记录）反而会在合法的空包上误报。
     */
    private static void requireZip(Path file) throws IOException {
        byte[] head = new byte[2];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.read(head);
        }
        if (n < 2 || head[0] != 'P' || head[1] != 'K') {
            throw new IOException("这不像是一个 zip 文件（模型包应是 .zip）：" + file);
        }
    }

    /**
     * 解压。
     *
     * <p><b>按不受信任的输入处理</b>：
     * <ul>
     *   <li>条目路径必须落在目标目录内（否则 {@code ../../} 能写到外面去）；</li>
     *   <li>路径里带反斜杠的一律拒绝（那是 Windows 风格的穿越，
     *       在 Unix 上是合法文件名、在 Windows 上是穿越——两种解释都得堵）；</li>
     *   <li>条目数与总字节数有上限（防 zip 炸弹）。</li>
     * </ul>
     */
    private List<String> extract(Path zipFile, Path dest) throws IOException {
        return extractArchive(zipFile, dest, maxEntries, maxTotalBytes);
    }

    /**
     * 把一个<b>不受信任的</b> zip 解到指定目录。
     *
     * <p><b>为什么单独暴露成 public static</b>：离线模型包是「一个 zip 里面装着
     * 一个 zip 加上它的签名」。外层那个 zip 同样来自用户选的文件，
     * 于是它同样要受路径穿越与 zip 炸弹的防护。
     * 而这些防护规则<b>只该有一份</b>——写第二份的话，迟早有一份忘了防。
     *
     * <p>本方法<b>只做</b>：路径安全、体积上限、条目数上限。
     * <b>不</b>做模型包校验——那由调用方按需决定。
     *
     * @param zipFile       zip 文件
     * @param dest          解到哪个目录（会被创建）
     * @param maxEntries    条目数上限
     * @param maxTotalBytes 解压后总字节上限
     * @return 解出来的条目名（相对目录）
     * @throws IOException 越界、超限、不是合法 zip
     */
    public static List<String> extractArchive(Path zipFile, Path dest,
                                               int maxEntries, long maxTotalBytes) throws IOException {
        List<String> names = new ArrayList<>();
        // 条目数要**把目录也算进去**：一个几万个空目录的包能白白占掉磁盘 inode，
        // 而它一个字节都不解压。只数文件的话，这个洞是开的。
        int entryCount = 0;
        long total = 0;
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.isBlank()) {
                    continue;
                }
                entryCount++;
                if (entryCount > maxEntries) {
                    throw new IOException("模型包里的条目超过 " + maxEntries + " 个，不像是正常的模型包");
                }
                if (name.indexOf('\\') >= 0) {
                    throw new IOException("模型包里有带反斜杠的路径条目：" + name);
                }
                Path out;
                try {
                    out = root.resolve(name).normalize();
                } catch (InvalidPathException e) {
                    // 条目名里带 NUL 之类的非法字符时，Path 会抛**非受检**异常，
                    // 那会绕过 install 的 IOException 契约，一路冒到最外层变成一句
                    // JVM 的原始报错。这里换成人能看懂的话。
                    throw new IOException("模型包里有非法的条目名（含有不能出现在路径里的字符）：" + name, e);
                }
                if (!out.startsWith(root)) {
                    throw new IOException("模型包里有越出安装目录的路径条目：" + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                // 字节上限要**边写边判**：写完再判的话，
                // 一个几百 GB 的条目会先把磁盘写满才报错——那时上限已经没意义了。
                try (BoundedOutputStream os = new BoundedOutputStream(
                        Files.newOutputStream(out), maxTotalBytes, total)) {
                    in.transferTo(os);
                }
                total += Files.size(out);
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            throw new IOException("模型包是空的（里面一个文件都没有）");
        }
        return names;
    }

    /**
     * 写多少都不许超过限额的输出流。
     *
     * <p><b>为什么要专门一个类</b>：超限必须发生在<b>写出去之前</b>。
     * 写成「先写、事后看文件多大」的话，超限的那一刻数据已经落盘了——
     * 上限就成了事后通知，不是防线。
     */
    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long limit;
        private final long alreadyWritten;
        private long written;

        BoundedOutputStream(OutputStream out, long limit, long alreadyWritten) {
            super(out);
            this.limit = limit;
            this.alreadyWritten = alreadyWritten;
        }

        @Override
        public void write(int b) throws IOException {
            check(1);
            out.write(b);
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(len);
            out.write(b, off, len);
            written += len;
        }

        private void check(int len) throws IOException {
            if (alreadyWritten + written + len > limit) {
                // 报的是**总上限**，不是「还剩多少」——读的人要知道自己撞的是哪条线。
                // 上限小于 1MB 时报字节数：报「超过 0MB 上限」读起来像坏了。
                String cap = limit >= 1024 * 1024
                        ? (limit / 1024 / 1024) + "MB"
                        : (limit + " 字节");
                throw new IOException("模型包解压后超过 " + cap + " 上限，不装");
            }
        }
    }

    /**
     * 整棵删掉一个目录。
     *
     * <p><b>为什么是 public</b>：{@link ModelPackageStore} 也要删目录
     * （删一个历史版本、清理暂存），两份实现迟早会分叉——
     * 一份会漏掉「符号链接」的处理，另一份不会。所以只有一份。
     *
     * @return true 表示确实删干净了；false 表示有东西删不掉（通常是别的进程正占着）
     */
    public static boolean deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return true;
        }
        final boolean[] ok = {true};
        try {
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
                    ok[0] = false;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return false;
        }
        return ok[0];
    }
}
