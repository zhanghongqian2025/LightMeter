package com.litemask.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** 运行中的工具构建身份：发布版本与实际承载代码的 jar 摘要。 */
public final class ToolIdentity {

    private static final String RESOURCE = "/META-INF/litemask/package-identity.properties";
    private static final String VERSION = loadVersion();
    private static final String ARTIFACT_SHA256 = loadArtifactSha256();

    private ToolIdentity() {
    }

    public static String version() {
        return VERSION;
    }

    /**
     * 当前代码来源是一个普通文件（发布包里的 fat jar）时返回它的 SHA-256。
     * IDE/测试目录不是单一不可变工件，因而返回 {@code null}，绝不伪造摘要。
     */
    public static String artifactSha256() {
        return ARTIFACT_SHA256;
    }

    private static String loadVersion() {
        try (InputStream in = ToolIdentity.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return "未知";
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            String version = props.getProperty("app.version");
            return version == null || version.isBlank() ? "未知" : version.trim();
        } catch (IOException e) {
            return "未知";
        }
    }

    private static String loadArtifactSha256() {
        try {
            if (ToolIdentity.class.getProtectionDomain() == null
                    || ToolIdentity.class.getProtectionDomain().getCodeSource() == null) {
                return null;
            }
            URI location = ToolIdentity.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path artifact = Path.of(location).toAbsolutePath().normalize();
            if (!Files.isRegularFile(artifact)) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
