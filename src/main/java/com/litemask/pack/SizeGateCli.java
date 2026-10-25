package com.litemask.pack;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 体积闸门的命令行入口：给打包脚本用的**纯 JDK 单文件入口**。
 *
 * <p>【为什么要有它】
 * {@code tools/measure-size.sh} 是 bash —— <b>Windows 构建机不保证有 bash</b>，
 * 所以 {@code tools/package-windows.ps1} 一直没能接上体积闸门
 * （它会打印体积，然后照样打「PASS 打包并验证通过」）。
 * → 判定逻辑放在 {@link SizeGate}（纯 JDK），两个平台调<b>同一份</b>判定。
 *
 * <p>【用法】
 * <pre>
 * java -cp target/desens-core.jar com.litemask.pack.SizeGateCli \
 *      --policy tools/size-budget.properties \
 *      --l0-bytes 88068060 --l0-path target/runtime-image \
 *      --l1-bytes 13528468 --l1-path target/desens-core.jar \
 *      --l2-bytes 7570000  --l2-path models/ner
 * </pre>
 *
 * <p>【退出码】
 * <ul>
 *   <li>0 = 完整且在预算内</li>
 *   <li>1 = 超预算，或必需组件缺失（这次的合计不成立）</li>
 *   <li>2 = 参数/策略表有问题（<b>不是</b>「通过」）</li>
 * </ul>
 *
 * <p>【输出的形状为什么要和 measure-size.sh 对齐】
 * 链日志里印的体积会被 {@code work/chain-truths.py} 逐字对账
 * （三层行、合计行、预算行、「在预算内」那行），
 * 而 macOS 侧与 Windows 侧是<b>两套实现</b> →
 * 两边的形状必须对得上，守卫（work/check-size-gate-agreement.py）才比得了。
 */
public final class SizeGateCli {

    private SizeGateCli() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.exit(execute(args, out));
    }

    /**
     * 跑一次闸门，返回**进程退出码**。
     *
     * <p>【为什么要把错误处理收在这里】
     * 参数/策略表有问题时，{@code main} 必须给<b>非 0</b> 的退出码
     * （2 = 参数/策略表有问题，<b>不是</b>「通过」），
     * 而不能让异常直接冒出去 —— 冒出去的话，
     * 「闸门自己坏了」和「包超预算」在调用方眼里就分不开了。
     * → 单测也调这个方法（而不是 main），这样这条契约能被测到。
     */
    static int execute(String[] args, PrintStream out) {
        try {
            return run(args, out);
        } catch (Exception e) {
            out.println("X 体积闸门自己出错了（这不算「通过」）：" + e);
            return 2;
        }
    }

    static int run(String[] args, PrintStream out) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("看不懂的参数：" + a);
            }
            String key = a.substring(2);
            if (key.endsWith("-missing")) {
                missing.add(key.substring(0, key.length() - "-missing".length()));
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("参数 " + a + " 缺值");
            }
            opt.put(key, args[++i]);
        }

        Path policyPath = Paths.get(opt.getOrDefault("policy", "tools/size-budget.properties"));
        if (!Files.isRegularFile(policyPath)) {
            throw new IllegalArgumentException("找不到策略表：" + policyPath.toAbsolutePath());
        }
        SizeGate.Policy policy = SizeGate.loadPolicy(policyPath);
        long budgetMib = policy.budgetMib();
        if (opt.containsKey("budget-mib")) {
            budgetMib = Long.parseLong(opt.get("budget-mib").trim());
        }

        List<SizeGate.Layer> layers = new ArrayList<>();
        for (String id : new String[] {"L0", "L1", "L2"}) {
            SizeGate.LayerSpec spec = policy.spec(id);
            String path = opt.getOrDefault(id.toLowerCase() + "-path", "");
            if (missing.contains(id.toLowerCase())) {
                layers.add(new SizeGate.Layer(id, spec.name(), path, 0L, false, spec.required()));
                continue;
            }
            String raw = opt.get(id.toLowerCase() + "-bytes");
            if (raw == null) {
                throw new IllegalArgumentException(
                        "缺 " + id + " 的字节数（要么给 --" + id.toLowerCase()
                                + "-bytes，要么显式说它缺失：--" + id.toLowerCase() + "-missing）");
            }
            long bytes;
            try {
                bytes = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(id + " 的字节数不是整数：" + raw);
            }
            if (bytes < 0) {
                throw new IllegalArgumentException(id + " 的字节数不能为负：" + raw);
            }
            layers.add(new SizeGate.Layer(id, spec.name(), path, bytes, true, spec.required()));
        }

        SizeGate.Verdict v = SizeGate.evaluate(layers, budgetMib);

        // ---- 表（形状与 tools/measure-size.sh 对齐）----
        out.printf("%-22s %-46s %12s %10s %10s%n", "层", "路径", "字节", "MiB", "MB");
        for (SizeGate.Layer l : v.layers()) {
            if (!l.present()) {
                // 缺失的层**只印三列** —— 与 measure-size.sh 的形状一致：
                // 「字节」那一格里带说明（「0（未找到；可选组件）」），
                // 后面没有 MiB/MB 两个数，链的解析器因此**不会**把它当成一层体积
                // （链要求三层齐；少一层 → 解析器报「没解析出三层体积」→ 链失败）。
                out.printf("%-22s %-46s %12s%n", l.id() + " " + l.name(), l.path(),
                        l.bytesCell());
                continue;
            }
            out.printf("%-22s %-46s %12s %10s %10s%n",
                    l.id() + " " + l.name(), l.path(), l.bytesCell(),
                    SizeGate.mib(l.bytes()), SizeGate.mb(l.bytes()));
        }
        out.printf("%-22s %-46s %12s %10s %10s%n", "合计", "", Long.toString(v.totalBytes()),
                v.totalMib(), v.totalMb());
        // 预算行：两个口径都列，标签写清楚比较用哪个。
        out.printf("预算 %d MB = %s MiB = %d 字节（比较用 MiB）%n",
                v.budgetMib(), SizeGate.q2(BigDecimal.valueOf(v.budgetMib())),
                v.budgetBytes());

        out.println();
        if (!v.complete()) {
            out.println("X 有必需组件缺失，这次量出来的合计不成立");
            for (String p : v.problems()) {
                out.println("  - " + p);
            }
            return 1;
        }
        if (!v.withinBudget()) {
            out.printf("X 超出预算：%s MiB > %d MB%n", v.totalMib(), v.budgetMib());
            return 1;
        }
        out.printf("PASS 在预算内：%s MiB（余量 %s MiB）%n", v.totalMib(), v.slackMib());
        return 0;
    }
}
