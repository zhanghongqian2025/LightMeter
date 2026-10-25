package com.litemask.cli;

import java.io.PrintStream;

/**
 * 子命令自己的 {@code --help} 输出的统一排版。
 *
 * <p><b>为什么要有这一层</b>
 *   块 38 开工前的实测：{@code redact --help} 不给用法（反而**真的执行了脱敏**），
 *   {@code rules --help} 更隐蔽 —— 它<b>静默执行了 rules</b>，退出码 0，
 *   用户拿到一份规则表，却以为自己看到了用法。
 *   → 修法是「每个子命令都有一份自己的用法」，而这些用法的<b>排版必须一致</b>，
 *     否则「哪个子命令有 help」这件事又得靠人肉比对。
 *   → 排版写在这里一处：{@code 用法:} 块 → 空行 → {@code 说明:} 块。
 *
 * <p><b>为什么用 print 而不是 println</b>
 *   文本块自带结尾换行，{@code println} 会**再多打一个换行**（空行）。
 *   块 37 的守卫按空行切分「用法:」块 —— 多打的空行会把命令行切到块外，
 *   守卫就会以为 help 里没有这些命令。
 */
final class CliHelp {

    private CliHelp() {
    }

    /**
     * 打印一个子命令自己的帮助：{@code 用法:} + {@code 说明:}。
     *
     * @param out       输出流（{@code --help} 走 stdout；报错时走 stderr）
     * @param usageText 命令行（真源：各命令类自己的 {@code usageText()}）
     * @param notesText 说明（真源：各命令类自己的 {@code usageNotesText()}）
     */
    static void print(PrintStream out, String usageText, String notesText) {
        out.print("用法:\n");
        out.print(usageText);
        out.println();
        out.print("说明:\n");
        out.print(notesText);
    }
}
