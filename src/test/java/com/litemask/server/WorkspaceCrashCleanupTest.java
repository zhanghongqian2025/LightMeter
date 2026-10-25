package com.litemask.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃残留的清扫。
 *
 * <p><b>为什么单独开一个测试类</b>：这一块的核心不是「返回值对不对」，
 * 而是「<b>盘上少了什么、多了什么</b>」——判据全部是目录/文件的存在性，
 * 任何「看起来像」的比对都不算数。
 *
 * <p><b>怎么造出「崩溃残留」</b>：不真的去 kill 一个进程，而是在盘上摆出
 * 「锁文件在、但没人持锁」的样子——这正是 SIGKILL 之后内核留下的状态
 * （锁由内核持有，进程一死内核就放）。
 * 摆出来的形状和真崩溃一致，所以测的是同一段判定。
 *
 * <p><b>造不出「主人在位」的样子</b>：那需要另一个进程真的持着锁。
 * 同 JVM 里 {@code FileChannel.tryLock()} 会抛
 * {@code OverlappingFileLockException}，正好等价于「锁被占着」，
 * 所以「另一个 Workspace 实例」就能代表「另一个还活着的实例」。
 */
class WorkspaceCrashCleanupTest {

    private static final byte[] CONTENT = "原始文档内容".getBytes(StandardCharsets.UTF_8);

    /** 一个符合本工具会话 id 格式的假会话名（32 位十六进制）。 */
    private static String fakeSid() {
        return "0123456789abcdef0123456789abcdef";
    }

    @Test
    @DisplayName("主人已死（锁没人持）的会话：目录、文档、锁文件全被清掉")
    void 死会话被清掉(@TempDir Path tmp) throws Exception {
        Path root = Files.createTempDirectory(tmp, "root");
        String sid = fakeSid();
        Path sessions = root.resolve("sessions");
        Path locks = root.resolve("locks");
        Files.createDirectories(sessions.resolve(sid));
        Files.createDirectories(locks);
        // 崩溃留下的原文档：它是这个工具最该清掉的东西
        Path doc = sessions.resolve(sid).resolve("原文档.pdf");
        Files.write(doc, CONTENT);
        // 崩溃留下的锁文件：没人持它 = 主人已死
        Path lockFile = locks.resolve(sid + ".lock");
        Files.write(lockFile, "4242".getBytes(StandardCharsets.UTF_8));

        try (Workspace ws = Workspace.open(root)) {
            assertFalse(Files.exists(doc),
                    "崩溃残留的原文档必须被清掉：它躺在盘上没人知道它是什么");
            assertFalse(Files.exists(sessions.resolve(sid)), "死会话的目录应被清掉");
            assertFalse(Files.exists(lockFile), "死会话的锁文件应被清掉");
            assertTrue(Files.isDirectory(ws.sessionDir()), "新会话的目录应建好");
            assertNotEquals(sid, ws.sessionId(), "新会话不能和被清掉的那个同名");
        }
    }

    @Test
    @DisplayName("还活着的实例：另一个实例启动时不能清掉它的目录和文档")
    void 活会话不被删(@TempDir Path tmp) throws Exception {
        Path root = Files.createTempDirectory(tmp, "root");
        Workspace alive = Workspace.open(root);
        try {
            Workspace.StoredFile doc = alive.store(CONTENT, "在办的合同.docx");
            Path aliveDir = alive.sessionDir();
            Path aliveLock = root.resolve("locks").resolve(alive.sessionId() + ".lock");
            assertTrue(Files.exists(aliveLock), "活着的实例应持有自己的锁文件");

            // 第二个实例启动 → 它启动时会做一次清扫
            try (Workspace other = Workspace.open(root)) {
                assertNotEquals(alive.sessionId(), other.sessionId(), "两个实例应有各自的会话");
                assertTrue(Files.isDirectory(aliveDir),
                        "活实例的会话目录被另一个实例删了：清扫把在位的主人当成了崩溃残留");
                assertTrue(Files.isRegularFile(doc.path()),
                        "活实例的用户文档被另一个实例删了");
                assertTrue(Files.exists(aliveLock), "活实例的锁文件不该被删");
            }
        } finally {
            alive.close();
        }
    }

    @Test
    @DisplayName("不是本工具建的目录一律不碰：用户数据安全优先于清残留")
    void 非本工具目录不碰(@TempDir Path tmp) throws Exception {
        Path root = Files.createTempDirectory(tmp, "root");
        Path sessions = root.resolve("sessions");
        Path locks = root.resolve("locks");
        Files.createDirectories(sessions.resolve("我的资料"));
        Files.createDirectories(locks);
        Path mine = sessions.resolve("我的资料").resolve("重要.pdf");
        Files.write(mine, CONTENT);
        // 锁文件在，但名字不是本工具的会话 id 格式
        Files.write(locks.resolve("我的资料.lock"), "x".getBytes(StandardCharsets.UTF_8));

        // 另一种形态：目录名是本工具的 id 格式，但**没有对应的锁文件**
        String orphanSid = "fedcba9876543210fedcba9876543210";
        Path orphan = sessions.resolve(orphanSid);
        Files.createDirectories(orphan);
        Path orphanDoc = orphan.resolve("没锁的.pdf");
        Files.write(orphanDoc, CONTENT);

        try (Workspace ws = Workspace.open(root)) {
            assertTrue(Files.isRegularFile(mine),
                    "用户自己命名的目录被删了：--workspace 可能指着用户自己的资料目录");
            assertTrue(Files.isRegularFile(orphanDoc),
                    "没有锁文件的会话目录被删了：清扫只该认锁文件指向的会话");
        }
    }

    @Test
    @DisplayName("正常退出：自己的锁文件被删掉，锁目录留着")
    void 正常退出会删锁文件(@TempDir Path tmp) throws Exception {
        Path root = Files.createTempDirectory(tmp, "root");
        String sid;
        try (Workspace ws = Workspace.open(root)) {
            sid = ws.sessionId();
            assertTrue(Files.exists(root.resolve("locks").resolve(sid + ".lock")),
                    "运行期间应持有锁文件");
        }
        assertFalse(Files.exists(root.resolve("locks").resolve(sid + ".lock")),
                "正常退出后锁文件应被删掉，否则下次启动会误判成崩溃残留");
        assertTrue(Files.isDirectory(root.resolve("locks")), "锁目录本身留着");
    }

    @Test
    @DisplayName("工作目录根下的其它东西不会被清掉")
    void 根目录其它东西不动(@TempDir Path tmp) throws Exception {
        Path root = Files.createTempDirectory(tmp, "root");
        Path mine = root.resolve("我的文件.txt");
        Files.write(mine, CONTENT);

        try (Workspace ws = Workspace.open(root)) {
            assertTrue(Files.isRegularFile(mine), "根目录下的用户文件不该在运行期间被删");
        }
        assertTrue(Files.isRegularFile(mine), "退出后根目录下的用户文件也不该被删");
    }
}
