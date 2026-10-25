package com.litemask.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 还原「用户是怎么调起我的」这一前缀。
 *
 * <p><b>为什么要有这个</b>：工具的输出里会建议用户敲下一条命令
 * （比如「要退回用 xxx model rollback &lt;版本键&gt;」）。
 * 如果这里写死一个名字，而那个名字在这台机器上并不存在
 * （可执行文件其实叫 {@code LightMeter}），用户照着敲就是 command not found——
 * 工具指了一条走不通的路，这比不说还糟。
 *
 * <p><b>怎么还原</b>：进程信息里的参数分成两段：
 * <pre>
 *   [JVM 层的参数] + [程序级参数]
 *   例如 java -jar x.jar model install foo.zip
 *       JVM 层 = [-jar, x.jar]，程序级 = [model, install, foo.zip]
 * </pre>
 * 已知程序级参数时，末尾能精确对上，剩下的就是 JVM 层的那一段；
 * JVM 层为空说明是原生启动器（{@code .app} 里那个可执行文件），这时用可执行文件本身的名字。
 *
 * <p><b>取不到时怎么办</b>：返回空串，由调用方退回「只写子命令」的形式
 * （{@code model rollback}）。<b>宁可少写一个前缀，也不能写一个这台机器上不存在的名字。</b>
 */
public final class CliInvocation {

    /** 前缀最长允许多长；超了说明拿到的是测试框架之类的一大串参数，对用户没意义。 */
    private static final int MAX_PREFIX = 200;

    private CliInvocation() {
    }

    /**
     * 推导前缀的纯函数版本，便于直接测。
     *
     * @param programArgs 程序级参数（不含可执行文件本身）
     * @param command     可执行文件的路径，可能为空
     * @param allArgs     进程的全部参数（JVM 层 + 程序级）
     * @return 前缀；取不到时是空串
     */
    public static String prefix(List<String> programArgs, String command, String[] allArgs) {
        String[] all = allArgs == null ? new String[0] : allArgs;
        int n = programArgs == null ? 0 : programArgs.size();
        if (n <= 0 || n > all.length) {
            return "";
        }
        List<String> jvm = new ArrayList<>(Arrays.asList(all));
        if (!jvm.subList(jvm.size() - n, jvm.size()).equals(programArgs)) {
            // 末尾对不上：说明拿到的进程参数与本次程序级参数不是同一串
            // （典型是在测试框架里跑）。这时不猜，直接放弃前缀。
            return "";
        }
        jvm = new ArrayList<>(jvm.subList(0, jvm.size() - n));

        String base = baseName(command);
        String prefix;
        if (jvm.isEmpty()) {
            prefix = base;
        } else {
            prefix = base.isEmpty() ? String.join(" ", jvm) : base + " " + String.join(" ", jvm);
        }
        return prefix.length() > MAX_PREFIX ? "" : prefix;
    }

    /**
     * 当前进程的前缀。
     *
     * @param programArgs 程序级参数（不含可执行文件本身）
     * @return 前缀；取不到时是空串
     */
    public static String prefix(List<String> programArgs) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        return prefix(programArgs, info.command().orElse(""), info.arguments().orElse(new String[0]));
    }

    /**
     * 当前进程的前缀（数组版）。
     *
     * @param programArgs 程序级参数（不含可执行文件本身）
     * @return 前缀；取不到时是空串
     */
    public static String prefix(String[] programArgs) {
        return prefix(programArgs == null ? null : Arrays.asList(programArgs));
    }

    /**
     * 把前缀和子命令拼成一条建议给用户的命令。
     *
     * @param prefix    调用前缀，可能为空
     * @param sub       子命令及其参数，例如 {@code model rollback abc123}
     * @return 可直接照着敲的命令
     */
    public static String command(String prefix, String sub) {
        if (prefix == null || prefix.isBlank()) {
            return sub;
        }
        return prefix + " " + sub;
    }

    private static String baseName(String command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        try {
            Path fn = Path.of(command).getFileName();
            return fn == null ? "" : fn.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
