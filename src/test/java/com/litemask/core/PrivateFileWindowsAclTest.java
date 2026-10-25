package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Windows 上的「只给属主可读」。
 *
 * <p><b>为什么这条必须存在</b>：{@link PrivateFile} 原来只在 POSIX 上收紧权限，
 * Windows 那条分支把 {@code UnsupportedOperationException} <b>吞掉</b>了 ——
 * 于是 Windows 上「映射件只给属主看」这件事<b>没有任何一条测试在看</b>，
 * 而产品却对外承诺了它。本类把这条承诺在 Windows 上变成会红的判据。
 *
 * <p><b>本类只在 Windows 上跑</b>：POSIX 的等价判据在
 * {@link PrivateFileTest} 里（那些断言没有删，只是各归各的平台）。
 */
class PrivateFileWindowsAclTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("Windows：写出的映射件只有属主能访问")
    void mapFileIsOwnerOnlyOnWindows() throws Exception {
        assumeTrue(PrivateFile.isWindows(), "这条只适用于 Windows");
        Path f = tmp.resolve("map.json");
        PrivateFile.writeString(f, "{\"mapping\":{\"<身份证1>\":\"11010519491231002X\"}}");
        OwnerOnlyAssert.assertOwnerOnlyFile(f);
    }

    @Test
    @DisplayName("Windows：会话目录只有属主能进，且带继承标志")
    void sessionDirIsOwnerOnlyOnWindows() throws Exception {
        assumeTrue(PrivateFile.isWindows(), "这条只适用于 Windows");
        Path dir = Files.createDirectories(tmp.resolve("session"));
        PrivateFile.tightenDir(dir);
        OwnerOnlyAssert.assertOwnerOnlyDir(dir);
    }

    @Test
    @DisplayName("Windows：目录里新建的文件不会拿到空 DACL（空 DACL = 人人可读）")
    void filesInsideTightenedDirAreNotWorldReadable() throws Exception {
        assumeTrue(PrivateFile.isWindows(), "这条只适用于 Windows");
        Path dir = Files.createDirectories(tmp.resolve("session"));
        PrivateFile.tightenDir(dir);
        Path inside = Files.createFile(dir.resolve("上传的原件.docx"));
        AclFileAttributeView view = Files.getFileAttributeView(inside, AclFileAttributeView.class);
        assertTrue(view != null, "读不到 ACL");
        List<AclEntry> acl = view.getAcl();
        assertFalse(acl.isEmpty(), "ACL 是空的 —— Windows 语义下空 DACL 等于**所有人完全可读**");
        OwnerOnlyAssert.assertOwnerOnlyFile(inside);
    }

    @Test
    @DisplayName("Windows：覆盖已有文件后权限不会放松")
    void overwriteDoesNotRelaxAclOnWindows() throws Exception {
        assumeTrue(PrivateFile.isWindows(), "这条只适用于 Windows");
        Path f = tmp.resolve("map.json");
        PrivateFile.writeString(f, "第一次");
        PrivateFile.writeString(f, "第二次");
        OwnerOnlyAssert.assertOwnerOnlyFile(f);
        assertEquals("第二次", Files.readString(f));
    }

    @Test
    @DisplayName("Windows：ACL 里不出现给「其他人」的 ALLOW")
    void noForeignPrincipalIsAllowed() throws Exception {
        assumeTrue(PrivateFile.isWindows(), "这条只适用于 Windows");
        Path f = tmp.resolve("map.json");
        PrivateFile.writeString(f, "内容");
        List<AclEntry> acl = PrivateFile.aclOf(f);
        assertFalse(acl.isEmpty(), "ACL 是空的");
        java.nio.file.attribute.UserPrincipal owner = Files.getOwner(f);
        for (AclEntry e : acl) {
            if (e.type() != AclEntryType.ALLOW) {
                continue;
            }
            assertEquals(owner, e.principal(),
                    "有别的 principal 被授予了访问权（Everyone / Users 组 / 其它用户）—— 同机其他账户读得到原值");
        }
    }
}
