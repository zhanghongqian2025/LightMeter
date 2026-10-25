package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ModelPackageInstaller}：把一个模型包压缩文件装到指定目录。
 *
 * <p><b>这些测试的价值主要在负向用例上。</b>安装器干的事是
 * 「把一份不受信任的压缩文件铺到磁盘上，然后让它生效」，
 * 而正向用例只要压缩文件正常就必然过，几乎测不出什么。
 * 真正会出事的是两类：
 * <ul>
 *   <li><b>写到不该写的地方</b>（zip 穿越）——那是往用户机器上乱放文件；</li>
 *   <li><b>装失败时把原来那份弄没了</b>——用户原来那份是能用的，
 *       装失败顺手毁掉它，等于「想升级反而降级」。</li>
 * </ul>
 *
 * <p><b>为什么用假校验器</b>：默认校验器要真的把 ONNX 模型加载起来，
 * 而真模型 14MB、仓库里也没有——单元测试没法自带一份。
 * 假校验器造出来的东西是假的，但<b>被检验的那段逻辑</b>
 * （解压、越界防护、换装、失败回退）是真的。
 * 默认校验器那条真路径由 {@link #installsRealToyPackageWithDefaultValidator()}
 * 用玩具模型包单独覆盖，不靠这条缝蒙混过去。
 */
class ModelPackageInstallerTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ 正向

    @Test
    @DisplayName("装得上：文件落地、报得出模型 id、列得出装了哪些文件")
    void installsPackage() throws Exception {
        Path zip = zipOf("manifest.json", bytes("{}"), "ner.onnx", bytes("model"),
                "vocab.txt", bytes("vocab"));
        Path target = tmp.resolve("目标");

        ModelPackageInstaller.Result r =
                new ModelPackageInstaller(new FakeValidator("模型-x")).install(zip, target);

        assertEquals("模型-x", r.modelId(), "报出的模型 id 应来自校验器的返回值");
        assertEquals(target.toRealPath(), r.directory().toRealPath(),
                "装好的目录应是安装目标本身");
        assertEquals(List.of("manifest.json", "ner.onnx", "vocab.txt"), r.files(),
                "文件列表应按解压顺序（清单点名的顺序）报出，且不多不少");
        assertTrue(r.warnings().isEmpty(), "顺利装完不该有提醒，实际: " + r.warnings());

        assertEquals("model", read(target.resolve("ner.onnx")), "装好的文件内容应与包里一致");
        assertEquals("vocab", read(target.resolve("vocab.txt")), "装好的文件内容应与包里一致");
        assertTrue(Files.isRegularFile(target.resolve("manifest.json")), "清单也要装进去");
    }

    @Test
    @DisplayName("装到已有目录上：旧的被换掉，且不留暂存/备份残留")
    void replacesExistingInstall() throws Exception {
        Path target = tmp.resolve("目标");
        Files.createDirectories(target);
        Files.writeString(target.resolve("old.txt"), "旧版本");

        Path zip = zipOf("new.txt", bytes("新版本"));
        new ModelPackageInstaller(new FakeValidator("模型-y")).install(zip, target);

        assertTrue(Files.exists(target.resolve("new.txt")), "新文件应装好");
        assertFalse(Files.exists(target.resolve("old.txt")),
                "旧文件应被换掉（这是「换装」，不是「合并」）");
        assertEquals("新版本", read(target.resolve("new.txt")), "装上的应是新内容");
        assertNoResidue(target, "换装后不应留下暂存目录或备份目录");
    }

    @Test
    @DisplayName("真玩具模型包 + 默认校验器：这条生产路径本身是通的")
    void installsRealToyPackageWithDefaultValidator() throws Exception {
        // 用真的 NerModelPackage.load 当校验器 —— 不走那条缝。
        // 玩具包由 NerModelFixtures 生成，期望值不依赖任何机器学习框架。
        Path pkgDir = NerModelFixtures.writePackage(tmp.resolve("玩具包"));
        Path zip = zipDirectory(pkgDir, "manifest.json", "ner.onnx", "vocab.txt");
        Path target = tmp.resolve("目标");

        ModelPackageInstaller.Result r = new ModelPackageInstaller().install(zip, target);

        assertEquals("test-fixture", r.modelId(), "默认校验器应报出清单里的 id");
        assertTrue(Files.isRegularFile(target.resolve("ner.onnx")), "模型应装进去");
        assertTrue(Files.isRegularFile(target.resolve("vocab.txt")), "词表应装进去");
        assertNoResidue(target, "装完不应有残留");
    }

    // ------------------------------------------------------- 校验失败不许动手

    @Test
    @DisplayName("校验不通过：装不上，而且原来那份原封不动")
    void validationFailureKeepsPreviousInstall() throws Exception {
        Path target = tmp.resolve("目标");
        Files.createDirectories(target);
        Files.writeString(target.resolve("old.txt"), "旧版本");

        Path zip = zipOf("ner.onnx", bytes("model"));
        FakeValidator v = new FakeValidator(null);
        v.failWith = "这份包不合法：测试用的理由";

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(v).install(zip, target),
                "校验不通过时必须抛错，不能当装好了");

        assertTrue(e.getMessage().contains("测试用的理由"),
                "错误消息应带上校验器自己说的原因，实际: " + e.getMessage());
        assertEquals(1, v.seen.size(),
                "校验器必须被真的调用过 —— 否则这次失败可能是解压阶段失败，"
                        + "测的就不是「校验不过不许装」这条了");
        assertTrue(Files.exists(target.resolve("old.txt")),
                "**原来那份必须在** —— 用户原来那份是能用的，装失败不该毁掉它");
        assertEquals("旧版本", read(target.resolve("old.txt")),
                "原来那份的内容不该被改");
        assertFalse(Files.exists(target.resolve("ner.onnx")),
                "没通过校验的包不该被装进去");
        assertNoResidue(target, "装失败后不应留下暂存目录或备份目录");
    }

    @Test
    @DisplayName("真玩具包但摘要对不上：默认校验器必须拒")
    void rejectsRealPackageWithWrongDigest() throws Exception {
        Path pkgDir = NerModelFixtures.writePackage(tmp.resolve("玩具包"));
        // 改模型的一个字节：清单里的 sha256 就对不上了。
        Path zip = zipDirectoryWithCorruptedModel(pkgDir);
        Path target = tmp.resolve("目标");

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller().install(zip, target),
                "摘要对不上的包必须被拒");
        assertTrue(e.getMessage().contains("摘要"),
                "错误消息应指出是摘要对不上，实际: " + e.getMessage());
        assertFalse(Files.exists(target.resolve("ner.onnx")), "被拒的包不该留下文件");
        assertNoResidue(target, "被拒后不应有残留");
    }

    // ------------------------------------------------------- 换装失败不许丢东西

    @Test
    @DisplayName("换装失败时原来那份会被放回去（装失败不能等于降级）")
    void restoresPreviousInstallWhenSwapFails() throws Exception {
        Path target = tmp.resolve("目标");
        Files.createDirectories(target);
        Files.writeString(target.resolve("old.txt"), "旧版本");

        Path zip = zipOf("new.txt", bytes("新版本"));
        // 换装那一步（暂存目录 → 目标）失败，其它步子照常。
        ModelPackageInstaller.Mover failing = (from, to) -> {
            if (from.getFileName().toString().contains("安装中-")) {
                throw new IOException("模拟换装失败");
            }
            Files.move(from, to);
        };

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x"), failing)
                        .install(zip, target),
                "换装失败必须抛错");
        assertTrue(e.getMessage().contains("模拟换装失败"),
                "错误消息应说明换装失败，实际: " + e.getMessage());
        assertTrue(Files.exists(target.resolve("old.txt")),
                "**原来那份必须被放回原位** —— 装失败不该让用户手里的模型包消失");
        assertEquals("旧版本", read(target.resolve("old.txt")), "放回去的内容不该被改");
        assertNoResidue(target, "换装失败后不应留下暂存目录或备份目录");
    }

    @Test
    @DisplayName("旧的也放不回去时必须说出来（不能默默吞掉）")
    void reportsWhenRestoreAlsoFails() throws Exception {
        Path target = tmp.resolve("目标");
        Files.createDirectories(target);
        Files.writeString(target.resolve("old.txt"), "旧版本");

        Path zip = zipOf("new.txt", bytes("新版本"));
        // 换装失败，且**放回去**也失败——这是最坏的一种：用户原来那份躺在 backup 里。
        ModelPackageInstaller.Mover failing = (from, to) -> {
            String n = from.getFileName().toString();
            if (n.contains("安装中-") || n.contains(".旧-")) {
                throw new IOException("模拟搬不动");
            }
            Files.move(from, to);
        };

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x"), failing)
                        .install(zip, target),
                "换装失败必须抛错");
        // 关键：用户原来那份不在原位了，这件事必须出现在消息里。
        // 失败时没有 Result 可言（Result 只在成功时返回），所以消息是唯一的出口。
        assertTrue(e.getMessage().contains("没能放回原位"),
                "错误消息必须说明原来那份没被放回去，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("旧-"),
                "错误消息必须给出旧目录所在的位置（用户要能自己把它挪回去），实际: " + e.getMessage());
    }

    // ------------------------------------------------------------ 越界与上限

    @Test
    @DisplayName("越界条目（../）被拒，且文件不会写到目标外面")
    void rejectsEntryEscapingTargetDirectory() throws Exception {
        Path zip = zipOf("../evil.txt", bytes("逃出去"));
        Path target = tmp.resolve("目标");

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x")).install(zip, target),
                "越界条目必须被拒");
        assertTrue(e.getMessage().contains("越出"),
                "错误消息应说明是越界条目，实际: " + e.getMessage());
        assertFalse(Files.exists(tmp.resolve("evil.txt")),
                "**文件不能写到目标目录外面去** —— 这是整个越界防护的意义所在");
        assertFalse(Files.exists(target), "被拒后不该留下安装目录");
        assertNoResidue(target, "被拒后不应有残留");
    }

    @Test
    @DisplayName("绝对路径条目被拒")
    void rejectsAbsolutePathEntry() throws Exception {
        Path outside = tmp.resolve("abs-evil.txt");
        Path zip = zipOf(outside.toAbsolutePath().toString().replace('\\', '/'), bytes("逃出去"));
        Path target = tmp.resolve("目标");

        assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x")).install(zip, target),
                "绝对路径条目必须被拒");
        assertFalse(Files.exists(outside), "绝对路径条目不能被写出去");
    }

    @Test
    @DisplayName("反斜杠条目被拒（那是 Windows 风格的穿越）")
    void rejectsBackslashEntry() throws Exception {
        Path zip = zipOf("..\\evil.txt", bytes("逃出去"));
        Path target = tmp.resolve("目标");

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x")).install(zip, target),
                "带反斜杠的条目必须被拒");
        assertTrue(e.getMessage().contains("反斜杠"),
                "错误消息应说明是反斜杠条目，实际: " + e.getMessage());
        assertFalse(Files.exists(tmp.resolve("evil.txt")), "文件不能被写到外面去");
    }

    @Test
    @DisplayName("空包被拒")
    void rejectsEmptyPackage() throws Exception {
        Path zip = zipOf();  // 一个条目都没有
        Path target = tmp.resolve("目标");

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x")).install(zip, target),
                "空包必须被拒");
        assertTrue(e.getMessage().contains("空"),
                "错误消息应说明是空包，实际: " + e.getMessage());
        assertNoResidue(target, "被拒后不应有残留");
    }

    @Test
    @DisplayName("包文件不存在时报错说清是什么")
    void rejectsMissingPackageFile() throws Exception {
        Path missing = tmp.resolve("根本没有这个.zip");
        Path target = tmp.resolve("目标");

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x")).install(missing, target),
                "包文件不存在必须报错");
        assertTrue(e.getMessage().contains("不存在"),
                "错误消息应说明文件不存在，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("条目数超上限被拒")
    void rejectsTooManyEntries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            entries.put("f" + i + ".txt", bytes("x"));
        }
        Path zip = zipEntries(entries);
        Path target = tmp.resolve("目标");

        // 上限调小到 3：测的是同一段代码、同一个判定，只是把阈值调小。
        // 真造 65 个条目也能测，但那样这条测试就只测了「数字大小」。
        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x"))
                        .limitEntries(3)
                        .install(zip, target),
                "条目数超上限必须被拒");
        assertTrue(e.getMessage().contains("超过"),
                "错误消息应说明是条目数超限，实际: " + e.getMessage());
        assertNoResidue(target, "被拒后不应有残留");
    }

    @Test
    @DisplayName("目录条目也算进条目数上限（否则几万个空目录就能占满 inode）")
    void countsDirectoryEntriesTowardsLimit() throws Exception {
        // 一个只有目录、没有文件的包：正常情况下它会被「空包」那条拒。
        // 但如果目录不计数，几万个空目录就绕过了条目数上限。
        Path zip = zipWithDirectories(70);
        Path target = tmp.resolve("目标");

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x"))
                        .limitEntries(10)
                        .install(zip, target),
                "目录条目也必须计入上限");
        assertTrue(e.getMessage().contains("超过"),
                "错误消息应说明是条目数超限（而不是「空包」），实际: " + e.getMessage());
    }

    @Test
    @DisplayName("解压后字节数超上限被拒")
    void rejectsPackageExceedingByteLimit() throws Exception {
        Path zip = zipOf("big.txt", bytes("0123456789".repeat(100)));
        Path target = tmp.resolve("目标");

        // 上限 50 字节，包解开是 1000 字节。
        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x"))
                        .limitTotalBytes(50)
                        .install(zip, target),
                "字节数超上限必须被拒");
        assertTrue(e.getMessage().contains("上限"),
                "错误消息应说明是字节上限，实际: " + e.getMessage());
        assertNoResidue(target, "被拒后不应有残留");
    }

    @Test
    @DisplayName("不是 zip 的文件被拒，且说的是「不是 zip」而不是「包是空的」")
    void rejectsNonZipFile() throws Exception {
        Path notZip = tmp.resolve("模型包.txt");
        Files.writeString(notZip, "这根本不是 zip");
        Path target = tmp.resolve("目标");

        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x")).install(notZip, target),
                "不是 zip 的文件必须被拒");
        // 消息的方向很重要：说「不是 zip」，用户才知道自己传错了文件；
        // 说「包是空的」，用户会以为包坏了，去查包。
        assertTrue(e.getMessage().contains("zip"),
                "错误消息应说明这不是 zip，实际: " + e.getMessage());
        assertNoResidue(target, "被拒后不应有残留");
    }

    @Test
    @DisplayName("条目名带非法字符（NUL）时报人话，而不是抛 JVM 的原始异常")
    void rejectsEntryNameWithIllegalCharacters() throws Exception {
        Path zip = zipWithNulEntryName();
        Path target = tmp.resolve("目标");

        // assertThrows(IOException.class, ...) 本身就是那道守卫：
        // Path.resolve 遇到 NUL 会抛 InvalidPathException（非受检），
        // 它不是 IOException 的子类 —— 断言会直接失败，而不是「碰巧通过」。
        IOException e = assertThrows(IOException.class,
                () -> new ModelPackageInstaller(new FakeValidator("模型-x")).install(zip, target),
                "非法条目名必须被拒，而且要以 IOException 的形式");
        assertTrue(e.getMessage().contains("非法的条目名"),
                "错误消息应说明是条目名非法，实际: " + e.getMessage());
        assertNoResidue(target, "被拒后不应有残留");
    }

    // ------------------------------------------------------------------ 辅助

    /** 假校验器：只回报 id 或按要求失败，并把被校验的目录记下来。 */
    private static final class FakeValidator implements ModelPackageInstaller.Validator {
        private final String id;
        String failWith;
        final List<Path> seen = new ArrayList<>();

        FakeValidator(String id) {
            this.id = id;
        }

        @Override
        public String validate(Path dir) throws Exception {
            seen.add(dir);
            if (failWith != null) {
                throw new IOException(failWith);
            }
            return id;
        }
    }

    // 下面这几个「造 zip」的辅助都转交给 NerModelFixtures：
    // 「怎么把模型包打成 zip」是一个事实，两处各写一份迟早会分叉。
    private static byte[] bytes(String s) {
        return NerModelFixtures.bytes(s);
    }

    private static String read(Path p) throws IOException {
        return NerModelFixtures.read(p);
    }

    /** 断言安装目标的旁边没有留下暂存目录或备份目录。 */
    private static void assertNoResidue(Path target, String message) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        String base = target.getFileName().toString();
        try (Stream<Path> kids = Files.list(parent)) {
            List<String> residue = kids.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(base + ".安装中-") || n.startsWith(base + ".旧-"))
                    .sorted()
                    .toList();
            assertTrue(residue.isEmpty(), message + "，实际残留: " + residue);
        }
    }

    /**
     * 造一个 zip：参数是「条目名, 内容」成对给出的。
     *
     * <p>内容可以是 String 或 byte[]——两种都要用（越界用例要写二进制内容，
     * 正常用例写文本更顺手）。
     */
    private static Path zipOf(Object... nameThenContent) throws IOException {
        return NerModelFixtures.zipOf(nameThenContent);
    }

    private static Path zipEntries(Map<String, byte[]> entries) throws IOException {
        return NerModelFixtures.zipEntries(entries);
    }

    /** 造一个只有目录条目的 zip。 */    private static Path zipWithDirectories(int count) throws IOException {
        Path zip = Files.createTempFile("模型包", ".zip");
        try (OutputStream os = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(os)) {
            for (int i = 0; i < count; i++) {
                zos.putNextEntry(new ZipEntry("d" + i + "/"));
                zos.closeEntry();
            }
        }
        return zip;
    }

    /** 造一个条目名里带 NUL 的 zip（NUL 不能出现在路径里）。 */
    private static Path zipWithNulEntryName() throws IOException {
        Path zip = Files.createTempFile("模型包", ".zip");
        try (OutputStream os = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("bad\u0000name.txt"));
            zos.write(bytes("x"));
            zos.closeEntry();
        }
        return zip;
    }

    /** 把一个目录里的若干文件打成 zip。 */
    private static Path zipDirectory(Path dir, String... names) throws IOException {
        return NerModelFixtures.zipDirectory(dir, names);
    }

    /** 同上，但把模型文件的中间一个字节翻掉（清单里的摘要就对不上了）。 */
    private static Path zipDirectoryWithCorruptedModel(Path dir) throws IOException {
        return NerModelFixtures.zipDirectoryWithCorruptedModel(dir);
    }

    /** 让编译期就知道 {@link ModelPackageInstaller} 的公开形状没变。 */
    @Test
    @DisplayName("安装器的公开常量与签名没变（挡住无意中的 API 漂移）")
    void publicApiIsStable() {
        assertNotNull(ModelPackageInstaller.MAX_ENTRIES);
        assertEquals(64, ModelPackageInstaller.MAX_ENTRIES, "条目数上限");
        assertEquals(512L * 1024 * 1024, ModelPackageInstaller.MAX_TOTAL_BYTES, "字节上限");
    }
}
