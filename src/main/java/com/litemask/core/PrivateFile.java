package com.litemask.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「只给自己看」的文件落盘。
 *
 * <p><b>它挡的是什么</b>：映射件是本工具唯一含原文的产物，而它原本是
 * {@code Files.writeString} 直接写出去的 —— 权限取决于 umask，默认是 0644，
 * 也就是**同一台机器上的任何用户都能读**。会话目录同理：默认 0755，
 * 于是「上传的原件」与「映射件」在服务运行期间对同机其他用户是敞开的。
 *
 * <p><b>为什么先写临时文件再改名</b>：直接收紧权限的话，
 * 文件从创建到收紧之间有一个窗口，别的用户可能在这段时间里读到内容。
 * 临时文件在**同一个目录**里（同一文件系统 → 改名是原子的），
 * 它的名字带随机后缀、内容写完才改名，最终路径从不存在「权限是宽的」那一刻。
 *
 * <p><b>为什么权限定义在 {@link #OWNER_ONLY} 这一处</b>：0600 这个事实
 * 要被 CLI 与服务端同时用到，分两处写就迟早漂移（一边 0600 一边 0644，
 * 而谁也不会发现，因为「能读」在任何一边的测试里都成立）。
 *
 * <h2>Windows：ACL，而不是「不管」</h2>
 *
 * <p><b>2026-10-25 修掉的真缺陷</b>：这个类原来只调
 * {@code Files.setPosixFilePermissions}，而在 Windows 上那一句会抛
 * {@link UnsupportedOperationException} —— 原代码把它<b>吞掉</b>了，
 * 于是 Windows 上「只给自己看」这个承诺<b>完全没有兑现</b>：
 * 映射件与上传的原件按 umask 落盘，同机其他普通账户读得到。
 * 更糟的是它<b>看起来是成功的</b> —— 不报错、不留痕，
 * 用户与发布者都会以为文件是私有的。
 *
 * <p><b>现在怎么做</b>：Windows 走 ACL 模型：
 * <ul>
 *   <li>文件：把 DACL 换成「仅属主」的 ALLOW 条目（读/写/改属性/读 ACL/改 ACL/删除/同步）；</li>
 *   <li>目录：同一条目，并带上 {@code FILE_INHERIT_ACE} + {@code DIRECTORY_INHERIT_ACE}，
 *       让**目录里新建的文件**自动继承同一套「仅属主」权限。</li>
 * </ul>
 *
 * <p><b>为什么目录必须带继承标志</b>（这是最容易漏、后果最严重的一处）：
 * Windows 上，如果一个目录的 DACL 里**没有可继承的条目**，
 * 那么在它里面新建的文件会得到一个**空 DACL** ——
 * 而 Windows 的语义里「空 DACL」等于<b>所有人都完全访问</b>。
 * 也就是说，只把目录本身收紧、却忘了加继承标志，
 * 效果反而比不收紧更糟：目录进不去，但里面的文件人人可读。
 *
 * <p><b>不再静默忽略</b>：ACL 视图不可用（FAT32 等不支持 ACL 的文件系统）
 * 或设置失败时，抛 {@link IOException} 并说清楚原因 ——
 * 「不支持安全权限时明确失败，不静默忽略后声称安全」。
 * 同理，POSIX 系统上 {@code setPosixFilePermissions} 不被支持时也改为
 * fail-closed（原来也是静默吞掉）。
 */
public final class PrivateFile {

    /** 文件：只给属主读写。 */
    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    /**
     * 目录：只给属主读、写、**进**。
     *
     * <p>【为什么目录不能复用文件的那一套权限 —— 这是实测踩到的】
     *   目录上没有「执行位」就**进不去**：{@code rw-------} 的目录属主自己也遍历不了
     *   （能 ls 出名字，却打不开里面的东西）。
     *   本轮第一次写就是照着文件用了 {@code rw-------}，
     *   结果 {@code Workspace} 一开就 AccessDenied —— 82 条单测当场报错。
     *   → 目录与文件必须各用各的权限集合，**不能共用一个常量**。
     */
    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY_DIR =
            PosixFilePermissions.fromString("rwx------");

    /**
     * Windows 下「属主可完全控制一个文件」需要的权限集合。
     *
     * <p>对应 Windows 文档里「Full Control」的常规子集：
     * 读/写数据、追加、读/写 EA、读/写属性、读/改 ACL、改属主、删除、同步。
     *
     * <p><b>【READ/WRITE_NAMED_ATTRS 必须有 —— 这是实测踩到的（2026-10-25）】</b>
     *   JDK 里这两个常量的位值是 0x0008 / 0x0010，对应 Windows 的
     *   {@code FILE_READ_EA} / {@code FILE_WRITE_EA}，
     *   而 {@code GENERIC_WRITE} <b>包含</b> {@code FILE_WRITE_EA}。
     *   → 一开始按「命名 ACE 专用、基本 ACE 里会被拒」的印象把它们排除了，
     *     结果 {@code Files.write} 立刻 AccessDenied：
     *     DACL 里没有 FILE_WRITE_EA，CreateFile(GENERIC_WRITE) 就过不去。
     *   （本机实测：ACL 里带这两个 → 写成功；去掉 → AccessDenied。）
     *
     * <p>【枚举名的坑】JDK 里 {@code AclEntryPermission} 同时提供两套等价常量：
     * 文件视角的 {@code READ_DATA/WRITE_DATA/APPEND_DATA} 与
     * 目录视角的 {@code LIST_DIRECTORY/ADD_FILE/ADD_SUBDIRECTORY}，
     * 它们的<b>位值完全相同</b>（0x0001 / 0x0002 / 0x0004）。
     * 这里按对象类型选用**语义正确**的那一套，避免读代码的人误以为目录能读数据流。
     */
    private static final AclEntryPermission[] OWNER_ONLY_FILE_PERMS = {
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.DELETE,
            AclEntryPermission.SYNCHRONIZE,
    };

    /** 目录比文件多三条：能进去（执行）、能删里面的东西（DELETE_CHILD）、能加子项。 */
    private static final AclEntryPermission[] OWNER_ONLY_DIR_PERMS = {
            AclEntryPermission.LIST_DIRECTORY,
            AclEntryPermission.ADD_FILE,
            AclEntryPermission.ADD_SUBDIRECTORY,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.DELETE,
            AclEntryPermission.SYNCHRONIZE,
    };

    private PrivateFile() {
    }

    /**
     * 写一个只给属主可读的文件（父目录会一并创建）。
     *
     * @param path 目标路径
     * @param content 内容
     * @throws IOException 写不动
     */
    public static void write(Path path, byte[] content) throws IOException {
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, ".litemask-private-", ".tmp");
        try {
            tighten(tmp, false);
            Files.write(tmp, content);
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // 【为什么 move 之后还要再收紧一次】
            //   移动在 Windows 上通常是「改名」，DACL 跟着源文件走；
            //   但若平台/文件系统退化成「复制 + 删除」，目标的 ACL 会保留**旧目标**的
            //   （可能是宽的）。再收紧一次让不变量与移动方式无关。
            tighten(target, false);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * 写一个只给属主可读的文件（UTF-8 文本）。
     *
     * @param path 目标路径
     * @param content 内容
     * @throws IOException 写不动
     */
    public static void writeString(Path path, String content) throws IOException {
        write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 把一个目录的权限收紧到「只给属主」（POSIX 0700 / Windows ACL）。
     *
     * <p>会话目录里放着上传的**原件**与**映射件**，默认 0755 等于对同机其他用户敞开。
     *
     * @param dir 目录
     * @throws IOException 改不动
     */
    public static void tightenDir(Path dir) throws IOException {
        tighten(dir, true);
    }

    /**
     * 收紧权限：按平台走各自的权限模型。
     *
     * @param p 目标
     * @param directory 目标是不是目录
     * @throws IOException 改不动，或该平台/文件系统不支持安全权限（fail-closed）
     */
    private static void tighten(Path p, boolean directory) throws IOException {
        if (isWindows()) {
            tightenWindowsAcl(p, directory);
            return;
        }
        try {
            Files.setPosixFilePermissions(p, directory ? OWNER_ONLY_DIR : OWNER_ONLY);
        } catch (UnsupportedOperationException e) {
            // 【2026-10-25】原来这里是静默吞掉。
            //   吞掉的后果是：文件按 umask 落盘（0644/0755），
            //   而调用方以为「已经收紧」——同机其他用户读得到原值。
            //   → fail-closed，并把「这个文件系统不支持权限」这件事说出来。
            throw new IOException("这个文件系统不支持 POSIX 权限（" + p + "）——"
                    + "无法把含原值的文件限制为仅属主可读，拒绝继续", e);
        }
    }

    /**
     * Windows：把 DACL 换成「仅属主」。
     *
     * <p>目录额外带继承标志，让里面的文件自动继承同一套权限（理由见类注释）。
     */
    private static void tightenWindowsAcl(Path p, boolean directory) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("这个文件系统不支持 Windows ACL（" + p + "）——"
                    + "无法把含原值的文件限制为仅当前用户可读，拒绝继续。"
                    + "请把工作目录放在支持 ACL 的磁盘上（例如 NTFS），不要放在 FAT32/exFAT 上");
        }
        UserPrincipal owner;
        try {
            owner = Files.getOwner(p);
        } catch (IOException e) {
            // 【注意】UserPrincipalNotFoundException 是 IOException 的子类，
            //   写在多 catch 里会编译不过（" Alternatives in a multi-catch statement
            //   cannot be related by subclassing"）—— 这里只捕 IOException 就够。
            throw new IOException("读不出文件属主（" + p + "）—— 无法设置 ACL，拒绝继续", e);
        }
        AclEntry.Builder b = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(directory ? OWNER_ONLY_DIR_PERMS : OWNER_ONLY_FILE_PERMS);
        if (directory) {
            // 【关键】没有继承标志 → 目录里新建的文件会得到空 DACL
            //   → Windows 语义下「空 DACL = 人人完全可读」→ 比不收紧更糟。
            b.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        List<AclEntry> acl = new ArrayList<>();
        acl.add(b.build());
        try {
            view.setAcl(acl);
        } catch (IOException e) {
            throw new IOException("设置 Windows ACL 失败（" + p + "）：" + e.getMessage(), e);
        }
    }

    /** 本机是不是 Windows（决定走哪套权限模型）。 */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 读一个路径的 ACL（供测试与排障）。
     *
     * @param p 目标
     * @return ACL 条目；该文件系统不支持 ACL 时返回空列表
     * @throws IOException 读不动
     */
    public static List<AclEntry> aclOf(Path p) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (view == null) {
            return List.of();
        }
        return view.getAcl();
    }

    /** 供测试与排障用：文件的权限集合。 */
    public static Set<java.nio.file.attribute.PosixFilePermission> ownerOnly() {
        return OWNER_ONLY;
    }

    /** 供测试与排障用：目录的权限集合（比文件多一个执行位）。 */
    public static Set<java.nio.file.attribute.PosixFilePermission> ownerOnlyDir() {
        return OWNER_ONLY_DIR;
    }
}
