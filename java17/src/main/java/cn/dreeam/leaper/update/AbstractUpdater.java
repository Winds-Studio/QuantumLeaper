package cn.dreeam.leaper.update;

import cn.dreeam.leaper.Http;
import cn.dreeam.leaper.LoaderConfig;
import cn.dreeam.leaper.Resources;
import cn.dreeam.leaper.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Manifest;

/**
 * Auto update: downloads the newest core jar of the configured channel to {@code <repoDir>/updates/} and uses its
 * paperclip data (patches and lists) instead of our own. The loader code itself is not updated, that requires
 * replacing the server jar.
 *
 * <p>Failures never stop the server from starting, it starts with the data in use before.
 */
public abstract class AbstractUpdater {

    private static final String STATE_FILE = "state.properties";

    // Overrides the project download API url. Only for debugging and test cases.
    private static final String API_OVERRIDE_PROPERTY = "leaper.update.api";

    private final ApiType apiType;
    private final String apiUrl;

    /**
     * @param apiType response format of the build download API
     * @param apiUrl  project url of the API, without version, e.g. {@code https://api.leafmc.one/v2/projects/leaf}.
     *                Can be overridden with {@code -D}{@value #API_OVERRIDE_PROPERTY}.
     */
    protected AbstractUpdater(final ApiType apiType, final String apiUrl) {
        this.apiType = apiType;
        this.apiUrl = apiUrl;
    }

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

    public Resources selectResources(final LoaderConfig config, final Path repoDir) {
        if (!config.autoUpdate()) {
            return Resources.OWN_JAR;
        }
        final String api = System.getProperty(API_OVERRIDE_PROPERTY, this.apiUrl);
        return selectResources(config, repoDir, ownJar(), new UpdateApi(this.apiType, api));
    }

    static Resources selectResources(final LoaderConfig config, final Path repoDir, final Path ownJar, final UpdateApi api) {
        if (ownJar == null) {
            System.out.println("Auto update skipped: not running from a jar");
            return Resources.OWN_JAR;
        }

        final String version = minecraftVersion(Resources.OWN_JAR);
        if (version == null) {
            System.out.println("Auto update skipped: unknown Minecraft version");
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
            UpdateApi.Build own = null;
            for (final UpdateApi.Build b : builds) {
                if (b.sha256().equalsIgnoreCase(ownSha256) && (own == null || b.build() > own.build())) {
                    own = b;
                }
            }

            if (latest == null) {
                System.out.println("No " + config.updateChannel().configName + " build for " + version);
            } else if (own != null && own.build() >= latest.build()) {
                System.out.println("Up to date (build " + own.build() + ")");
                state = null;
            } else if (own == null) {
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
        try (DirectoryStream<Path> files = Files.newDirectoryStream(updatesDir)) {
            for (final Path old : files) {
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
            final int requiredJava = javaVersion(resources);
            if (Runtime.version().feature() < requiredJava) {
                System.out.println("Build " + state.build() + " requires Java " + requiredJava + " or above, not using it."
                    + " For information on how to update Java, see https://docs.papermc.io/misc/java-install");
                return Resources.OWN_JAR;
            }
            final String loaderVersion = loaderVersion(resources);
            final String ownVersion = ownLoaderVersion();
            if (loaderVersion != null && ownVersion != null && !loaderVersion.equals(ownVersion)) {
                System.out.println();
                System.out.println("[QuantumLeaper]");
                System.out.println("Loader update: " + ownVersion + " -> " + loaderVersion);
                System.out.println("Replace the server jar with " + file + " to update the loader.");
                System.out.println();
            }
            System.out.println("Using build " + state.build() + " from " + file);
            System.out.println("To go back to this server jar, delete " + updatesDir.resolve(STATE_FILE)
                + " and set auto-update.enabled to false");
            return resources;
        } catch (final IOException e) {
            System.out.println("Ignoring " + file + ": " + Http.describe(e));
            return Resources.OWN_JAR;
        }
    }

    /**
     * @return the {@code Implementation-Version} of the running loader, or {@code null} when not running from a jar
     */
    static String ownLoaderVersion() {
        return AbstractUpdater.class.getPackage().getImplementationVersion();
    }

    /**
     * @return the {@code Implementation-Version} of the loader in the jar, or {@code null} if there is none
     */
    static String loaderVersion(final Resources resources) throws IOException {
        final InputStream manifest = resources.open("/META-INF/MANIFEST.MF");
        if (manifest == null) {
            return null;
        }
        try (manifest) {
            return new Manifest(manifest).getMainAttributes().getValue("Implementation-Version");
        }
    }

    /**
     * @return the {@code java_version} of {@code version.json}, or 0 if unknown
     */
    static int javaVersion(final Resources resources) {
        try {
            final String json = resources.readText("/version.json");
            if (json == null) {
                return 0;
            }
            final Object value = ((Map<?, ?>) UpdateApi.parseJson(json)).get("java_version");
            return value instanceof Number number ? number.intValue() : 0;
        } catch (final IOException | RuntimeException e) {
            return 0;
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
            final Path path = Path.of(AbstractUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (final URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    public enum ApiType {
        BIBLIOTHEK,
        FILLV3
    }
}
