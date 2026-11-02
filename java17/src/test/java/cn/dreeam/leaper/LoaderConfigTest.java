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
        return dir.resolve("config/leaf-loader.yaml");
    }

    @Test
    void createsDefaultFileWithComments() throws Exception {
        final LoaderConfig config = LoaderConfig.read(file());

        assertEquals(LoaderConfig.defaults(), config);
        final String content = Files.readString(file());
        assertTrue(content.contains("# Maven repositories used to download libraries"), content);
        assertTrue(content.contains(LoaderConfig.DEFAULT_MAVEN_REPOSITORIES.get(0)), content);
        assertTrue(content.contains("download-retries: 2"), content);
    }

    @Test
    void keepsUserValuesAndAddsMissingOptions() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "maven-repositories:\n- https://repo.example.com/maven2/\n- https://mirror.example.cn/maven/\n");

        final LoaderConfig config = LoaderConfig.read(file());

        assertEquals(List.of("https://repo.example.com/maven2/", "https://mirror.example.cn/maven/"), config.mavenRepositories());
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

        Files.writeString(file(), "maven-repositories: []\n");
        assertThrows(IllegalStateException.class, () -> LoaderConfig.read(file()));
    }

    @Test
    void rejectsBrokenYaml() throws Exception {
        Files.createDirectories(file().getParent());
        final String broken = "maven-repositories: [unclosed\n";
        Files.writeString(file(), broken);
        assertThrows(Exception.class, () -> LoaderConfig.read(file()));

        // The user's file is left untouched for them to fix
        assertEquals(broken, Files.readString(file()));
        try (var files = Files.list(file().getParent())) {
            assertEquals(1, files.count());
        }
    }
}
