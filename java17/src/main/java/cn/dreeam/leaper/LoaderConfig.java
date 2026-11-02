package cn.dreeam.leaper;

import io.github.thatsmusic99.configurationmaster.api.ConfigFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loader settings stored in {@code config/leaf-loader.yaml}.
 */
record LoaderConfig(List<String> mavenRepositories, int downloadRetries) {

    static final Path FILE = Path.of("config", "leaf-loader.yaml");

    static final String MAVEN_REPOSITORIES = "maven-repositories";
    static final String DOWNLOAD_RETRIES = "download-retries";

    static final List<String> DEFAULT_MAVEN_REPOSITORIES =
        List.of("https://maven-central.storage-download.googleapis.com/maven2/");
    static final int DEFAULT_DOWNLOAD_RETRIES = 2;

    static LoaderConfig defaults() {
        return new LoaderConfig(DEFAULT_MAVEN_REPOSITORIES, DEFAULT_DOWNLOAD_RETRIES);
    }

    /**
     * Loads the config, creating it or filling in missing options.
     */
    static LoaderConfig load(final Path file) {
        try {
            return read(file);
        } catch (final Exception e) {
            throw Util.fail("Failed to load loader config " + file.toAbsolutePath(), e);
        }
    }

    static LoaderConfig read(final Path file) throws Exception {
        if (Files.exists(file)) {
            // ConfigurationMaster would back up a broken file and silently continue with the defaults,
            // fail instead so the user's settings (e.g. repositories) are never ignored
            new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
        } else {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }

        final ConfigFile config = ConfigFile.loadConfig(file.toFile());
        config.addDefault(MAVEN_REPOSITORIES, DEFAULT_MAVEN_REPOSITORIES,
            "Maven repositories used to download libraries, tried in order.\n"
                + "Setting this replaces the default repository.");
        config.addDefault(DOWNLOAD_RETRIES, DEFAULT_DOWNLOAD_RETRIES,
            "How many times to retry a failed download from the same repository.");

        final List<String> repositories = config.getStringList(MAVEN_REPOSITORIES);
        final int retries = config.getInteger(DOWNLOAD_RETRIES, DEFAULT_DOWNLOAD_RETRIES);

        config.save();

        if (repositories.isEmpty()) {
            throw new IllegalStateException(MAVEN_REPOSITORIES + " must not be empty");
        }
        if (retries < 0) {
            throw new IllegalStateException(DOWNLOAD_RETRIES + " must not be negative");
        }
        return new LoaderConfig(List.copyOf(repositories), retries);
    }
}
