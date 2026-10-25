package com.litemask.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * 把模型包从网上取回来。
 *
 * <p><b>为什么要有它</b>：方案 B 把模型包从核心包里拆了出去，
 * 「用户手上没有模型包」是常态，而没有模型包时中文人名与机构名**不会**被脱敏。
 * 模型包必须有一条不靠人手复制目录的到达路径——这就是它。
 *
 * <p><b>它只负责「取回来」，不负责「认不认」</b>：验签与安装都不在这里。
 * 判据只有一处：{@link com.litemask.ner.ModelTrust#verifyPackage} 认不认这份包。
 * 这一层刻意不自己算摘要、不自己比签名——那样同一个信任判断会有两个实现，
 * 而两个实现迟早会不一致。
 *
 * <p><b>下载这一层要挡的四件事</b>：
 * <ol>
 *   <li><b>协议降级</b>：https 重定向到 http 一律拒绝。签名能挡住内容被换，
 *       但明文传输会把「谁在下载模型包」泄漏出去，而且重定向到 http 往往是
 *       有人在中途动手脚的迹象。</li>
 *   <li><b>体积写爆磁盘</b>：超过上限就中止并删掉半截文件。</li>
 *   <li><b>半截文件冒充完整文件</b>：先写 .part，写完再改名；
 *       服务器声明的长度与实收字节对不上时报错。</li>
 *   <li><b>重定向打转</b>：跳数有上限。</li>
 * </ol>
 */
public final class ModelFetcher {

    /** 最多跟几跳重定向。 */
    public static final int MAX_REDIRECTS = 5;

    /** 模型包体积上限：512 MiB。超了就是「下错了东西」，不是「模型包很大」。 */
    public static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;

    /** 签名文件体积上限：4 MiB。签名信封是几行 JSON，不可能更大。 */
    public static final long MAX_SIGNATURE_BYTES = 4L * 1024L * 1024L;

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 30_000;

    /** 读取超时（毫秒）。 */
    public static final int READ_TIMEOUT_MS = 120_000;

    /**
     * 取回来的东西。
     *
     * @param packageFile  模型包落在本地的路径
     * @param signatureFile 签名落在本地的路径（**紧挨着**模型包，
     *                      因为验签按「旁挂」约定找它）
     * @param bytes        模型包收了多少字节
     * @param finalUrl     最后实际取的是哪个地址（跟过重定向）
     */
    public record Fetched(Path packageFile, Path signatureFile, long bytes, String finalUrl) {
    }

    private ModelFetcher() {
    }

    /**
     * 下载模型包与它的签名。
     *
     * @param packageUrl   模型包地址
     * @param signatureUrl 签名地址
     * @param destDir      落到哪个目录（会自己建）
     * @return 取回来的东西
     * @throws IOException 取不到、或者取到的东西不合格。消息直接给用户看。
     */
    public static Fetched fetch(String packageUrl, String signatureUrl, Path destDir) throws IOException {
        return fetch(packageUrl, signatureUrl, destDir, MAX_PACKAGE_BYTES, MAX_SIGNATURE_BYTES);
    }

    /**
     * 见 {@link #fetch(String, String, Path)}：体积上限可指定。
     *
     * <p><b>为什么留这个口</b>：上限是 512 MiB，而测它要真的发 512 MiB——
     * 那不叫测试，叫压力测试。生产路径永远用
     * {@link #MAX_PACKAGE_BYTES}；这里只是把**同一个上限判定**的阈值调小，
     * 让它在毫秒级内被触发。判定逻辑是同一份，数字不同。
     *
     * @param packageUrl      模型包地址
     * @param signatureUrl    签名地址
     * @param destDir         落到哪个目录
     * @param maxPackageBytes 模型包体积上限
     * @param maxSignatureBytes 签名文件体积上限
     * @return 取回来的东西
     * @throws IOException 取不到、或者取到的东西不合格
     */
    static Fetched fetch(String packageUrl, String signatureUrl, Path destDir,
                         long maxPackageBytes, long maxSignatureBytes) throws IOException {
        URI pkgUri = checkedUri(packageUrl, "模型包地址");
        Files.createDirectories(destDir);
        String name = fileNameFor(pkgUri);
        Path pkgFile = destDir.resolve(name);
        Path sigFile = destDir.resolve(name + ".sig");
        Downloaded pkg = download(pkgUri, pkgFile, maxPackageBytes, "模型包");
        // 签名默认跟着**包实际落在哪儿**走，而不是跟着「用户给的那个地址」走。
        //
        // 为什么：发布方把 zip 与 .sig 放在**同一个目录**里，这是旁挂约定
        // （见 ModelTrust.signatureFileFor）。如果地址有重定向（发布方给的是
        // 「最新版」这种跳转地址），那么「用户给的那个地址」与「文件实际在哪儿」
        // 根本不是同一个地方 —— 跟着用户给的地址找，多半会找到一个 404。
        URI sigUri;
        if (signatureUrl != null && !signatureUrl.isBlank()) {
            sigUri = checkedUri(signatureUrl, "签名地址");
        } else {
            sigUri = checkedUri(pkg.finalUrl + ".sig", "签名地址");
        }
        download(sigUri, sigFile, maxSignatureBytes, "签名文件");
        return new Fetched(pkgFile, sigFile, pkg.bytes, pkg.finalUrl);
    }

    /** 一次下载的结果。 */
    private record Downloaded(long bytes, String finalUrl) {
    }

    /**
     * 解析并检查一个地址。
     *
     * <p><b>只放行 http / https</b>：别的协议（file://、ftp://、jar:…）
     * 对一个「从网上取模型包」的命令没有意义，放行只会多出「本地文件被当远程包」
     * 这种说不清的失败模式。
     *
     * @param raw  地址原文
     * @param what 这是个什么东西（报错时要说清是哪个）
     * @return 解析好的地址
     * @throws IOException 地址不合格
     */
    public static URI checkedUri(String raw, String what) throws IOException {
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IOException(what + "不是一个合法的地址：" + raw + "（" + e.getReason() + "）");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IOException(what + "不是一个完整的地址（少了协议）：" + raw);
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lower) && !"https".equals(lower)) {
            throw new IOException(what + "只支持 http 或 https（这里是 " + scheme + "）：" + raw);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IOException(what + "里没有主机名：" + raw);
        }
        return uri;
    }

    /**
     * 从地址里取一个安全的本地文件名。
     *
     * <p><b>为什么不用服务器给的文件名</b>：一是它可能带路径（{@code ../../}），
     * 二是它可能带 Windows 不许的字符，三是它可能每次都不一样——
     * 而签名文件是按「包名 + .sig」旁挂找的，名字必须能自己定下来。
     * 名字只影响临时文件长什么样，不影响验签。
     *
     * @param uri 地址
     * @return 文件名
     */
    static String fileNameFor(URI uri) {
        String path = uri.getPath();
        String base = "";
        if (path != null) {
            int slash = path.lastIndexOf('/');
            base = slash >= 0 ? path.substring(slash + 1) : path;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 100; i++) {
            char c = base.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(safe ? c : '_');
        }
        String name = sb.toString();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "model.zip";
        }
        return name;
    }

    /**
     * 检查一次重定向能不能跟。
     *
     * <p><b>规则只有一条，但它是要紧的那一条</b>：不许从 https 降到 http。
     * 签名能挡住内容被换，挡不住「谁在下载模型包」被看见；
     * 而 https 忽然变成 http，通常意味着中间有人动过手脚。
     * 升级（http → https）是允许的。
     *
     * <p><b>为什么单独抽成一个方法</b>：这条规则要在真正的 https 连接里才拦得住，
     * 而单测里起一个真的 https 服务端要证书、要密钥库——
     * 于是这条规则就永远只能靠「读代码觉得对」来担保。
     * 抽出来之后它能被直接测：给定一个 https 的来源与一个 http 的目标，
     * 规则必须拒绝。
     *
     * @param from 跳之前在哪儿
     * @param to   要跳到哪儿
     * @throws IOException 不许跟
     */
    static void checkRedirect(URI from, URI to) throws IOException {
        String toScheme = to.getScheme();
        if (toScheme == null) {
            throw new IOException("重定向目标不是一个完整的地址（少了协议）：" + to);
        }
        String lower = toScheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lower) && !"https".equals(lower)) {
            throw new IOException("重定向目标只支持 http 或 https（这里是 " + toScheme + "）：" + to);
        }
        if ("https".equalsIgnoreCase(from.getScheme()) && "http".equals(lower)) {
            throw new IOException("拒绝跟随从 https 降到 http 的重定向（"
                    + from + " → " + to + "）：明文传输既漏底，又通常意味着中间有人动过手脚。");
        }
    }

    /**
     * 取一个文件下来。
     *
     * @param uri      取哪个地址
     * @param target   落到哪
     * @param maxBytes 体积上限
     * @param what     这是个什么东西（报错时要说清）
     * @return 收到多少字节
     * @throws IOException 取不到或不合格
     */
    private static Downloaded download(URI uri, Path target, long maxBytes, String what) throws IOException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = open(current);
            int code;
            try {
                code = conn.getResponseCode();
            } catch (IOException e) {
                conn.disconnect();
                throw new IOException(networkHint(current, e), e);
            }
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isBlank()) {
                    throw new IOException(what + "的服务器返回了重定向（HTTP " + code + "）却没给新地址："
                            + current);
                }
                URI next;
                try {
                    next = current.resolve(location.trim());
                } catch (IllegalArgumentException e) {
                    throw new IOException(what + "的重定向地址不合法：" + location);
                }
                checkRedirect(current, next);
                current = next;
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException(what + "下载失败：服务器返回 HTTP " + code
                        + (conn.getResponseMessage() == null ? "" : "（" + conn.getResponseMessage() + "）")
                        + "，地址 " + current);
            }
            try {
                long declared = conn.getContentLengthLong();
                long written = copyOut(conn.getInputStream(), target, maxBytes, what);
                if (declared >= 0 && declared != written) {
                    throw new IOException(what + "下载不完整：服务器说 " + declared
                            + " 字节，实际收到 " + written + " 字节（地址 " + current + "）。");
                }
                return new Downloaded(written, current.toString());
            } catch (IOException e) {
                throw new IOException(e.getMessage(), e);
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException(what + "的重定向跳了太多次（上限 " + MAX_REDIRECTS + " 跳），最后停在 " + current);
    }

    /**
     * 打开一个连接。
     *
     * <p><b>不跟随重定向</b>：重定向要自己处理——因为「https 降到 http」这种事
     * 只有自己逐跳看协议才能挡得住，交给库默认行为就等于放弃这道关。
     *
     * @param uri 地址
     * @return 连上了的连接
     * @throws IOException 连不上
     */
    private static HttpURLConnection open(URI uri) throws IOException {
        URL url;
        try {
            url = encodeNonAsciiPath(uri).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("地址不合法：" + uri);
        }
        java.net.URLConnection raw = url.openConnection();
        if (!(raw instanceof HttpURLConnection conn)) {
            throw new IOException("这个地址不是 HTTP 地址：" + uri);
        }
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "LightMeter/1.0");
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    /**
     * 把响应体写进目标文件。
     *
     * <p><b>先写 .part 再改名</b>：写一半就断线时，磁盘上留下的是
     * {@code xxx.zip.part}，不是 {@code xxx.zip}。改名之前，那个名字是空的——
     * 于是「拿一个半截文件去验签」这件事不可能发生。
     *
     * @param in       响应体
     * @param target   目标文件
     * @param maxBytes 体积上限
     * @param what     这是个什么东西
     * @return 写了多少字节
     * @throws IOException 写失败或超限
     */
    private static long copyOut(InputStream in, Path target, long maxBytes, String what) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long total = 0;
        try (InputStream inStream = in; OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = inStream.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException(what + "超过了体积上限（" + (maxBytes / (1024 * 1024)) + " MiB）："
                            + "这多半不是模型包，先停手，别把磁盘写满。");
                }
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        if (total == 0) {
            Files.deleteIfExists(part);
            throw new IOException(what + "下载下来是空的（0 字节）。");
        }
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        return total;
    }

    /**
     * 把地址路径里的非 ASCII 字符 percent 编码。
     *
     * <p><b>为什么必须做</b>：模型包的文件名很可能是中文
     * （「中文人名识别模型-v9.zip」这种东西一点都不稀奇），
     * 而 {@link java.net.HttpURLConnection} <b>不会</b>替你把路径里的中文编码成
     * {@code %E4...}——它会把原始 UTF-8 字节塞进请求行，服务器一看不是合法请求行，
     * 直接回一个 400。于是「地址是对的，但就是下不下来」。
     *
     * <p><b>为什么只碰路径</b>：查询串与片段已经由 URI 自己管好了；
     * 而且已经 percent 编码过的部分（{@code %XX}）是 ASCII，原样保留，
     * 不会被二次编码。
     *
     * @param uri 原地址
     * @return 路径已编码的地址
     * @throws IOException 拼不出合法地址
     */
    static URI encodeNonAsciiPath(URI uri) throws IOException {
        String rawPath = uri.getRawPath();
        if (rawPath == null || isAscii(rawPath)) {
            return uri;
        }
        StringBuilder path = new StringBuilder();
        for (byte b : rawPath.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c < 0x80) {
                path.append((char) c);
            } else {
                path.append('%');
                path.append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)));
                path.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(uri.getScheme()).append("://");
        if (uri.getRawAuthority() != null) {
            out.append(uri.getRawAuthority());
        }
        out.append(path);
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            out.append('#').append(uri.getRawFragment());
        }
        try {
            return new URI(out.toString());
        } catch (URISyntaxException e) {
            throw new IOException("地址编码后不合法：" + out);
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把网络异常翻译成用户看得懂的话。
     *
     * <p><b>为什么值得单独写</b>：{@code ConnectException: Connection refused}
     * 这种原文对非技术用户没有意义，而「离线」「证书不受信」「超时」
     * 三件事的下一步动作完全不同，不能混成一句「下载失败」。
     *
     * @param uri 在取哪个地址
     * @param e   异常
     * @return 给用户看的话
     */
    static String networkHint(URI uri, IOException e) {
        String host = uri.getHost();
        if (e instanceof UnknownHostException) {
            return "连不上 " + host + "：这台机器解析不出这个地址。离线状态下模型包没法自动下载。";
        }
        if (e instanceof ConnectException) {
            return "连不上 " + host + "：" + e.getMessage() + "。离线状态下模型包没法自动下载。";
        }
        if (e instanceof SSLException) {
            return "与 " + host + " 的 TLS 握手失败：" + e.getMessage()
                    + "。证书不受信时不要绕过验证——那等于把整条信任链丢掉，"
                    + "而模型包的签名正是靠这条链才作数。";
        }
        if (e instanceof SocketTimeoutException) {
            return "与 " + host + " 的连接超时（" + (READ_TIMEOUT_MS / 1000) + " 秒内没拿到数据）。";
        }
        return "下载 " + uri + " 失败：" + e.getMessage();
    }
}
