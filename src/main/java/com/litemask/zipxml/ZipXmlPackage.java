package com.litemask.zipxml;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 「zip + XML」文档共用的读写管道。
 *
 * <p>docx / xlsx / pptx / ofd 的骨架是同一种东西：一个 zip，里面若干 XML，
 * 文本藏在少数几种节点里。把「打开、遍历、解析、序列化、写回」收敛到一处，
 * 各格式的脱敏器只写「哪些节点是文本」这一件真正有格式差异的事。
 *
 * <p>这个类最初叫 {@code OoxmlSupport}，OFD 接进来之后这个名字就开始骗人了——
 * 它对 OOXML 没有任何特殊逻辑，去掉名字里的 OOXML 只是让读代码的人少绕一次弯。
 *
 * <p><b>fail-closed 是这里的硬约束</b>：解析不了就抛异常，绝不原样放行。
 * 静默跳过意味着交付一份没脱干净的文档，而用户完全看不出来——对脱敏工具来说
 * 这是最严重的失败模式。
 *
 * <p><b>文档不可信</b>。M1-f 实测：一个 597KB 的 zip 能展开成 600MB，
 * {@code readAllBytes()} 会把它整个塞进堆。所以「一个文档 = 一个 {@link Session}」：
 * 打开时就核对条目数与条目名，每次读取按实际读到的字节数扣预算（不是按中央目录声明的
 * 大小——那是攻击者自己写的），写回时同样扣，避免一个 10MB 的输入把磁盘写满。
 */
public final class ZipXmlPackage {

    private ZipXmlPackage() {
    }

    /**
     * 一个文档能消耗多少资源。
     *
     * <p>默认值不是「越大越安全」，而是「比任何真实办公文档都宽、比任何炸弹都紧」：
     * 真实 docx/xlsx/pptx/ofd 的条目数在几十到几千量级，单个 part 在几百 KB 量级，
     * XML 嵌套深度在 20~40 量级。512 的深度上限给真实文档留了一个数量级的余量，
     * 同时让「递归遍历一个恶意深树」在可控距离内就被拦下。
     */
    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxXmlDepth) {

        public static final Limits DEFAULT =
                new Limits(20_000, 256L << 20, 2L << 30, 512);

        /** 测试用：把每个上限都压到最小，证明闸门确实会响而不是形同虚设。 */
        public static Limits tiny() {
            return new Limits(3, 64, 128, 2);
        }
    }

    /**
     * 打开一个 zip 文档，并立刻做<b>不需要读内容</b>就能做的全部校验。
     *
     * <p>校验放在构造期而不是第一次读取时，是为了让「打开一个明显不对的文件」
     * 与「读到某个 part 时才发现不对」在调用方看来是同一件事：抛异常。
     */
    public static Session open(Path path) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(path.toFile());
        } catch (IOException e) {
            throw new IOException("不是有效的 zip 文档包（打开失败）：" + path, e);
        }
        boolean ok = false;
        try {
            Session session = new Session(zip, Limits.DEFAULT);
            ok = true;
            return session;
        } finally {
            if (!ok) {
                zip.close();
            }
        }
    }

    /** 测试与工具使用：自定义上限打开。 */
    public static Session open(Path path, Limits limits) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(path.toFile());
        } catch (IOException e) {
            throw new IOException("不是有效的 zip 文档包（打开失败）：" + path, e);
        }
        boolean ok = false;
        try {
            Session session = new Session(zip, limits);
            ok = true;
            return session;
        } finally {
            if (!ok) {
                zip.close();
            }
        }
    }

    /** 条目名是否像一份 XML 部件。 */
    public static boolean isXmlPart(String name) {
        return name.endsWith(".xml");
    }

    /** 把文本节点的内容整个换掉：清子节点、写入新文本。 */
    public static void setText(org.w3c.dom.Element element, String text) {
        while (element.getFirstChild() != null) {
            element.removeChild(element.getFirstChild());
        }
        if (!text.isEmpty()) {
            element.appendChild(element.getOwnerDocument().createTextNode(text));
        }
    }

    /**
     * 写回 zip 时的额外动作。
     *
     * @param renames 条目重命名（旧名 -&gt; 新名），OFD 的附件文件名带个人信息时用
     * @param drop    直接丢弃的条目，OFD 的签章目录用
     */
    public record WriteOptions(Map<String, String> renames, Set<String> drop) {
        public static final WriteOptions NONE = new WriteOptions(Map.of(), Set.of());
    }

    /**
     * 一个已打开的 zip 文档。所有读取共用一份预算，因此「总量」是整份文档意义上的总量。
     */
    public static final class Session implements AutoCloseable {

        private final ZipFile zip;
        private final Limits limits;
        private final List<ZipEntry> entries;
        private long spent;

        private Session(ZipFile zip, Limits limits) throws IOException {
            this.zip = zip;
            this.limits = limits;
            List<ZipEntry> all = new ArrayList<>();
            zip.stream().forEach(all::add);
            if (all.size() > limits.maxEntries()) {
                throw new IOException("zip 条目数 " + all.size() + " 超过上限 " + limits.maxEntries()
                        + "，已中止（拒绝处理构造异常的文件）");
            }
            for (ZipEntry entry : all) {
                rejectUnsafeName(entry.getName());
            }
            this.entries = List.copyOf(all);
        }

        /** 条目名一旦带路径穿越或绝对路径，写出去的文件就会去指向包外。真实办公文档不会出现。 */
        private static void rejectUnsafeName(String name) throws IOException {
            if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) {
                throw new IOException("zip 条目名「" + name + "」是绝对路径，已中止");
            }
            for (String segment : name.split("[/\\\\]")) {
                if (segment.equals("..")) {
                    throw new IOException("zip 条目名「" + name + "」含上级目录穿越（..），已中止");
                }
            }
        }

        public List<ZipEntry> entries() {
            return entries;
        }

        public ZipFile zip() {
            return zip;
        }

        /**
         * 读一个条目到内存。
         *
         * <p>上限按<b>实际读到的字节</b>判定，不看 {@code ZipEntry.getSize()}——
         * 那个值来自中央目录，是文件作者自己填的；实测 597KB 的 zip 就能声明出
         * 600MB 的 size 并真的解出 600MB。
         */
        public byte[] read(ZipEntry entry) throws IOException {
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                long inEntry = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    inEntry += n;
                    if (inEntry > limits.maxEntryBytes()) {
                        throw new IOException("条目「" + entry.getName() + "」解压后超过单条目上限 "
                                + limits.maxEntryBytes() + " 字节，已中止（拒绝处理疑似 zip 炸弹的文件）");
                    }
                    charge(n, entry.getName());
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        }

        private void charge(long bytes, String where) throws IOException {
            if (bytes > limits.maxTotalBytes() - spent) {
                throw new IOException("文档解压总量超过上限 " + limits.maxTotalBytes()
                        + " 字节（已读 " + spent + "，正在读「" + where + "」），已中止"
                        + "（拒绝处理疑似 zip 炸弹的文件）");
            }
            spent += bytes;
        }

        /** 已消耗的解压字节，写进报告让「为什么停了」可解释。 */
        public long bytesSpent() {
            return spent;
        }

        /**
         * 解析 XML：先流式数一遍嵌套深度，过了才让 DOM 建树。
         *
         * <p>顺序不能反。实测 JDK 自带的 Xerces 解析 20 万层嵌套不抛异常也不爆栈，
         * 真正的风险是建出来的树把内存吃光、以及后续递归遍历自己的栈。
         * 流式预扫描在第 {@code maxXmlDepth + 1} 层就停，实测 50 万层也能立刻放弃。
         */
        public Document parse(byte[] xml) throws IOException {
            guardXml(xml);
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                // XXE 防护：文档来自用户，不可信，一律不解析外部实体
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                DocumentBuilder builder = factory.newDocumentBuilder();
                builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
                try (InputStream in = new ByteArrayInputStream(xml)) {
                    return builder.parse(in);
                }
            } catch (ParserConfigurationException | SAXException | IllegalArgumentException e) {
                throw new IOException("包内的 XML 无法解析，已中止（拒绝静默放行未脱敏内容）", e);
            }
        }

        private void guardXml(byte[] xml) throws IOException {
            XMLStreamReader reader = null;
            try {
                XMLInputFactory factory = XMLInputFactory.newInstance();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
                factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
                // 新版 JDK 自带 StAX 还有一层较低的默认深度闸门。让它比产品闸门多一层，
                // 才能由下面这条稳定、可解释的限制先报出“嵌套深度”，而不是泛化成解析失败。
                factory.setProperty("jdk.xml.maxElementDepth", limits.maxXmlDepth() + 1);
                reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
                int depth = 0;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (++depth > limits.maxXmlDepth()) {
                            throw new IOException("XML 嵌套深度超过上限 " + limits.maxXmlDepth()
                                    + "，已中止（拒绝处理构造异常的文件）");
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                    }
                }
            } catch (XMLStreamException e) {
                // 畸形 XML 在这里就被挡住：同一个 fail-closed 出口，不给 DOM 任何机会
                throw new IOException("包内的 XML 无法解析，已中止（拒绝静默放行未脱敏内容）", e);
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (XMLStreamException ignored) {
                        // 关闭失败不影响判定：解析结果已经拿到了
                    }
                }
            }
        }

        public byte[] serialize(Document doc) throws IOException {
            try {
                TransformerFactory factory = TransformerFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                Transformer transformer = factory.newTransformer();
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                transformer.setOutputProperty(OutputKeys.INDENT, "no");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                transformer.transform(new DOMSource(doc), new StreamResult(out));
                return out.toByteArray();
            } catch (TransformerException | TransformerFactoryConfigurationError e) {
                throw new IOException("XML 序列化失败", e);
            }
        }

        public void write(Path out, Map<String, byte[]> changed) throws IOException {
            write(out, changed, WriteOptions.NONE);
        }

        /**
         * 写回 zip：{@code changed} 里的 part 用新字节，其余 part 逐字节原样复制。
         *
         * <p>原样复制不只是省事——重新序列化没改过的 part 会改变属性顺序与缩进，
         * 让「这次到底改了什么」变得没法用 diff 核对。
         *
         * <p>没改过的 part 走的是流式拷贝，不进堆，但<b>照样扣预算</b>：
         * 否则一个把炸弹塞进 {@code word/media/} 的文件可以绕过总量上限把磁盘写满。
         */
        public void write(Path out, Map<String, byte[]> changed, WriteOptions options) throws IOException {
            Map<String, String> renames = options.renames();
            Set<String> target = new HashSet<>();
            for (ZipEntry entry : entries) {
                if (entry.isDirectory() || options.drop().contains(entry.getName())) {
                    continue;
                }
                String mapped = renames.getOrDefault(entry.getName(), entry.getName());
                rejectUnsafeName(mapped);
                if (!target.add(mapped)) {
                    throw new IOException("重命名后出现重名条目 " + mapped
                            + "，已中止（会产生指向错误附件的文档）");
                }
            }
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            try (OutputStream fos = Files.newOutputStream(tmp);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                for (ZipEntry entry : entries) {
                    if (options.drop().contains(entry.getName())) {
                        continue;
                    }
                    ZipEntry copy = new ZipEntry(renames.getOrDefault(entry.getName(), entry.getName()));
                    copy.setTime(entry.getTime());
                    zos.putNextEntry(copy);
                    if (!entry.isDirectory()) {
                        byte[] replacement = changed.get(entry.getName());
                        if (replacement != null) {
                            zos.write(replacement);
                            charge(replacement.length, entry.getName());
                        } else {
                            copyCounted(zip, entry, zos);
                        }
                    }
                    zos.closeEntry();
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }

        private void copyCounted(ZipFile source, ZipEntry entry, OutputStream sink) throws IOException {
            try (InputStream in = source.getInputStream(entry)) {
                byte[] buffer = new byte[64 * 1024];
                long inEntry = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    inEntry += n;
                    if (inEntry > limits.maxEntryBytes()) {
                        throw new IOException("条目「" + entry.getName() + "」解压后超过单条目上限 "
                                + limits.maxEntryBytes() + " 字节，已中止（拒绝处理疑似 zip 炸弹的文件）");
                    }
                    charge(n, entry.getName());
                    sink.write(buffer, 0, n);
                }
            }
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }
}
