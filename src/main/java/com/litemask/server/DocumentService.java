package com.litemask.server;

import com.litemask.core.Exclusions;
import com.litemask.core.HitGrouping;
import com.litemask.core.RedactionEngine;
import com.litemask.core.RedactionResult;
import com.litemask.core.RedactionSession;
import com.litemask.core.Rule;
import com.litemask.docx.DocxRedactor;
import com.litemask.ofd.OfdRedactor;
import com.litemask.pdf.PdfRedactor;
import com.litemask.pptx.PptxRedactor;
import com.litemask.xlsx.XlsxRedactor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把五种格式的脱敏结果归一化成界面能用的同一份结构。
 *
 * <p>五个 redactor 各自有 {@code Report}/{@code Hit}，字段各不相同。
 * 界面不该、也不需要知道这些差异，所以归一化收在这里。
 *
 * <p><b>归一化时必须保住的一件事：位置信息有多少说多少，没有就说没有。</b>
 * 位置信息只影响用户核对时能看到多少上下文，不影响排除——
 * 排除键是 {@code (规则键, 原值)}，与位置无关。
 * 五种格式现在都能给出位置（PDF 给的是「第 N 页 · 文本块 M」），
 * 但位置<b>粒度</b>各不相同：Word 是段、Excel 是单元格、PDF 是内容流里的文本块。
 * 归一化不抹平这个差异——把「文本块 3」说成「第 3 行」就是骗用户。
 */
public final class DocumentService {

    /** 单条命中最多回传几个位置。多余的只报计数。 */
    private static final int MAX_LOCATORS = 20;

    public enum Format {
        DOCX, XLSX, PPTX, PDF, OFD, TXT;

        public static Format of(String extension) throws UnsupportedFormatException {
            String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
            return switch (ext) {
                case ".docx" -> DOCX;
                case ".xlsx" -> XLSX;
                case ".pptx" -> PPTX;
                case ".pdf" -> PDF;
                case ".ofd" -> OFD;
                case ".txt" -> TXT;
                default -> throw new UnsupportedFormatException(ext);
            };
        }

        public String label() {
            return switch (this) {
                case DOCX -> "Word 文档";
                case XLSX -> "Excel 工作簿";
                case PPTX -> "PowerPoint 演示文稿";
                case PDF -> "PDF";
                case OFD -> "OFD 版式文档";
                case TXT -> "纯文本";
            };
        }
    }

    /**
     * 归一化后的一条命中。
     *
     * @param ruleKey     规则键，界面回传排除项时就用它
     * @param original    原值（敏感数据，只回给通过鉴权的同源页面）
     * @param token       文档里实际写入的占位符；PDF 是 ASCII，其余是中文
     * @param occurrences 同一值在文档里出现的次数
     * @param locators    每一处出现的位置说明，最多 {@link #MAX_LOCATORS} 条。
     *                    每条都带自己的<b>出现序号</b>（引擎分配），
     *                    界面「只保留第 N 处」用的就是它。
     */
    public record HitItem(String ruleKey, String original, String token,
                          int occurrences, List<Loc> locators, boolean hasLocators,
                          List<Member> members) {

        /**
         * 一处出现：它在文档里的位置，以及它在全文里的第几次出现。
         *
         * @param occurrence 0 基的出现序号，由引擎分配。
         * @param locator    位置说明，如「第 3 段」「Sheet1!B7」「第 2 页 · 文本块 1」
         */
        public record Loc(int occurrence, String locator) {
        }

        /**
         * 一行里的一处命中。
         *
         * @param ruleKey  命中它的规则键
         * @param original 命中的原值
         * @param token    文档里实际写入的占位符
         */
        public record Member(String ruleKey, String original, String token) {
        }

        public HitItem {
            members = members == null || members.isEmpty() ? List.of() : List.copyOf(members);
        }

        /**
         * 这一行覆盖了几个<b>值</b>。
         *
         * <p>普通行是 1；归组行是 N（一个标识符被切成 N 段）。
         * 界面上的计数一律用「值」而不是「行」：用户勾的是值，
         * 脱敏的也是值，报告里写「保留 3 个值」比「保留 1 行」准确。
         */
        public int valueCount() {
            return members.isEmpty() ? 1 : members.size();
        }
    }

    /**
     * 扫描/应用的结果。
     *
     * @param hits        按 {@code (规则键, 原值)} 聚合后的命中
     * @param occurrences 命中总处数（不是去重后的条数）
     * @param notes       必须让用户看到的话，比如「这份 PDF 没有文本层」
     */
    public record Result(Format format, List<HitItem> hits, int occurrences,
                         Map<String, String> mapping, List<String> notes, Stats stats) {
    }

    /**
     * 各格式自己的计数，原样透传给界面而不是硬塞进一个通用结构。
     *
     * <p>刻意做成链式的 {@code put}：早先用 {@code of(String... kv)} 再 parseInt，
     * 那个写法把「键和值的顺序必须成对」这件事留给运行时检查——
     * 少写一个值就把整条请求打成 500，而这里全是编译期就该知道的事。
     */
    public static final class Stats {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Stats put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        public Map<String, Object> values() {
            return Map.copyOf(values);
        }
    }

    private DocumentService() {
    }

    /**
     * 跑一遍脱敏并归一化（纯正则，不带任何 AI 来源）。
     *
     * <p>保留这个重载是为了让「不启用 NER」是一条<b>显式</b>的选择而不是遗漏。
     */
    public static Result redact(Path src, Path out, Format format, List<Rule> rules,
                                Exclusions exclusions) throws IOException {
        return redact(src, out, format, rules, exclusions, List.of());
    }

    /**
     * 跑一遍脱敏并归一化。
     *
     * @param exclusions 用户要保留的命中；传 {@code Exclusions.NONE} 即全脱
     * @param sources   AI 来源（如 NER）。<b>与正则共用同一条替换通路</b>——
     *                   绝不给 AI 开第二条路径，否则两边的令牌编号会对不上、还原不回去
     */
    public static Result redact(Path src, Path out, Format format, List<Rule> rules,
                                Exclusions exclusions, List<com.litemask.core.EntitySource> sources)
            throws IOException {
        Exclusions excl = exclusions == null ? Exclusions.NONE : exclusions;
        List<com.litemask.core.EntitySource> srcs = sources == null ? List.of() : sources;
        List<Raw> raws = new ArrayList<>();
        Map<String, String> mapping;
        List<String> notes = new ArrayList<>();
        Stats stats;

        switch (format) {
            case DOCX -> {
                DocxRedactor.Report r = DocxRedactor.redact(src, out, rules, excl, srcs);
                for (DocxRedactor.Hit h : r.hits()) {
                    raws.add(new Raw(h.ruleKey(), h.original(), h.token(),
                            h.part() + " 第" + h.paragraphIndex() + "段",
                            h.unit(), h.start(), h.end(), h.occurrence()));
                }
                mapping = r.mapping();
                stats = new Stats()
                        .put("扫描段落", r.paragraphsScanned())
                        .put("改写部件", r.partsRewritten())
                        .put("清空属性", r.metadataFieldsCleared());
            }
            case XLSX -> {
                XlsxRedactor.Report r = XlsxRedactor.redact(src, out, rules, excl, srcs);
                for (XlsxRedactor.Hit h : r.hits()) {
                    raws.add(new Raw(h.ruleKey(), h.original(), h.token(),
                            h.part() + " " + h.locator(),
                            h.unit(), h.start(), h.end(), h.occurrence()));
                }
                mapping = r.mapping();
                stats = new Stats()
                        .put("扫描位置", r.locationsScanned())
                        .put("改写部件", r.partsRewritten())
                        .put("数值转文本", r.numericCellsConverted())
                        .put("工作表名", r.sheetNamesRedacted())
                        .put("清空属性", r.metadataFieldsCleared());
                if (r.numericCellsConverted() > 0) {
                    notes.add("有 " + r.numericCellsConverted()
                            + " 个数值单元格被转成了文本，该列的求和与排序会失效。");
                }
            }
            case PPTX -> {
                PptxRedactor.Report r = PptxRedactor.redact(src, out, rules, excl, srcs);
                for (PptxRedactor.Hit h : r.hits()) {
                    raws.add(new Raw(h.ruleKey(), h.original(), h.token(),
                            h.part() + " " + h.locator(),
                            h.unit(), h.start(), h.end(), h.occurrence()));
                }
                mapping = r.mapping();
                stats = new Stats()
                        .put("扫描位置", r.locationsScanned())
                        .put("改写部件", r.partsRewritten())
                        .put("图表数值", r.numericChartValuesRedacted())
                        .put("清空属性", r.metadataFieldsCleared());
                if (r.numericChartValuesRedacted() > 0) {
                    notes.add("有 " + r.numericChartValuesRedacted()
                            + " 个图表数值缓存被脱敏。pptx 的图表数据是内嵌的、打开时不重算，"
                            + "PowerPoint 会提示数据无效，刷新图表即可。");
                }
            }
            case PDF -> {
                PdfRedactor.Report r = PdfRedactor.redact(src, out, rules, new PdfRedactor.Options(),
                        excl, srcs);
                // 位置信息现在有了：内容流（哪一页 / 哪个表单）+ 文本块 + 块内偏移。
                // 所以 PDF 与其它格式一样，既能给出「在哪儿」，也能参与归组。
                //
                // token 用 Hit 里那个 ASCII 的：它是 PDF 里实际画出来的那个。
                // 引擎发的是中文令牌，照抄它就等于在界面上报了一个文档里并不存在的东西。
                for (PdfRedactor.Hit h : r.hits()) {
                    raws.add(new Raw(h.ruleKey(), h.original(), h.token(),
                            h.part() + " " + h.locator(),
                            h.unit(), h.start(), h.end(), h.occurrence()));
                }
                mapping = r.mapping();
                stats = new Stats()
                        .put("页数", r.pageCount())
                        .put("内容流", r.unitCount())
                        .put("可读字符", r.textChars())
                        .put("不可见字符", r.invisibleChars())
                        .put("清除隐藏信息", r.removedMetadata().size());
                notes.addAll(r.warnings());
                for (String m : r.removedMetadata()) {
                    notes.add("已清除：" + m);
                }
                if (!r.hasTextLayer()) {
                    // 「0 命中」在这里等于「没得脱」，不能报成「已脱干净」。
                    // 界面必须能把这句话显示出来，否则用户会拿扫描件当脱敏过的文件传出去。
                    notes.add("这份 PDF 没有文本层（多半是扫描件）。本工具不做 OCR，"
                            + "图像里的内容一个字都没有处理——0 处不是「文档干净」，"
                            + "是「没有可读的文本」。请先转存为带文本层的 PDF 再脱敏。");
                }
                if (r.hasInvisibleText()) {
                    notes.add("文档含 " + r.invisibleChars()
                            + " 个不可见字符（渲染模式 3，扫描件上盖的 OCR 层）。"
                            + "这类内容屏幕上看不见，但复制、检索都能拿到，已一并脱敏。");
                }
                if (!r.result().changes().isEmpty()) {
                    // PDF 的归组边界必须说出来：它只在**同一条内容流的同一个文本块内**生效。
                    // 跨块（换了一行）或跨内容流（页面正文 vs 表单）的两处命中，
                    // 在原文里本来就不挨着，所以不归组——这是对的，不是缺陷。
                    // 但用户看到「别的格式归成一行、PDF 没归」时会以为漏了，
                    // 所以这里要讲清楚边界在哪。
                    notes.add("PDF 的位置是「第 N 页 · 文本块 M」：同一条内容流里由定位算子"
                            + "（Tm/Td/TD/T* 等）切出的一段文本，通常就是一行，"
                            + "但排版工具也可能在一行之内重新定位，所以它不等同于肉眼看到的行。"
                            + "归组只在同一个文本块内生效——跨块或跨内容流的两处命中仍会显示成多行。");
                }
            }
            case OFD -> {
                OfdRedactor.Report r = OfdRedactor.redact(src, out, rules, excl, srcs);
                for (OfdRedactor.Hit h : r.hits()) {
                    raws.add(new Raw(h.ruleKey(), h.original(), h.token(),
                            h.part() + " " + h.carrier(),
                            h.unit(), h.start(), h.end(), h.occurrence()));
                }
                mapping = r.mapping();
                stats = new Stats()
                        .put("文本对象", r.textObjects())
                        .put("TextCode", r.textCodes())
                        .put("改写部件", r.partsRewritten())
                        .put("清空字段", r.fieldsCleared())
                        .put("改名条目", r.entriesRenamed())
                        .put("移除签章", r.signatureEntriesDropped());
                if (!r.clearedFields().isEmpty()) {
                    notes.add("已清空 " + r.clearedFields().size()
                            + " 个文档属性字段（人名/工具名，任何正则都命中不了）："
                            + String.join("、", r.clearedFields()));
                }
                if (r.entriesRenamed() > 0) {
                    notes.add("已改名 " + r.entriesRenamed()
                            + " 个附件条目（文件名里的个人信息），指向它们的引用已同步更新。");
                }
                if (r.signatureEntriesDropped() > 0) {
                    notes.add("已移除 " + r.signatureEntriesDropped()
                            + " 个签章条目：脱敏改动了页面内容，原签名必然验不过。"
                            + "留着它只会让读者看到「签名无效」，删掉并告知才是诚实的做法。");
                }
            }
            case TXT -> {
                String text = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);
                // 必须用带编号的重载。走 4 参那条（numbering=null）的话命中序号恒为 -1，
                // 位置级排除一条都匹配不上——而 txt 整篇就是一个段，
                // 用户勾「第 1 次」会得到一份原样全脱的文件，没有任何报错。
                // 直接用会话：它已经持有那个跨段计数器，顺带把分配器也管了。
                RedactionSession session = new RedactionSession(text, rules, excl, srcs);
                RedactionResult r = session.redact(text);
                java.nio.file.Files.writeString(out, r.sanitizedText(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (var c : r.changes()) {
                    // 整篇 txt 就是**一个**文本单元：段内偏移就是全文偏移。
                    raws.add(new Raw(c.ruleKey(), c.original(), c.token(),
                            "偏移 " + c.start() + "–" + c.end(),
                            HitGrouping.unitId("txt", "0"), c.start(), c.end(), c.occurrence()));
                }
                mapping = r.mapping();
                stats = new Stats()
                        .put("字符数", text.length());
            }
            default -> throw new IllegalStateException(
                    "枚举新增了成员但这里没接上: " + format
                            + "。这是代码缺陷，不是用户输入的问题。");
        }
        addCoverageNote(format, notes);
        // NER 状态必须进 notes，不能只进 stats：
        // stats 是给界面看的数字，notes 才是用户真正会读到的句子。
        // 「0 处」与「没装模型所以人名没脱」在数字上长得一模一样，
        // 只有把话说明白，用户才不会拿一份漏了人名的文件当脱敏过的传出去。
        //
        // 【为什么看 isActive 而不是「列表非空」】模型可以在服务运行期间被装上，
        // 于是列表里那个来源可能正因「模型还没准备好」而什么都没认出来。
        // 那种情况下「列表非空」是**假话**——它会让界面显示「识别来源：本地模型」，
        // 而实际上这一份文档的人名一个都没脱。
        boolean aiActive = false;
        for (com.litemask.core.EntitySource s : srcs) {
            if (s.isActive()) {
                aiActive = true;
                stats = stats.put("识别来源", s.name());
            }
        }
        if (!aiActive) {
            notes.add("中文人名/机构名识别未启用，本次只处理了正则覆盖的标识符"
                    + "（身份证、手机号、银行卡、邮箱等）。**中文人名与机构名没有被脱敏。**"
                    + "如需处理，请在界面的「识别模型」一栏里安装模型。");
        }
        return new Result(format, aggregate(raws, notes), raws.size(), mapping, notes, stats);
    }

    /**
     * 各格式的处理边界。这是每次结果都必须带回界面的产品事实，
     * 不能只写在帮助页：用户正在决定「这份文件能不能交出去」的时候才最需要它。
     */
    private static void addCoverageNote(Format format, List<String> notes) {
        String note = switch (format) {
            case DOCX -> "Word 本次处理的是已覆盖的文本与明确属性。"
                    + "图片像素、嵌入文件、域代码（field codes）与其他未明确覆盖的属性不会改写，"
                    + "不能据此认为所有隐藏信息已清除。";
            case XLSX -> "Excel 本次处理的是已覆盖的单元格、名称、批注与属性。"
                    + "图片像素、嵌入对象、公式源文本与图表缓存不在处理范围，"
                    + "不能据此认为工作簿的所有隐藏内容已清除。";
            case PPTX -> "PowerPoint 本次处理的是已覆盖的文本、备注、属性和图表缓存。"
                    + "图片像素、嵌入工作簿与 OLE 对象不会改写；"
                    + "即使图表缓存已修改，也不代表嵌入的原始数据源已处理。";
            case PDF -> "PDF 本次处理的是可解析文本层与已明确覆盖的隐藏条目。"
                    + "图片像素不会改变：扫描件、图文混排 PDF 和带 OCR 文本层的 PDF 都一样，"
                    + "脱掉 OCR 文本不等于涂黑底下的图片。"
                    + "未明确覆盖的深层对象也不做全文档已清理保证。";
            case OFD -> "OFD 本次处理的是文本对象、已覆盖属性、附件名与签章条目。"
                    + "图片像素和附件文件的内容不会改写；附件改名不等于附件内容已脱敏。";
            case TXT -> null;
        };
        if (note != null) {
            notes.add(note);
        }
    }

    /**
     * 还原的结果。
     *
     * @param count    实际替换了几处
     * @param warnings 必须让用户看到的话（PDF 还原时的字体/字形警告、OFD 的不可逆项…）
     */
    public record RestoreResult(int count, List<String> warnings) {
    }

    /**
     * 还原：把令牌换回原文。
     *
     * <p>与 {@link #redact} 对称：同一个 {@link Format} 分发到同一个 redactor 的
     * {@code restore}。**映射的方向是「令牌 → 原值」**（{@code token -> original}），
     * 与 CLI 侧完全一致——所以 GUI 产出的映射文件能直接喂给 {@code LightMeter restore --map}。
     *
     * <p><b>还原的是文本内容，不是文件字节。</b>元数据清空、签章移除、
     * 数值单元格转文本这些动作都是<b>不可逆</b>的：原值从来没被保存过，
     * 还原也带不回来。这一点必须由 {@link RestoreResult#warnings()} 说出去，
     * 不能让用户拿到一份「还原件」就以为它与原件逐字节相同。
     */
    public static RestoreResult restore(Path in, Path out, Format format,
                                        Map<String, String> mapping) throws IOException {
        com.litemask.core.TaskContext.current().checkCancelled();
        Map<String, String> m = mapping == null ? Map.of() : mapping;
        List<String> warnings = new ArrayList<>();
        if (m.isEmpty()) {
            java.nio.file.Files.copy(in, out,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            warnings.add("映射为空：这份脱敏件没有可还原的令牌，已原样生成还原件。");
            warnings.add("还原的是**文本内容**：文档属性清空、签章移除、数值单元格转文本这些动作不可逆，"
                    + "还原件与原件在文本上一致，但不是同一个文件。");
            return new RestoreResult(0, List.copyOf(warnings));
        }
        int count = switch (format) {
            case DOCX -> DocxRedactor.restore(in, out, m);
            case XLSX -> XlsxRedactor.restore(in, out, m);
            case PPTX -> PptxRedactor.restore(in, out, m);
            case OFD -> {
                int n = OfdRedactor.restore(in, out, m);
                warnings.add("OFD 的 Author/Creator 是直接清空的、签章已移除，"
                        + "这两项原值从未保存，还原不会带回来。");
                yield n;
            }
            case PDF -> {
                PdfRedactor.Report r = PdfRedactor.restore(in, out, m);
                warnings.addAll(r.warnings());
                yield r.changeCount();
            }
            case TXT -> {
                String text = java.nio.file.Files.readString(in, java.nio.charset.StandardCharsets.UTF_8);
                RedactionEngine.Restored restored = RedactionEngine.restoreCounted(text, m);
                java.nio.file.Files.writeString(out, restored.text(),
                        java.nio.charset.StandardCharsets.UTF_8);
                yield restored.count();
            }
        };
        com.litemask.core.TaskContext.current().checkCancelled();
        warnings.add("还原的是**文本内容**：文档属性清空、签章移除、数值单元格转文本这些动作不可逆，"
                + "还原件与原件在文本上一致，但不是同一个文件。");
        return new RestoreResult(count, List.copyOf(warnings));
    }

    /**
     * 按 {@code (规则键, 原值)} 聚合。
     *
     * <p>这个键就是排除项的键——界面把它原样发回来即可，
     * 不需要服务端再发一个不透明的 id（不透明的 id 会让人在排障时对不上号）。
     *
     * <p>顺序按首次出现，保持稳定：同一份文件两次扫描的命中列表顺序一致，
     * 否则界面上的复选框会在刷新后跑到别的行去。
     *
     * <p><b>位置说明带着自己的出现序号</b>：第 i 条位置说明对应引擎算出来的「第 i 次出现」，
     * 序号由引擎分配、原样透传（见 {@link com.litemask.core.Match#occurrence}）。
     * <b>不是这里按清单顺序数出来的</b>——那会让「同一个事实有两处定义」：
     * 只要某一种格式的命中缺位置信息（序号就少一个）或归组吃掉了一段，
     * 数出来的序号就会与引擎判「第 N 次」用的那个数错开，
     * 用户勾的第 2 处就会落到别的位置上去，而界面上看不出任何异常。
     * 超过 {@link #MAX_LOCATORS} 的部分不展开，但<b>必须说出来</b>——
     * 界面上「出现 30 次」配一份 20 行的位置清单，用户会以为那就是全部。
     */
    private static List<HitItem> aggregate(List<Raw> raws, List<String> notes) {
        List<HitGrouping.PositionedHit> positioned = new ArrayList<>(raws.size());
        for (Raw raw : raws) {
            positioned.add(new HitGrouping.PositionedHit(
                    raw.ruleKey, raw.original, raw.token, raw.locator, raw.unit, raw.start, raw.end,
                    raw.occurrence));
        }
        // 先归组：原文里紧挨着、被不同规则分别认领的几处命中合成一条。
        List<HitGrouping.Group> groups = HitGrouping.group(positioned);

        // 没归组的命中按 (规则键, 原值) 聚合：同一个值在文档里出现多次时，
        // 界面上是一行，「出现处」是它的出现次数。
        Map<String, ValueAgg> byKey = new LinkedHashMap<>();
        for (HitGrouping.Group g : groups) {
            if (g.merged()) {
                continue;
            }
            HitGrouping.PositionedHit m = g.members().get(0);
            String key = 值键(m.ruleKey(), m.original());
            ValueAgg agg = byKey.get(key);
            if (agg == null) {
                agg = new ValueAgg(m.ruleKey(), m.original(), m.token());
                byKey.put(key, agg);
            }
            agg.count++;
            if (m.locator() != null) {
                agg.hasLoc = true;
                if (agg.locators.size() < MAX_LOCATORS) {
                    // 序号取自引擎，不是这里数出来的：
                    // 引擎判「第 N 次」用的就是这个数，界面上也得是同一个数。
                    agg.locators.add(new HitItem.Loc(m.occurrence(), m.locator()));
                }
            }
        }

        List<HitItem> out = new ArrayList<>(groups.size());
        Set<String> emitted = new HashSet<>();
        for (HitGrouping.Group g : groups) {
            if (g.merged()) {
                out.add(mergedItem(g));
                continue;
            }
            HitGrouping.PositionedHit m = g.members().get(0);
            String key = 值键(m.ruleKey(), m.original());
            if (!emitted.add(key)) {
                continue;                       // 同一个值的第 2、3 次出现，已并进上面那行
            }
            ValueAgg agg = byKey.get(key);
            out.add(new HitItem(agg.ruleKey, agg.original, agg.token, agg.count,
                    List.copyOf(agg.locators), agg.hasLoc,
                    List.of(new HitItem.Member(agg.ruleKey, agg.original, agg.token))));
        }
        if (notes != null) {
            int truncated = 0;
            int hidden = 0;
            for (HitItem h : out) {
                // 必须先看 hasLocators：没有位置信息的命中，locators 恒为空，
                // 而 occurrences 是 1 —— 不加这一句，每一份这样的文档都会被算成
                // 「有 N 个值出现次数超过 20 次、另有 N 处未展开」，
                // 那句话对只出现一次的值是**假的**。
                if (h.hasLocators() && h.locators().size() < h.occurrences()) {
                    truncated++;
                    hidden += h.occurrences() - h.locators().size();
                }
            }
            if (truncated > 0) {
                notes.add("有 " + truncated + " 个值出现次数超过 " + MAX_LOCATORS
                        + " 次，位置清单只展开前 " + MAX_LOCATORS + " 次，另有 " + hidden
                        + " 处未展开。未展开的那些仍然会被正常脱敏，只是界面上选不到位置。");
            }
        }
        return out;
    }

    /**
     * 归组出来的一行。
     *
     * <p><b>映射不变</b>：这一行仍然对应 N 个令牌、N 条映射条目。
     * 归组只改「界面上怎么显示」，不改「文档里写了什么」「怎么还原」。
     */
    private static HitItem mergedItem(HitGrouping.Group g) {
        List<HitItem.Member> members = new ArrayList<>(g.valueCount());
        List<HitItem.Loc> locators = new ArrayList<>();
        boolean hasLoc = false;
        for (HitGrouping.PositionedHit m : g.members()) {
            members.add(new HitItem.Member(m.ruleKey(), m.original(), m.token()));
            if (m.locator() != null) {
                hasLoc = true;
                if (locators.stream().noneMatch(l -> l.locator().equals(m.locator()))) {
                    locators.add(new HitItem.Loc(m.occurrence(), m.locator()));
                }
            }
        }
        return new HitItem(g.ruleKey(), g.original(), g.token(), 1,
                List.copyOf(locators), hasLoc, members);
    }

    /**
     * 排除项的键：{@code (规则键, 原值)}。
     *
     * <p>用 NUL 而不是空串拼键：{@code ("phone", "138")} 与
     * {@code ("phone1", "38")} 拼出来必须是两个不同的键。写成
     * {@code ruleKey + original} 时前者是 {@code phone138}、后者也是 {@code phone138}，
     * 两类命中的计数与位置会串成一坨，界面上「第 N 次」从此指不到地方。
     * 这个字符不可能出现在规则键或原值里。
     */
    private static String 值键(String ruleKey, String original) {
        return ruleKey + '\0' + original;
    }

    /** 同一个值在文档里的聚合结果。 */
    private static final class ValueAgg {
        final String ruleKey;
        final String original;
        final String token;
        final List<HitItem.Loc> locators = new ArrayList<>();
        int count;
        boolean hasLoc;

        ValueAgg(String ruleKey, String original, String token) {
            this.ruleKey = ruleKey;
            this.original = original;
            this.token = token;
        }
    }

    /**
     * 归一化之前的原始命中。
     *
     * @param locator 位置说明；可为 null（没有位置信息的命中）
     * @param unit    所属文本单元的标识；null 表示不知道 → 不参与归组
     * @param start   命中在单元文本里的起点；-1 表示不知道 → 不参与归组
     * @param end     命中在单元文本里的终点（不含）
     * @param occurrence 同一 {@code (规则键, 原值)} 在整篇文档里的第几次出现，0 基；
     *                   {@link com.litemask.core.Match#UNNUMBERED} 表示这一轮没有编号。
     *                   <b>原样透传</b>：它由引擎分配，界面上「第 N 处」与引擎
     *                   判「第 N 次」用的是同一个数，中间任何一层都不许自己重数。
     */
    private record Raw(String ruleKey, String original, String token, String locator,
                       String unit, int start, int end, int occurrence) {
    }

    /** 不支持的文件类型。 */
    public static final class UnsupportedFormatException extends Exception {
        public UnsupportedFormatException(String extension) {
            super("不支持的文件类型: " + extension);
        }
    }
}
