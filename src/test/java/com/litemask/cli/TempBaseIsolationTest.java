package com.litemask.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：**测试期的临时目录基准必须是「本构建私有」的**。
 *
 * <p><b>它在守什么</b>：{@code ModelDownloadTest.下载用的临时目录不留下残留}
 * 的观测方式是「数 {@code java.io.tmpdir} 下以 {@code litemask-model-download-}
 * 开头的目录个数，前数一次、后数一次，要求相等」。
 *
 * <p><b>为什么这条前提必须显式断言</b>：那个基准默认是**全机共享**的
 * （macOS 上是 {@code /var/folders/…/T}）。四路并行跑对照实验时
 * （主树 + 3 个副本，各有一套 surefire JVM），每一路都在同一个基准里
 * 创建并删除自己的下载临时目录 → A 路「数之前」与「数之后」之间，
 * B 路建了一个目录 → 计数对不上 → <b>两路直接红在「起飞前基线不是绿的」</b>。
 * 实测：2026-10-20 块 14 的第一轮里 w0 与 w3 两路就是这样没的
 * （报「期望 37、实际 36」）。
 *
 * <p><b>为什么不改那条断言</b>：断言本身没错，它的前提是「下载没留下临时目录」；
 * 错的是**观测的基准不私有**——在共享基准上数个数，数的就不只是
 * 「这次下载留下的东西」。所以修的是**前提**，不是断言
 * （见 {@code pom.xml} 里 surefire 的 {@code java.io.tmpdir}）。
 *
 * <p><b>为什么这条要单独写成一条测试</b>：隔离配置写在 {@code pom.xml} 里，
 * 而 {@code pom.xml} 不在任何断言的射程内——它被谁悄悄删掉，
 * 症状是「跑对照实验时随机两路假红」，而那条红**长得像产品缺陷**。
 * → 把它变成一条会红、且红得可读的断言。
 */
class TempBaseIsolationTest {

    @Test
    void 临时目录基准是本构建私有的() {
        String raw = System.getProperty("java.io.tmpdir");
        assertTrue(raw != null && !raw.isBlank(),
                "java.io.tmpdir 必须有值——它决定「临时目录基准」在哪");

        Path tmpBase = Path.of(raw).toAbsolutePath().normalize();
        Path buildDir = Path.of(System.getProperty("user.home", "."))
                .toAbsolutePath().normalize().getParent();

        assertTrue(buildDir != null,
                "user.home 必须在某个目录里（测试期的 user.home 指向 "
                        + System.getProperty("user.home") + "）");

        assertTrue(tmpBase.startsWith(buildDir),
                "测试期的临时目录基准必须是**本构建私有**的，否则并行跑对照实验时"
                        + "各路会互相干扰（ModelDownloadTest 数的是 "
                        + "java.io.tmpdir 下 litemask-model-download- 前缀的目录个数，"
                        + "共享基准上这个数会被别的进程改掉）"
                        + "；实际：tmpdir=" + tmpBase + "，构建目录=" + buildDir);

        assertTrue(Files.isDirectory(tmpBase),
                "临时目录基准必须已经存在（否则 Files.createTempDirectory 会失败）：" + tmpBase);
    }

    /**
     * 守卫（真正咬得住的那条）：**实际建出来的**临时目录必须落在本构建目录里。
     *
     * <p><b>为什么不能只看属性值</b>：属性对了，不代表 {@code Files.createTempDirectory}
     * 会用那个基准——它读的是 {@code sun.nio.fs.TempFileHelper} 里
     * <b>类加载时缓存</b>的 {@code defaultTmpDir}（{@code java.io.tmpdir} 只在类加载时读一次）。
     *
     * <p><b>实测踩过的坑（2026-10-20 块 14）</b>：第一版把隔离写在 surefire 的
     * {@code systemPropertyVariables} 里 → 那是 <b>JVM 启动之后</b>才设的属性 →
     * 属性值确实是 {@code …/target}，但 TempFileHelper 早就把默认的
     * {@code /var/folders/…/T} 缓存下来了 → <b>下载用的临时目录仍然建在共享目录里</b>，
     * 而 {@code ModelDownloadTest} 数的是 {@code target/} → 前后都是 0 →
     * <b>那条断言恒为真</b>。后果：对照实验里的「破坏➯：下载用的临时目录不清理」
     * 咬不住（变异体写进去了、测试也跑了，但测试看不见那个残留目录）。
     * → 「四路并行的假红消失了」看起来像成功，其实是<b>把断言改空了</b>。
     *
     * <p><b>所以这条守卫只认行为</b>：真的建一个临时目录，看它落在哪儿。
     * 属性那一层（{@code 临时目录基准是本构建私有的}）留着当说明，
     * 但它单独存在时<b>证明不了任何事</b>。
     */
    @Test
    void 实际建出来的临时目录落在本构建目录里() throws IOException {
        Path buildDir = Path.of(System.getProperty("user.home", "."))
                .toAbsolutePath().normalize().getParent();
        assertTrue(buildDir != null, "user.home 必须在某个目录里");

        Path probe = Files.createTempDirectory("litemask-isolation-probe-");
        try {
            Path realBase = probe.getParent().toAbsolutePath().normalize();
            assertTrue(realBase.startsWith(buildDir),
                    "Files.createTempDirectory 实际用的基准必须在**本构建私有**的目录里，"
                            + "否则 ModelDownloadTest 数的是 target/、东西却建在共享的 "
                            + "/var/folders/…/T 里 → 那条断言会恒为真"
                            + "（这是块 14 真实踩过并修过的坑）；"
                            + "实际落在：" + realBase + "，构建目录：" + buildDir);
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
