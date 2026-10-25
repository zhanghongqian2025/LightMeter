package com.litemask.cli;

import com.litemask.core.SecretMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 映射件加密封装的**命令行层**测试。
 *
 * <p>为什么已经有了 {@code SecretMapTest} 还要这一层：
 * 单元测试证的是「封装格式本身对不对」，这一层证的是
 * <b>「命令真的按承诺行事」</b>——
 * <ul>
 *   <li>给了口令 → 落盘的文件里<b>真的没有原值</b>（不是「调用了 seal 就算数」）；</li>
 *   <li>不给口令 → 行为与从前一致（明文），但权限收紧了；</li>
 *   <li>还原时对不上 → <b>明确失败</b>，而不是「静默什么都不替换」。</li>
 * </ul>
 * 最后一条是这一层最要紧的：封装件没有 {@code mapping} 字段，
 * 当普通映射读会得到一个**空映射** → 还原「成功」但文件根本没变，
 * 而用户看不出任何异常。
 */
class MapSealCliTest {

    @TempDir
    Path tmp;

    /** 样例里放一个会被命中的手机号。 */
    private static final String ORIGINAL = "联系人张三，手机 13800138000，邮箱 a.b@example.com。\n";
    private static final String PHONE = "13800138000";

    private static Path writeSample(Path dir) throws Exception {
        Path in = dir.resolve("样本.txt");
        Files.writeString(in, ORIGINAL, StandardCharsets.UTF_8);
        return in;
    }

    private static Path writePassphrase(Path dir, String name, String value) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, value, StandardCharsets.UTF_8);
        return f;
    }

    /** 跑一条命令，把 stdout/stderr 收下来（不刷屏，也能断言话术）。 */
    private static int run(java.util.concurrent.Callable<Integer> cmd, ByteArrayOutputStream out,
                           ByteArrayOutputStream err) throws Exception {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return cmd.call();
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private static Set<PosixFilePermission> perms(Path p) throws Exception {
        return Files.getPosixFilePermissions(p);
    }

    @Test
    @DisplayName("给了口令：映射件里没有原值，且是封装格式")
    void sealedMapHasNoPlaintext() throws Exception {
        Path in = writeSample(tmp);
        Path pw = writePassphrase(tmp, "pw.txt", "我的口令");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int rc = run(() -> Main.redact(new String[]{
                in.toString(), "--map-pass-file", pw.toString()}), out, err);
        assertEquals(0, rc, "redact 失败：" + err.toString(StandardCharsets.UTF_8));

        Path map = Path.of(in + ".redacted.map.json");
        assertTrue(Files.isRegularFile(map), "映射件没产出");
        String text = Files.readString(map, StandardCharsets.UTF_8);
        assertFalse(text.contains(PHONE), "映射件里出现了原值");
        assertTrue(SecretMap.isSealed(text.getBytes(StandardCharsets.UTF_8)), "映射件不是封装格式");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("已用口令封装"),
                "命令行应当告诉用户映射件是封装过的");
    }

    @Test
    @DisplayName("给了口令：同一个口令能还原，逐字一致")
    void restoreWithSamePassphrase() throws Exception {
        Path in = writeSample(tmp);
        Path pw = writePassphrase(tmp, "pw.txt", "我的口令");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, run(() -> Main.redact(new String[]{
                in.toString(), "--map-pass-file", pw.toString()}), out, err));

        Path redacted = Path.of(in.toString().replace(".txt", ".redacted.txt"));
        assertTrue(Files.isRegularFile(redacted), "脱敏件没产出");
        String redactedText = Files.readString(redacted, StandardCharsets.UTF_8);
        assertFalse(redactedText.contains(PHONE), "脱敏件里还有原值");

        out.reset();
        err.reset();
        int rc = run(() -> Main.restore(new String[]{
                redacted.toString(),
                "--map", in + ".redacted.map.json",
                "--map-pass-file", pw.toString()}), out, err);
        assertEquals(0, rc, "还原失败：" + err.toString(StandardCharsets.UTF_8));

        Path restored = Path.of(redacted.toString().replace(".txt", ".restored.txt"));
        assertEquals(ORIGINAL, Files.readString(restored, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("不给口令去还原一个封装件：明确失败，不许「静默什么都不替换」")
    void restoreSealedWithoutPassphraseFails() throws Exception {
        Path in = writeSample(tmp);
        Path pw = writePassphrase(tmp, "pw.txt", "我的口令");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, run(() -> Main.redact(new String[]{
                in.toString(), "--map-pass-file", pw.toString()}), out, err));

        Path redacted = Path.of(in.toString().replace(".txt", ".redacted.txt"));
        err.reset();
        int rc = run(() -> Main.restore(new String[]{
                redacted.toString(), "--map", in + ".redacted.map.json"}), out, err);
        assertNotEquals(0, rc, "没给口令却「还原成功」了");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("需要口令"),
                "错误信息应当说清要口令：" + err.toString(StandardCharsets.UTF_8));
        // 最要紧的一条：还原件不能被产出成「没还原」的样子还报成功。
        Path restored = Path.of(redacted.toString().replace(".txt", ".restored.txt"));
        assertFalse(Files.exists(restored), "失败却产出了还原件");
    }

    @Test
    @DisplayName("口令不对：还原失败，且错误信息不出现原值")
    void restoreWithWrongPassphraseFails() throws Exception {
        Path in = writeSample(tmp);
        Path pw = writePassphrase(tmp, "pw.txt", "我的口令");
        Path wrong = writePassphrase(tmp, "wrong.txt", "别的口令");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, run(() -> Main.redact(new String[]{
                in.toString(), "--map-pass-file", pw.toString()}), out, err));

        Path redacted = Path.of(in.toString().replace(".txt", ".redacted.txt"));
        err.reset();
        int rc = run(() -> Main.restore(new String[]{
                redacted.toString(),
                "--map", in + ".redacted.map.json",
                "--map-pass-file", wrong.toString()}), out, err);
        assertNotEquals(0, rc, "口令不对却「还原成功」了");
        String msg = err.toString(StandardCharsets.UTF_8);
        assertFalse(msg.contains(PHONE), "错误信息里出现了原值");
        assertTrue(msg.contains("口令不对"), "错误信息应当说口令不对：" + msg);
    }

    @Test
    @DisplayName("映射件绑定脱敏件：拿 v1 映射还原 v2 必须在写输出前拒绝")
    void mismatchedDocumentAndMapFailsClosed() throws Exception {
        Path v1 = Files.createDirectories(tmp.resolve("v1"));
        Path v2 = Files.createDirectories(tmp.resolve("v2"));
        Path one = v1.resolve("样本.txt");
        Path two = v2.resolve("样本.txt");
        // 两个版本保留不同的非敏感正文，确保它们是可区分的脱敏文件；若脱敏后
        // 字节逐一相同，就不存在可供还原端判断“来自哪一次脱敏”的文件证据。
        Files.writeString(one, "版本一：手机 13800138000。\n", StandardCharsets.UTF_8);
        Files.writeString(two, "版本二：手机 13900139000。\n", StandardCharsets.UTF_8);
        Path pw = writePassphrase(tmp, "pw.txt", "我的口令");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertEquals(0, run(() -> Main.redact(new String[]{
                one.toString(), "--map-pass-file", pw.toString()}), out, err));
        out.reset();
        err.reset();
        assertEquals(0, run(() -> Main.redact(new String[]{
                two.toString(), "--map-pass-file", pw.toString()}), out, err));

        Path redactedV2 = v2.resolve("样本.redacted.txt");
        Path mapV1 = Path.of(one + ".redacted.map.json");
        out.reset();
        err.reset();
        int rc = run(() -> Main.restore(new String[]{
                redactedV2.toString(), "--map", mapV1.toString(),
                "--map-pass-file", pw.toString()}), out, err);

        assertEquals(1, rc, "错配映射必须拒绝");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("不属于这份脱敏件"),
                "应当说清映射与文档不配对：" + err.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(v2.resolve("样本.redacted.restored.txt")),
                "校验失败前不能写出一份含错误原值的还原件");
    }

    @Test
    @DisplayName("脱敏件被改一个字节：绑定摘要必须在写输出前拒绝")
    void changedRedactedDocumentFailsClosed() throws Exception {
        Path in = writeSample(tmp);
        Path pw = writePassphrase(tmp, "pw.txt", "我的口令");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, run(() -> Main.redact(new String[]{
                in.toString(), "--map-pass-file", pw.toString()}), out, err));

        Path redacted = tmp.resolve("样本.redacted.txt");
        Files.writeString(redacted,
                Files.readString(redacted, StandardCharsets.UTF_8) + "被改动\n",
                StandardCharsets.UTF_8);
        out.reset();
        err.reset();
        int rc = run(() -> Main.restore(new String[]{
                redacted.toString(), "--map", in + ".redacted.map.json",
                "--map-pass-file", pw.toString()}), out, err);

        assertEquals(1, rc);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("不属于这份脱敏件"));
        assertFalse(Files.exists(tmp.resolve("样本.redacted.restored.txt")));
    }

    @Test
    @DisplayName("旧版无摘要映射仍可还原，但必须明确警告无法核对配对关系")
    void legacyUnboundMapRestoresWithWarning() throws Exception {
        Path redacted = tmp.resolve("旧件.redacted.txt");
        Path legacyMap = tmp.resolve("旧件.map.json");
        Files.writeString(redacted, "手机 <手机号1>。\n", StandardCharsets.UTF_8);
        Files.writeString(legacyMap,
                "{\"mapping\":{\"<手机号1>\":\"13800138000\"},\"changes\":[]}",
                StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int rc = run(() -> Main.restore(new String[]{
                redacted.toString(), "--map", legacyMap.toString()}), out, err);

        assertEquals(0, rc);
        assertEquals("手机 13800138000。\n",
                Files.readString(tmp.resolve("旧件.redacted.restored.txt"), StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("旧版映射件"),
                "必须提醒旧映射无法核对配对关系：" + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("给了口令但映射件是明文：明确报错（别让用户以为加密了）")
    void plaintextMapWithPassphraseFails() throws Exception {
        Path in = writeSample(tmp);
        Path pw = writePassphrase(tmp, "pw.txt", "我的口令");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // 不给口令 → 明文映射件
        assertEquals(0, run(() -> Main.redact(new String[]{in.toString()}), out, err));

        Path redacted = Path.of(in.toString().replace(".txt", ".redacted.txt"));
        err.reset();
        int rc = run(() -> Main.restore(new String[]{
                redacted.toString(),
                "--map", in + ".redacted.map.json",
                "--map-pass-file", pw.toString()}), out, err);
        assertNotEquals(0, rc, "给明文映射件配口令却「成功」了");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("不是加密封装"),
                "错误信息应当说清这个映射件没加密：" + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("不给口令：行为与从前一致（明文映射），但权限收紧到 0600")
    void plaintextMapStaysCompatibleButPrivate() throws Exception {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"), "本机不支持 POSIX 权限");
        Path in = writeSample(tmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, run(() -> Main.redact(new String[]{in.toString()}), out, err));

        Path map = Path.of(in + ".redacted.map.json");
        String text = Files.readString(map, StandardCharsets.UTF_8);
        assertTrue(text.contains(PHONE), "明文映射件里应当仍然有原值（向后兼容）");
        assertFalse(SecretMap.isSealed(text.getBytes(StandardCharsets.UTF_8)), "明文映射件不该是封装件");
        // 【2026-10-25】原来这里写死断言 POSIX 的 0600，
        //   在 Windows 上 Files.getPosixFilePermissions 直接抛异常 → 这条测试整类红。
        //   而「映射件只给属主可读」这个承诺在 Windows 上是走 ACL 的 ——
        //   断言要跟平台走，但**断言本身一条都没少**（见 OwnerOnlyAssert）。
        com.litemask.core.OwnerOnlyAssert.assertOwnerOnlyFile(map);
    }

    @Test
    @DisplayName("明文映射件照旧能还原（向后兼容没有被这一块破坏）")
    void plaintextMapStillRestores() throws Exception {
        Path in = writeSample(tmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, run(() -> Main.redact(new String[]{in.toString()}), out, err));

        Path redacted = Path.of(in.toString().replace(".txt", ".redacted.txt"));
        out.reset();
        err.reset();
        int rc = run(() -> Main.restore(new String[]{
                redacted.toString(), "--map", in + ".redacted.map.json"}), out, err);
        assertEquals(0, rc, "明文映射件还原失败：" + err.toString(StandardCharsets.UTF_8));
        Path restored = Path.of(redacted.toString().replace(".txt", ".restored.txt"));
        assertEquals(ORIGINAL, Files.readString(restored, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("口令来源只能给一个")
    void onlyOnePassphraseSourceAllowed() throws Exception {
        Path in = writeSample(tmp);
        Path pw = writePassphrase(tmp, "pw.txt", "pw");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = run(() -> Main.redact(new String[]{
                in.toString(), "--map-pass-file", pw.toString(), "--map-pass-stdin"}), out, err);
        assertEquals(2, rc, "两个口令来源应当被拒（退出码 2）");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("只能给一个"),
                "错误信息应当说清：" + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("口令来源取不到内容：失败，不许「当作没有口令」继续跑")
    void missingPassphraseFailsClosed() throws Exception {
        Path in = writeSample(tmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = run(() -> Main.redact(new String[]{
                in.toString(), "--map-pass-file", tmp.resolve("没有这个文件").toString()}), out, err);
        assertEquals(2, rc, "取不到口令应当失败（退出码 2）");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("取不到"),
                "错误信息应当说清：" + err.toString(StandardCharsets.UTF_8));
        // 关键：不能产出一个「没加密」的映射件。
        assertFalse(Files.exists(Path.of(in + ".redacted.map.json")), "取不到口令却还是写了映射件");
    }
}
