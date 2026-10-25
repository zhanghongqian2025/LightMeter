package com.litemask.zipxml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 不信任文档（M1-f）。
 *
 * <p>这一组测的不是「功能对不对」，而是「拿到一个恶意文件会怎样」。
 * 结论必须永远是<b>明确报错</b>，不能是「原样放行」——放行等于交付一份没脱干净的文档，
 * 而用户看不出来。
 *
 * <p><b>每个断言都检查异常消息里的原因短语</b>，不只是「抛了 IOException」。
 * 只断言「抛异常」的探针最容易空转：文件打不开、XML 畸形、条目名非法，任何一个原因
 * 都能让那条测试变绿，于是真正被撤掉的那道闸门根本没被测到。
 */
class UntrustedPackageTest {

    /** 用例里反复出现的极小上限：把所有限额都压到「正常文档绝不可能达到」的位置。 */
    private static final ZipXmlPackage.Limits TINY = ZipXmlPackage.Limits.tiny();

    // ---------------------------------------------------------------- 夹具

    private static Path zip(Path dir, String name, Map<String, byte[]> parts) throws IOException {
        Path out = dir.resolve(name);
        try (OutputStream fos = Files.newOutputStream(out);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return out;
    }

    private static Path zip(Path dir, String name, String entryName, String content) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put(entryName, content.getBytes(StandardCharsets.UTF_8));
        return zip(dir, name, parts);
    }

    private static byte[] repeat(char ch, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ch);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 断言失败时把实际消息打出来，否则「抛了但不是这个原因」会被当成通过。 */
    private static IOException expectFails(Path path, ZipXmlPackage.Limits limits, String why) {
        return expectFails(path, limits, why, "");
    }

    private static IOException expectFails(Path path, ZipXmlPackage.Limits limits,
                                            String why, String mustContain) {
        try {
            try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(path, limits)) {
                // 打开阶段就要挡住的情形：连 entries() 都不该拿到
                var entries = pkg.entries();
                for (ZipEntry entry : entries) {
                    pkg.read(entry);
                }
            }
        } catch (IOException e) {
            assertTrue(e.getMessage() != null && e.getMessage().contains(mustContain),
                    why + "：应以「" + mustContain + "」为由拒绝，实际消息是「" + e.getMessage() + "」");
            return e;
        }
        fail(why + "：本应拒绝，实际正常通过");
        return null;
    }

    // ------------------------------------------------------- zip 炸弹与限额

    /**
     * M1-f 实测：597KB 的 zip 能展开成 600MB，原来的 {@code readAllBytes()} 会整个塞进堆。
     * 这里用极小上限复现同一形态，并要求<b>异常在分配之前</b>就抛。
     */
    @Test
    void 单条目解压超限必须拒绝(@TempDir Path dir) throws IOException {
        Path bomb = zip(dir, "bomb.docx",
                Map.of("word/bomb.xml", repeat('A', 512 * 1024)));
        expectFails(bomb, TINY, "解压后 512KB 的条目", "单条目上限");
    }

    /** 总量上限必须是<b>整份文档共享</b>的：每条都合法，加起来超了照样拒。 */
    @Test
    void 逐条都合规但累计超总量也必须拒绝(@TempDir Path dir) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("a.xml", repeat('a', 60));
        parts.put("b.xml", repeat('b', 60));
        parts.put("c.xml", repeat('c', 60));
        Path file = zip(dir, "sum.docx", parts);
        IOException e = expectFails(file, TINY, "三条各 60 字节、上限 128 字节", "解压总量超过上限");
        assertTrue(e.getMessage().contains("已读"), "总量报错应带上已读字节数，便于判断差多少");
    }

    /** 条目数是「不用读内容就能查」的东西，所以必须在打开时就拒。 */
    @Test
    void 条目数超限在打开阶段就拒绝(@TempDir Path dir) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            parts.put("p" + i + ".xml", "<a/>".getBytes(StandardCharsets.UTF_8));
        }
        Path file = zip(dir, "many.docx", parts);
        expectFails(file, TINY, "8 个条目、上限 3", "条目数");
    }

    /**
     * 没被改过的 part 走流式拷贝，不进堆——但照样要扣预算。
     * 否则把炸弹塞进 {@code word/media/} 就能绕过总量上限把磁盘写满。
     */
    @Test
    void 未改动的条目在写回时同样受限(@TempDir Path dir) throws IOException {
        Path file = zip(dir, "media.docx", Map.of(
                "word/document.xml", "<a/>".getBytes(StandardCharsets.UTF_8),
                "word/media/huge.bin", repeat('Z', 256 * 1024)));
        Path out = dir.resolve("out.docx");
        IOException e;
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file, TINY)) {
            // 故意不读任何条目，直接写回：写回路径自己必须会拦
            e = assertThrows(IOException.class, () -> pkg.write(out, Map.of()));
        }
        assertTrue(e.getMessage().contains("单条目上限"),
                "写回未改动条目时应以单条目上限为由拒绝，实际是「" + e.getMessage() + "」");
    }

    /**
     * 写回路径上的<b>总量</b>预算。
     *
     * <p>上一条只压到了单条目上限，于是「写回不扣总量预算」这个变异撤不掉——
     * 单条目那道还在挡，测试照样绿，看起来像有牙齿其实没咬到被撤的那一层。
     * 这里三条各 60 字节、上限 128 字节，每条都合规，只有累计会超——
     * 只有真正扣了总量预算才会拦下来。
     */
    @Test
    void 未改动的条目在写回时同样受总量约束(@TempDir Path dir) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("word/media/a.bin", repeat('a', 60));
        parts.put("word/media/b.bin", repeat('b', 60));
        parts.put("word/media/c.bin", repeat('c', 60));
        Path file = zip(dir, "sumwrite.docx", parts);
        Path out = dir.resolve("sumwrite.out.docx");
        IOException e;
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file, TINY)) {
            e = assertThrows(IOException.class, () -> pkg.write(out, Map.of()));
        }
        assertTrue(e.getMessage().contains("解压总量超过上限"),
                "写回未改动条目时应以总量上限为由拒绝，实际是「" + e.getMessage() + "」");
    }

    // -------------------------------------------------------- 条目名与路径

    @Test
    void 上级目录穿越条目名必须拒绝(@TempDir Path dir) throws IOException {
        Path file = zip(dir, "slip.docx", "../evil.xml", "<a/>");
        expectFails(file, ZipXmlPackage.Limits.DEFAULT, "条目名 ../evil.xml", "穿越");
    }

    @Test
    void 深层穿越条目名必须拒绝(@TempDir Path dir) throws IOException {
        Path file = zip(dir, "slip2.docx", "a/b/../../../evil.xml", "<a/>");
        expectFails(file, ZipXmlPackage.Limits.DEFAULT, "条目名 a/b/../../../evil.xml", "穿越");
    }

    @Test
    void 反斜杠穿越同样必须拒绝(@TempDir Path dir) throws IOException {
        Path file = zip(dir, "slip3.docx", "a\\..\\..\\evil.xml", "<a/>");
        expectFails(file, ZipXmlPackage.Limits.DEFAULT, "反斜杠写法", "穿越");
    }

    @Test
    void 绝对路径条目名必须拒绝(@TempDir Path dir) throws IOException {
        Path posix = zip(dir, "abs.docx", "/etc/passwd", "x");
        expectFails(posix, ZipXmlPackage.Limits.DEFAULT, "条目名 /etc/passwd", "绝对路径");
        Path windows = zip(dir, "abs2.docx", "C:\\evil.xml", "x");
        expectFails(windows, ZipXmlPackage.Limits.DEFAULT, "条目名 C:\\evil.xml", "绝对路径");
    }

    /** 正常条目名里出现「.」是合法的（zip 里很常见），不能误伤。 */
    @Test
    void 单点目录名不应被误判为穿越(@TempDir Path dir) throws IOException {
        Path file = zip(dir, "dot.docx", "word/./document.xml", "<a/>");
        assertDoesNotThrow(() -> {
            try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file)) {
                assertEquals(1, pkg.entries().size());
            }
        }, "「.」是普通目录名，不是路径穿越");
    }

    // ------------------------------------------------------------- XML 攻击

    /** XXE：文档来自用户，不可信，一律不解析外部实体。 */
    @Test
    void 带外部实体的DOCTYPE必须拒绝(@TempDir Path dir) throws IOException {
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<root>&xxe;</root>";
        Path file = zip(dir, "xxe.docx", "word/document.xml", xxe);
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file)) {
            byte[] xml = pkg.read(pkg.entries().get(0));
            IOException e = assertThrows(IOException.class, () -> pkg.parse(xml));
            assertTrue(e.getMessage().contains("无法解析"),
                    "XXE 应以「无法解析」为由拒绝，实际是「" + e.getMessage() + "」");
        }
    }

    /** billion laughs：实体自嵌套展开，DOCTYPE 一律禁掉，展开根本不会发生。 */
    @Test
    void 实体自嵌套展开必须拒绝且不耗尽内存(@TempDir Path dir) throws IOException {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY a \"1234567890\">");
        for (int i = 1; i < 10; i++) {
            char prev = (char) ('a' + i - 1);
            char cur = (char) ('a' + i);
            sb.append("<!ENTITY ").append(cur).append(" \"");
            for (int j = 0; j < 10; j++) {
                sb.append('&').append(prev).append(';');
            }
            sb.append("\">");
        }
        sb.append("]><lolz>&j;</lolz>");
        Path file = zip(dir, "lolz.docx", "word/document.xml", sb.toString());
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file)) {
            byte[] xml = pkg.read(pkg.entries().get(0));
            IOException e = assertThrows(IOException.class, () -> pkg.parse(xml));
            assertTrue(e.getMessage().contains("无法解析"),
                    "实体展开攻击应被同一个 fail-closed 出口拦住，实际是「" + e.getMessage() + "」");
        }
    }

    /** 畸形 XML：fail-closed 的基本盘，原地放行就是交付一份没脱干净的文档。 */
    @Test
    void 畸形XML必须拒绝而不是放行(@TempDir Path dir) throws IOException {
        Path file = zip(dir, "broken.docx", "word/document.xml", "<a><b></a>");
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file)) {
            byte[] xml = pkg.read(pkg.entries().get(0));
            IOException e = assertThrows(IOException.class, () -> pkg.parse(xml));
            assertTrue(e.getMessage().contains("无法解析"),
                    "畸形 XML 应以「无法解析」为由拒绝，实际是「" + e.getMessage() + "」");
        }
    }

    /**
     * 超深嵌套：实测 JDK 自带 Xerces 解析 20 万层不抛异常也不爆栈，
     * 真正的风险是建出来的树吃光内存、以及后续递归遍历自己的栈。
     * 所以必须在建 DOM <b>之前</b>用流式扫描拦下。
     */
    @Test
    void 超深嵌套XML必须在建树前被拦下(@TempDir Path dir) throws IOException {
        Path file = zip(dir, "deep.docx", "word/document.xml", nest(20_000));
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file, ZipXmlPackage.Limits.DEFAULT)) {
            byte[] xml = pkg.read(pkg.entries().get(0));
            IOException e = assertThrows(IOException.class, () -> pkg.parse(xml));
            assertTrue(e.getMessage().contains("嵌套深度"),
                    "超深 XML 应以「嵌套深度」为由拒绝，实际是「" + e.getMessage() + "」");
        }
    }

    /**
     * 深度闸门不能误伤真实文档。
     *
     * <p>真实 OOXML 的嵌套在 20~40 层量级，默认上限 512 留了一个数量级的余量；
     * 这条断言的是「余量真的存在」，否则闸门一旦过紧就是拿真实文件换安全。
     */
    @Test
    void 真实深度的XML不被深度闸门误伤(@TempDir Path dir) throws IOException {
        Path file = zip(dir, "normal.docx", "word/document.xml", nest(40));
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file)) {
            byte[] xml = pkg.read(pkg.entries().get(0));
            assertTrue(pkg.parse(xml) != null, "40 层嵌套是真实文档的量级，不该被拦");
        }
    }

    /**
     * 比「必须拒绝」更强、也更贴近要保护的东西的不变式：<b>外部实体的内容绝不能进入文档</b>。
     *
     * <p>为什么不只断言「抛异常」：M1-f 实测 XXE 防护是三层叠加的
     * （StAX {@code SUPPORT_DTD=false}、DOM {@code disallow-doctype-decl}、兜底 EntityResolver），
     * 单独撤掉任何一层，对面那层都还在拦——所以「必须抛异常」那条断言对单层变异不敏感。
     * 这条断言的是最终结果：无论走哪条路，本机文件的内容都不许出现在 DOM 里。
     *
     * <p>目标文件由本测试自己创建，不依赖宿主机上有什么，结论在任何机器上都成立。
     */
    @Test
    void 外部实体的内容绝不进入文档(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("secret.txt");
        String marker = "TOP-SECRET-MARKER-9C1F";
        Files.writeString(secret, marker, StandardCharsets.UTF_8);

        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
                + "<root>&xxe;</root>";
        Path file = zip(dir, "leak.docx", "word/document.xml", xxe);
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file)) {
            byte[] xml = pkg.read(pkg.entries().get(0));
            try {
                var doc = pkg.parse(xml);
                String text = doc.getDocumentElement().getTextContent();
                assertFalse(text.contains(marker),
                        "外部实体被展开了：文档里出现了本机文件的内容 —— 这是 XXE 泄漏");
            } catch (IOException expected) {
                assertNotNull(expected.getMessage(), "拒绝时的消息不能为空，否则用户看不懂出了什么事");
            }
        }
    }

    // ------------------------------------------------------------ 正常路径

    /**
     * 防「闸门把一切都拒了所以看起来很安全」：一份正常文档必须在默认上限下
     * 完整跑通 打开 → 读 → 解析 → 改 → 写回。
     */
    @Test
    void 正常文档在默认上限下完整跑通(@TempDir Path dir) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        parts.put("word/document.xml",
                ("<w:document xmlns:w=\"x\"><w:body><w:p><w:r><w:t>" + nest(30) + "</w:t></w:r></w:p></w:body></w:document>")
                        .getBytes(StandardCharsets.UTF_8));
        parts.put("word/media/image1.png", repeat('p', 4096));
        Path file = zip(dir, "ok.docx", parts);
        Path out = dir.resolve("ok.out.docx");

        long spent;
        try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(file)) {
            for (ZipEntry entry : pkg.entries()) {
                if (!ZipXmlPackage.isXmlPart(entry.getName())) {
                    continue;
                }
                byte[] xml = pkg.read(entry);
                assertTrue(pkg.parse(xml) != null);
            }
            pkg.write(out, Map.of("[Content_Types].xml", "<Types2/>".getBytes(StandardCharsets.UTF_8)));
            spent = pkg.bytesSpent();
        }
        assertTrue(Files.size(out) > 0, "正常文档应写出非空文件");
        assertTrue(spent > 0, "预算应记录实际读过的字节，否则「总量上限」名不副实");

        try (ZipXmlPackage.Session check = ZipXmlPackage.open(out)) {
            boolean saw = false;
            for (ZipEntry entry : check.entries()) {
                if (entry.getName().equals("[Content_Types].xml")) {
                    assertEquals("<Types2/>", new String(check.read(entry), StandardCharsets.UTF_8));
                    saw = true;
                }
            }
            assertTrue(saw, "写回后应能读回改动过的条目");
        }
    }

    private static String nest(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("<a>");
        }
        sb.append("x");
        for (int i = 0; i < depth; i++) {
            sb.append("</a>");
        }
        return sb.toString();
    }
}
