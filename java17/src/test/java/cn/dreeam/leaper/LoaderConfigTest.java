package cn.dreeam.leaper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderConfigTest {

    @TempDir
    Path dir;

    Path file() {
        return dir.resolve("config/leaf-loader.yml");
    }

    @Test
    void createsDefaultFileWithComments() throws Exception {
        final LoaderConfig config = LoaderConfig.read(file());

        assertEquals(LoaderConfig.defaults(), config);
        final String content = Files.readString(file());
        assertTrue(content.contains("# Maven Central mirrors used to download libraries"), content);
        assertTrue(content.contains(LoaderConfig.DEFAULT_MAVEN_CENTRAL_MIRRORS.get(0)), content);
        assertTrue(content.contains("download-retries: 2"), content);
    }

    @Test
    void keepsUserValuesAndAddsMissingOptions() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "maven-central-mirrors:\n- https://repo.example.com/maven2/\n- https://mirror.example.cn/maven/\n");

        final LoaderConfig config = LoaderConfig.read(file());

        assertEquals(List.of("https://repo.example.com/maven2/", "https://mirror.example.cn/maven/"), config.mavenCentralMirrors());
        assertEquals(LoaderConfig.DEFAULT_DOWNLOAD_RETRIES, config.downloadRetries());
        final String content = Files.readString(file());
        assertTrue(content.contains("https://mirror.example.cn/maven/"), content);
        assertTrue(content.contains("download-retries: 2"), content);
    }

    @Test
    void rejectsInvalidValues() throws Exception {
        Files.createDirectories(file().getParent());

        Files.writeString(file(), "download-retries: -1\n");
        assertThrows(IllegalStateException.class, () -> LoaderConfig.read(file()));

        Files.writeString(file(), "maven-central-mirrors: []\n");
        assertThrows(IllegalStateException.class, () -> LoaderConfig.read(file()));
    }

    @Test
    void updateOptions() throws Exception {
        assertEquals(null, LoaderConfig.read(file()).updateChannel());
        assertTrue(Files.readString(file()).contains("update-channel: ''"), Files.readString(file()));

        Files.writeString(file(), "update-channel: experimental\nversion: \"26.10\"\n");
        final LoaderConfig config = LoaderConfig.read(file());
        assertEquals(LoaderConfig.UpdateChannel.EXPERIMENTAL, config.updateChannel());
        assertEquals("26.10", config.version());

        Files.writeString(file(), "update-channel: beta\n");
        assertThrows(IllegalStateException.class, () -> LoaderConfig.read(file()));

        // Unquoted, YAML would read it as the number 26.1
        Files.writeString(file(), "update-channel: stable\nversion: 26.10\n");
        assertThrows(IllegalStateException.class, () -> LoaderConfig.read(file()));
    }

    @Test
    void rejectsBrokenYaml() throws Exception {
        Files.createDirectories(file().getParent());
        final String broken = "maven-central-mirrors: [unclosed\n";
        Files.writeString(file(), broken);
        assertThrows(Exception.class, () -> LoaderConfig.read(file()));

        // The user's file is left untouched for them to fix
        assertEquals(broken, Files.readString(file()));
        try (var files = Files.list(file().getParent())) {
            assertEquals(1, files.count());
        }
    }
}
