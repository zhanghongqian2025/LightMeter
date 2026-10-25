package com.litemask.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心包体积闸门：判定必须**精确**、**fail-closed**，而且两侧口径必须一致。
 *
 * <p><b>这个类防的是什么缺陷（2026-10-21 块 25）</b>：
 * 体积闸门原来只存在于 {@code tools/measure-size.sh}（bash），
 * 而 <b>Windows 构建机不保证有 bash</b> →
 * {@code tools/package-windows.ps1} <b>从来没接上闸门</b>：
 * 它会打印体积，然后照样打「PASS 打包并验证通过」。
 * 也就是说：本项目<b>唯一的硬指标只在半个平台上生效</b>。
 *
 * <p><b>顺带锁住的另外三个真问题</b>：
 * <ol>
 *   <li><b>按四舍五入后的 MiB 比预算</b> → 放过 0.005 MiB 以内的超额
 *       （旧 bash 写法：90.0044 MiB 舍入成 90.00 → 判「在预算内」）；</li>
 *   <li><b>余量按舍入后的 MiB 再减</b> → 与链的解析器（精确相减）差 0.01
 *       → 链会报「链日志里余量印的是 …，按预算算是 …」；</li>
 *   <li><b>策略表按 ISO-8859-1 读</b>（Properties 的历史规范）→ 中文层名变乱码。</li>
 * </ol>
 */
class SizeGateTest {

    private static final long BUDGET = 90L;
    private static final long BUDGET_BYTES = BUDGET * SizeGate.MIB;

    private static SizeGate.Layer layer(String id, String name, long bytes, boolean present,
                                        boolean required) {
        return new SizeGate.Layer(id, name, "/" + id, bytes, present, required);
    }

    private static List<SizeGate.Layer> threeLayers(long l0, long l1, long l2) {
        List<SizeGate.Layer> ls = new ArrayList<>();
        ls.add(layer("L0", "jlink 运行时", l0, true, true));
        ls.add(layer("L1", "应用与核心依赖", l1, true, true));
        ls.add(layer("L2", "NER 模型包", l2, true, false));
        return ls;
    }

    // ---------------------------------------------------------------- 判定

    @Test
    @DisplayName("预算内就通过，合计与余量都对")
    void withinBudgetPasses() {
        SizeGate.Verdict v = SizeGate.evaluate(threeLayers(51559749L, 13528468L, 14160149L),
                BUDGET);
        assertTrue(v.complete(), "三层齐 → 合计成立");
        assertTrue(v.withinBudget(), "75.58 MiB < 90 MiB → 在预算内");
        assertTrue(v.pass());
        assertEquals(79248366L, v.totalBytes());
        assertEquals("75.58", v.totalMib());
        assertEquals("79.25", v.totalMb());
        assertEquals("14.42", v.slackMib());
    }

    @Test
    @DisplayName("恰好等于预算：94371840 字节（90 MiB）→ 通过（不多不少）")
    void exactlyAtBudgetPasses() {
        SizeGate.Verdict v = SizeGate.evaluate(threeLayers(BUDGET_BYTES, 0L, 0L), BUDGET);
        assertTrue(v.pass(), "正好 90 MiB 必须在预算内（多一字节才算超）");
        assertEquals("90.00", v.totalMib());
        assertEquals("0.00", v.slackMib());
    }

    @Test
    @DisplayName("超预算 1 字节 → 不通过（按字节精确比，不按四舍五入后的 MiB 比）")
    void oneByteOverFails() {
        SizeGate.Verdict v = SizeGate.evaluate(threeLayers(BUDGET_BYTES + 1L, 0L, 0L), BUDGET);
        assertFalse(v.withinBudget(),
                "超 1 字节就是超了 —— 旧写法会把 90.0000009 MiB 舍成 90.00 然后放过");
        assertFalse(v.pass());
        assertTrue(v.problems().toString().contains("超出预算"), "要说清为什么失败");
    }

    @Test
    @DisplayName("超预算 4643 字节（舍入后仍是 90.00 MiB）→ 不通过")
    void overButRoundsToBudgetStillFails() {
        // 90.0044 MiB → 四舍五入是 90.00 MiB；旧 bash 比的就是这个舍入值 → 会放过。
        long total = BUDGET_BYTES + 4643L;
        assertEquals("90.00", SizeGate.mib(total), "前提：这个值舍入后确实看着没超");
        SizeGate.Verdict v = SizeGate.evaluate(threeLayers(total, 0L, 0L), BUDGET);
        assertFalse(v.pass(), "看着没超也算超 —— 闸门比的是字节，不是显示用的那个数");
    }

    @Test
    @DisplayName("必需层缺失 → 判失败，且不拿少了一层的合计去比预算")
    void missingRequiredLayerFails() {
        List<SizeGate.Layer> ls = new ArrayList<>();
        ls.add(layer("L0", "jlink 运行时", 0L, false, true));
        ls.add(layer("L1", "应用与核心依赖", 13528468L, true, true));
        ls.add(layer("L2", "NER 模型包", 0L, false, false));
        SizeGate.Verdict v = SizeGate.evaluate(ls, BUDGET);
        assertFalse(v.complete(), "必需层缺了 → 这次的合计不成立");
        assertFalse(v.pass(), "合计不成立就不许判通过");
        assertFalse(v.withinBudget(), "少了一层的合计<b>不许</b>拿去比预算");
        assertTrue(v.problems().toString().contains("L0"), "要说清缺的是哪一层");
    }

    @Test
    @DisplayName("可选层缺失 → 仍然通过（模型是可选的），但它按 0 计入")
    void missingOptionalLayerStillPasses() {
        List<SizeGate.Layer> ls = new ArrayList<>();
        ls.add(layer("L0", "jlink 运行时", 51559749L, true, true));
        ls.add(layer("L1", "应用与核心依赖", 13528468L, true, true));
        ls.add(layer("L2", "NER 模型包", 0L, false, false));
        SizeGate.Verdict v = SizeGate.evaluate(ls, BUDGET);
        assertTrue(v.complete(), "模型包缺失不影响合计成立");
        assertTrue(v.pass(), "没装模型也要能发包（方案 B）");
        assertEquals(65088217L, v.totalBytes(), "51,559,749 + 13,528,468");
        assertTrue(v.problems().isEmpty(), "可选层缺失不是失败：" + v.problems());
    }

    @Test
    @DisplayName("可选层缺失时，报告里必须显式标出来（默默少 7 MiB 不行）")
    void missingOptionalLayerIsVisible() {
        SizeGate.Layer l = layer("L2", "NER 模型包", 0L, false, false);
        assertEquals("0（未找到；可选组件）", l.bytesCell(),
                "缺失的可选层要显式写出来 —— 读的人会以为模型是免费的");
    }

    @Test
    @DisplayName("负字节数 → 当场报错（不许负数悄悄抵消掉一层）")
    void negativeBytesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SizeGate.evaluate(threeLayers(-1L, 0L, 0L), BUDGET));
    }

    @Test
    @DisplayName("预算不是正数 → 当场报错")
    void nonPositiveBudgetRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SizeGate.evaluate(threeLayers(1L, 1L, 1L), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> SizeGate.evaluate(threeLayers(1L, 1L, 1L), -90L));
    }

    @Test
    @DisplayName("层列表为空 → 当场报错（空表不许判通过）")
    void emptyLayersRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SizeGate.evaluate(new ArrayList<>(), BUDGET));
    }

    // ---------------------------------------------------------------- 显示口径

    @Test
    @DisplayName("MiB / MB 的显示口径与链的 q2 一致")
    void displayMatchesChain() {
        // 链（work/chain-truths.py 的 derive_sizes）用 Decimal + ROUND_HALF_UP。
        assertEquals("49.17", SizeGate.mib(51559749L));
        assertEquals("51.56", SizeGate.mb(51559749L));
        assertEquals("12.90", SizeGate.mib(13528468L));
        assertEquals("13.53", SizeGate.mb(13528468L));
        assertEquals("90.00", SizeGate.mib(BUDGET_BYTES));
    }

    @Test
    @DisplayName("余量按**精确值**减，不是按舍入后的 MiB 再减（否则与链差 0.01）")
    void slackUsesExactValue() {
        // 79,036,416 B = 75.38 MiB（精确 75.3849…）
        //   精确相减 → 14.63（链的做法）
        //   舍入后相减 → 90 - 75.38 = 14.62（旧 bash 的做法 → 链会报「余量对不上」）
        long total = 79036416L;
        assertEquals("75.38", SizeGate.mib(total));
        SizeGate.Verdict v = SizeGate.evaluate(threeLayers(total, 0L, 0L), BUDGET);
        assertEquals("14.63", v.slackMib(),
                "余量必须从字节数精确算；按显示用的舍入值再减会差 0.01");
    }

    // ---------------------------------------------------------------- 策略表

    private static Path writePolicy(Path dir, String body) throws Exception {
        Path p = dir.resolve("size-budget.properties");
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static final String GOOD_POLICY =
            "budget.mib=90\n"
            + "layer.L0.name=jlink 运行时\n"
            + "layer.L0.required=true\n"
            + "layer.L1.name=应用与核心依赖\n"
            + "layer.L1.required=true\n"
            + "layer.L2.name=NER 模型包\n"
            + "layer.L2.required=false\n";

    @Test
    @DisplayName("策略表按 UTF-8 读：中文层名原样读出（不乱码）")
    void policyIsReadAsUtf8(@TempDir Path dir) throws Exception {
        Path p = writePolicy(dir, GOOD_POLICY);
        SizeGate.Policy pol = SizeGate.loadPolicy(p);
        assertEquals("jlink 运行时", pol.spec("L0").name(),
                "Properties.load(InputStream) 按 ISO-8859-1 读 → 中文层名会变乱码");
        assertEquals("应用与核心依赖", pol.spec("L1").name());
        assertEquals("NER 模型包", pol.spec("L2").name());
        assertEquals(90L, pol.budgetMib());
        assertTrue(pol.spec("L0").required());
        assertFalse(pol.spec("L2").required());
    }

    @Test
    @DisplayName("策略表缺 budget.mib → 当场报错（不许默认 90）")
    void policyWithoutBudgetRejected(@TempDir Path dir) throws Exception {
        Path p = writePolicy(dir, GOOD_POLICY.replace("budget.mib=90\n", ""));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> SizeGate.loadPolicy(p));
        assertTrue(e.getMessage().contains("budget.mib"), "要说清缺哪一项：" + e.getMessage());
    }

    @Test
    @DisplayName("策略表缺某一层的 name 或 required → 当场报错")
    void policyWithMissingLayerFieldRejected(@TempDir Path dir) throws Exception {
        Path noName = writePolicy(dir, GOOD_POLICY.replace("layer.L1.name=应用与核心依赖\n", ""));
        assertThrows(IllegalArgumentException.class, () -> SizeGate.loadPolicy(noName));
        Path noReq = writePolicy(dir, GOOD_POLICY.replace("layer.L2.required=false\n", ""));
        assertThrows(IllegalArgumentException.class, () -> SizeGate.loadPolicy(noReq));
    }

    @Test
    @DisplayName("策略表里的 required 只能写 true/false（写错就报错，不许当 false）")
    void policyWithBadRequiredRejected(@TempDir Path dir) throws Exception {
        Path p = writePolicy(dir, GOOD_POLICY.replace("layer.L2.required=false",
                "layer.L2.required=maybe"));
        assertThrows(IllegalArgumentException.class, () -> SizeGate.loadPolicy(p));
    }

    // ---------------------------------------------------------------- 输出形状

    @Test
    @DisplayName("CLI 输出的形状能被链的解析器认（三层行 / 合计行 / 预算行 / 在预算内那行）")
    void cliOutputIsParseableByChainRegexes(@TempDir Path dir) throws Exception {
        Path p = writePolicy(dir, GOOD_POLICY);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int rc;
        try (PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            rc = SizeGateCli.execute(new String[] {
                    "--policy", p.toString(),
                    "--l0-bytes", "51559749", "--l0-path", "/x/rt",
                    "--l1-bytes", "13528468", "--l1-path", "/x/jar",
                    "--l2-bytes", "14160149", "--l2-path", "/x/ner"}, out);
        }
        String text = buf.toString(StandardCharsets.UTF_8);
        assertEquals(0, rc, "在预算内 → 退出码 0：" + text);

        // 这几条正则与 work/chain-truths.py 里的一字不差（链要认这些行）。
        Pattern layerRow = Pattern.compile(
                "^(L[012])\\s+\\S.*?\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s*$", Pattern.MULTILINE);
        Matcher lm = layerRow.matcher(text);
        int rows = 0;
        while (lm.find()) {
            rows++;
        }
        assertEquals(3, rows, "三层行必须都能被链的解析器认：" + text);

        Matcher total = Pattern.compile("^合计\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s*$",
                Pattern.MULTILINE).matcher(text);
        assertTrue(total.find(), "合计行必须能被链的解析器认：" + text);
        assertEquals("79248366", total.group(1));
        assertEquals("75.58", total.group(2));
        assertEquals("79.25", total.group(3));

        Matcher budget = Pattern.compile("预算 (\\d+) MB = ([\\d.]+) MiB", Pattern.MULTILINE)
                .matcher(text);
        assertTrue(budget.find(), "预算行必须能被链的解析器认：" + text);
        assertEquals("90", budget.group(1));
        assertEquals("90.00", budget.group(2));

        Matcher pass = Pattern.compile("在预算内：([\\d.]+) MiB（余量 ([\\d.]+) MiB）")
                .matcher(text);
        assertTrue(pass.find(), "「在预算内」那行必须能被链的解析器认：" + text);
        assertEquals("75.58", pass.group(1));
        assertEquals("14.42", pass.group(2));
    }

    @Test
    @DisplayName("CLI：超预算 → 退出码 1；缺参数 → 退出码 2（都不是「通过」）")
    void cliExitCodes(@TempDir Path dir) throws Exception {
        Path p = writePolicy(dir, GOOD_POLICY);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int rc;
        try (PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            rc = SizeGateCli.execute(new String[] {
                    "--policy", p.toString(),
                    "--l0-bytes", Long.toString(BUDGET_BYTES + 1), "--l0-path", "/x/rt",
                    "--l1-bytes", "1", "--l1-path", "/x/jar",
                    "--l2-bytes", "1", "--l2-path", "/x/ner"}, out);
        }
        assertEquals(1, rc, "超预算 → 1（不许报 PASS）");
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("超出预算"));

        buf = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            rc = SizeGateCli.execute(new String[] {
                    "--policy", p.toString(),
                    "--l0-bytes", "1", "--l0-path", "/x/rt"}, out);
        }
        assertEquals(2, rc, "缺层的字节数 → 2（不许猜一个）");
    }

    @Test
    @DisplayName("CLI：层缺失必须显式声明（--lN-missing），不许默默当 0")
    void cliRequiresExplicitMissing(@TempDir Path dir) throws Exception {
        Path p = writePolicy(dir, GOOD_POLICY);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int rc;
        try (PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            rc = SizeGateCli.execute(new String[] {
                    "--policy", p.toString(),
                    "--l0-bytes", "1", "--l0-path", "/x/rt",
                    "--l1-bytes", "1", "--l1-path", "/x/jar",
                    "--l2-missing"}, out);
        }
        assertEquals(0, rc, "可选层显式说缺失 → 按 0 计入，可以通过：" + buf);
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("0（未找到；可选组件）"),
                "缺失要显式打出来");
    }

    @Test
    @DisplayName("恰好 .xx5 的字节数：按 HALF_UP 舍入（不是 C 库的 double，也不是银行家舍入）")
    void tieRoundingUsesHalfUp() {
        // 131,072 B = 0.125 MiB
        //   HALF_UP（链的 Decimal、Java 的 BigDecimal）→ 0.13
        //   awk 的 printf "%.2f"（C 库 double）        → 0.12
        //   Python 的 round()（银行家舍入）            → 0.12
        // 三方对账守卫（work/check-size-gate-agreement.py）用**同一个数**验：
        // 老算法在这一档确实会错 —— 所以这一档才有判别力。
        assertEquals("0.13", SizeGate.mib(131072L),
                "0.125 MiB 必须按 HALF_UP 进位到 0.13；用 double 舍入会打成 0.12");

        // 62.125 MiB：合计落在 tie 上
        assertEquals("62.13", SizeGate.mib(65142784L));

        // 79.255 MB：MB 口径的 tie（分母是 10^6，不是 2^20）
        assertEquals("79.26", SizeGate.mb(79255000L));

        // 余量落在 tie 上：90 - 89.875 = 0.125 MiB
        //   注意这里是**精确值相减后**再舍入，不是「先把 89.875 舍成 89.88 再减」
        //   （后者会得 0.12）。
        SizeGate.Verdict v = SizeGate.evaluate(threeLayers(94240768L, 0L, 0L), BUDGET);
        assertEquals("0.13", v.slackMib(),
                "余量必须在精确值上相减再一次性舍入");
    }

    @Test
    @DisplayName("层顺序与数量按策略表来（读错就红，不许恒真）")
    void policyDrivesLayers(@TempDir Path dir) throws Exception {
        Path p = writePolicy(dir, GOOD_POLICY);
        SizeGate.Policy pol = SizeGate.loadPolicy(p);
        assertEquals(3, pol.layers().size(), "策略表必须正好三层（L0/L1/L2）");
        assertEquals(Arrays.asList("L0", "L1", "L2"),
                new ArrayList<>(pol.layers().keySet()), "三层的顺序也是口径的一部分");
    }
}
