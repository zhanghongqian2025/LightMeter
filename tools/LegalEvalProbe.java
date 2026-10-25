import com.litemask.core.EntitySource;
import com.litemask.core.Match;
import com.litemask.ner.NerEntitySource;
import com.litemask.ner.NerModelPackage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 法律文书域留出集的评测器（M3-3）。
 *
 * <p>用法：{@code java -cp <jar> LegalEvalProbe <模型包目录> <BIO 文件>}
 *
 * <h2>为什么不在 Python 侧跑</h2>
 *
 * <p>训练脚本里的 {@code evaluate()} 用的是**内存里的 torch 模型**。
 * 那条路径能回答「训练收敛得怎么样」，回答不了「交付出去的那个模型包行不行」。
 * 本项目已经吃过这个亏的实体版：训练日志打印 F1 0.8397，
 * 拿导出的 {@code .onnx} + {@code vocab.txt} 重新打分只有 0.1209——
 * 两个文件各自都合法，合起来才不合法。
 *
 * <p>所以这里走的是**只读产物**的路径：磁盘上的模型包目录 → 七道闸 →
 * ONNX Runtime → {@code SequenceDecoder} → {@code NerEntitySource}。
 * 验收看的必须是这条路径上的数字，因为用户拿到的是这条路径的产物。
 *
 * <h2>标尺为什么独立</h2>
 *
 * <p>BIO→span 的转换、预测结果的归一化、P/R/F1 的计算，这里各写了一份，
 * <b>没有</b>复用 {@code SlidingWindowNer} / {@code SequenceDecoder} / {@code NerRecognizer}
 * 里的任何一行。被测的是「模型识别得准不准」，
 * 标尺若与被测共用实现，测出来的就是「实现自洽」，不是「实现正确」。
 *
 * <h2>它证明不了什么</h2>
 *
 * <p>评测集是模板合成的，句式多样性远低于真实法律文书，
 * 所以这个 F1 是<b>上界估计</b>，不是现场错误率。
 * 详见 {@code tools/gen_legal_eval.py} 的模块文档。
 */
public final class LegalEvalProbe {

    /** 一条标注：起止 + 类型。类型用金标侧的 PER/ORG，与规则键分开。 */
    private record Span(int start, int end, String kind) {
    }

    private record Sentence(String text, List<Span> gold) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("用法: LegalEvalProbe <模型包目录> <BIO 文件>");
            System.exit(2);
        }
        Path pkgDir = Path.of(args[0]);
        Path bioFile = Path.of(args[1]);

        List<Sentence> sents = readBio(bioFile);
        int goldTotal = sents.stream().mapToInt(s -> s.gold().size()).sum();
        if (goldTotal == 0) {
            // 前提不成立就报错，不静默报一个「F1 = 0」或者「F1 = 1」。
            System.err.println("X " + bioFile + " 里没有金标实体，拒绝评测");
            System.exit(1);
        }

        // 模型包自带的词表，用来判断一条误报里是否含未登录字（走 UNK embedding）。
        // 读的是**交付产物**里的那份，不是训练脚本内存里的那份。
        Set<String> unlogged = loadUnlogged(pkgDir.resolve("vocab.txt"));

        long t0 = System.nanoTime();
        try (NerModelPackage pkg = NerModelPackage.load(pkgDir)) {
            long loadMs = (System.nanoTime() - t0) / 1_000_000;
            EntitySource ner = pkg.entitySource();

            int windowChars = pkg.manifest().window().chars();
            int overlap = pkg.manifest().window().overlap();
            int tp = 0;
            int fp = 0;
            int fn = 0;
            // 分桶：>256 字走滑窗，与短句不是同一条推理路径。
            Map<String, int[]> buckets = new TreeMap<>();
            // 分类别：PER 与 ORG 的短板常常不是同一个原因，混在一起看不出该修哪个。
            Map<String, int[]> byKind = new TreeMap<>();
            byKind.put("PER", new int[3]);
            byKind.put("ORG", new int[3]);
            int[] missed = new int[2]; // [0]=完全没认 [1]=边界重叠但切错（含异类型重叠，见下）
            // ---- 误差结构分解（v6 的诊断依据，见下） ----
            //
            // 只报「F1 + 8 条样本」的时候，下一步该做什么有三种可能，
            // 而它们要修的地方完全不同：加模板 / 修边界 / 扩词表。
            // v5 就是没分辨这三种、凭感觉加了三个模板，结果 PER 召回塌了。
            //
            // 所以这里把 fn 与 fp 拆成**互斥且穷尽**的分桶，且只输出聚合计数、
            // 不输出表面串——见 §为什么这里只印计数。
            int[] missKind = new int[3]; // 0=完全没认 1=同类型重叠 2=仅异类型重叠
            // 同上，但按类别分开。合并着看会掩盖靶子：
            // 人名整体召回低，可能是「大量漏检」也可能是「大量切错」，修法不同。
            Map<String, int[]> missKindByType = new TreeMap<>();
            missKindByType.put("PER", new int[3]);
            missKindByType.put("ORG", new int[3]);
            int[] cutDir = new int[7];   // 边界切错时 预测长-金标长，索引 [-3..+3]
            int[] fpKind = new int[5];   // 0=与金标重叠(切长) 1=紧邻金标(外扩) 2=孤立 3=孤立且含未登录字 4=与「已精确命中」的金标重叠(重复上报)
            Map<Integer, int[]> recallByLen = new TreeMap<>(); // 金标长度 -> {命中, 总数}
            // 长句里「实体起点离最近切线多远」-> {命中, 总数}。
            //
            // 用来回答一个二选一的问题：长句召回低是**滑窗把实体切没了**，
            // 还是**模型在长上下文里本来就认不准**。
            // 两者的修法完全不同，而只看「长句 R 0.753 / 短句 R 0.806」分不出来。
            Map<String, int[]> recallByCutDist = new TreeMap<>();
            Map<String, String> errorSamples = new LinkedHashMap<>();

            for (Sentence s : sents) {
                String bucket = s.text().length() > 256 ? "长(>256)" : "短(<=256)";
                int[] acc = buckets.computeIfAbsent(bucket, k -> new int[3]);
                List<Span> pred = toSpans(ner.collect(s.text()));
                int[] r = score(s.gold(), pred);
                acc[0] += r[0];
                acc[1] += r[1];
                acc[2] += r[2];
                tp += r[0];
                fp += r[1];
                fn += r[2];
                for (String kind : new String[] {"PER", "ORG"}) {
                    int[] a = byKind.get(kind);
                    int[] rk = score(s.gold().stream().filter(x -> x.kind().equals(kind)).toList(),
                            pred.stream().filter(x -> x.kind().equals(kind)).toList());
                    a[0] += rk[0];
                    a[1] += rk[1];
                    a[2] += rk[2];
                }
                missed[0] += countMissedAs(s.gold(), pred, false);
                missed[1] += countMissedAs(s.gold(), pred, true);
                decomposeMiss(s, pred, missKind, cutDir, recallByLen);
                decomposeMissByType(s, pred, missKindByType);
                decomposeFp(s, pred, fpKind, unlogged);
                if (s.text().length() > windowChars) {
                    // 只喂长句：短句起点不会绕回 modulo 位置，
                    // 混进来会造出一个「贴着切线」的假象。
                    bucketByCutDistance(s, pred, recallByCutDist, windowChars, overlap);
                }

                if (r[1] > 0 || r[2] > 0) {
                    if (errorSamples.size() < 8) {
                        errorSamples.put(excerpt(s.text()),
                                String.format("桶=%s tp=%d fp=%d fn=%d", bucket, r[0], r[1], r[2]));
                    }
                }
            }

            System.out.printf("模型包 %s（加载 %d ms）%n", pkgDir, loadMs);
            System.out.printf("评测集 %s：%d 句 / 金标实体 %d%n", bioFile.getFileName(), sents.size(), goldTotal);
            System.out.printf("实体级严格匹配：P %.4f R %.4f F1 %.4f（tp=%d fp=%d fn=%d）%n",
                    prf(tp, fp, fn)[0], prf(tp, fp, fn)[1], prf(tp, fp, fn)[2], tp, fp, fn);
            for (var e : buckets.entrySet()) {
                int[] a = e.getValue();
                double[] p = prf(a[0], a[1], a[2]);
                System.out.printf("  %-10s P %.4f R %.4f F1 %.4f（tp=%d fp=%d fn=%d / 金标 %d）%n",
                        e.getKey(), p[0], p[1], p[2], a[0], a[1], a[2], a[0] + a[2]);
            }

            // ---- 诊断：漏掉的实体里，多少是「完全没认」，多少是「边界切错」 ----
            //
            // 这两类的修法完全不同：
            //   * 完全没认 → 模型没在这个上下文里学会这个类型（训练覆盖缺口）
            //   * 边界切错 → 模型认出了大致位置，但起止偏了（切分策略/CRF 转移问题）
            // 只报一个 F1 分不出这两者，而它们的下一步完全不同。
            System.out.printf("%n分类别：%-6s %6s %6s %6s %6s%n", "类型", "tp", "fp", "fn", "F1");
            for (String kind : new String[] {"PER", "ORG"}) {
                int[] a = byKind.get(kind);
                double[] p = prf(a[0], a[1], a[2]);
                System.out.printf("  %-6s %6d %6d %6d %6.4f%n", kind, a[0], a[1], a[2], p[2]);
            }
            System.out.printf("%n漏检归因（旧口径，只看字符重叠）：完全没认出 %d 个 /"
                    + " 有重叠但起止或类型不符 %d 个%n", missed[0], missed[1]);
            System.out.printf("  —— 新的互斥三分：完全没认 %d / 同类型重叠(切边界) %d /"
                    + " 仅异类型重叠(认错类别) %d%n",
                    missKind[0], missKind[1], missKind[2]);

            // ---- 对账：任何分桶之和必须等于总数，对不上直接抛 ----
            //
            // 不加这条的话，一个分桶写错（比如「紧邻」的定义里 off-by-one）
            // 只会让某一栏数字偏小，而**不会有任何报错**。
            // 三处分桶都对着总数，才能相信这些数字。
            //
            // 这条断言已经抓到过一次真实的不一致：新写的方向分桶 116，
            // 老口径的「边界重叠」117，差的正是「人名金标被机构预测盖住」那类。
            int missSum = missKind[0] + missKind[1] + missKind[2];
            if (missSum != fn) {
                throw new IllegalStateException("漏检三分桶之和 " + missSum
                        + " != 漏检总数 " + fn);
            }
            if (missKind[0] != missed[0] || missKind[1] + missKind[2] != missed[1]) {
                throw new IllegalStateException("新旧两套漏检口径不自洽："
                        + "新 [完全没认,同类型,异类型]=" + Arrays.toString(missKind)
                        + " vs 旧 [完全没认,有重叠]=" + Arrays.toString(missed));
            }
            int cutSum = 0;
            for (int v : cutDir) {
                cutSum += v;
            }
            if (cutSum != missKind[1]) {
                throw new IllegalStateException("边界切错方向分桶之和 " + cutSum
                        + " != 同类型重叠总数 " + missKind[1]);
            }
            int fpSum = 0;
            for (int v : fpKind) {
                fpSum += v;
            }
            if (fpSum != fp) {
                throw new IllegalStateException("误报分桶之和 " + fpSum + " != 误报总数 " + fp);
            }
            int lenTotal = 0;
            int lenHit = 0;
            for (int[] a : recallByLen.values()) {
                lenTotal += a[1];
                lenHit += a[0];
            }
            if (lenTotal != goldTotal) {
                throw new IllegalStateException("按长度分层的金标之和 " + lenTotal
                        + " != 金标实体总数 " + goldTotal);
            }

            System.out.printf("%n边界切错的方向（预测长度 - 金标长度）：%n");
            String[] cutName = {"-3(切短)", "-2(切短)", "-1(切短)", "0(等长错位)",
                    "+1(切长)", "+2(切长)", "+3(切长)"};
            for (int i = 0; i < cutDir.length; i++) {
                System.out.printf("  %-12s %5d%n", cutName[i], cutDir[i]);
            }
            System.out.printf("%n漏检构成（按类别）：%n");
            System.out.printf("  %-6s %8s %10s %10s%n", "类型", "完全没认", "切边界", "认错类别");
            int[] perK = missKindByType.get("PER");
            int[] orgK = missKindByType.get("ORG");
            System.out.printf("  %-6s %8d %10d %10d%n", "PER", perK[0], perK[1], perK[2]);
            System.out.printf("  %-6s %8d %10d %10d%n", "ORG", orgK[0], orgK[1], orgK[2]);
            if (perK[0] + perK[1] + perK[2] != byKind.get("PER")[2]
                    || orgK[0] + orgK[1] + orgK[2] != byKind.get("ORG")[2]) {
                throw new IllegalStateException("分类别漏检构成与分类别 fn 对不上："
                        + "PER " + Arrays.toString(perK) + " vs fn=" + byKind.get("PER")[2]
                        + "；ORG " + Arrays.toString(orgK) + " vs fn=" + byKind.get("ORG")[2]);
            }
            // 按桶互校：总和相等**不能**推出分桶相等。
            //
            // 这是本文件踩过的真坑：分类别那张表的第一版把 PER 的 253 个漏检里
            // 262 个记进「切边界」，而三项加起来仍然是 253，所以对账通过、
            // 数字全错。上面那条总和断言对这种错误**结构性无能**。
            //
            // 判据：每类别的每一桶，都必须等于总表同一桶里该类别的份额；
            // 由于同类型的桶只有两个类别，逐桶直接相等即可，不需要「份额」这种含糊说法。
            if (perK[0] != missKind[0] - orgK[0] || perK[1] != missKind[1] - orgK[1]
                    || perK[2] != missKind[2] - orgK[2]) {
                throw new IllegalStateException("分类别与总表逐桶对不上（不是和，是每桶）："
                        + "PER " + Arrays.toString(perK) + " + ORG " + Arrays.toString(orgK)
                        + " 应逐桶等于总表 " + Arrays.toString(missKind));
            }
            System.out.printf("%n误报归因：%n");
            System.out.printf("  %-40s %5d%n", "与金标重叠（金标未被命中=切长）", fpKind[0]);
            System.out.printf("  %-40s %5d%n", "与金标重叠（金标已命中=重复上报）", fpKind[4]);
            System.out.printf("  %-40s %5d%n", "紧邻金标不重叠（外扩1~2字）", fpKind[1]);
            System.out.printf("  %-40s %5d%n", "孤立且全字登录", fpKind[2]);
            System.out.printf("  %-40s %5d%n", "孤立且含未登录字(UNK)", fpKind[3]);
            System.out.printf("%n按金标长度分层的召回：%n");
            System.out.printf("  %-10s %8s %8s %8s%n", "长度", "命中", "总数", "召回");
            for (var e : recallByLen.entrySet()) {
                int[] a = e.getValue();
                System.out.printf("  %-10s %8d %8d %8.4f%n",
                        e.getKey() + " 字", a[0], a[1], (double) a[0] / a[1]);
            }
            int longHit = 0;
            int longTotal = 0;
            for (var e : recallByLen.entrySet()) {
                if (e.getKey() >= 7) {
                    longHit += e.getValue()[0];
                    longTotal += e.getValue()[1];
                }
            }
            System.out.printf("  —— 合计 %d/%d，长实体(>=7字) %d/%d%n",
                    lenHit, lenTotal, longHit, longTotal);

            // 长句按「离切线多远」分档。窗口参数取自模型包 manifest，
            // 不硬编码——写死一份就等于假设模型包永远是 256/64，
            // 哪天改了窗口而这里没改，表会安静地指错方向。
            if (!recallByCutDist.isEmpty()) {
                System.out.printf("%n长句里「实体起点离最近切线多远」的召回"
                        + "（窗口 %d 字 / 重叠 %d / 步长 %d）：%n",
                        windowChars, overlap, windowChars - overlap);
                int cutTotal = 0;
                int cutHit = 0;
                for (var e : recallByCutDist.entrySet()) {
                    int[] a = e.getValue();
                    cutTotal += a[1];
                    cutHit += a[0];
                    System.out.printf("  离切线 %-12s %6d/%-6d 召回 %.4f%n",
                            e.getKey(), a[0], a[1], (double) a[0] / a[1]);
                }
                System.out.printf("  —— 长句金标合计 %d/%d%n", cutHit, cutTotal);
            }

            if (!errorSamples.isEmpty()) {
                System.out.println("有误差的样本（前 8 条，实际值）：");
                for (var e : errorSamples.entrySet()) {
                    System.out.println("  [" + e.getValue() + "] " + e.getKey());
                }
            }
        }
    }

    /**
     * 按「实体起点到最近切线的距离」给长句里的金标分桶，统计各自的召回。
     *
     * <p>切线位置由窗口参数决定：第 k 个窗口是 {@code [k*step, k*step+windowChars)}，
     * 所以左切线在 {@code pos ≡ 0 (mod step)}，
     * 右切线在 {@code pos ≡ windowChars ≡ windowChars - step (mod step)}。
     *
     * <p>判定标准很直接：<b>若「贴着切线」那几档的召回明显低于其余档，
     * 就是滑窗在丢实体</b>；若各档差不多，长句召回低就不是窗口的锅，
     * 而是模型在长上下文里认不准——那要改的是训练而不是切分。
     *
     * <p>只看短句的分母会是 0（短句根本不切窗），所以这里只喂长句。
     */
    private static void bucketByCutDistance(Sentence s, List<Span> pred,
            Map<String, int[]> acc, int windowChars, int overlap) {
        int step = windowChars - overlap;
        for (Span g : s.gold()) {
            int pos = g.start() % step;
            int rightCut = windowChars % step;
            int d = Math.min(pos, Math.abs(pos - rightCut));
            d = Math.min(d, Math.abs(d - step));
            String key = d == 0 ? "0(正贴切线)" : d <= 2 ? "1-2" : d <= 8 ? "3-8"
                    : d <= 24 ? "9-24" : "25+";
            int[] a = acc.computeIfAbsent(key, k -> new int[2]);
            a[1]++;
            if (pred.contains(g)) {
                a[0]++;
            }
        }
    }

    /** 读模型包自带的词表（每行一个字）。文件缺失时返回空集——由调用方决定是否报错。 */
    private static Set<String> loadUnlogged(Path vocabFile) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(vocabFile)) {
            throw new IllegalStateException("模型包里没有词表：" + vocabFile
                    + "（没它就没法区分「认错了」与「字不认识」）");
        }
        for (String line : Files.readAllLines(vocabFile, StandardCharsets.UTF_8)) {
            if (line.length() == 1) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * 把漏检拆成**三项互斥**：完全没认 / 同类型重叠（边界切错）/ 异类型重叠（认错类别）。
     *
     * <p>三项不是同一件事，修法也不同：
     * <ul>
     *   <li>完全没认 → 训练覆盖缺口（多半是词表/句式没见过）</li>
     *   <li>边界切错 → 实体该有多长没学会，或把角色词吞进去了</li>
     *   <li>认错类别 → 模型把人名判成机构，或反过来</li>
     * </ul>
     *
     * <p>「异类型重叠」单列是被对账断言逼出来的：老口径的「边界重叠但切错」
     * 只看字符有没有重叠、不看类型，于是一个人名金标被机构预测盖住时
     * 也被记成「切错」。第一版按同类型分桶，两边对不上（116 vs 117）。
     * 差的不是数据，是**两处定义不同**——而这种不一致在只报总数时是看不见的。
     *
     * <p>方向那一层只统计「同类型重叠」的那部分，回答模型是切短了还是切长了。
     * 「最佳匹配」取重叠最多、重叠相同时取更短的那个：切长与切短要能分到两侧，
     * 否则会互相抵消后消失，**而它偏小的方向恰好是「误差被掩盖」的方向**。
     */
    private static void decomposeMiss(Sentence s, List<Span> pred, int[] missKind,
            int[] cutDir, Map<Integer, int[]> recallByLen) {
        for (Span g : s.gold()) {
            int len = g.end() - g.start();
            int[] acc = recallByLen.computeIfAbsent(len, k -> new int[2]);
            acc[1]++;
            if (pred.contains(g)) {
                acc[0]++;
                continue;
            }
            Span best = null;
            int bestOv = 0;
            boolean anyOtherKind = false;
            for (Span p : pred) {
                int ov = Math.min(p.end(), g.end()) - Math.max(p.start(), g.start());
                if (ov <= 0) {
                    continue;
                }
                if (!p.kind().equals(g.kind())) {
                    anyOtherKind = true;
                    continue;
                }
                if (best == null || ov > bestOv
                        || (ov == bestOv && (p.end() - p.start()) < (best.end() - best.start()))) {
                    best = p;
                    bestOv = ov;
                }
            }
            if (best == null) {
                missKind[anyOtherKind ? 2 : 0]++;
                continue;
            }
            missKind[1]++;
            int d = (best.end() - best.start()) - len;
            if (d < -3) {
                d = -3;
            } else if (d > 3) {
                d = 3;
            }
            cutDir[d + 3]++;
        }
    }

    /**
    /** 按类别统计漏检构成。与总表用同一套判据，且会被总表的对账断言兜住。 */
    private static void decomposeMissByType(Sentence s, List<Span> pred,
            Map<String, int[]> acc) {
        for (String kind : new String[] {"PER", "ORG"}) {
            List<Span> gold = s.gold().stream().filter(g -> g.kind().equals(kind)).toList();
            List<Span> p2 = pred.stream().filter(p -> p.kind().equals(kind)).toList();
            int[] a = acc.get(kind);
            for (Span g : gold) {
                if (p2.contains(g)) {
                    continue;
                }
                // 三桶必须与 decomposeMiss 同口径。
                //
                // 重叠判据第一版把两项写重了，缺 g.start() < p.end() 那一半，于是
                // 「起点在金标末尾之前的同类预测」就算重叠——金标**前面**那些毫不相干
                // 的人名也被算进来，把 PER 漏检整片记成「切边界」。
                //
                // 它没被抓到是因为对账只查**和**：分类别三项加起来仍等于该类别的 fn，
                // 分对分错都对。**只对总和的对账抓不出分错桶**，故另有逐桶互校。
                boolean ovSame = p2.stream()
                        .anyMatch(p -> p.start() < g.end() && g.start() < p.end());
                // 第三桶「认错类别」要单独判：只看同类型预测的话，一个被**异类型**
                // 预测盖住的金标会落进「完全没认」，分类别的完全没认就会比总表
                // 多出恰好「认错类别」那几个。
                boolean ovOther = pred.stream()
                        .anyMatch(p -> !p.kind().equals(kind)
                                && p.start() < g.end() && g.start() < p.end());
                a[ovSame ? 1 : (ovOther ? 2 : 0)]++;
            }
        }
    }

    /**
     * 把误报拆成互斥的四桶。
     *
     * <p>「紧邻」定义为：与某个金标实体**之间没有别的字符**，或只隔 1 个字符。
     * 这类误报几乎都是把角色词、「有限公司」之类的后缀、或者称谓吞进了实体；
     * 与之相对的「孤立」误报才是真的幻觉。两者的修法完全不同。
     *
     * <p>第四桶单列「孤立且含未登录字」，因为它量的是**词表覆盖**而不是识别质量——
     * 把它算进识别指标会让数字既不能证明模型好、也不能证明模型差。
     */
    private static void decomposeFp(Sentence s, List<Span> pred,
            int[] fpKind, Set<String> vocab) {
        for (Span p : pred) {
            if (s.gold().contains(p)) {
                continue;
            }
            boolean overlaps = s.gold().stream()
                    .anyMatch(g -> g.start() < p.end() && p.start() < g.end());
            if (overlaps) {
                // 两种「重叠」是完全不同的缺陷，必须分开：
                //   dup=false：重叠的金标**没有**被精确命中——这是模型切长了，
                //             该修的是训练。
                //   dup=true ：重叠的金标**已经被精确命中**了，却还多出一个预测。
                //             这是**同一条实体被报了两遍且边界不一致**——
                //             跨窗口合并没去干净。它会让精确匹配凭空少一分，
                //             而模型本身一点问题都没有。
                boolean anyOverlapGoldMatched = s.gold().stream()
                        .anyMatch(g -> pred.contains(g)
                                && g.start() < p.end() && p.start() < g.end());
                fpKind[anyOverlapGoldMatched ? 4 : 0]++;
                continue;
            }
            boolean adjacent = s.gold().stream()
                    .anyMatch(g -> gapChars(g, p) <= 1);
            if (adjacent) {
                fpKind[1]++;
                continue;
            }
            boolean anyUnknown = false;
            outer:
            for (int i = p.start(); i < p.end(); i++) {
                if (!vocab.contains(String.valueOf(s.text().charAt(i)))) {
                    anyUnknown = true;
                    break outer;
                }
            }
            fpKind[anyUnknown ? 3 : 2]++;
        }
    }

    /** 两个 span 之间的字符数（重叠时为负）。 */
    private static int gapChars(Span a, Span b) {
        if (a.end() <= b.start()) {
            return b.start() - a.end();
        }
        if (b.end() <= a.start()) {
            return a.start() - b.end();
        }
        return -1;
    }

    private static double[] prf(int tp, int fp, int fn) {
        double p = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
        double r = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
        double f = p + r == 0 ? 0.0 : 2 * p * r / (p + r);
        return new double[] {p, r, f};
    }

    /** 严格匹配：起止与类型三者全中才算 tp。返回 {tp, fp, fn}。 */
    private static int[] score(List<Span> gold, List<Span> pred) {
        List<Span> rest = new ArrayList<>(gold);
        int tp = 0;
        for (Span p : pred) {
            int i = rest.indexOf(p);
            if (i >= 0) {
                rest.remove(i);
                tp++;
            }
        }
        return new int[] {tp, pred.size() - tp, rest.size()};
    }

    /**
     * EntitySource 的输出归一化成 span。
     *
     * <p>规则键到金标类型的映射写在这里而不是从被测代码取：
     * 取被测代码的常量，就等于用被测实现定义「什么算 PER」。
     */
    private static List<Span> toSpans(List<Match> matches) {
        List<Span> out = new ArrayList<>();
        for (Match m : matches) {
            String kind;
            if (NerEntitySource.RULE_PERSON.equals(m.ruleKey())) {
                kind = "PER";
            } else if (NerEntitySource.RULE_ORGANIZATION.equals(m.ruleKey())) {
                kind = "ORG";
            } else {
                continue;
            }
            out.add(new Span(m.start(), m.end(), kind));
        }
        return out;
    }

    /**
     * 读 MSRA 风格的 {@code 字\t标签} BIO 文件。
     *
     * <p>分隔符是<b>制表符</b>，这一点是实测出来的（{@code od -c} 看到的是
     * {@code 当\tO\n}）；写成 {@code split(" ")} 会在第一行就报「格式不对」。
     */
    private static List<Sentence> readBio(Path file) throws IOException {
        List<Sentence> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<String> kinds = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                if (text.length() > 0) {
                    out.add(new Sentence(text.toString(), bioToSpans(text.toString(), kinds)));
                    text.setLength(0);
                    kinds.clear();
                }
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                throw new IllegalStateException("BIO 行缺制表符：" + line);
            }
            text.append(line, 0, tab);
            kinds.add(line.substring(tab + 1).trim());
        }
        if (text.length() > 0) {
            out.add(new Sentence(text.toString(), bioToSpans(text.toString(), kinds)));
        }
        return out;
    }

    /** 逐字 BIO → span。独立实现，不复用被测代码。 */
    private static List<Span> bioToSpans(String text, List<String> tags) {
        if (text.length() != tags.size()) {
            throw new IllegalStateException(
                    "句长 " + text.length() + " 与标签数 " + tags.size() + " 不一致");
        }
        List<Span> out = new ArrayList<>();
        int i = 0;
        while (i < tags.size()) {
            String t = tags.get(i);
            if (t.startsWith("B-")) {
                String kind = t.substring(2);
                int j = i + 1;
                String cont = "I-" + kind;
                while (j < tags.size() && tags.get(j).equals(cont)) {
                    j++;
                }
                out.add(new Span(i, j, kind));
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * 数「漏检」里属于某一类的个数。
     *
     * @param overlapOnly true 时只数「与某个预测实体有字符重叠但起止不同」的
     *                    （边界切错）；false 时只数「与任何预测都不重叠」的（完全没认）。
     */
    private static int countMissedAs(List<Span> gold, List<Span> pred, boolean overlapOnly) {
        int n = 0;
        for (Span g : gold) {
            boolean exact = pred.contains(g);
            if (exact) {
                continue;
            }
            boolean overlaps = pred.stream().anyMatch(p -> p.start() < g.end() && g.start() < p.end());
            if (overlaps == overlapOnly) {
                n++;
            }
        }
        return n;
    }

    private static String excerpt(String text) {
        return text.length() > 70 ? text.substring(0, 70) + "…" : text;
    }
}
