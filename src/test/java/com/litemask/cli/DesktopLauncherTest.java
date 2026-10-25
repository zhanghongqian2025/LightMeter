package com.litemask.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面启动器的参数路由。
 *
 * <p>为什么这条要有测试：{@code .app} 的可执行文件同时是桌面启动器和命令行程序。
 * 两种走岔了都不报错，只是「双击没反应」或「终端里跑不了 CLI」，
 * 而这两种都不会让任何已有测试变红——因为它们都不在单测覆盖的路径上。
 *
 * <p><b>覆盖范围声明</b>：这里只验「有参数 → 转交 {@link Main}」这一半。
 * 「无参数 → 起本地服务」那一半<b>故意不在单测里跑</b>：
 * {@code ServeCommand.run} 会一直阻塞到进程被杀，塞进单测就是一个挂死的测试。
 * 它的验证在 M4 的端到端里——真的把打好的 {@code .app} 启动起来看服务起没起。
 * 这里不假装覆盖了它。
 */
class DesktopLauncherTest {

    @Test
    @DisplayName("带参数时转交 Main：--help 打印用法并正常返回（不退出进程）")
    void delegatesToMainWhenArgsPresent() {
        // 用 --help 而不是 redact/restore：前者在 Main 里走 usage() 后 return，
        // 不调 System.exit，所以断言能安全地跑完。
        // 这条断言本身也证明了「确实转交了 Main」——
        // 用法文本只有 Main.usage() 会打印，DesktopLauncher 自己一个字的用法都不写。
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            DesktopLauncher.main(new String[]{"--help"});
        } finally {
            System.setOut(old);
        }
        String printed = buf.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("用法"),
                "带参数时应当转交 Main 并由它打印用法；实际输出：" + printed);
        assertTrue(printed.contains("redact"),
                "用法里应当列出子命令；实际输出：" + printed);
    }

    @Test
    @DisplayName("--help 走的是 usage 分支，进程没被 System.exit 打断（能走到断言之后就是证据）")
    void helpDoesNotExit() {
        // 这条不单独断言任何输出——它的全部价值在于「测试能跑完」。
        // 如果 DesktopLauncher 改成对 --help 也 exit，这条会直接以
        // "进程在测试方法里退出" 的形式失败，而不是安静地通过。
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            DesktopLauncher.main(new String[]{"-h"});
        } finally {
            System.setOut(old);
        }
        assertTrue(buf.size() > 0, "-h 也应当打印用法");
    }
}
