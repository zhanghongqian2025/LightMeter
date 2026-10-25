package com.litemask.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口令来源。
 *
 * <p><b>这里最要紧的一条是「取不到就是取不到」</b>：四种来源读不出内容时
 * 一律抛异常，**绝不**「当作没有口令」继续跑 —— 那会让用户以为映射件加密了，
 * 实际落盘的还是明文，而界面上看不出任何异常。
 */
class PassphraseSourceTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("文件：取第一行，行尾换行不算口令的一部分")
    void readsFirstLineOfFile() throws Exception {
        Path f = tmp.resolve("pw.txt");
        Files.writeString(f, "我的口令\n", StandardCharsets.UTF_8);
        char[] pw = PassphraseSource.file(f.toString()).read();
        assertEquals("我的口令", new String(pw));
    }

    @Test
    @DisplayName("文件：只有换行结尾也能正确截断（Windows 的 CRLF）")
    void stripsCrLf() throws Exception {
        Path f = tmp.resolve("pw.txt");
        Files.writeString(f, "pw\r\n", StandardCharsets.UTF_8);
        assertEquals("pw", new String(PassphraseSource.file(f.toString()).read()));
    }

    @Test
    @DisplayName("文件：空文件 / 只有一行空 → 取不到，抛异常")
    void emptyFileFails() throws Exception {
        Path empty = tmp.resolve("empty.txt");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        assertThrows(PassphraseSource.PassphraseUnavailableException.class,
                () -> PassphraseSource.file(empty.toString()).read());

        Path blank = tmp.resolve("blank.txt");
        Files.writeString(blank, "\n", StandardCharsets.UTF_8);
        assertThrows(PassphraseSource.PassphraseUnavailableException.class,
                () -> PassphraseSource.file(blank.toString()).read());
    }

    @Test
    @DisplayName("文件：不存在 → 抛异常，不是「当作没有口令」")
    void missingFileFails() {
        assertThrows(PassphraseSource.PassphraseUnavailableException.class,
                () -> PassphraseSource.file(tmp.resolve("没有这个文件").toString()).read());
    }

    @Test
    @DisplayName("标准输入：取第一行")
    void readsFirstLineOfStdin() throws Exception {
        char[] pw = PassphraseSource.stdin(new ByteArrayInputStream(
                "管道里的口令\n第二行不要\n".getBytes(StandardCharsets.UTF_8))).read();
        assertEquals("管道里的口令", new String(pw));
    }

    @Test
    @DisplayName("标准输入：空的 → 抛异常")
    void emptyStdinFails() {
        assertThrows(PassphraseSource.PassphraseUnavailableException.class,
                () -> PassphraseSource.stdin(new ByteArrayInputStream(new byte[0])).read());
        assertThrows(PassphraseSource.PassphraseUnavailableException.class,
                () -> PassphraseSource.stdin(new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8))).read());
    }

    @Test
    @DisplayName("环境变量：没设置 / 是空的 → 抛异常")
    void envMissingFails() {
        String name = "LITEMASK_TEST_绝对不存在的环境变量_9F3A";
        assertThrows(PassphraseSource.PassphraseUnavailableException.class,
                () -> PassphraseSource.env(name).read());
    }

    @Test
    @DisplayName("交互式：没有终端 → 抛异常并说清替代办法（不静默等输入）")
    void promptWithoutConsoleFails() {
        // 测试进程没有 TTY，System.console() 为 null。
        if (System.console() != null) {
            return; // 极少数情况下真有 TTY，交互这条由人工验
        }
        PassphraseSource.PassphraseUnavailableException e =
                assertThrows(PassphraseSource.PassphraseUnavailableException.class,
                        () -> PassphraseSource.prompt().read());
        assertTrue(e.getMessage().contains("--map-pass-env"),
                "失败信息应当给出替代办法：" + e.getMessage());
    }

    @Test
    @DisplayName("describe 说清来源，报错时用户知道该改哪儿")
    void describeIsSpecific() {
        assertTrue(PassphraseSource.env("PW").describe().contains("PW"));
        assertTrue(PassphraseSource.file("/tmp/pw").describe().contains("/tmp/pw"));
        assertTrue(PassphraseSource.stdin().describe().contains("标准输入"));
        assertTrue(PassphraseSource.prompt().describe().contains("交互式"));
    }

    @Test
    @DisplayName("口令内容原样取回（不做任何 trim / 大小写变换）")
    void keepsPassphraseVerbatim() throws Exception {
        Path f = tmp.resolve("pw.txt");
        Files.writeString(f, "  空格 和 大写 ABC  ", StandardCharsets.UTF_8);
        char[] pw = PassphraseSource.file(f.toString()).read();
        assertArrayEquals("  空格 和 大写 ABC  ".toCharArray(), pw);
    }
}
