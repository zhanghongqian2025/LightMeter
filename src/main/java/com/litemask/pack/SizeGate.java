package com.litemask.pack;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 核心包体积闸门：三层实测 + 硬指标判定。
 *
 * <p>【为什么要有这个类，而不是只在 shell 脚本里比一下】
 * 体积是本项目<b>唯一的硬指标</b>（核心包 ≤ 90 MiB），也是唯一一个
 * 「涨了不会有人发现」的东西 —— 功能坏了会红、测试会挂、用户会报错；
 * 体积涨了 3 MiB 时一切都正常，只是文档不再说得对。
 *
 * <p>【为什么以前它在 Windows 侧是空的 —— 2026-10-21 块 25 修掉的真缺陷】
 * 闸门原来只存在于 {@code tools/measure-size.sh}（bash 脚本）。
 * Windows 构建机<b>不保证有 bash</b>，而 {@code tools/package-windows.ps1}
 * <b>从来没接上闸门</b> —— 它会打印体积，然后照样打「PASS 打包并验证通过」。
 * 也就是说：90 MiB 这条硬指标<b>只在半个平台上生效</b>。
 * → 这个类把判定搬进纯 JDK，Windows 侧调用<b>同一份</b>逻辑。
 *
 * <p>【判定口径 —— 与 tools/measure-size.sh 必须逐字一致】
 * <ul>
 *   <li>三层：L0 = jlink 运行时；L1 = 应用与核心依赖（fat jar）；L2 = NER 模型包（可选）</li>
 *   <li><b>必需层缺失 → 这次的合计不成立 → 判失败</b>（fail-closed）。
 *       不拿「少了一层」的合计去比预算 —— 那等于把「没量到」说成「量过了」</li>
 *   <li>可选层缺失 → 按 0 计入，但必须<b>显式标出来</b>
 *       （默默少 7 MiB，读的人会以为「模型是免费的」）</li>
 *   <li>比较用 <b>MiB</b>（1024^2 字节）；报给人看时 MiB 与 MB 两套都列</li>
 *   <li>比较按<b>字节精确</b>比，不按四舍五入后的 MiB 比 ——
 *       舍入后再比会放过 0.005 MiB 以内的超额</li>
 * </ul>
 *
 * <p>【预算与层定义从哪来】
 * {@code tools/size-budget.properties} 是<b>唯一真源</b>：macOS 侧用 grep/sed 读它，
 * Windows 侧与本类用 java.util.Properties 读它 —— 同一个事实只在一处定义。
 */
public final class SizeGate {

    /** MiB 的字节数（1024^2）。 */
    public static final long MIB = 1048576L;

    /** MB 的字节数（1000^2）—— 只用于**报给人看**，比较一律用 MiB。 */
    public static final long MB = 1000000L;

    /**
     * 中间精度：用 DECIMAL128（34 位）做除法，再 setScale(2)。
     *
     * <p>【为什么不用 double】
     * 链的解析器（work/chain-truths.py 的 derive_sizes）用 Python 的
     * {@code Decimal} + {@code ROUND_HALF_UP} 算同样的数，链日志里印出来的
     * MiB/MB/余量会被它<b>逐字对账</b> —— 两边的舍入规则必须完全一致，
     * 否则链会报「口径不一致」而真因在两个实现的差异上。
     * → 统一走 BigDecimal，且**先精确相除再一次性舍入**
     * （先除到两位再舍入会造成「双重舍入」，边界上差 0.01）。
     */
    private static final MathContext PRECISION = new MathContext(34, RoundingMode.HALF_UP);

    private SizeGate() {
    }

    /** 一层的实测值。 */
    public static final class Layer {
        private final String id;
        private final String name;
        private final String path;
        private final long bytes;
        private final boolean present;
        private final boolean required;

        public Layer(String id, String name, String path, long bytes, boolean present,
                     boolean required) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.bytes = bytes;
            this.present = present;
            this.required = required;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String path() {
            return path;
        }

        public long bytes() {
            return bytes;
        }

        public boolean present() {
            return present;
        }

        public boolean required() {
            return required;
        }

        /** 这一行在报告里怎么写（缺失的可选层要显式标出来）。 */
        public String bytesCell() {
            if (present) {
                return Long.toString(bytes);
            }
            return required ? "MISSING（必需组件）" : "0（未找到；可选组件）";
        }
    }

    /** 判定结果。 */
    public static final class Verdict {
        private final List<Layer> layers;
        private final long totalBytes;
        private final long budgetMib;
        private final long budgetBytes;
        private final boolean complete;
        private final boolean withinBudget;
        private final List<String> problems;
        private final String totalMib;
        private final String totalMb;
        private final String slackMib;

        Verdict(List<Layer> layers, long totalBytes, long budgetMib, boolean complete,
                boolean withinBudget, List<String> problems, String totalMib, String totalMb,
                String slackMib) {
            this.layers = Collections.unmodifiableList(new ArrayList<>(layers));
            this.totalBytes = totalBytes;
            this.budgetMib = budgetMib;
            this.budgetBytes = budgetMib * MIB;
            this.complete = complete;
            this.withinBudget = withinBudget;
            this.problems = Collections.unmodifiableList(new ArrayList<>(problems));
            this.totalMib = totalMib;
            this.totalMb = totalMb;
            this.slackMib = slackMib;
        }

        public List<Layer> layers() {
            return layers;
        }

        public long totalBytes() {
            return totalBytes;
        }

        public long budgetMib() {
            return budgetMib;
        }

        public long budgetBytes() {
            return budgetBytes;
        }

        /** 必需层齐了吗（不齐 → 这次的合计不成立，不能拿去比预算）。 */
        public boolean complete() {
            return complete;
        }

        /** 在预算内吗（**前提是 complete**）。 */
        public boolean withinBudget() {
            return withinBudget;
        }

        /** 这次能不能发（完整 + 在预算内）。 */
        public boolean pass() {
            return complete && withinBudget;
        }

        public List<String> problems() {
            return problems;
        }

        public String totalMib() {
            return totalMib;
        }

        public String totalMb() {
            return totalMb;
        }

        public String slackMib() {
            return slackMib;
        }
    }

    /** 策略表里的一层定义。 */
    public static final class LayerSpec {
        private final String id;
        private final String name;
        private final boolean required;

        LayerSpec(String id, String name, boolean required) {
            this.id = id;
            this.name = name;
            this.required = required;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public boolean required() {
            return required;
        }
    }

    /** 策略表：预算 + 三层定义。 */
    public static final class Policy {
        private final long budgetMib;
        private final Map<String, LayerSpec> layers;

        Policy(long budgetMib, Map<String, LayerSpec> layers) {
            this.budgetMib = budgetMib;
            this.layers = Collections.unmodifiableMap(new LinkedHashMap<>(layers));
        }

        public long budgetMib() {
            return budgetMib;
        }

        public Map<String, LayerSpec> layers() {
            return layers;
        }

        public LayerSpec spec(String id) {
            LayerSpec s = layers.get(id);
            if (s == null) {
                throw new IllegalArgumentException("策略表里没有这一层：" + id);
            }
            return s;
        }
    }

    /**
     * 读策略表（{@code tools/size-budget.properties}）。
     *
     * <p>【为什么不接受缺项】
     * 策略表缺 {@code budget.mib} → 预算不明 → 闸门**必须失败**。
     * 退化成「默认 90」就是拿「猜的」当「知道的」——
     * 这正是块 24 在 Windows 侧端口上修掉的同一个毛病。
     */
    public static Policy loadPolicy(Path policyPath) throws IOException {
        Properties props = new Properties();
        // 【为什么必须显式给 UTF-8 —— 踩过的坑】
        //   Properties.load(InputStream) 按 **ISO-8859-1** 读（历史规范，改不了）。
        //   策略表里有中文层名（「jlink 运行时」）→ 用 InputStream 读会得到乱码
        //   （UTF-8 的字节被当成 Latin-1 解释），打出来的表里层名是一串「è¿ éæ¶」。
        //   → 用 load(Reader) + 显式 UTF-8；shell 侧读的是同一份 UTF-8 文件，两边才对得上。
        try (InputStream in = Files.newInputStream(policyPath)) {
            props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        String raw = props.getProperty("budget.mib", "").trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("策略表里没有 budget.mib：" + policyPath);
        }
        long budget;
        try {
            budget = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("策略表里的 budget.mib 不是整数：" + raw);
        }
        if (budget <= 0) {
            throw new IllegalArgumentException("策略表里的 budget.mib 必须为正：" + raw);
        }
        Map<String, LayerSpec> layers = new LinkedHashMap<>();
        for (String id : new String[] {"L0", "L1", "L2"}) {
            String name = props.getProperty("layer." + id + ".name", "").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("策略表里没有 layer." + id + ".name");
            }
            String req = props.getProperty("layer." + id + ".required", "").trim();
            if (req.isEmpty()) {
                throw new IllegalArgumentException("策略表里没有 layer." + id + ".required");
            }
            boolean required;
            if ("true".equalsIgnoreCase(req)) {
                required = true;
            } else if ("false".equalsIgnoreCase(req)) {
                required = false;
            } else {
                throw new IllegalArgumentException(
                        "策略表里 layer." + id + ".required 只能是 true/false：" + req);
            }
            layers.put(id, new LayerSpec(id, name, required));
        }
        return new Policy(budget, layers);
    }

    /**
     * 判定。
     *
     * <p>【规则】
     * <ul>
     *   <li>必需层缺失 → complete=false → 判失败，<b>不</b>拿少了一层的合计比预算</li>
     *   <li>可选层缺失 → 记 0，但要显式标出来</li>
     *   <li>比较按<b>字节精确</b>：totalBytes &gt; budgetMib * MIB → 超</li>
     * </ul>
     */
    public static Verdict evaluate(List<Layer> layers, long budgetMib) {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("三层不能为空");
        }
        if (budgetMib <= 0) {
            throw new IllegalArgumentException("预算必须为正数：" + budgetMib);
        }
        long total = 0;
        boolean complete = true;
        List<String> problems = new ArrayList<>();
        for (Layer l : layers) {
            if (l == null) {
                throw new IllegalArgumentException("层不能为 null");
            }
            if (l.bytes() < 0) {
                throw new IllegalArgumentException("层的字节数不能为负：" + l.id());
            }
            if (!l.present()) {
                if (l.required()) {
                    complete = false;
                    problems.add("必需组件缺失：" + l.id() + " " + l.name()
                            + "（" + l.path() + "）");
                }
                continue;
            }
            total += l.bytes();
        }
        long budgetBytes = budgetMib * MIB;
        boolean within = complete && total <= budgetBytes;
        if (complete && !within) {
            problems.add("超出预算：" + mib(total) + " MiB > " + budgetMib + " MiB");
        }
        BigDecimal totalMibExact = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(MIB),
                PRECISION);
        BigDecimal slackExact = BigDecimal.valueOf(budgetMib).subtract(totalMibExact);
        return new Verdict(layers, total, budgetMib, complete, within, problems,
                q2(totalMibExact), mb(total), q2(slackExact));
    }

    /** 字节数 → MiB（两位小数，四舍五入）。 */
    public static String mib(long bytes) {
        return q2(BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(MIB), PRECISION));
    }

    /** 字节数 → MB（两位小数，四舍五入）。 */
    public static String mb(long bytes) {
        return q2(BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(MB), PRECISION));
    }

    /** 四舍五入到两位小数（HALF_UP；与链的 q2 一致）。 */
    public static String q2(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
