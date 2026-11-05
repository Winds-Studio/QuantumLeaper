package cn.dreeam.leaper.update;

import cn.dreeam.leaper.LoaderConfig;
import cn.dreeam.leaper.Resources;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AbstractUpdaterTest {

    @TempDir
    Path dir;

    @Test
    void readsLoaderVersionFromManifest() throws Exception {
        final Path jar = dir.resolve("core.jar");
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.2.3");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            out.putNextEntry(new JarEntry("version.json"));
            out.write(new byte[] {'{', '}'});
            out.closeEntry();
        }

        assertEquals("1.2.3", AbstractUpdater.loaderVersion(Resources.ofJar(jar)));
    }

    @Test
    void missingManifestVersionIsNull() throws Exception {
        final Path jar = dir.resolve("core.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("version.json"));
            out.write(new byte[] {'{', '}'});
            out.closeEntry();
        }

        assertNull(AbstractUpdater.loaderVersion(Resources.ofJar(jar)));
    }
}
