package com.litemask.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 产品身份（版本号）：从 <b>jar 里那份</b> {@code package-identity.properties} 读。
 *
 * <p><b>为什么不在代码里写死版本号</b>
 *   顶层 help 以前写的是「· M3-2」—— 那是<b>阶段标签</b>，不是版本号；
 *   项目走到 M4 之后它就过期了，而过期了<b>没有任何东西会报</b>
 *   （编译不红、测试不红，help 照打不误）。
 *   → 版本号只写在 {@code tools/package-identity.properties} 一处
 *     （那是<b>跨端唯一真源</b>，macOS / Windows 两个打包脚本都从它读），
 *     构建时把它原样带进 jar，运行时再从 classpath 读回来。
 *   → 于是「安装器里显示的版本」与「命令行 help 里显示的版本」<b>必然一致</b>：
 *     它们读的是同一个文件。
 *
 * <p><b>读不到时怎么办</b>
 *   运行时读不到就显示「未知」——CLI 不能因为版本号读不到就拒绝干活。
 *   但「显示成未知」这件事本身由 {@code work/check-cli-help-everywhere.py} 盯住
 *   （它拿真二进制比 help 里的版本与 identity 文件里的版本），所以不会悄悄退化。
 */
final class ProductIdentity {

    /** 构建时从 {@code tools/} 过滤进来的那份身份表（见 pom.xml 的 resources 配置）。 */
    private static final String RESOURCE = "/META-INF/litemask/package-identity.properties";

    private static String cachedVersion;

    private ProductIdentity() {
    }

    /**
     * 产品版本号；读不到时返回「未知」（不是空串、不是 null）。
     *
     * <p>缓存：help 在一次运行里可能被打印多次，而读 classpath 资源有 IO 开销。
     */
    static synchronized String version() {
        if (cachedVersion != null) {
            return cachedVersion;
        }
        String v = null;
        try (InputStream in = ProductIdentity.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                // 显式给 UTF-8：这份表里有中文注释，Properties 默认按 ISO-8859-1 读。
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                v = props.getProperty("app.version");
            }
        } catch (IOException e) {
            v = null;
        }
        cachedVersion = (v == null || v.isBlank()) ? "未知" : v.trim();
        return cachedVersion;
    }
}
