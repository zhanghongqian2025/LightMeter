package com.litemask.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.litemask.core.Change;
import com.litemask.core.PrivateFile;
import com.litemask.core.RedactionResult;
import com.litemask.core.RedactionEngine;
import com.litemask.core.Exclusions;
import com.litemask.core.RedactionSession;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import com.litemask.core.SecretMap;
import com.litemask.docx.DocxRedactor;
import com.litemask.ofd.OfdRedactor;
import com.litemask.pdf.PdfRedactor;
import com.litemask.pptx.PptxRedactor;
import com.litemask.xlsx.XlsxRedactor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 命令行入口。M0 只做一件事：把文本脱敏/还原做扎实，够测试、够人工验收即可。
 *
 * <pre>
 *   redact  脱敏文本，产出 .redacted 与 .map.json
 *   restore 用 .map.json 还原
 *   rules   查看规则库
 * </pre>
 */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            usage(System.out);
            return;
        }
        try {
            switch (args[0]) {
                case "redact" -> System.exit(redact(tail(args)));
                case "restore" -> System.exit(restore(tail(args)));
                case "rules" -> System.exit(rules(tail(args)));
                case "serve" -> System.exit(ServeCommand.run(tail(args)));
                case "model" -> System.exit(ModelCommand.run(tail(args), CliInvocation.prefix(args), System.out, System.err));
                case "uninstall" -> System.exit(UninstallCommand.run(tail(args), System.out, System.err));
                case "autostart" -> System.exit(AutostartCommand.run(tail(args), System.out, System.err));
                default -> {
                    System.err.println("未知子命令: " + args[0]);
                    usage(System.err);
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    /** 包级可见是为了让单测能直接跑这条命令（{@link #main} 会 System.exit，测不了）。 */
    static int redact(String[] args) throws IOException {
        Options opt;
        try {
            opt = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("错误: " + e.getMessage());
            usage(System.err);
            return 2;
        }
        if (opt.helpRequested) {
            // 【为什么 --help 必须在任何动作之前短路】
            //   块 38 开工前的实测：`redact 合同.txt --help` **真的把合同脱敏了**
            //   （产出了 .redacted.txt 与映射件），最后才因为找不到文件 `--help` 报错。
            //   → 「看一眼用法」不该变成「脱敏一遍」。
            CliHelp.print(System.out, redactUsageText(), redactUsageNotes());
            return 0;
        }
        if (opt.positional.isEmpty()) {
            System.err.println("缺少输入文件。用 - 表示从标准输入读取。");
            return 2;
        }
        // 【为什么在动手之前就取口令】
        //   口令取不到就必须当场停：等到文档都脱敏完了才发现取不到，
        //   用户会拿到一个「脱敏了但映射没写出来」的结果 —— 而界面上看不出任何异常。
        if (opt.mapPass != null
                && opt.mapPass.kind() == PassphraseSource.Kind.STDIN
                && opt.positional.size() == 1
                && "-".equals(opt.positional.get(0))) {
            System.err.println("错误: --map-pass-stdin 与「从标准输入读文档」（-）不能同时用"
                    + "（两者都要占住标准输入）");
            return 2;
        }
        char[] passphrase;
        try {
            passphrase = resolvePassphrase(opt);
        } catch (PassphraseFailure e) {
            System.err.println("错误: 取不到映射件口令：" + e.getMessage());
            return 2;
        }
        MapWriter mapWriter = new MapWriter(passphrase);
        List<Rule> rules = opt.rulesPath == null
                ? RuleLoader.loadFromClasspath("rules/core-rules.json")
                : RuleLoader.load(Path.of(opt.rulesPath));

        // 模型要么找到、要么明说「没找到」。加载失败（摘要不对、版本不认）会直接抛，
        // 不在这里降级成「当作没装」——那会让用户以为自己装了模型、工具却当没装。
        com.litemask.ner.NerModelRegistry registry;
        try {
            registry = com.litemask.ner.NerModelRegistry.discover(opt.nerModelDir);
        } catch (com.litemask.ner.NerModelRegistry.OrtExceptionWrapper e) {
            System.err.println("错误: " + e.getMessage());
            return 1;
        }
        com.litemask.ner.NerModelRegistry.set(registry);
        List<com.litemask.core.EntitySource> sources = registry.sources();

        // 必须先说这一句再干活。产物里「张三」原样保留，而终端如果只报「1 处」，
        // 用户会以为那就是全部——「只脱到了正则覆盖的东西」这件事必须显式说出来。
        System.out.println(registry.describe());
        if (!sources.isEmpty()) {
            System.out.println("  （命中的人名与机构名会以 <人名1> / <机构名1> 这样的令牌写入，"
                    + "并进入还原映射）");
        }

        try {
            return redactAll(opt, rules, sources, mapWriter);
        } finally {
            registry.close();
            com.litemask.ner.NerModelRegistry.set(null);
        }
    }

    private static int redactAll(Options opt, List<Rule> rules,
                                 List<com.litemask.core.EntitySource> sources,
                                 MapWriter mapWriter) throws IOException {
        if (opt.positional.size() == 1 && "-".equals(opt.positional.get(0))) {
            String text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            RedactionSession session = new RedactionSession(text, rules, Exclusions.NONE, sources);
            RedactionResult result = session.redact(text);
            // 【2026-10-25】这一段是**数据**，不是给人看的提示语 ——
            //   它会被重定向进文件（`LightMeter redact - < in.txt > out.txt`），
            //   所以编码必须**明确**是 UTF-8，不能跟着 System.out 的编码走。
            //   Windows 上 System.out 默认是 GBK（中文系统）或 cp1252（其它系统），
            //   跟过去 = 落盘的文件是 GBK/乱码，下游读出来就是坏的。
            //   → 直接把 UTF-8 字节写进同一条流。
            byte[] out = result.sanitizedText().getBytes(StandardCharsets.UTF_8);
            System.out.write(out, 0, out.length);
            System.out.flush();
            if (opt.mapPath != null) {
                mapWriter.write(Path.of(opt.mapPath), textMappingPayload(result));
            }
            return 0;
        }

        for (String input : opt.positional) {
            Path in = Path.of(input);
            if (isDocx(in)) {
                redactDocx(in, opt, rules, sources, mapWriter);
                continue;
            }
            if (isXlsx(in)) {
                redactXlsx(in, opt, rules, sources, mapWriter);
                continue;
            }
            if (isPptx(in)) {
                redactPptx(in, opt, rules, sources, mapWriter);
                continue;
            }
            if (isPdf(in)) {
                redactPdf(in, opt, rules, sources, mapWriter);
                continue;
            }
            if (isOfd(in)) {
                redactOfd(in, opt, rules, sources, mapWriter);
                continue;
            }
            String text = Files.readString(in, StandardCharsets.UTF_8);
            RedactionSession session = new RedactionSession(text, rules, Exclusions.NONE, sources);
            RedactionResult result = session.redact(text);

            // 与其余五种格式走同一个派生规则（基名 + .redacted + 原后缀）。
            //
            // 原来是 `input + ".redacted"`，产出 `合同.txt.redacted`——后缀不再是 .txt，
            // 在 macOS/Windows 上双击打不开（系统不知道用哪个程序打开），
            // 而同一批命令里的 docx 却产出 `合同.redacted.docx`。两种命名规则并存没有理由。
            //
            // 注意**映射名不要跟着改成从输出名派生**：`合同.docx.redacted.map.json`
            // 看着不对称，但它是刻意从**输入名**派生的——同一份文件脱敏两次时
            // 输出是 `合同.redacted.docx` / `合同.redacted.redacted.docx`，
            // 映射则是 `合同.docx.redacted.map.json` / `合同.redacted.docx.redacted.map.json`，
            // 两次互不覆盖。若改成从输出名派生，第二次就把第一次的映射盖掉了。
            Path out = opt.outputPath != null ? Path.of(opt.outputPath)
                    : redactedName(in);
            Files.writeString(out, result.sanitizedText(), StandardCharsets.UTF_8);

            Path map = opt.mapPath != null ? Path.of(opt.mapPath)
                    : Path.of(input + ".redacted.map.json");
            mapWriter.write(map, textMappingPayload(result));

            System.out.printf("%s → %s（%d 处，映射 %s）%n",
                    in, out, result.changes().size(), map);
        }
        return 0;
    }

    private static void redactDocx(Path in, Options opt, List<Rule> rules,
                                   List<com.litemask.core.EntitySource> sources,
                                   MapWriter mapWriter) throws IOException {
        Path out = opt.outputPath != null ? Path.of(opt.outputPath)
                : redactedName(in);
        Path map = opt.mapPath != null ? Path.of(opt.mapPath)
                : Path.of(in + ".redacted.map.json");
        DocxRedactor.Report report = DocxRedactor.redact(in, out, rules, Exclusions.NONE, sources);
        mapWriter.write(map, docxMappingPayload(report));
        System.out.printf("%s → %s（%d 处，清空 %d 个文档属性，映射 %s）%n",
                in, out, report.hits().size(), report.metadataFieldsCleared(), map);
    }

    /** 包级可见是为了让单测能直接跑这条命令。 */
    static int restore(String[] args) throws IOException {
        Options opt;
        try {
            opt = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("错误: " + e.getMessage());
            usage(System.err);
            return 2;
        }
        if (opt.helpRequested) {
            CliHelp.print(System.out, restoreUsageText(), restoreUsageNotes());
            return 0;
        }
        if (opt.positional.isEmpty() || opt.mapPath == null) {
            System.err.println("用法: restore <文件...> --map <映射文件>");
            return 2;
        }
        char[] passphrase;
        try {
            passphrase = resolvePassphrase(opt);
        } catch (PassphraseFailure e) {
            System.err.println("错误: 取不到映射件口令：" + e.getMessage());
            return 2;
        }
        Path mapPath = Path.of(opt.mapPath);
        if (!Files.isRegularFile(mapPath)) {
            System.err.println("错误: 映射文件不存在或不是普通文件：" + mapPath);
            return 2;
        }
        byte[] raw = Files.readAllBytes(mapPath);
        byte[] payload = raw;
        boolean sealed = SecretMap.isSealed(raw);
        if (sealed) {
            // 【为什么「是封装件但没给口令」要单独报错，而不是当成普通映射读】
            //   封装件的顶层没有 "mapping" 字段，当普通映射读会得到一个空映射，
            //   于是「还原」会**静默地什么都不替换** —— 用户拿到一份没还原的文件，
            //   而界面上看不出任何异常。
            if (passphrase == null) {
                System.err.println("错误: 这个映射件是加密封装的（" + SecretMap.format() + "），需要口令才能还原。");
                System.err.println("  用 --map-pass-env / --map-pass-file / --map-pass-stdin / --map-pass-prompt 提供口令。");
                return 2;
            }
            try {
                payload = SecretMap.open(raw, passphrase);
            } catch (SecretMap.SecretMapException e) {
                System.err.println("错误: " + e.getMessage());
                return 1;
            }
        } else if (passphrase != null) {
            // 反过来也要报：用户以为自己加密了，其实这个文件根本没加密。
            System.err.println("错误: 给了口令，但这个映射件不是加密封装的（是明文 JSON）——请去掉口令重试。");
            return 2;
        }
        Map<String, Object> mapFile;
        try {
            mapFile = JSON.readValue(payload, Map.class);
        } catch (Exception e) {
            System.err.println("错误: 映射文件读不出来（" + e.getMessage() + "）");
            return 1;
        }
        Object mappingNode = mapFile.get("mapping");
        if (!(mappingNode instanceof Map)) {
            System.err.println("错误: 映射文件里没有 mapping 段，还原不了。");
            return 1;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> mapping = (Map<String, String>) mappingNode;

        for (String input : opt.positional) {
            Path in = Path.of(input);
            if (isXlsx(in)) {
                Path out = restoredName(in);
                int n = XlsxRedactor.restore(in, out, mapping);
                System.out.printf("%s → %s（还原 %d 处）%n", in, out, n);
                continue;
            }
            if (isPptx(in)) {
                Path out = restoredName(in);
                int n = PptxRedactor.restore(in, out, mapping);
                System.out.printf("%s → %s（还原 %d 处）%n", in, out, n);
                continue;
            }
            if (isDocx(in)) {
                Path out = restoredName(in);
                int n = DocxRedactor.restore(in, out, mapping);
                System.out.printf("%s → %s（还原 %d 处文本槽）%n", in, out, n);
                continue;
            }
            if (isOfd(in)) {
                Path out = restoredName(in);
                int n = OfdRedactor.restore(in, out, mapping);
                System.out.printf("%s → %s（还原 %d 处）%n", in, out, n);
                System.out.println("  说明：OFD 的 Author/Creator 是直接清空的、签章已移除，"
                        + "这两项原值从未保存，还原不会带回来。");
                continue;
            }
            if (isPdf(in)) {
                Path out = restoredName(in);
                PdfRedactor.Report r = PdfRedactor.restore(in, out, mapping);
                System.out.printf("%s → %s（还原 %d 处）%n", in, out, r.changeCount());
                for (String w : r.warnings()) {
                    System.out.println("  注意：" + w);
                }
                continue;
            }
            String text = Files.readString(in, StandardCharsets.UTF_8);
            String restored = RedactionEngine.restore(text, mapping);
            Path out = restoredName(in);
            Files.writeString(out, restored, StandardCharsets.UTF_8);
            boolean identical = restored.equals(text);
            System.out.printf("%s → %s（还原后%s）%n", in, out, identical ? "内容未变" : "已还原");
        }
        return 0;
    }

    static boolean isDocx(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    static boolean isXlsx(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    static boolean isPptx(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pptx");
    }

    static boolean isPdf(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    static boolean isOfd(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ofd");
    }

    /** {@code /a/合同.docx} → {@code /a/合同.redacted.docx}：保住目录，只换文件名，保住原扩展名。 */
    static Path redactedName(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        Path parent = p.getParent();
        return parent == null ? Path.of(base + ".redacted" + ext) : parent.resolve(base + ".redacted" + ext);
    }

    /**
     * {@code /a/合同.redacted.docx} → {@code /a/合同.redacted.restored.docx}。
     *
     * <p>与 {@link #redactedName} 完全对称：同样是「保住目录、只换文件名、原扩展名留在最后」。
     *
     * <p>抽出这个方法是因为原先这里有**六份**各自抄一遍的推导：
     * xlsx / pptx / docx / ofd / pdf 各写了一次 {@code name.substring(0, name.length() - ".xxx".length()) + ".restored.xxx"}，
     * 而 txt 走的是 {@code input + ".restored"}。抄了六份就意味着改规则要改六处，
     * 漏一处就会出现「docx 产出 {@code 合同.redacted.restored.docx}、txt 却产出
     * {@code 合同.redacted.txt.restored}」这种同族分叉——本轮真的抓到了这一条。
     *
     * <p>用 {@code lastIndexOf('.')} 而不是按固定后缀长度减，这样多层扩展名
     * （{@code a.tar.gz}）与无扩展名（{@code README}）都走同一条路。
     */
    static Path restoredName(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        Path parent = p.getParent();
        return parent == null ? Path.of(base + ".restored" + ext) : parent.resolve(base + ".restored" + ext);
    }

    private static void redactXlsx(Path in, Options opt, List<Rule> rules,
                                 List<com.litemask.core.EntitySource> sources,
                                 MapWriter mapWriter) throws IOException {
        Path out = opt.outputPath != null ? Path.of(opt.outputPath) : redactedName(in);
        Path map = opt.mapPath != null ? Path.of(opt.mapPath) : Path.of(in + ".redacted.map.json");
        XlsxRedactor.Report report = XlsxRedactor.redact(in, out, rules, Exclusions.NONE, sources);
        mapWriter.write(map, buildPayload(report.mapping(), report.hits().stream()
                .map(h -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("part", h.part());
                    item.put("locator", h.locator());
                    item.put("ruleKey", h.ruleKey());
                    item.put("original", h.original());
                    item.put("token", h.token());
                    return item;
                }).toList()));
        System.out.printf("%s → %s（%d 处，工作表名 %d 个，清空 %d 个属性，映射 %s）%n",
                in, out, report.hits().size(), report.sheetNamesRedacted(), report.metadataFieldsCleared(), map);
        if (report.numericCellsConverted() > 0) {
            // 必须说清楚：数值单元格转成文本后求和会失效，这不是能悄悄带过的副作用
            System.out.printf("注意：%d 个数值单元格已转成文本，该列的求和/排序会失效。%n",
                    report.numericCellsConverted());
        }
    }

    /**
     * PDF 管线。
     *
     * <p>报告里必须把「没脱到」和「没有可脱的」分开说：
     * 零命中有两种完全不同的含义——文档干净，或者**根本没有文本层**（扫描件）。
     * 只报「0 处」的话，第二个会被读成第一个，那是本工具最不能犯的错。
     */
    private static void redactPdf(Path in, Options opt, List<Rule> rules,
                                 List<com.litemask.core.EntitySource> sources,
                                 MapWriter mapWriter) throws IOException {
        Path out = opt.outputPath != null ? Path.of(opt.outputPath) : redactedName(in);
        Path map = opt.mapPath != null ? Path.of(opt.mapPath) : Path.of(in + ".redacted.map.json");
        PdfRedactor.Report report = PdfRedactor.redact(in, out, rules, new PdfRedactor.Options(),
                Exclusions.NONE, sources);
        mapWriter.write(map, buildPayload(report.mapping(), report.hits().stream()
                .map(h -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    // 位置：内容流（第几页 / 哪个表单）+ 文本块。
                    // 与 docx/xlsx/ofd 的映射件同构——同一个字段名、同一种含义，
                    // 用户在五种格式之间切换时不用重新学。
                    item.put("part", h.part());
                    item.put("locator", h.locator());
                    item.put("ruleKey", h.ruleKey());
                    item.put("original", h.original());
                    // 必须写 PDF 里实际画的那个 ASCII 令牌。引擎的令牌是中文的，
                    // 照抄它就等于在映射文件里写了一句文档里并不存在的话。
                    item.put("token", h.token());
                    // 中文令牌也记一份：有人拿映射去核对引擎的输出时需要它，
                    // 而它在 PDF 里并不存在，所以不能只留它。
                    item.put("engineToken", h.engineToken());
                    return item;
                }).toList()));
        System.out.printf("%s → %s（%d 处，%d 页 / %d 条内容流 / %d 个字符，清掉 %d 项隐藏信息，映射 %s）%n",
                in, out, report.changeCount(), report.pageCount(), report.unitCount(),
                report.textChars(), report.removedMetadata().size(), map);
        for (String removed : report.removedMetadata()) {
            System.out.println("  已清除：" + removed);
        }
        for (String w : report.warnings()) {
            System.out.println("  注意：" + w);
        }
        if (!report.hasTextLayer()) {
            // 「零命中」在这里等于「没得脱」，不能报成「已脱干净」
            System.out.println("  警告：这份 PDF 没有文本层（多半是扫描件）。"
                    + "本工具不做 OCR，图像里的内容一个字都没有处理——"
                    + "上面的 0 处不是「文档干净」，是「没有可读的文本」。"
                    + "请先把它转存为带文本层的 PDF 再脱敏。");
            // 【2026-10-25】不能「只附一句提示」就把这份文件当脱敏件交出去。
            //   没有文本层 → 脱敏跑完零命中 → 产出的文件与**原件逐字相同**
            //   （图片里原封不动）。把它叫「脱敏件」就是在**掩盖未处理的内容**：
            //   用户拿到一份看着正常的文件，扫一遍就知道里面的内容一个字都没动。
            //   → 删掉产物 + 报错，让用户先把它转成带文本层的 PDF。
            //   （与本机服务那条路一致：服务端 apply 也会拒掉这种文件。）
            //
            //   只删**我们自己派生的**那两个路径：用户用 -o/-map 指定的路径
            //   可能本来就指着别的文件，删它超出「清理自己的产物」的范围
            //   （内容已经被覆盖过了，再删就是二次伤害）。
            if (opt.outputPath == null) {
                Files.deleteIfExists(out);
            }
            if (opt.mapPath == null) {
                Files.deleteIfExists(map);
            }
            throw new IOException("这份 PDF 没有文本层（多半是扫描件），"
                    + "图像里的内容本工具一个字都没有处理 —— 不把它当脱敏件交付。"
                    + "请先把它转存为带文本层的 PDF 再脱敏。");
        }
        if (report.hasInvisibleText()) {
            System.out.printf("  注意：文档含 %d 个不可见字符（渲染模式 3，扫描件上盖的 OCR 层）。"
                    + "这类内容屏幕上看不见，但复制、检索都能拿到，已一并脱敏。%n",
                    report.invisibleChars());
        }
        System.out.println("  说明：PDF 侧的令牌是 ASCII 形式（如 <ID_1>），"
                + "与 Word/Excel 里的中文令牌写法不同，两边不能混用映射文件。");
    }

    /**
     * OFD 管线。
     *
     * <p>OFD 的敏感信息散在正文之外，报告必须把它们单独列出来：
     * 文档属性里的业务数据、附件名、签章——用户不会知道这些被处理过，
     * 而「我以为只脱了正文」正是最容易出事的误解。
     */
    private static void redactOfd(Path in, Options opt, List<Rule> rules,
                                 List<com.litemask.core.EntitySource> sources,
                                 MapWriter mapWriter) throws IOException {
        Path out = opt.outputPath != null ? Path.of(opt.outputPath) : redactedName(in);
        Path map = opt.mapPath != null ? Path.of(opt.mapPath) : Path.of(in + ".redacted.map.json");
        OfdRedactor.Report report = OfdRedactor.redact(in, out, rules, Exclusions.NONE, sources);
        mapWriter.write(map, buildPayload(report.mapping(), report.hits().stream()
                .map(h -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("part", h.part());
                    item.put("locator", h.carrier());
                    item.put("ruleKey", h.ruleKey());
                    item.put("original", h.original());
                    item.put("token", h.token());
                    return item;
                }).toList()));
        System.out.printf("%s → %s（%d 处，%d 个文本对象 / %d 个 TextCode，改写 %d 个部件，映射 %s）%n",
                in, out, report.hits().size(), report.textObjects(), report.textCodes(),
                report.partsRewritten(), map);
        if (!report.clearedFields().isEmpty()) {
            System.out.printf("  已清空 %d 个文档属性字段（人名/工具名，任何正则都命中不了）：%n",
                    report.clearedFields().size());
            for (String field : report.clearedFields()) {
                System.out.println("    " + field);
            }
        }
        if (report.entriesRenamed() > 0) {
            System.out.printf("  已改名 %d 个附件条目（文件名里的个人信息），指向它们的引用已同步更新。%n",
                    report.entriesRenamed());
        }
        if (report.signatureEntriesDropped() > 0) {
            // 签名必然失效这件事必须自己说，不能让用户打开文件时才发现
            System.out.printf("  已移除 %d 个签章条目：脱敏改动了页面内容，原签名必然验不过。"
                    + "留着它只会让读者看到「签名无效」，删掉并告知才是诚实的做法。%n",
                    report.signatureEntriesDropped());
        }
        System.out.println("  说明：令牌比原值短时该处会留一段空白，但后续内容不会错位；"
                + "还原后文字与原文一致，跨 TextCode 的切分方式回不来。");
    }

    private static void redactPptx(Path in, Options opt, List<Rule> rules,
                                 List<com.litemask.core.EntitySource> sources,
                                 MapWriter mapWriter) throws IOException {
        Path out = opt.outputPath != null ? Path.of(opt.outputPath) : redactedName(in);
        Path map = opt.mapPath != null ? Path.of(opt.mapPath) : Path.of(in + ".redacted.map.json");
        PptxRedactor.Report report = PptxRedactor.redact(in, out, rules, Exclusions.NONE, sources);
        mapWriter.write(map, buildPayload(report.mapping(), report.hits().stream()
                .map(h -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("part", h.part());
                    item.put("locator", h.locator());
                    item.put("ruleKey", h.ruleKey());
                    item.put("original", h.original());
                    item.put("token", h.token());
                    return item;
                }).toList()));
        System.out.printf("%s → %s（%d 处，清空 %d 个属性，映射 %s）%n",
                in, out, report.hits().size(), report.metadataFieldsCleared(), map);
        if (report.numericChartValuesRedacted() > 0) {
            // pptx 的图表数据是内嵌的、打开时不会重算，所以改数值缓存是唯一选择；
            // 代价是 PowerPoint 会提示数据无效，需要用户刷新图表。
            System.out.printf("注意：%d 个图表数值缓存已脱敏，PowerPoint 打开时会提示数据无效，刷新图表即可。%n",
                    report.numericChartValuesRedacted());
        }
    }

    private static void writeDocxMapping(Path path, DocxRedactor.Report report) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mapping", report.mapping());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (DocxRedactor.Hit hit : report.hits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("part", hit.part());
            item.put("paragraph", hit.paragraphIndex());
            item.put("slot", hit.slotIndex());
            item.put("ruleKey", hit.ruleKey());
            item.put("original", hit.original());
            item.put("token", hit.token());
            changes.add(item);
        }
        payload.put("changes", changes);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, JSON.writeValueAsString(payload), StandardCharsets.UTF_8);
    }

    private static int rules(String[] args) {
        Options opt;
        try {
            opt = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("错误: " + e.getMessage());
            usage(System.err);
            return 2;
        }
        if (opt.helpRequested) {
            // 【这一条是块 38 里最隐蔽的一个】
            //   开工前 `rules --help` **静默执行了 rules**：退出码 0、stderr 全空，
            //   stdout 是一张规则表 —— 用户以为自己看到了用法，其实跑了一条命令。
            //   「没有任何异常输出」正是它最危险的地方：看起来一切正常。
            CliHelp.print(System.out, rulesUsageText(), rulesUsageNotes());
            return 0;
        }
        List<Rule> rules = opt.rulesPath == null
                ? RuleLoader.loadFromClasspath("rules/core-rules.json")
                : RuleLoader.load(Path.of(opt.rulesPath));
        System.out.printf("%-16s %-14s %6s %-10s %s%n", "KEY", "占位前缀", "优先级", "校验器", "说明");
        System.out.println("-".repeat(100));
        for (Rule r : rules) {
            System.out.printf("%-16s %-14s %6d %-10s %s%n",
                    r.key(), r.tokenPrefix(), r.sortOrder(), r.validator(), r.name());
        }
        System.out.printf("%n共 %d 条规则%n", rules.size());
        return 0;
    }

    /** 文本（txt / 标准输入）那一份映射载荷。 */
    private static Map<String, Object> textMappingPayload(RedactionResult result) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Change c : result.changes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleKey", c.ruleKey());
            item.put("original", c.original());
            item.put("token", c.token());
            item.put("start", c.start());
            item.put("end", c.end());
            changes.add(item);
        }
        return buildPayload(result.mapping(), changes);
    }

    /** docx 那一份映射载荷。 */
    private static Map<String, Object> docxMappingPayload(DocxRedactor.Report report) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (DocxRedactor.Hit hit : report.hits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("part", hit.part());
            item.put("paragraph", hit.paragraphIndex());
            item.put("slot", hit.slotIndex());
            item.put("ruleKey", hit.ruleKey());
            item.put("original", hit.original());
            item.put("token", hit.token());
            changes.add(item);
        }
        return buildPayload(report.mapping(), changes);
    }

    private static Map<String, Object> buildPayload(Map<String, String> mapping, List<Map<String, Object>> changes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mapping", mapping);
        payload.put("changes", changes);
        return payload;
    }

    /**
     * 映射件的写出口。
     *
     * <p>它是**唯一**决定「映射件长什么样、落在什么权限上」的地方 ——
     * 六条格式管线（txt / docx / xlsx / pptx / pdf / ofd）都从这里出，
     * 而不是各自调 {@code Files.writeString}。
     * 散着写的话，迟早有一条格式的映射件忘了设权限，
     * 而「忘了」这件事在测试里是**看不见**的：读得出来就说明没坏。
     *
     * <p>两件事在这里做，且**只在这里**做：
     * <ol>
     *   <li>给了口令 → 载荷先被 {@link SecretMap} 封装，文件里没有原值；</li>
     *   <li>落盘权限收紧到 0600（{@link PrivateFile}）。</li>
     * </ol>
     */
    private static final class MapWriter {

        /** 口令；{@code null} 表示「不封装，写明文」——与本工具从前的行为一致。 */
        private final char[] passphrase;

        /** 只提示一次：多份文档时不必每份都喊一遍。 */
        private boolean announced;

        MapWriter(char[] passphrase) {
            this.passphrase = passphrase;
        }

        boolean encrypted() {
            return passphrase != null;
        }

        void write(Path path, Map<String, Object> payload) throws IOException {
            byte[] json = JSON.writeValueAsBytes(payload);
            if (passphrase != null) {
                byte[] sealed;
                try {
                    sealed = SecretMap.seal(json, passphrase);
                } catch (SecretMap.SecretMapException e) {
                    throw new IOException("封装映射件失败：" + e.getMessage(), e);
                }
                PrivateFile.write(path, sealed);
                if (!announced) {
                    announced = true;
                    System.out.println("  映射件已用口令封装（" + SecretMap.format() + " v"
                            + SecretMap.VERSION + "）：文件里没有原值，还原时需要同一个口令。");
                }
                return;
            }
            PrivateFile.write(path, json);
        }
    }

    /**
     * 取口令。
     *
     * @return 口令；{@code null} 表示用户没要求封装（写明文）
     * @throws PassphraseFailure 口令来源给不出内容
     */
    private static char[] resolvePassphrase(Options opt) throws PassphraseFailure {
        if (opt.mapPass == null) {
            return null;
        }
        try {
            return opt.mapPass.read();
        } catch (PassphraseSource.PassphraseUnavailableException e) {
            throw new PassphraseFailure(e.getMessage());
        }
    }

    /** 口令取不到。 */
    private static final class PassphraseFailure extends Exception {
        PassphraseFailure(String message) {
            super(message);
        }
    }

    /**
     * 打印用法。
     *
     * <p><b>为什么分几次打印</b>：{@code model} 与 {@code autostart} 的命令行
     * 由各自的命令类提供（唯一真源），顶层直接嵌进来 —— 顶层以前是**手抄**，
     * 抄漏了 {@code model download} / {@code model trust} / 整块 {@code autostart}，
     * 用户照 README 敲会得到「未知子命令」。
     *
     * <p><b>为什么用 print 而不是 println</b>：文本块自带结尾换行，
     * {@code println} 会**再多打一个换行**（空行）。而块 37 的守卫按空行切分
     * 「用法:」块 —— 空行会把 model / autostart 那几行切到块外，
     * 守卫就会以为 help 里没有这两块命令。
     *
     * <p><b>排版约定</b>：每个段落用 {@code print}（段末恰好一个换行），
     * 段落之间的空行用单独的 {@code println()} 打。
     *
     * <p>包级可见是为了让单测能直接拿到帮助文本（{@link #main} 会 System.exit，测不了）。
     */
    static void usage(PrintStream out) {
        // 版本号**现算**：从 jar 里那份 package-identity.properties 读（跨端唯一真源）。
        // 以前这里写死的是「· M3-2」—— 那是**阶段标签**而不是版本号，
        // 项目走到 M4 之后它就过期了，而过期了**没有任何东西会报**
        // （编译不红、测试不红，help 照打不误）。
        out.print("轻量单机脱敏工具 · " + ProductIdentity.version() + "\n");
        out.println();
        out.print("用法:\n");
        redactUsageLines(out);
        restoreUsageLines(out);
        rulesUsageLines(out);
        ServeCommand.usageLines(out);
        ModelCommand.usageLines(out);
        AutostartCommand.usageLines(out);
        UninstallCommand.usageLines(out);
        out.println();
        out.print("""
                映射件（含全部原值，是本工具唯一会写出敏感数据的东西）：
                  默认写明文 JSON，但文件权限收紧到 0600（只给属主读）；
                  给了口令就改成加密封装（文件里没有原值），还原时要用同一个口令。
                  口令只从环境变量 / 文件 / 标准输入 / 交互式提示这四处取，
                  **不提供命令行上直接给口令的形式**（那会进 shell 历史与 ps 输出）。
                """);
        out.println();
        out.print("""
                支持格式:
                  .docx  正文/表格/页眉页脚/文本框/批注/修订留痕全覆盖，并清空文档属性
                  .xlsx  单元格/工作表名/定义名称/批注/数值转文本，并清空文档属性
                  .pptx  幻灯片/版式/母版/备注页/图表数据缓存，并清空文档属性
                  .pdf   文本层 + 表单 XObject；另清元数据、批注作者、内嵌附件、
                          JavaScript、书签标题；输出整体重写，不保留历史版本
                  .ofd   正文/模板/批注/关键字属性全覆盖；文档属性里的业务数据按规则脱敏、
                          Author/Creator 清空、附件条目改名、签章移除并报出
                  .txt   整篇按一个段处理
                """);
        out.println();
        out.print("""
                中文人名 / 机构名识别（NER）:
                  模型包是独立分发的（方案 B），核心包里没有。未安装时工具照常可用，
                  但**中文人名与机构名不会被脱敏**，终端会明确报出这一点。
                  查找顺序：--ner-model <目录> > 环境变量 LITEMASK_NER_MODEL
                            > <程序目录>/models/ner > ~/.litemask/models/ner
                  装模型包用 `model install <模型包.zip>`（默认装到上面最后一个位置）。
                  换下来的旧版本会保留在历史里（`model list` 能看到），
                  新版本不合适时用 `model rollback` 退回去。
                """);
        out.println();
        out.print("""
                模型包（model 子命令）:
                """);
        ModelCommand.usageNotes(out);
        out.println();
        out.print("""
                开机自动启动（autostart）:
                """);
        AutostartCommand.usageOptions(out);
        out.println();
        out.print("""
                说明:
                  这份说明随时可以用 --help 再看一遍；README 上写的是同一套命令与开关。
                """);
        redactUsageNotes(out);
        restoreUsageNotes(out);
        rulesUsageNotes(out);
        out.print("""
                  PDF 令牌为 ASCII 形式（<ID_1>），Word/Excel/OFD 为中文令牌，两边映射文件不通用
                  PDF 若没有文本层（扫描件）会明确报出——那不是「文档干净」
                """);
        serveUsageNotes(out);
        uninstallUsageNotes(out);
    }

    // ------------------------------------------------------------------
    // 各命令自己的用法（唯一真源）
    //
    // 【为什么要拆出来 —— 块 38 的根因修法】
    //   块 38 开工前的实测：`redact --help` 不给用法（反而**真的执行了脱敏**），
    //   `rules --help` 更隐蔽 —— 它**静默执行了 rules**，退出码 0，
    //   用户拿到一份规则表，却以为自己看到了用法。
    //   根因不是「忘了加 --help」，是**这些命令的用法只有一份、写在别处**：
    //   顶层 help 里的那几行文字，和它们各自该有的用法，天然是两份。
    //   → 现在 redact / restore / rules 三个命令的用法行与说明行各自成一份真源，
    //     顶层 help 与各自的 `--help` 都从那里取 —— 加一行只改一处。
    //   serve / uninstall 同理（那两个类的真源在它们自己那儿）。
    // ------------------------------------------------------------------

    /** {@code redact} 的命令行。顶层 help 与 {@code redact --help} 共用这一份。 */
    static String redactUsageText() {
        return """
                  redact  <文件...> [-o 输出 | --output 输出] [--map 映射.json] [--rules 规则.json]
                          [--ner-model 模型目录]
                          [--map-pass-env 变量 | --map-pass-file 文件 | --map-pass-stdin | --map-pass-prompt]
                  redact -            从标准输入读，结果打到标准输出
                """;
    }

    static void redactUsageLines(PrintStream out) {
        out.print(redactUsageText());
    }

    /** {@code redact} 的说明段（不含命令行，也不含抬头）。 */
    static String redactUsageNotes() {
        return """
                  redact 会为每个输入产出 <基名>.redacted<原后缀> 与 <原文件名>.redacted.map.json
                    （两种命名故意不一样：映射从输入名派生，所以对同一文件脱敏两次不会互相覆盖）
                """;
    }

    static void redactUsageNotes(PrintStream out) {
        out.print(redactUsageNotes());
    }

    /** {@code restore} 的命令行。 */
    static String restoreUsageText() {
        return """
                  restore <文件...> --map <映射.json>
                          [--map-pass-env 变量 | --map-pass-file 文件 | --map-pass-stdin | --map-pass-prompt]
                """;
    }

    static void restoreUsageLines(PrintStream out) {
        out.print(restoreUsageText());
    }

    /** {@code restore} 的说明段。 */
    static String restoreUsageNotes() {
        return """
                  restore 依据映射文件把占位符换回原文，输出 <基名>.restored<原后缀>
                """;
    }

    static void restoreUsageNotes(PrintStream out) {
        out.print(restoreUsageNotes());
    }

    /** {@code rules} 的命令行。 */
    static String rulesUsageText() {
        return """
                  rules   [--rules 规则.json]
                """;
    }

    static void rulesUsageLines(PrintStream out) {
        out.print(rulesUsageText());
    }

    /** {@code rules} 的说明段。 */
    static String rulesUsageNotes() {
        return """
                  rules 列出当前用的规则表；--rules 可以换一份自定义规则文件。
                """;
    }

    static void rulesUsageNotes(PrintStream out) {
        out.print(rulesUsageNotes());
    }

    /** {@code serve} 的说明段（命令行在 {@link ServeCommand} 自己那儿）。 */
    static void serveUsageNotes(PrintStream out) {
        ServeCommand.usageNotes(out);
    }

    /** {@code uninstall} 的说明段（命令行在 {@link UninstallCommand} 自己那儿）。 */
    static void uninstallUsageNotes(PrintStream out) {
        UninstallCommand.usageNotes(out);
    }

    private static String[] tail(String[] args) {
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    /**
     * 极简参数解析：本工具只有几个开关，不值得引依赖。
     *
     * <p><b>【块 38 改了什么 —— 根因就在 {@code default} 那一支】</b>
     *   原来 {@code default -> o.positional.add(args[i]);} 意味着：
     *   <b>任何不认识的 {@code --} 开关都被当成了文件名</b>。
     *   → {@code redact --ouput 合同.txt} 报出来的是「错误: --ouput」，
     *     那其实<b>不是</b>「工具认出了我拼错的开关」，而是「文件 {@code --ouput} 不存在」
     *     的异常消息 —— 用户看着像被认出来了，实际它根本没认。
     *   → 更糟的是 {@code redact 合同.txt --help}：{@code --help} 也被当成文件名，
     *     而合同**真的被脱敏了**（产出了 .redacted.txt 与映射件），最后才报错。
     *   → 现在：不认识的开关当场报成「未知开关」（并尽量指出你是不是想写另一个），
     *     {@code --help} 在任何动作之前短路。
     */
    private static final class Options {
        final List<String> positional = new ArrayList<>();
        String outputPath;
        String mapPath;
        String rulesPath;
        /**
         * NER 模型包目录。为 {@code null} 时按
         * {@link com.litemask.ner.NerModelRegistry#discover} 的顺序自动找。
         */
        String nerModelDir;
        /**
         * 映射件的口令来源。为 {@code null} 表示**不封装**（写明文），
         * 与本工具从前的行为一致 —— 加密是可选的，不是强制的。
         */
        PassphraseSource mapPass;
        /** 用户要的是帮助：解析完就短路，**不做任何动作**。 */
        boolean helpRequested;

        /**
         * 本组命令认识的全部开关 —— <b>从用法文本现算</b>，不另抄一份清单。
         *
         * <p>【为什么不另抄一份】
         *   另抄一份 = 第三份拷贝：加了开关忘了改清单，解析器就会把用户正在用的
         *   开关报成「未知开关」——而清单自己不会喊。
         *   用法文本（{@code redactUsageText()} 等）本来就是「这个命令认哪些开关」
         *   的真源，从它推导只改一处。
         */
        static final List<String> KNOWN_FLAGS = knownFlags();

        private static List<String> knownFlags() {
            List<String> flags = new ArrayList<>(CliHints.flagsIn(
                    redactUsageText() + restoreUsageText() + rulesUsageText()));
            // --help / -h 处处都认，但不写进「用法:」块：
            // 写进去的话，块 37 的守卫会要求 README 也教这两个写法，
            // 而 README 只教了 --help（-h 是顺手支持的别名，不必占一行版面）。
            if (!flags.contains("-h")) {
                flags.add("-h");
            }
            if (!flags.contains("--help")) {
                flags.add("--help");
            }
            return List.copyOf(flags);
        }

        static Options parse(String[] args) {
            Options o = new Options();
            boolean endOfFlags = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (endOfFlags) {
                    o.positional.add(arg);
                    continue;
                }
                if ("--".equals(arg)) {
                    // POSIX 惯例：-- 之后的一切都是文件名（哪怕它以 - 开头）。
                    endOfFlags = true;
                    continue;
                }
                switch (arg) {
                    case "-o", "--output" -> o.outputPath = o.value(args, ++i, "-o/--output");
                    case "--map" -> o.mapPath = o.value(args, ++i, "--map");
                    case "--rules" -> o.rulesPath = o.value(args, ++i, "--rules");
                    case "--ner-model" -> o.nerModelDir = o.value(args, ++i, "--ner-model");
                    case "--map-pass-env" -> o.setMapPass(
                            PassphraseSource.env(o.value(args, ++i, "--map-pass-env")));
                    case "--map-pass-file" -> o.setMapPass(
                            PassphraseSource.file(o.value(args, ++i, "--map-pass-file")));
                    case "--map-pass-stdin" -> o.setMapPass(PassphraseSource.stdin());
                    case "--map-pass-prompt" -> o.setMapPass(PassphraseSource.prompt());
                    case "-h", "--help" -> o.helpRequested = true;
                    default -> {
                        // 单独的 "-" 是「从标准输入读文档」，不是开关。
                        if (arg.length() > 1 && arg.startsWith("-")) {
                            throw new IllegalArgumentException(
                                    CliHints.unknownSwitchMessage(arg, KNOWN_FLAGS));
                        }
                        o.positional.add(arg);
                    }
                }
            }
            return o;
        }

        /**
         * 取下一个参数的值；缺值时**报错**而不是抛数组越界。
         *
         * <p>越界异常会一路冒到 {@code main} 的兜底 catch，报出来的是
         * 「Index 3 out of bounds for length 3」——用户看不懂。
         */
        private static String value(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " 后面缺值");
            }
            return args[index];
        }

        private void setMapPass(PassphraseSource src) {
            if (mapPass != null) {
                throw new IllegalArgumentException(
                        "映射件的口令来源只能给一个（已经有 " + mapPass.describe() + "）");
            }
            mapPass = src;
        }
    }

    private Main() {
    }
}