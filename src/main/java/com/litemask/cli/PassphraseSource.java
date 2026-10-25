package com.litemask.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 映射件口令的来源。
 *
 * <p><b>为什么不提供「命令行上直接给口令」这种形式</b>：
 * {@code --map-pass 秘密} 会同时进两处——shell 的历史记录（{@code ~/.bash_history}）
 * 和 {@code ps} 的输出（同机任何用户都能看到别人的命令行）。
 * 口令这种「知道了就能拿到全部原值」的东西，不该从这两个口子漏出去。
 *
 * <p>所以这里只提供三种「不经过命令行」的入口，外加一种交互式：
 * <ul>
 *   <li>{@code ENV}：从环境变量取（环境变量也会出现在 {@code /proc/<pid>/environ}，
 *       但同机其他用户默认读不到，比命令行参数安全一个量级）；</li>
 *   <li>{@code FILE}：从一个文件取第一行（适合 CI 与密钥管理工具）；</li>
 *   <li>{@code STDIN}：从标准输入取第一行（适合管道）；</li>
 *   <li>{@code PROMPT}：交互式提示，<b>不回显</b>。</li>
 * </ul>
 *
 * <p><b>拿不到就是拿不到</b>：四种入口读不出内容时一律抛异常，
 * 绝不「当作没有口令」继续跑 —— 那会让用户以为加密了、实际没加密。
 */
public final class PassphraseSource {

    /** 口令的来源种类。 */
    public enum Kind {
        /** 环境变量。 */
        ENV,
        /** 文件的第一行。 */
        FILE,
        /** 标准输入的第一行。 */
        STDIN,
        /** 交互式提示（不回显）。 */
        PROMPT
    }

    private final Kind kind;
    private final String argument;
    /** 标准输入的来源；{@code null} 表示用进程的标准输入。给测试用。 */
    private final InputStream stdin;

    private PassphraseSource(Kind kind, String argument, InputStream stdin) {
        this.kind = kind;
        this.argument = argument;
        this.stdin = stdin;
    }

    /** 从环境变量取。 */
    public static PassphraseSource env(String name) {
        return new PassphraseSource(Kind.ENV, name, null);
    }

    /** 从文件的第一行取。 */
    public static PassphraseSource file(String path) {
        return new PassphraseSource(Kind.FILE, path, null);
    }

    /** 从标准输入的第一行取。 */
    public static PassphraseSource stdin() {
        return new PassphraseSource(Kind.STDIN, null, null);
    }

    /** 从指定流的第一行取（给测试用；生产路径用 {@link #stdin()}）。 */
    static PassphraseSource stdin(InputStream in) {
        return new PassphraseSource(Kind.STDIN, null, in);
    }

    /** 交互式提示。 */
    public static PassphraseSource prompt() {
        return new PassphraseSource(Kind.PROMPT, null, null);
    }

    public Kind kind() {
        return kind;
    }

    /** 供错误信息用：说清楚「这个来源是什么」。 */
    public String describe() {
        switch (kind) {
            case ENV:
                return "环境变量 " + argument;
            case FILE:
                return "文件 " + argument;
            case STDIN:
                return "标准输入";
            case PROMPT:
                return "交互式提示";
            default:
                return kind.name();
        }
    }

    /**
     * 读出口令。
     *
     * @return 口令字符数组（调用方用完应当清零）
     * @throws PassphraseUnavailableException 这个来源读不出口令
     */
    public char[] read() throws PassphraseUnavailableException {
        switch (kind) {
            case ENV:
                return fromEnv();
            case FILE:
                return fromFile();
            case STDIN:
                return fromStream(stdin != null ? stdin : System.in);
            case PROMPT:
                return fromConsole();
            default:
                throw new PassphraseUnavailableException("未知的口令来源");
        }
    }

    private char[] fromEnv() throws PassphraseUnavailableException {
        String v = System.getenv(argument);
        if (v == null) {
            throw new PassphraseUnavailableException("环境变量 " + argument + " 没有设置");
        }
        if (v.isEmpty()) {
            throw new PassphraseUnavailableException("环境变量 " + argument + " 是空的");
        }
        return v.toCharArray();
    }

    private char[] fromFile() throws PassphraseUnavailableException {
        Path p = Path.of(argument);
        if (!Files.isRegularFile(p)) {
            throw new PassphraseUnavailableException("口令文件不存在或不是普通文件：" + argument);
        }
        try (InputStream in = Files.newInputStream(p)) {
            return fromStream(in);
        } catch (IOException e) {
            throw new PassphraseUnavailableException("读口令文件失败：" + argument + "（" + e.getMessage() + "）");
        }
    }

    private char[] fromStream(InputStream in) throws PassphraseUnavailableException {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                throw new PassphraseUnavailableException("标准输入里没有口令（读到的是空）");
            }
            if (line.isEmpty()) {
                throw new PassphraseUnavailableException("口令是空的");
            }
            return line.toCharArray();
        } catch (IOException e) {
            throw new PassphraseUnavailableException("读口令失败：" + e.getMessage());
        }
    }

    private char[] fromConsole() throws PassphraseUnavailableException {
        Console console = System.console();
        if (console == null) {
            throw new PassphraseUnavailableException("当前不是交互式终端，无法提示输入口令"
                    + "（请改用 --map-pass-env / --map-pass-file / --map-pass-stdin）");
        }
        char[] pw = console.readPassword("请输入映射件口令：");
        if (pw == null || pw.length == 0) {
            throw new PassphraseUnavailableException("口令是空的");
        }
        return pw;
    }

    /** 口令取不到。 */
    public static class PassphraseUnavailableException extends Exception {
        public PassphraseUnavailableException(String message) {
            super(message);
        }
    }
}
