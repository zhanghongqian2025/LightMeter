package com.litemask.ner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 在临时目录里拼出一个模型包，供 {@link NerModelPackageTest} 与
 * {@link OnnxTensorShapesTest} 使用。
 *
 * <p><b>为什么用玩具模型而不是真模型</b>：真模型 7MB，放进测试资源会让每个测试类
 * 都拖着它；更重要的是期望值。玩具模型的每个通道分数是显式写死的常量，
 * 「张三是人名」这件事可以在测试里手算出来，不依赖任何机器学习框架。
 * 这满足本项目的一条硬规矩：<b>标尺必须独立于被测实现</b>。
 *
 * <p>夹具由 {@code tools/make_ner_fixture.py} 生成（纯标准库手写 protobuf），
 * 权重表 {@code table[64][5]}，字符 id 写死：
 * {@code 。=1 张=2 三=3 丰=4 北=5 京=6 大=7 学=8}，其余为 UNK(0)。
 * 所以词表文件的前 8 行必须严格按这个顺序，否则玩具模型认不出这些字。
 */
final class NerModelFixtures {

    /** 夹具的嵌入矩阵行数（{@code table} 的第一维）。词表字数 + 1 必须等于它。 */
    static final int EMBEDDING_ROWS = 64;

    /** 夹具里构成实体的字符，顺序即 id 顺序（从 1 开始）。 */
    static final List<String> CHARS =
            List.of("。", "张", "三", "丰", "北", "京", "大", "学");

    /**
     * 补齐到 {@link #EMBEDDING_ROWS} - 1 个字的填充字符，不参与任何实体。
     *
     * <p>必须<b>互不相同且够用</b>：曾经写成只有 26 个小写字母再取模复用，
     * 于是词表里出现重复字符，{@code CharVocab} 先于所有断言报「重复字符」——
     * 整批负向用例红的理由全是那一条，真正的闸门一条都没跑到。
     * 下面 {@link #vocabText()} 里的前提断言就是为了让这类问题当场暴露。
     */
    private static final String PADDING =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private NerModelFixtures() {
    }

    static byte[] onnx(boolean withCrf) {
        String name = withCrf ? "/ner-fixture-crf.onnx" : "/ner-fixture.onnx";
        try (InputStream in = NerModelFixtures.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("找不到测试夹具 " + name
                        + "（用 python3 tools/make_ner_fixture.py 生成）");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读测试夹具失败: " + name, e);
        }
    }

    /** 与玩具模型字符 id 完全一致的词表：{@link #EMBEDDING_ROWS} - 1 个字。 */
    static String vocabText() {
        int need = EMBEDDING_ROWS - 1 - CHARS.size();
        // 前提断言，不是防御性代码：填充字符不够用会在下面取模回绕，
        // 造出一份**有重复字符**的词表，于是 CharVocab 先于所有闸门报「重复字符」，
        // 整批负向用例红的理由全是那一条，真正的闸门一条都没跑到。
        if (need > PADDING.length()) {
            throw new IllegalStateException("填充字符不够：" + need + " 个可用，只有 "
                    + PADDING.length() + " 个。宁可让夹具构建失败，也不要造出重复字符的词表。");
        }
        StringBuilder sb = new StringBuilder();
        for (String c : CHARS) {
            sb.append(c).append('\n');
        }
        for (int i = 0; i < need; i++) {
            sb.append(PADDING.charAt(i)).append('\n');
        }
        return sb.toString();
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 一个可加载的清单。{@code withCrf} 决定要不要挂 transition 输出。
     *
     * <p>{@code modelBytes}/{@code vocabText} 允许调用方替换，
     * 因为「词表与模型不是同一对」这类测试必须能故意造出不匹配的组合。
     */
    static Map<String, Object> manifest(byte[] modelBytes, String vocab, boolean withCrf) {
        Map<String, Object> io = new LinkedHashMap<>();
        io.put("input", "ids");
        io.put("emission", "logits");
        if (withCrf) {
            io.put("transition", "transition");
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("path", "ner.onnx");
        model.put("sha256", sha256(modelBytes));
        Map<String, Object> vocabRef = new LinkedHashMap<>();
        vocabRef.put("path", "vocab.txt");
        vocabRef.put("sha256", sha256(vocab.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("model", model);
        files.put("vocab", vocabRef);

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("chars", 64);
        window.put("overlap", 16);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("formatVersion", NerModelPackage.SUPPORTED_FORMAT_VERSION);
        m.put("id", "test-fixture");
        m.put("arch", "gather-toy");
        m.put("tags", List.of("O", "B-PER", "I-PER", "B-ORG", "I-ORG"));
        Map<String, String> tagTypes = new LinkedHashMap<>();
        tagTypes.put("PER", "person");
        tagTypes.put("ORG", "organization");
        m.put("tagTypes", tagTypes);
        m.put("entityTypes", List.of("person", "organization"));
        m.put("maxEntityChars", 8);
        m.put("scoreSpace", "LOGIT");
        m.put("window", window);
        m.put("io", io);
        m.put("embeddingTensor", "table");
        m.put("files", files);
        m.put("metrics", Map.of("note", "test fixture"));
        m.put("provenance", Map.of("note", "test fixture"));
        return m;
    }

    /** 写出一个完整可加载的模型包，返回它的目录。 */
    static Path writePackage(Path dir, byte[] modelBytes, String vocab, boolean withCrf)
            throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve("ner.onnx"), modelBytes);
        Files.writeString(dir.resolve("vocab.txt"), vocab, StandardCharsets.UTF_8);
        return writeManifest(dir, manifest(modelBytes, vocab, withCrf));
    }

    /** 只写清单（模型与词表要调用方自己摆好，用于测「文件缺失」那几条）。 */
    static Path writeManifest(Path dir, Map<String, Object> manifest) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), toJson(manifest), StandardCharsets.UTF_8);
        return dir;
    }

    /** 写一个默认可加载的包。 */
    static Path writePackage(Path dir) throws IOException {
        return writePackage(dir, onnx(false), vocabText(), false);
    }

    /**
     * 手写一个极小的 JSON 序列化器（只处理本测试造出来的那些类型）。
     *
     * <p>刻意不引 Jackson：清单构造是测试夹具的一部分，用它自己的写法最省事，
     * 而且 Jackson 默认会拒绝某些顺序，写一个反而更可控。
     */
    static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeJson(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            writeJsonString(sb, String.valueOf(value));
        }
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // 造 zip 的公共辅助
    //
    // 【为什么放在这里而不是各测试类各写一份】
    //   「怎么把一个模型包打成 zip」是**一个事实**。两个测试类各写一份的话，
    //   迟早有一份改了而另一份没改——于是「同一个包」在两个测试里长得不一样，
    //   而两边都绿。共享一份就没有这个分叉。
    // ------------------------------------------------------------------

    /**
     * 造一个 zip：条目名 → 内容。
     *
     * @param entries 条目名到内容的映射（写入顺序即 zip 里的顺序）
     */
    static Path zipEntries(Map<String, byte[]> entries) throws IOException {
        Path zip = Files.createTempFile("模型包", ".zip");
        try (OutputStream os = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return zip;
    }

    /** 造一个 zip：参数是「名字、内容」成对。 */
    static Path zipOf(Object... nameThenContent) throws IOException {
        if (nameThenContent.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须成对（名字、内容），实际给了 "
                    + nameThenContent.length + " 个");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < nameThenContent.length; i += 2) {
            Object content = nameThenContent[i + 1];
            byte[] data = content instanceof byte[] b ? b : bytes((String) content);
            entries.put((String) nameThenContent[i], data);
        }
        return zipEntries(entries);
    }

    /** 把一个目录里的若干文件打成 zip。 */
    static Path zipDirectory(Path dir, String... names) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String n : names) {
            entries.put(n, Files.readAllBytes(dir.resolve(n)));
        }
        return zipEntries(entries);
    }

    /** 同上，但把模型文件的中间一个字节翻掉（清单里的摘要就对不上了）。 */
    static Path zipDirectoryWithCorruptedModel(Path dir) throws IOException {
        byte[] model = Files.readAllBytes(dir.resolve("ner.onnx"));
        if (model.length == 0) {
            throw new IllegalStateException("前提：模型文件不能是空的，否则改不了");
        }
        int pos = model.length / 2;
        model[pos] = (byte) (model[pos] ^ 0xFF);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", Files.readAllBytes(dir.resolve("manifest.json")));
        entries.put("ner.onnx", model);
        entries.put("vocab.txt", Files.readAllBytes(dir.resolve("vocab.txt")));
        return zipEntries(entries);
    }

    /** 字符串的 UTF-8 字节。 */
    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 读一个文本文件。 */
    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
