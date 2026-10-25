package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link NerModelPackage}：模型包加载器的七道闸。
 *
 * <p><b>这些测试的价值全在负向用例上。</b>加载器干的事是「检查一堆东西，
 * 检查不过就拒绝」，而正向用例只要清单写对了就必然过，几乎测不出什么。
 * 真正会出事的是「本该拒绝却放行了」——那意味着一个坏包被加载，
 * 然后实体识别静默出错、文档少脱或者错脱。所以下面每一条闸都有一个
 * 「构造一个坏包，断言它被拒」的用例。
 *
 * <p>其中 {@link #rejectsEmbeddingRowMismatch()} 单独立出来，
 * 因为它是本项目<b>实际踩过</b>的那一类故障的防线，详见那个用例的注释。
 */
class NerModelPackageTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ 正向

    @Test
    @DisplayName("完整的包能加载，并能识别出玩具模型里的实体")
    void loadsCompletePackage() throws Exception {
        Path dir = NerModelFixtures.writePackage(tmp.resolve("ok"));
        try (NerModelPackage pkg = NerModelPackage.load(dir)) {
            assertEquals(63, pkg.vocab().size(), "词表应是 64 - 1（UNK 占位）");
            assertEquals(64, pkg.vocab().idCount());
            assertEquals("test-fixture", pkg.manifest().id());
            assertEquals(5, pkg.recognizer().scheme().size());

            List<EntitySpan> spans = pkg.recognizer().recognizeAll("张三到北京大学。");
            List<String> texts = new ArrayList<>();
            for (EntitySpan s : spans) {
                texts.add("张三到北京大学。".substring(s.start(), s.end()) + "/" + s.type().code());
            }
            // 必须真的认出两个实体。只断言「不抛异常」的话，
            // 一个永远返回空列表的实现也能全绿——而那正是「NER 没生效」的现场。
            assertEquals(List.of("张三/person", "北京大学/organization"), texts,
                    "玩具模型的权重表是写死的，这个结果应当是确定的");
        }
    }

    @Test
    @DisplayName("带 CRF 的包能加载，识别结果与不带 CRF 的一致")
    void loadsPackageWithCrf() throws Exception {
        Path plain = NerModelFixtures.writePackage(
                tmp.resolve("plain"), NerModelFixtures.onnx(false),
                NerModelFixtures.vocabText(), false);
        Path crf = NerModelFixtures.writePackage(
                tmp.resolve("crf"), NerModelFixtures.onnx(true),
                NerModelFixtures.vocabText(), true);
        String text = "张三到北京大学。";
        try (NerModelPackage a = NerModelPackage.load(plain);
             NerModelPackage b = NerModelPackage.load(crf)) {
            List<EntitySpan> sa = a.recognizer().recognizeAll(text);
            List<EntitySpan> sb = b.recognizer().recognizeAll(text);
            assertEquals(sa.size(), sb.size(), "玩具模型的结构约束已经覆盖了转移分数，"
                    + "加不加 CRF 结果应当一致");
            for (int i = 0; i < sa.size(); i++) {
                assertEquals(sa.get(i).start(), sb.get(i).start());
                assertEquals(sa.get(i).type(), sb.get(i).type());
            }
        }
    }

    @Test
    @DisplayName("entitySource() 把识别结果接成引擎认识的 Match")
    void entitySourceProducesEngineMatches() throws Exception {
        Path dir = NerModelFixtures.writePackage(tmp.resolve("src"));
        try (NerModelPackage pkg = NerModelPackage.load(dir)) {
            var src = pkg.entitySource();
            assertEquals("ner", src.name());
            var matches = src.collect("张三到北京大学。");
            assertEquals(2, matches.size(), "应有 2 个 Match");
            assertEquals(NerEntitySource.RULE_PERSON, matches.get(0).ruleKey());
            assertEquals("张三", matches.get(0).text());
            assertEquals(NerEntitySource.RULE_ORGANIZATION, matches.get(1).ruleKey());
            assertEquals("北京大学", matches.get(1).text());
            // 优先级必须压过全部正则（实测最高 120）：否则机构名会被切成
            // 「北京 <地名1> 大学」，报告却一切正常。
            assertEquals(900, matches.get(0).sortOrder());
        }
    }

    // ------------------------------------------------------------------ 第 6 道闸：嵌入行数

    @Test
    @DisplayName("词表与模型不是同一对产物时拒绝加载——这正是本项目踩过的那个 bug")
    void rejectsEmbeddingRowMismatch() throws Exception {
        // 构造：词表少一个字 → idCount 变成 63，而嵌入矩阵仍是 64 行。
        //
        // 这个形态在真实项目里出现过：名单式语料合成把换行符带进字符表，
        // save 写出空行、load 跳过空行，4586 个字只加载回 4585 个，
        // 于是**每个字符的 id 从那行起整体错位一格**。现场是训练日志 F1 0.8397、
        // 产物重打分 0.1209、没有任何报错——两个文件各自都合法，合起来才不合法。
        //
        // 注意这里只改词表、不改 sha256 之外的东西，所以摘要闸会先放行，
        // 真正抓住它的是第 6 道闸。这正是要证明的：**逐个文件校验是不够的**。
        String vocab = NerModelFixtures.vocabText();
        String trimmed = vocab.substring(0, vocab.length() - 2);   // 去掉最后一个字和它的换行
        byte[] model = NerModelFixtures.onnx(false);
        Path dir = tmp.resolve("mismatch");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), trimmed, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"),
                NerModelFixtures.toJson(NerModelFixtures.manifest(model, trimmed, false)),
                StandardCharsets.UTF_8);

        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        String msg = e.getMessage();
        assertTrue(msg.contains("不是同一对产物"), "错误信息必须说清是模型与词表不配套，实际: " + msg);
        assertTrue(msg.contains("64"), "应当报出嵌入矩阵的行数，实际: " + msg);
        assertTrue(msg.contains("63"), "应当报出词表字数，实际: " + msg);
    }

    @Test
    @DisplayName("清单里的嵌入张量名不存在时拒绝——不能「查不到就当没问题」")
    void rejectsMissingEmbeddingTensor() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        m.put("embeddingTensor", "no.such.tensor");
        Path dir = tmp.resolve("noemb");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("no.such.tensor"), "错误信息里要点名这个张量: " + e.getMessage());
    }

    // ------------------------------------------------------------------ 第 1 道闸：格式版本

    @Test
    @DisplayName("不认识的清单版本一律拒绝，不做「尽力解析」")
    void rejectsUnknownFormatVersion() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        m.put("formatVersion", NerModelPackage.SUPPORTED_FORMAT_VERSION + 1);
        Path dir = tmp.resolve("badver");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("格式版本"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("清单里出现不认识的字段就拒绝——拼错的字段名不能被静默忽略")
    void rejectsUnknownManifestField() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        // 模拟真实的手滑：maxEntityChars 少打一个 s。
        m.put("maxEntityChar", 8);
        Path dir = tmp.resolve("unknownfield");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("maxEntityChar"), "应当点名那个多出来的字段: " + e.getMessage());
    }

    @Test
    @DisplayName("清单本身不是合法 JSON 时包成 InvalidModelPackage，不当 IOException 抛")
    void rejectsBrokenManifest() throws Exception {
        Path dir = tmp.resolve("broken");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{ not json", StandardCharsets.UTF_8);
        assertThrows(NerModelPackage.InvalidModelPackage.class, () -> NerModelPackage.load(dir));
    }

    // ------------------------------------------------------------------ 第 2 道闸：路径

    @Test
    @DisplayName("清单里的文件名不能越出模型包目录")
    void rejectsPathTraversal() {
        // 逐个字符测：这些形态都能让「按清单里的路径打开文件」跑到包外面去。
        // 与 zip-slip 同一类入口，只是这里走的是 JSON 而不是 zip 条目名。
        //
        // 列表里刻意混进了两种「只有分隔符/空字节检查能挡住」的输入：
        //  - 含 NUL 的名字：Path.resolve 会抛 InvalidPathException，
        //    那是个与「模型包不合法」完全无关的异常类型，调用方分不出来；
        //  - "..\\x"：在 Unix 上反斜杠是**合法文件名字符**，不构成穿越，
        //    但在 Windows 上就是上级目录。跨平台的行为必须在换平台之前就钉住。
        // 这两条都是第一版漏掉的：正因为漏了，「撤掉分隔符检查」这个变异拿到了 GREEN，
        // 而当时的现象被误读成「路径检查不重要」。
        for (String bad : List.of("../ner.onnx", "../../etc/passwd", "/etc/passwd",
                "sub/../ner.onnx", "a/b.onnx", "a\\b.onnx", "..\\ner.onnx", ".", "..", "")) {
            var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                    () -> NerModelPackage.resolveInside(tmp, bad),
                    "文件名 " + bad + " 必须被拒绝");
            assertNotNull(e.getMessage());
        }
        assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.resolveInside(tmp, "ner\0.onnx"),
                "含 NUL 的文件名必须被拒绝成 InvalidModelPackage，而不是 InvalidPathException");
    }

    @Test
    @DisplayName("清单指向 ../ner.onnx 时整包拒绝加载")
    void rejectsTraversalInManifest() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> modelRef = (Map<String, Object>) m.get("files") instanceof Map
                ? (Map<String, Object>) ((Map<?, ?>) m.get("files")).get("model") : null;
        assertNotNull(modelRef, "夹具的 files.model 结构变了，先修夹具");
        modelRef.put("path", "../ner.onnx");
        Path dir = tmp.resolve("trav");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        assertThrows(NerModelPackage.InvalidModelPackage.class, () -> NerModelPackage.load(dir));
    }

    @Test
    @DisplayName("模型包里的文件不能是符号链接")
    void rejectsSymlink() throws Exception {
        // 软链会让前面的路径检查形同虚设：名字看着在包内，
        // Files.isRegularFile 默认跟随它，于是读的是包外的文件。
        Path dir = tmp.resolve("link");
        Files.createDirectories(dir);
        Path outside = tmp.resolve("outside.onnx");
        Files.write(outside, NerModelFixtures.onnx(false));
        try {
            Files.createSymbolicLink(dir.resolve("ner.onnx"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;   // 本机不允许建软链（如 Windows 未开开发者模式），这条跳过
        }
        Files.writeString(dir.resolve("vocab.txt"), NerModelFixtures.vocabText(),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.resolveInside(dir, "ner.onnx"));
        assertTrue(e.getMessage().contains("符号链接"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("清单指向的普通文件不存在时拒绝，并点名是哪个文件")
    void rejectsMissingFile() throws Exception {
        Path dir = tmp.resolve("missing");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("vocab.txt"), NerModelFixtures.vocabText(),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.resolveInside(dir, "ner.onnx"));
        assertTrue(e.getMessage().contains("ner.onnx"), "实际: " + e.getMessage());
    }

    // ------------------------------------------------------------------ 第 3 道闸：摘要

    @Test
    @DisplayName("文件与清单里的摘要对不上时拒绝")
    void rejectsDigestMismatch() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        // 摘要仍按**原来的字节**算，但落盘的是别的字节 —— 模拟下载到一半 / 被替换。
        Path dir = tmp.resolve("digest");
        Files.createDirectories(dir);
        byte[] tampered = model.clone();
        tampered[tampered.length - 1] ^= 0xFF;
        Files.write(dir.resolve("ner.onnx"), tampered);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("摘要"), "实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("ner.onnx"), "错误信息要点名是哪个文件: " + e.getMessage());
    }

    @Test
    @DisplayName("清单里缺摘要字段时拒绝")
    void rejectsMissingDigest() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> vocabRef = (Map<String, Object>) ((Map<?, ?>) m.get("files")).get("vocab");
        vocabRef.remove("sha256");
        Path dir = tmp.resolve("nodigest");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("sha256"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("files 段只给了一半时拒绝——模型与词表必须来自同一次导出")
    void rejectsHalfPackage() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) m.get("files");
        files.remove("vocab");
        Path dir = tmp.resolve("half");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("model 与 vocab"), "实际: " + e.getMessage());
    }

    // ------------------------------------------------------------------ 第 4 道闸：词表

    @Test
    @DisplayName("词表中间有空行时拒绝，而不是跳过——跳过就是把错位藏起来")
    void rejectsBlankLineInVocab() throws Exception {
        String vocab = "。\n张\n\n三\n";
        // 只放 4 个字的词表必然与 64 行的嵌入对不上，所以要断言错误信息
        // 指向**空行**而不是行数不匹配——顺序错了这条测试就在测别的东西。
        byte[] model = NerModelFixtures.onnx(false);
        Path dir = tmp.resolve("blank");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"),
                NerModelFixtures.toJson(NerModelFixtures.manifest(model, vocab, false)),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("空行"),
                "必须指出是空行，而不是后面的行数不匹配。实际: " + e.getMessage());
    }

    @Test
    @DisplayName("词表末尾的换行是合法的，不能当成空行")
    void acceptsTrailingNewline() throws Exception {
        // 每个正常写出的文本文件都以换行结尾。要是把它当成空行拒绝，
        // 所有真实模型包都加载不了——这条是把「拒绝空行」和「拒绝多一个换行」分开。
        String withNewline = NerModelFixtures.vocabText();
        String withoutNewline = withNewline.substring(0, withNewline.length() - 1);
        assertTrue(withNewline.endsWith("\n"), "夹具前提：写出的词表以换行结尾");
        assertEquals(63, NerModelPackage.readVocab(writeTemp(withNewline)).size());
        assertEquals(63, NerModelPackage.readVocab(writeTemp(withoutNewline)).size(),
                "末尾换行可有可无，但不能改变词表长度");
    }

    @Test
    @DisplayName("空词表被拒绝")
    void rejectsEmptyVocab() throws Exception {
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.readVocab(writeTemp("")));
        assertTrue(e.getMessage().contains("空"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("词表里出现重复字符时被拒绝")
    void rejectsDuplicateCharInVocab() throws Exception {
        // 重复字符会让两个字符映射到同一个 id，其中一个永远不被用上，
        // 而嵌入矩阵多出来的那一行是空的。症状是「模型少认一种字」，
        // 不报错。交给 CharVocab 挡，这里只验证错误确实传上来了。
        assertThrows(IllegalArgumentException.class,
                () -> CharVocab.ofListedChars(NerModelPackage.readVocab(
                        writeTemp("张\n三\n张\n"))));
    }

    private Path writeTemp(String content) throws Exception {
        Path p = Files.createTempFile(tmp, "vocab", ".txt");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // ------------------------------------------------------------------ 标签方案

    @Test
    @DisplayName("标签后缀没在 tagTypes 里声明时拒绝")
    void rejectsUndeclaredTagType() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        @SuppressWarnings("unchecked")
        Map<String, String> tt = (Map<String, String>) m.get("tagTypes");
        tt.remove("ORG");
        Path dir = tmp.resolve("undeclared");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("ORG"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("tagTypes 指向本工具不认识的规则码时拒绝——模型包不能自己造脱敏规则")
    void rejectsUnknownRuleCode() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        @SuppressWarnings("unchecked")
        Map<String, String> tt = (Map<String, String>) m.get("tagTypes");
        tt.put("ORG", "bankcard");     // 想让模型包凭空造一条银行卡脱敏规则
        Path dir = tmp.resolve("unknownrule");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("bankcard"), "实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("person/organization"),
                "错误信息要列出白名单，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("完全没有 tagTypes 段时拒绝——不能让模型包自行决定")
    void rejectsMissingTagTypes() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        m.remove("tagTypes");
        Path dir = tmp.resolve("notagtypes");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("tagTypes"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("scoreSpace 写错时拒绝，不能猜")
    void rejectsUnknownScoreSpace() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, false);
        m.put("scoreSpace", "LOGPROB");
        Path dir = tmp.resolve("space");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("scoreSpace"), "实际: " + e.getMessage());
    }

    // ------------------------------------------------------------------ 通道数与转移矩阵

    @Test
    @DisplayName("模型通道数与标签方案对不上时拒绝")
    void rejectsChannelMismatch() throws Exception {
        byte[] sevenCh = readResource("/ner-fixture-7ch.onnx");
        // 前提断言：这个夹具确实是 7 通道的。不写这一条的话，
        // 下面的 reject 可能是因为别的原因红的，这条用例就变成永远为真。
        try (com.litemask.ner.OnnxRunner r =
                     com.litemask.ner.OnnxRunner.open(sevenCh, "ids", "logits")) {
            assertEquals(7, r.run(new long[]{1, 2, 3})[0].length, "夹具前提：7 通道模型");
        }
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(sevenCh, vocab, false);
        Path dir = tmp.resolve("channels");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), sevenCh);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("通道数"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("清单说有 transition 输出但模型没有时拒绝")
    void rejectsMissingTransitionOutput() throws Exception {
        byte[] model = NerModelFixtures.onnx(false);   // 不带 transition
        String vocab = NerModelFixtures.vocabText();
        Map<String, Object> m = NerModelFixtures.manifest(model, vocab, true);  // 清单说有
        Path dir = tmp.resolve("notrans");
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), model);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), NerModelFixtures.toJson(m),
                StandardCharsets.UTF_8);
        var e = assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(dir));
        assertTrue(e.getMessage().contains("transition"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("转移矩阵里的 -1e4 被还原成「不可能」，不是当成一个有限的分数")
    void restoresMaskedTransitions() throws Exception {
        // 这是本项目一个具体决定的另一半：训练侧屏蔽值是 -inf，
        // 导出成 ONNX 时换成 -1e4（-inf 会被某些后端算成 NaN）。
        // 加载侧**必须还原**，否则 SequenceDecoder 里那条
        // 「全路径 -Infinity 就报错」的安全网会被绕过——
        // 全是有限的 -1e4，isInfinite 不触发，于是模型包配错时
        // 不报错，而是编出一条结构非法的路径，也就是静默错脱。
        byte[] model = NerModelFixtures.onnx(true);
        String vocab = NerModelFixtures.vocabText();
        Path dir = NerModelFixtures.writePackage(tmp.resolve("crfmask"), model, vocab, true);
        try (NerModelPackage pkg = NerModelPackage.load(dir)) {
            TagScheme scheme = pkg.recognizer().scheme();
            int c = scheme.size();
            double[] tr = NerModelPackage.readTransitions(pkg.recognizerRunner(), "transition", scheme);
            int impossible = 0;
            for (int from = 0; from < c; from++) {
                for (int to = 0; to < c; to++) {
                    double v = tr[from * c + to];
                    if (!scheme.allows(from, to)) {
                        assertEquals(SequenceDecoder.IMPOSSIBLE, v,
                                "结构上非法的 [" + from + "][" + to + "] 必须是 -Infinity");
                        impossible++;
                    } else {
                        assertFalse(v == SequenceDecoder.IMPOSSIBLE,
                                "结构上合法且分数正常的 [" + from + "][" + to + "] 不该被抹成 -Infinity");
                        assertTrue(v > NerModelPackage.EXPORT_MASK_VALUE,
                                "合法转移的分数 " + v + " 不该被当成导出占位值");
                    }
                }
            }
            // 前提断言：夹具里确实有被屏蔽的位置，否则上面那些 assertFalse 恒真。
            assertTrue(impossible > 0, "夹具前提：应当存在结构上非法的转移，实际 0 个");
        }
    }

    // ------------------------------------------------------------------ 目录与关闭

    @Test
    @DisplayName("目录不存在时拒绝")
    void rejectsMissingDirectory() {
        assertThrows(NerModelPackage.InvalidModelPackage.class,
                () -> NerModelPackage.load(tmp.resolve("nope")));
    }

    @Test
    @DisplayName("关闭后再推理要报错，不能悄悄返回空")
    void closedPackageRefusesToRun() throws Exception {
        Path dir = NerModelFixtures.writePackage(tmp.resolve("closed"));
        NerModelPackage pkg = NerModelPackage.load(dir);
        assertFalse(pkg.recognizer().recognizeAll("张三").isEmpty(), "前提：关闭前能识别");
        pkg.close();
        pkg.close();   // 幂等
        var e = assertThrows(IllegalStateException.class, () -> pkg.recognizer().recognizeAll("张三"));
        assertTrue(e.getMessage().contains("关闭"), "实际: " + e.getMessage());
    }

    private static byte[] readResource(String name) throws Exception {
        try (java.io.InputStream in = NerModelPackageTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "找不到测试资源 " + name);
            return in.readAllBytes();
        }
    }
}
