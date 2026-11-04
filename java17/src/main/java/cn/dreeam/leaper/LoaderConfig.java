package cn.dreeam.leaper;

import io.github.thatsmusic99.configurationmaster.api.ConfigFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loader settings stored in {@code config/leaf-loader.yml}.
 */
record LoaderConfig(List<String> mavenCentralMirrors, int downloadRetries, boolean autoUpdate, UpdateChannel updateChannel) {

    /**
     * Auto update channels, and the matching channel name of the download API.
     */
    enum UpdateChannel {
        STABLE("stable"),
        EXPERIMENTAL("experimental");

        final String configName;

        UpdateChannel(final String configName) {
            this.configName = configName;
        }

        static UpdateChannel parse(final String value) {
            for (final UpdateChannel channel : values()) {
                if (channel.configName.equals(value)) {
                    return channel;
                }
            }
            throw new IllegalStateException(AUTO_UPDATE_CHANNEL + " must be stable or experimental, got: " + value);
        }
    }

    static final Path FILE = Path.of("config", "leaf-loader.yml");

    static final String MAVEN_CENTRAL_MIRRORS = "maven-central-mirrors";
    static final String DOWNLOAD_RETRIES = "download-retries";
    static final String AUTO_UPDATE_ENABLED = "auto-update.enabled";
    static final String AUTO_UPDATE_CHANNEL = "auto-update.channel";

    static final List<String> DEFAULT_MAVEN_CENTRAL_MIRRORS =
        List.of("https://maven-central.storage-download.googleapis.com/maven2/");
    static final int DEFAULT_DOWNLOAD_RETRIES = 2;
    static final boolean DEFAULT_AUTO_UPDATE = false;
    static final UpdateChannel DEFAULT_UPDATE_CHANNEL = UpdateChannel.STABLE;

    static LoaderConfig defaults() {
        return new LoaderConfig(DEFAULT_MAVEN_CENTRAL_MIRRORS, DEFAULT_DOWNLOAD_RETRIES, DEFAULT_AUTO_UPDATE, DEFAULT_UPDATE_CHANNEL);
    }

    /**
     * Loads the config, creating it or filling in missing options.
     * On the first start the default config is written and the launcher exits, so the user can edit it first.
     */
    static LoaderConfig load(final Path file) {
        final boolean firstStart = Files.notExists(file);
        final LoaderConfig config;
        try {
            config = read(file);
        } catch (final Exception e) {
            throw Util.fail("Failed to load loader config " + file.toAbsolutePath(), e);
        }

        if (firstStart) {
            System.out.println();
            System.out.println("[QuantumLeaper]");
            System.out.println("Created " + file.toAbsolutePath() + " with the default settings.");
            System.out.println("Check it first, then start the server again.");
            System.out.println();
            System.exit(0);
        }
        return config;
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
        config.addDefault(MAVEN_CENTRAL_MIRRORS, DEFAULT_MAVEN_CENTRAL_MIRRORS,
            "Maven Central mirrors used to download libraries, tried in order.\n"
                + "Setting this replaces the default mirror. Libraries from the PaperMC and Leaf repositories\n"
                + "are downloaded from those repositories after trying these.");
        config.addDefault(DOWNLOAD_RETRIES, DEFAULT_DOWNLOAD_RETRIES,
            "How many times to retry a failed download from the same repository.");

        config.addDefault(AUTO_UPDATE_ENABLED, DEFAULT_AUTO_UPDATE,
            "Auto update: on every start, download the newest build of the channel to the updates directory\n"
                + "and use its patches. Updating the loader itself requires replacing the server jar.");
        config.addDefault(AUTO_UPDATE_CHANNEL, DEFAULT_UPDATE_CHANNEL.configName,
            "Auto update channel: stable or experimental.");

        final List<String> repositories = config.getStringList(MAVEN_CENTRAL_MIRRORS);
        final int retries = config.getInteger(DOWNLOAD_RETRIES, DEFAULT_DOWNLOAD_RETRIES);
        final boolean autoUpdate = config.getBoolean(AUTO_UPDATE_ENABLED, DEFAULT_AUTO_UPDATE);
        final String channel = config.getString(AUTO_UPDATE_CHANNEL, DEFAULT_UPDATE_CHANNEL.configName).strip();

        config.save();

        if (repositories.isEmpty()) {
            throw new IllegalStateException(MAVEN_CENTRAL_MIRRORS + " must not be empty");
        }
        if (retries < 0) {
            throw new IllegalStateException(DOWNLOAD_RETRIES + " must not be negative");
        }
        return new LoaderConfig(
            List.copyOf(repositories),
            retries,
            autoUpdate,
            UpdateChannel.parse(channel)
        );
    }
}
