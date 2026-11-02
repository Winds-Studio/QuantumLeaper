package cn.dreeam.leaper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Auto update: downloads the newest core jar of the configured channel to {@code <repoDir>/updates/} and uses its
 * paperclip data (patches and lists) instead of our own. The loader code itself is not updated, that requires
 * replacing the server jar.
 *
 * <p>Failures never stop the server from starting, it starts with the data in use before.
 */
final class AutoUpdater {

    /**
     * The newest {@code META-INF/leaper-format} this loader can handle. A jar without it is format 1.
     * <ul>
     *     <li>1: all libraries are bundled, or come from the original jar or patches</li>
     *     <li>2: libraries may have to be downloaded from Maven repositories</li>
     * </ul>
     */
    static final int SUPPORTED_FORMAT = 2;

    private static final String STATE_FILE = "state.properties";

    /**
     * The update in use. {@code baseSha256} is the server jar it was downloaded for, if the server jar changes
     * (e.g. replaced by the user with a newer one) the update is discarded.
     */
    record State(String baseSha256, String version, int build, String file, String sha256) {

        static State read(final Path updatesDir) {
            final Path file = updatesDir.resolve(STATE_FILE);
            if (Files.notExists(file)) {
                return null;
            }
            final Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
                return new State(
                    properties.getProperty("base-sha256"),
                    properties.getProperty("version"),
                    Integer.parseInt(properties.getProperty("build")),
                    properties.getProperty("file"),
                    properties.getProperty("sha256")
                );
            } catch (final IOException | RuntimeException e) {
                System.out.println("Ignoring invalid auto update state " + file + ": " + Http.describe(e));
                return null;
            }
        }

        void write(final Path updatesDir) throws IOException {
            final Properties properties = new Properties();
            properties.setProperty("base-sha256", this.baseSha256);
            properties.setProperty("version", this.version);
            properties.setProperty("build", Integer.toString(this.build));
            properties.setProperty("file", this.file);
            properties.setProperty("sha256", this.sha256);

            final Path file = updatesDir.resolve(STATE_FILE);
            final Path temp = Util.tempFileFor(file);
            try {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    properties.store(out, "QuantumLeaper auto update, do not edit");
                }
                Util.moveAtomically(temp, file);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private AutoUpdater() {}

    static Resources selectResources(final LoaderConfig config, final Path repoDir) {
        if (config.updateChannel() == null) {
            return Resources.OWN_JAR;
        }
        final String api = System.getProperty("leaper.update.api", UpdateApi.DEFAULT_BASE);
        return selectResources(config, repoDir, ownJar(), new UpdateApi(api));
    }

    static Resources selectResources(final LoaderConfig config, final Path repoDir, final Path ownJar, final UpdateApi api) {
        if (ownJar == null) {
            System.out.println("Auto update skipped: not running from a jar");
            return Resources.OWN_JAR;
        }

        final String ownVersion = minecraftVersion(Resources.OWN_JAR);
        final String version = config.version() != null ? config.version() : ownVersion;
        if (version == null) {
            System.out.println("Auto update skipped: unknown Minecraft version, set \"version\" in " + LoaderConfig.FILE);
            return Resources.OWN_JAR;
        }

        final Path updatesDir = repoDir.resolve("updates");
        final String ownSha256;
        try {
            ownSha256 = HexFormat.of().formatHex(Util.sha256(ownJar));
        } catch (final IOException e) {
            System.out.println("Auto update skipped: failed to read " + ownJar + ": " + Http.describe(e));
            return Resources.OWN_JAR;
        }

        State state = State.read(updatesDir);
        if (state != null && (!ownSha256.equals(state.baseSha256()) || !version.equals(state.version()))) {
            // The server jar or the followed version changed since the update was downloaded
            state = null;
        }

        System.out.println("Checking for updates (" + config.updateChannel().configName + ", " + version + ")");
        try {
            final List<UpdateApi.Build> builds = api.builds(version);
            final UpdateApi.Build latest = UpdateApi.latest(builds, config.updateChannel());
            final UpdateApi.Build own = builds.stream()
                .filter(b -> b.sha256().equalsIgnoreCase(ownSha256))
                .max(java.util.Comparator.comparingInt(UpdateApi.Build::build))
                .orElse(null);

            if (latest == null) {
                System.out.println("No " + config.updateChannel().configName + " build for " + version);
            } else if (own != null && own.build() >= latest.build()) {
                System.out.println("Up to date (build " + own.build() + ")");
                state = null;
            } else if (own == null && version.equals(ownVersion)) {
                System.out.println("Auto update skipped: this server jar is not a published build");
                return Resources.OWN_JAR;
            } else if (state != null && state.sha256().equalsIgnoreCase(latest.sha256())) {
                System.out.println("Up to date (build " + latest.build() + ", " + updatesDir.resolve(state.file()) + ")");
            } else if (state != null && state.build() > latest.build()) {
                System.out.println("Keeping build " + state.build() + ", newer than the latest " + config.updateChannel().configName + " build");
            } else {
                state = download(latest, version, ownSha256, updatesDir, state);
            }
        } catch (final IOException e) {
            System.out.println("Failed to check for updates: " + Http.describe(e));
            System.out.println("Continuing with " + (state != null ? "build " + state.build() : "this server jar"));
        }

        return use(state, updatesDir);
    }

    private static State download(
        final UpdateApi.Build latest,
        final String version,
        final String ownSha256,
        final Path updatesDir,
        final State previous
    ) throws IOException {
        final Path file = updatesDir.resolve(latest.fileName());
        System.out.println("Downloading build " + latest.build() + " (" + latest.fileName() + ")");
        Http.download(latest.downloadUrl(), HexFormat.of().parseHex(latest.sha256()), file, true);

        final State state = new State(ownSha256, version, latest.build(), latest.fileName(), latest.sha256());
        state.write(updatesDir);

        // Keep the previous update for manual rollback, remove older ones
        try (Stream<Path> files = Files.list(updatesDir)) {
            for (final Path old : files.toList()) {
                final String name = old.getFileName().toString();
                final boolean keep = name.equals(STATE_FILE) || name.equals(state.file())
                    || (previous != null && name.equals(previous.file()));
                if (!keep && name.endsWith(".jar")) {
                    Files.deleteIfExists(old);
                }
            }
        }
        if (previous != null) {
            System.out.println("Previous build kept at " + updatesDir.resolve(previous.file()));
        }
        return state;
    }

    private static Resources use(final State state, final Path updatesDir) {
        if (state == null) {
            return Resources.OWN_JAR;
        }

        final Path file = updatesDir.resolve(state.file());
        // Verified on every start, not only after downloading
        if (!Util.isFileValid(file, HexFormat.of().parseHex(state.sha256()))) {
            System.out.println("Ignoring " + file + ": missing or hash check failed");
            return Resources.OWN_JAR;
        }

        try {
            final Resources resources = Resources.ofJar(file);
            final int format = format(resources);
            if (format > SUPPORTED_FORMAT) {
                System.out.println("Build " + state.build() + " requires a newer loader, replace the server jar with " + file);
                return Resources.OWN_JAR;
            }
            System.out.println("Using build " + state.build() + " from " + file);
            System.out.println("To go back to this server jar, delete " + updatesDir.resolve(STATE_FILE)
                + " and set update-channel to empty");
            return resources;
        } catch (final IOException e) {
            System.out.println("Ignoring " + file + ": " + Http.describe(e));
            return Resources.OWN_JAR;
        }
    }

    static int format(final Resources resources) throws IOException {
        final String text = resources.readText("/META-INF/leaper-format");
        if (text == null) {
            return 1;
        }
        try {
            return Integer.parseInt(text.strip());
        } catch (final NumberFormatException e) {
            throw new IOException("Invalid META-INF/leaper-format: " + text.strip());
        }
    }

    /**
     * @return the {@code id} of {@code version.json} (written by Mojang, copied into the jar by paperweight)
     */
    static String minecraftVersion(final Resources resources) {
        try {
            final String json = resources.readText("/version.json");
            if (json == null) {
                return null;
            }
            final Object id = ((Map<?, ?>) UpdateApi.parseJson(json)).get("id");
            return id == null ? null : id.toString();
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private static Path ownJar() {
        try {
            final Path path = Path.of(AutoUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (final URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
