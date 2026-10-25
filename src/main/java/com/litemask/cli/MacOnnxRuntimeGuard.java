package com.litemask.cli;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 在打包版 macOS 进程触碰 ORT 之前，把原生加载锁定到 App 内已签名目录。 */
final class MacOnnxRuntimeGuard {

    static final String NATIVE_PATH_PROPERTY = "onnxruntime.native.path";
    static final List<String> NATIVE_SKIP_PROPERTIES = List.of(
            "onnxruntime.native.onnxruntime.skip",
            "onnxruntime.native.onnxruntime4j_jni.skip");
    static final String NATIVE_RELATIVE_DIR = "native/onnxruntime";
    static final List<String> REQUIRED_LIBRARIES = List.of(
            "libonnxruntime.dylib", "libonnxruntime4j_jni.dylib");

    private MacOnnxRuntimeGuard() {
    }

    static void configure() {
        try {
            URI location = DesktopLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            configureFor(System.getProperty("os.name", ""), Path.of(location));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法定位 LightMeter 程序，拒绝初始化 ONNX Runtime", e);
        }
    }

    static void configureFor(String osName, Path codeLocation) {
        if (osName == null || !osName.toLowerCase(Locale.ROOT).contains("mac")) {
            return;
        }
        Path jar = codeLocation.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar) || !isPackagedAppJar(jar)) {
            // Maven/IDE 与普通开发 jar 保持 ORT 既有加载行为；只有 .app 发布形态强制固定目录。
            return;
        }
        Path appRoot = jar.getParent().getParent().getParent();
        Path nativeDir = jar.getParent().resolve(NATIVE_RELATIVE_DIR).normalize();
        try {
            Path realApp = appRoot.toRealPath();
            Path realNative = nativeDir.toRealPath();
            if (!realNative.startsWith(realApp) || Files.isSymbolicLink(nativeDir)) {
                throw new IllegalStateException("ONNX 原生库目录不在当前 LightMeter.app 的密封范围内");
            }
            for (String name : REQUIRED_LIBRARIES) {
                Path library = nativeDir.resolve(name);
                Path realLibrary = library.toRealPath();
                if (!realLibrary.getParent().equals(realNative)
                        || Files.isSymbolicLink(library) || !Files.isRegularFile(realLibrary)) {
                    throw new IllegalStateException("ONNX 原生库不是 App 内固定普通文件：" + name);
                }
            }
            // ORT 1.20 中 skip 的优先级高于 native.path；发布版不允许调用参数绕过加载。
            NATIVE_SKIP_PROPERTIES.forEach(System::clearProperty);
            System.setProperty(NATIVE_PATH_PROPERTY, realNative.toString());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LightMeter.app 缺少已签名 ONNX 原生库，拒绝启动", e);
        }
    }

    private static boolean isPackagedAppJar(Path jar) {
        Path appDir = jar.getParent();
        Path contents = appDir == null ? null : appDir.getParent();
        Path bundle = contents == null ? null : contents.getParent();
        return appDir != null && contents != null && bundle != null
                && "app".equals(appDir.getFileName().toString())
                && "Contents".equals(contents.getFileName().toString())
                && bundle.getFileName().toString().endsWith(".app");
    }
}
