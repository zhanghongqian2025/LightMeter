package com.litemask.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 调用前缀的还原。
 *
 * <p>这里防的是一条真实存在的缺陷：工具的输出里会建议用户敲一条命令，
 * 而那个命令里写死的程序名在这台机器上并不存在（可执行文件其实叫 {@code LightMeter}）。
 * 用户照着敲就是 command not found——工具指了一条走不通的路。
 */
class CliInvocationTest {

    @Test
    @DisplayName("原生启动器：用可执行文件自己的名字（.app 里那个是 LightMeter）")
    void nativeLauncher() {
        assertEquals("LightMeter", CliInvocation.prefix(
                List.of("model", "install", "foo.zip"),
                "/Users/x/LightMeter.app/Contents/MacOS/LightMeter",
                new String[]{"model", "install", "foo.zip"}));
    }

    @Test
    @DisplayName("java -jar：JVM 层参数一起带上，敲出来能直接跑")
    void javaJarLauncher() {
        assertEquals("java -jar x.jar", CliInvocation.prefix(
                List.of("model", "install"),
                "/opt/jdk/bin/java",
                new String[]{"-jar", "x.jar", "model", "install"}));
    }

    @Test
    @DisplayName("参数路径里也有 model 时不会切错地方（切错就会把 jar 路径当命令）")
    void pathContainingSubcommandName() {
        assertEquals("java -jar /Users/model/x.jar", CliInvocation.prefix(
                List.of("model", "install"),
                "/opt/jdk/bin/java",
                new String[]{"-jar", "/Users/model/x.jar", "model", "install"}));
    }

    @Test
    @DisplayName("取不到进程参数时返回空串：宁可不写前缀，也不写一个不存在的名字")
    void noProcessArgs() {
        assertEquals("", CliInvocation.prefix(
                List.of("model", "install"), "/opt/jdk/bin/java", new String[0]));
    }

    @Test
    @DisplayName("进程参数与本次程序参数对不上时返回空串（典型是在测试框架里跑）")
    void mismatchedArgs() {
        assertEquals("", CliInvocation.prefix(
                List.of("model", "install"), "/opt/jdk/bin/java",
                new String[]{"-jar", "x.jar", "model", "list"}));
    }

    @Test
    @DisplayName("程序级参数为空时返回空串")
    void noProgramArgs() {
        assertEquals("", CliInvocation.prefix(
                List.of(), "/opt/jdk/bin/java", new String[]{"-jar", "x.jar"}));
    }

    @Test
    @DisplayName("前缀过长（像是测试框架的一大串参数）时放弃")
    void tooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        assertEquals("", CliInvocation.prefix(
                List.of("model"), "/opt/jdk/bin/java", new String[]{sb.toString(), "model"}));
    }

    @Test
    @DisplayName("拼建议命令：没有前缀就只给子命令")
    void composeCommand() {
        assertEquals("LightMeter model rollback abc", CliInvocation.command("LightMeter", "model rollback abc"));
        assertEquals("model rollback abc", CliInvocation.command("", "model rollback abc"));
        assertEquals("model rollback abc", CliInvocation.command(null, "model rollback abc"));
    }
}
