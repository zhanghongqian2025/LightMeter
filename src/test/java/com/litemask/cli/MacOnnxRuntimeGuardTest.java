package com.litemask.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacOnnxRuntimeGuardTest {

    @TempDir
    Path tmp;

    @AfterEach
    void clearProperties() {
        System.clearProperty(MacOnnxRuntimeGuard.NATIVE_PATH_PROPERTY);
        MacOnnxRuntimeGuard.NATIVE_SKIP_PROPERTIES.forEach(System::clearProperty);
    }

    @Test
    void packagedMacOverridesExternalPathAndClearsSkip() throws Exception {
        Path jar = packagedJar(true);
        System.setProperty(MacOnnxRuntimeGuard.NATIVE_PATH_PROPERTY, "/tmp/attacker-native");
        MacOnnxRuntimeGuard.NATIVE_SKIP_PROPERTIES.forEach(p -> System.setProperty(p, "true"));

        MacOnnxRuntimeGuard.configureFor("Mac OS X", jar);

        Path expected = jar.getParent().resolve(MacOnnxRuntimeGuard.NATIVE_RELATIVE_DIR).toRealPath();
        assertEquals(expected.toString(),
                System.getProperty(MacOnnxRuntimeGuard.NATIVE_PATH_PROPERTY));
        for (String property : MacOnnxRuntimeGuard.NATIVE_SKIP_PROPERTIES) {
            assertEquals(null, System.getProperty(property));
        }
    }

    @Test
    void packagedMacFailsClosedWhenEitherSignedLibraryIsMissing() throws Exception {
        Path jar = packagedJar(false);
        assertThrows(IllegalStateException.class,
                () -> MacOnnxRuntimeGuard.configureFor("Mac OS X", jar));
    }

    @Test
    void nonMacAndDevelopmentClassesKeepExistingOrtBehavior() throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        System.setProperty(MacOnnxRuntimeGuard.NATIVE_PATH_PROPERTY, "/developer/path");
        MacOnnxRuntimeGuard.NATIVE_SKIP_PROPERTIES.forEach(p -> System.setProperty(p, "true"));

        MacOnnxRuntimeGuard.configureFor("Linux", tmp.resolve("anything.jar"));
        MacOnnxRuntimeGuard.configureFor("Mac OS X", classes);

        assertEquals("/developer/path", System.getProperty(MacOnnxRuntimeGuard.NATIVE_PATH_PROPERTY));
        for (String property : MacOnnxRuntimeGuard.NATIVE_SKIP_PROPERTIES) {
            assertEquals("true", System.getProperty(property));
        }
    }

    private Path packagedJar(boolean complete) throws Exception {
        Path appDir = Files.createDirectories(
                tmp.resolve("LightMeter.app/Contents/app"));
        Path jar = Files.write(appDir.resolve("desens-core.jar"), new byte[]{1});
        Path nativeDir = Files.createDirectories(
                appDir.resolve(MacOnnxRuntimeGuard.NATIVE_RELATIVE_DIR));
        Files.write(nativeDir.resolve("libonnxruntime.dylib"), new byte[]{1});
        if (complete) {
            Files.write(nativeDir.resolve("libonnxruntime4j_jni.dylib"), new byte[]{1});
        }
        return jar;
    }
}
