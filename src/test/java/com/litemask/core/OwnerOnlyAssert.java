package com.litemask.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「只给属主可读」这条不变量的跨平台断言。
 *
 * <p><b>为什么要有它</b>：原来的权限判据全写死成 POSIX（{@code 0600}），
 * 在 Windows 上只能整条跳过 —— 于是「Windows 上文件是不是私有的」
 * 这件事<b>从来没有任何一条测试在看</b>。
 * 而 {@link PrivateFile} 原来在 Windows 上是<b>静默不设权限</b>的
 * （见那个类的注释），两边叠在一起 = Windows 的文件隔离**无人看守**。
 *
 * <p><b>判据</b>：
 * <ul>
 *   <li>POSIX 文件系统：权限集合必须恰好是 {@code rw-------}；目录是 {@code rwx------}；</li>
 *   <li>Windows（ACL）：DACL 里<b>必须</b>有一条给属主的 ALLOW，
 *       且<b>不得</b>有任何一条给「别的 principal」的 ALLOW
 *       （Everyone / BUILTIN\Users / 其它用户组一律不许出现）。</li>
 * </ul>
 *
 * <p><b>为什么 Windows 侧要看「有没有别的 ALLOW」而不只是「属主有 ALLOW」</b>：
 * 只断言属主有条目的话，一个「属主 + Everyone」��� DACL 也能过 ——
 * 而那正是「同机人人可读」。必须断言**只有**属主。
 */
public final class OwnerOnlyAssert {

    private OwnerOnlyAssert() {
    }

    /** 文件：只给属主。 */
    public static void assertOwnerOnlyFile(Path p) throws Exception {
        assertOwnerOnly(p, false);
    }

    /** 目录：只给属主。 */
    public static void assertOwnerOnlyDir(Path p) throws Exception {
        assertOwnerOnly(p, true);
    }

    private static void assertOwnerOnly(Path p, boolean directory) throws Exception {
        if (Files.getFileStore(p).supportsFileAttributeView("posix")) {
            assertPosix(p, directory);
            return;
        }
        assertAcl(p, directory);
    }

    private static void assertPosix(Path p, boolean directory) throws Exception {
        java.util.Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
        assertEquals(directory ? PrivateFile.ownerOnlyDir() : PrivateFile.ownerOnly(), perms,
                "权限应当是 " + (directory ? "0700" : "0600") + "，实际 " + perms);
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ)
                        || perms.contains(PosixFilePermission.OTHERS_READ),
                "对同组/其他人可读：" + perms);
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ), "属主自己读不了：" + perms);
    }

    private static void assertAcl(Path p, boolean directory) throws Exception {
        AclFileAttributeView view = Files.getFileAttributeView(p, AclFileAttributeView.class);
        assertTrue(view != null, "这个文件系统没有 ACL 视图（" + p + "）—— Windows 上等于没有隔离");
        UserPrincipal owner = Files.getOwner(p);
        List<AclEntry> acl = view.getAcl();

        boolean ownerAllowed = false;
        for (AclEntry e : acl) {
            if (e.type() != AclEntryType.ALLOW) {
                continue;
            }
            if (e.principal().equals(owner)) {
                ownerAllowed = true;
                continue;
            }
            // 别的 principal 拿到了 ALLOW —— 这就是「同机其他普通账户读得到原值」。
            org.junit.jupiter.api.Assertions.fail(
                    "有别的 principal 被授予了访问权：" + e.principal()
                            + " → 权限 " + e.permissions() + "；属主是 " + owner
                            + "；完整 ACL = " + acl);
        }
        assertTrue(ownerAllowed, "属主在 ACL 里没有 ALLOW 条目，属主自己都访问不了：" + acl);

        if (directory) {
            // 【关键】目录必须带继承标志，否则里面新建的文件会拿到空 DACL
            //   → Windows 语义下「空 DACL = 人人完全可读」，比不收紧更糟。
            boolean inherits = false;
            for (AclEntry e : acl) {
                if (e.type() == AclEntryType.ALLOW && e.principal().equals(owner)) {
                    if (e.flags().contains(AclEntryFlag.FILE_INHERIT)
                            || e.flags().contains(AclEntryFlag.DIRECTORY_INHERIT)) {
                        inherits = true;
                    }
                }
            }
            assertTrue(inherits,
                    "目录的 ACL 没有继承标志 → 里面新建的文件会拿到空 DACL（= 人人可读）：" + acl);
        }
    }
}
