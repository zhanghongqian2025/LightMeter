package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 「只给自己看」的文件落盘。
 *
 * <p><b>为什么这条要有测试</b>：映射件是本工具唯一含原值的产物。
 * 权限是 0644 还是 0600，在**任何功能测试里都看不出来**——
 * 读得出来就说明「没坏」。所以必须有一条专门盯权限的判据，
 * 而且它得真的会因为权限变了而变红。
 */
class PrivateFileTest {

    @TempDir
    Path tmp;

    private static Set<PosixFilePermission> perms(Path p) throws IOException {
        return Files.getPosixFilePermissions(p);
    }

    private static boolean posixSupported(Path p) {
        try {
            Files.getPosixFilePermissions(p);
            return true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return false;
        }
    }

    @Test
    @DisplayName("写出的文件权限是 0600（只给属主读）")
    void writesWithOwnerOnlyPermissions() throws Exception {
        assumeTrue(posixSupported(tmp), "本机不支持 POSIX 权限（Windows），这条判据不适用");
        Path f = tmp.resolve("map.json");
        PrivateFile.writeString(f, "{\"mapping\":{}}");
        assertEquals(PrivateFile.ownerOnly(), perms(f),
                "映射件权限不是 0600 —— 同机其他用户能读到原值");
    }

    @Test
    @DisplayName("内容逐字一致（含中文）")
    void writesContentVerbatim() throws Exception {
        Path f = tmp.resolve("map.json");
        String content = "{\"mapping\":{\"<身份证1>\":\"11010519491231002X\"},\"note\":\"中文也要对\"}";
        PrivateFile.writeString(f, content);
        assertEquals(content, Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("覆盖已有文件：内容换新，权限仍是 0600")
    void overwriteKeepsPermissions() throws Exception {
        assumeTrue(posixSupported(tmp), "本机不支持 POSIX 权限");
        Path f = tmp.resolve("map.json");
        PrivateFile.writeString(f, "第一次");
        PrivateFile.writeString(f, "第二次");
        assertEquals("第二次", Files.readString(f, StandardCharsets.UTF_8));
        assertEquals(PrivateFile.ownerOnly(), perms(f));
    }

    @Test
    @DisplayName("父目录不存在时自动创建")
    void createsParentDirectory() throws Exception {
        Path f = tmp.resolve("a/b/c/map.json");
        PrivateFile.writeString(f, "内容");
        assertTrue(Files.isRegularFile(f));
    }

    @Test
    @DisplayName("临时文件不残留（写完只有一个目标文件）")
    void leavesNoTemporaryFiles() throws Exception {
        Path f = tmp.resolve("map.json");
        PrivateFile.writeString(f, "内容");
        try (Stream<Path> list = Files.list(tmp)) {
            assertEquals(1, list.count(), "目录里不止一个文件 → 临时文件没清掉");
        }
    }

    @Test
    @DisplayName("目标路径上没有「权限是宽的」那一刻（先写临时文件再改名）")
    void targetNeverExistsWithWidePermissions() throws Exception {
        assumeTrue(posixSupported(tmp), "本机不支持 POSIX 权限");
        Path f = tmp.resolve("map.json");
        // 先放一个同名文件，故意让 rename 走「覆盖已有目标」那条路。
        Files.writeString(f, "旧内容");
        PrivateFile.writeString(f, "新内容");
        assertEquals("新内容", Files.readString(f, StandardCharsets.UTF_8));
        assertEquals(PrivateFile.ownerOnly(), perms(f));
        try (Stream<Path> list = Files.list(tmp)) {
            assertEquals(1, list.count(), "临时文件没清掉");
        }
    }

    @Test
    @DisplayName("目录收紧：会话目录变成 0700")
    void tightenDirectory() throws Exception {
        assumeTrue(posixSupported(tmp), "本机不支持 POSIX 权限");
        Path dir = Files.createDirectories(tmp.resolve("session"));
        PrivateFile.tightenDir(dir);
        Set<PosixFilePermission> p = perms(dir);
        assertEquals(PrivateFile.ownerOnlyDir(), p, "目录权限不对");
        assertFalse(p.contains(PosixFilePermission.OTHERS_READ), "目录对其他用户可读：" + p);
        assertFalse(p.contains(PosixFilePermission.GROUP_READ), "目录对同组用户可读：" + p);
        assertTrue(p.contains(PosixFilePermission.OWNER_READ), "属主自己读不了：" + p);
        // 【为什么目录必须有执行位】没有它，属主自己也进不去这个目录
        // （能列出名字，却打不开里面的东西）。这里修过一次真事故：
        // 目录照着文件用了 rw-------，Workspace 一开就 AccessDenied。
        assertTrue(p.contains(PosixFilePermission.OWNER_EXECUTE), "目录缺执行位 → 进不去：" + p);
        // 并且要真能进去
        Path inside = Files.createFile(dir.resolve("里面.txt"));
        assertTrue(Files.isRegularFile(inside));
    }
}
