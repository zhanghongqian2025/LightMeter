package com.litemask.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WindowsRunKey}：Windows 开机自启的<b>全部约定</b>（纯逻辑）。
 *
 * <p><b>这里测的是什么</b>：「开机自启」这件事在 Windows 侧就等于三件事 ——
 * 写哪个键、那个值叫什么、值里那条命令行长什么样。
 * 约定错了，症状是「装上了但登录时不起不来」，而那个症状在真机上一轮登录才能复现一次，
 * 极难定位。→ 所以约定必须能在 macOS 上<b>逐字</b>测死。
 *
 * <p><b>标尺独立于被测实现</b>：期望值全部<b>手写</b>，
 * 不复用 {@code WindowsRunKey} 的常量 —— 复用只能证明它跟自己一致。
 * 唯一一处刻意引用实现常量的是「与 macOS 侧同源」那几条，
 * 因为那里要验的正是「两个平台确实是同一个事实」。
 */
class WindowsRunKeyTest {

    // ------------------------------------------------------------------
    // 键与值名
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Run 键是当前用户（HKCU）下的那一个 —— 写本机（HKLM）要提权")
    void runKeyIsPerUser() {
        // 手写期望值，不复用被测实现的常量。
        assertEquals("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", WindowsRunKey.RUN_KEY);
        assertFalse(WindowsRunKey.RUN_KEY.contains("HKLM"),
                "写 HKLM 要管理员权限，普通用户会被提权弹窗挡住：" + WindowsRunKey.RUN_KEY);
        assertFalse(WindowsRunKey.RUN_KEY.contains("HKEY_LOCAL_MACHINE"),
                WindowsRunKey.RUN_KEY);
    }

    @Test
    @DisplayName("注册表值名与 macOS 的 label 是同一个默认值（卸载路径不用分平台）")
    void valueNameIsSameAsMacLabel() {
        assertEquals("com.litemask.lightmeter", WindowsRunKey.DEFAULT_VALUE_NAME);
        // 这一条是**同源检查**：两个平台的值名必须是同一个字符串，
        // 否则卸载时「该删哪个名字」就要分平台判断（多一处会漂移的地方）。
        assertEquals(LaunchAgentPlist.DEFAULT_LABEL, WindowsRunKey.DEFAULT_VALUE_NAME);
    }

    @Test
    @DisplayName("可执行文件名与默认端口跟 macOS 侧对齐")
    void exeNameAndPortMatchMac() {
        assertEquals("LightMeter.exe", WindowsRunKey.EXE_NAME);
        assertEquals(8090, WindowsRunKey.DEFAULT_PORT);
        assertEquals(LaunchAgentPlist.DEFAULT_PORT, WindowsRunKey.DEFAULT_PORT);
    }

    // ------------------------------------------------------------------
    // 命令行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("命令行形态：可执行文件整体带引号 + serve --no-browser --port")
    void commandDataShape() {
        String d = WindowsRunKey.commandData(
                Path.of("C:\\Program Files\\LightMeter\\LightMeter.exe"), 8090);
        assertEquals("\"C:\\Program Files\\LightMeter\\LightMeter.exe\" serve --no-browser --port 8090", d);
    }

    @Test
    @DisplayName("端口是可以改的，且改完就出现在命令行里")
    void commandDataCarriesPort() {
        String d = WindowsRunKey.commandData(Path.of("C:\\a b\\L.exe"), 12345);
        assertTrue(d.endsWith(" serve --no-browser --port 12345"), d);
    }

    @Test
    @DisplayName("可执行文件路径必须绝对：相对路径在登录时按不确定的工作目录解析")
    void relativeExecutableIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WindowsRunKey.commandData(Path.of("LightMeter.exe"), 8090));
        assertTrue(e.getMessage().contains("绝对路径"),
                "报错要说清是绝对路径这件事：" + e.getMessage());
    }

    @Test
    @DisplayName("「绝对路径」的判定不依赖运行平台（macOS 上 C:\\... 也算绝对）")
    void absolutePathDetectionIsPlatformIndependent() {
        assertTrue(WindowsRunKey.isAbsolutePath("C:\\Program Files\\X\\a.exe"));
        assertTrue(WindowsRunKey.isAbsolutePath("D:/x/a.exe"));
        assertTrue(WindowsRunKey.isAbsolutePath("\\\\server\\share\\a.exe"));
        assertTrue(WindowsRunKey.isAbsolutePath("/usr/local/bin/a"));
        assertFalse(WindowsRunKey.isAbsolutePath("a.exe"));
        assertFalse(WindowsRunKey.isAbsolutePath("dir\\a.exe"));
        assertFalse(WindowsRunKey.isAbsolutePath(""));
        assertFalse(WindowsRunKey.isAbsolutePath(null));
    }

    @Test
    @DisplayName("路径里有双引号一律拒绝（Windows 文件名本来就不允许出现引号）")
    void embeddedQuoteIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WindowsRunKey.quoteExecutable(Path.of("C:\\a\"b\\L.exe")));
    }

    @Test
    @DisplayName("引号包裹：可执行文件路径整体被双引号包住")
    void quoteWrapsWholePath() {
        assertEquals("\"C:\\Program Files\\L.exe\"",
                WindowsRunKey.quoteExecutable(Path.of("C:\\Program Files\\L.exe")));
    }

    // ------------------------------------------------------------------
    // reg 命令
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reg add 的参数逐个对：键 /v 值名 /t REG_SZ /d 数据 /f")
    void addCommandArguments() {
        List<String> c = WindowsRunKey.addCommand("com.litemask.lightmeter",
                "\"C:\\a b\\L.exe\" serve --no-browser --port 8090");
        assertEquals(List.of("reg", "add",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "com.litemask.lightmeter",
                "/t", "REG_SZ",
                // 【2026-10-25 真 Windows 实测】/d 的值里双引号是**转义**的（\"）。
                //   真实的 reg.exe 会把 \" 还原成 " 存进注册表；
                //   不转义的话它把引号吃掉 —— 路径没空格时值少了引号（自检判失败），
                //   路径有空格时 reg add 直接报错（自启根本装不上）。
                "/d", "\\\"C:\\a b\\L.exe\\\" serve --no-browser --port 8090",
                "/f"), c);
    }

    @Test
    @DisplayName("reg delete 只删这一个值，不删整个键（不能连用户的其它自启一起干掉）")
    void deleteCommandTargetsOnlyOurValue() {
        List<String> c = WindowsRunKey.deleteCommand("com.litemask.lightmeter");
        assertEquals(List.of("reg", "delete",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "com.litemask.lightmeter", "/f"), c);
        assertFalse(c.contains("/ve"), "/ve 会删掉键的默认值（整个 Run 键的所有自启）：" + c);
    }

    @Test
    @DisplayName("reg query 查的是同一个键的同一个值")
    void queryCommandArguments() {
        List<String> c = WindowsRunKey.queryCommand("com.litemask.lightmeter");
        assertEquals(List.of("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "com.litemask.lightmeter"), c);
    }

    // ------------------------------------------------------------------
    // 解析 reg query 的输出
    // ------------------------------------------------------------------

    /** 本机 reg query 的真实输出形态（键路径一行 + 缩进的值一行）。 */
    private static final String REG_QUERY_OUTPUT =
            "\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\n"
            + "    com.litemask.lightmeter    REG_SZ    "
            + "\"C:\\Program Files\\LightMeter\\LightMeter.exe\" serve --no-browser --port 8090\n";

    @Test
    @DisplayName("能从 reg query 的输出里把值的内容原样取出来")
    void parseValueReadsRegQueryOutput() {
        assertEquals("\"C:\\Program Files\\LightMeter\\LightMeter.exe\" serve --no-browser --port 8090",
                WindowsRunKey.parseValue(REG_QUERY_OUTPUT, "com.litemask.lightmeter"));
    }

    @Test
    @DisplayName("数据里有空格也不能被切碎（按空白整体切是错的）")
    void parseValueKeepsSpacesInsideData() {
        String out = "    com.litemask.lightmeter    REG_SZ    \"C:\\a b c\\L.exe\" serve --no-browser --port 8090\n";
        assertEquals("\"C:\\a b c\\L.exe\" serve --no-browser --port 8090",
                WindowsRunKey.parseValue(out, "com.litemask.lightmeter"));
    }

    @Test
    @DisplayName("值名是另一个值的前缀时不能误匹配")
    void parseValueDoesNotMatchOnPrefix() {
        String out = "    com.litemask.lightmeterExtra    REG_SZ    other\n";
        assertNull(WindowsRunKey.parseValue(out, "com.litemask.lightmeter"),
                "前缀相同的另一个值不该被当成我们那个");
    }

    @Test
    @DisplayName("只有键名那一行（值不在）时返回 null")
    void parseValueKeyOnly() {
        String out = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\n";
        assertNull(WindowsRunKey.parseValue(out, "com.litemask.lightmeter"));
    }

    @Test
    @DisplayName("找不到就是 null；空输入与 null 输入都不许抛异常")
    void parseValueNotFoundOrEmpty() {
        assertNull(WindowsRunKey.parseValue("    别的值    REG_SZ    x\n", "com.litemask.lightmeter"));
        assertNull(WindowsRunKey.parseValue("", "com.litemask.lightmeter"));
        assertNull(WindowsRunKey.parseValue(null, "com.litemask.lightmeter"));
        assertNull(WindowsRunKey.parseValue("whatever", null));
        assertNull(WindowsRunKey.parseValue("whatever", ""));
    }

    @Test
    @DisplayName("类型后面没有数据（格式不认识）时不许瞎猜")
    void parseValueRejectsMalformedLine() {
        assertNull(WindowsRunKey.parseValue(
                "    com.litemask.lightmeter    REG_SZ\n", "com.litemask.lightmeter"));
    }

    @Test
    @DisplayName("数据为空时返回 null（空值等于「读不出内容」，不等于「没装」）")
    void parseValueRejectsEmptyData() {
        assertNull(WindowsRunKey.parseValue(
                "    com.litemask.lightmeter    REG_SZ    \n", "com.litemask.lightmeter"));
    }
}
