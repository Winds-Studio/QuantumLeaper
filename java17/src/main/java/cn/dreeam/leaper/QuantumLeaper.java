package cn.dreeam.leaper;

import cn.dreeam.leaper.update.Updater;
import io.papermc.paperclip.Paperclip;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

public final class QuantumLeaper {

    // The paperclip data in use: our own jar, or a newer core jar from the auto updater
    private static Resources resources = Resources.OWN_JAR;

    // Mostly for Paper development usage, see Paper docs for more.
    static final boolean PATCH_ONLY = Boolean.getBoolean("paperclip.patchonly");

    private QuantumLeaper() {}

    /**
     * Kept for compatibility, the launch entry is {@link Paperclip#main(String[])}.
     */
    public static void main(final String[] args) {
        Paperclip.main(args);
    }

    /**
     * Checks the environment, then downloads, patches and extracts everything needed for the server.
     * Exits in patch-only mode.
     *
     * @return the server classpath, versions before libraries
     */
    public static URL[] prepare() {
        if (Path.of("").toAbsolutePath().toString().contains("!")) {
            System.err.println("Leaf may not run in a directory containing '!'. Please rename the affected folder.");
            System.exit(1);
        }

        if (getJavaVersion() < 23) {
            try {
                // Preload RandomGenerator service for JVM to fix `JDK-8330005`
                final RandomGenerator unused = RandomGenerator.getDefault();
            } catch (IllegalArgumentException ignored) {
                // no-op, warning should on the server software side
            }
        }

        return setupClasspath();
    }

    /**
     * Runs the server main class, called on the {@code ServerMain} thread.
     *
     * <p>If Origami is used, this method will not be called, {@code OrigamiAgent.startMain}
     * runs instead. If we need to change this method, move changes to {@link #prepare()} instead.
     */
    public static void runMain(final String mainClassName, final ClassLoader classLoader, final String[] args) {
        try {
            final Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
            final MethodHandle mainHandle = MethodHandles.lookup()
                .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                .asFixedArity();
            mainHandle.invoke((Object) args);
        } catch (final Throwable t) {
            throw Util.sneakyThrow(t);
        }
    }

    private static URL[] setupClasspath() {
        final var repoDir = Path.of(System.getProperty("bundlerRepoDir", ""));

        final LoaderConfig config = LoaderConfig.load(LoaderConfig.FILE);
        final List<String> repositories = new ArrayList<>(config.mavenCentralMirrors());
        for (final String extra : LibraryDownloader.extraRepositories()) {
            repositories.add(extra);
        }
        final LibraryDownloader downloader = new LibraryDownloader(repositories.toArray(new String[0]), config.downloadRetries());
        // Skip updater on Paper patch only
        if (!PATCH_ONLY) {
            final JarSettings settings = JarSettings.read(Resources.OWN_JAR);
            resources = new Updater(settings.updateApiType(), settings.updateApiUrl()).selectResources(config, repoDir);
        }

        final PatchEntry[] patches = findPatches();
        final DownloadContext downloadContext = findDownloadContext();
        if (patches.length > 0 && downloadContext == null) {
            throw new IllegalArgumentException("patches.list file found without a corresponding original-url file");
        }

        final Path baseFile;
        if (downloadContext != null) {
            try {
                downloadContext.download(repoDir);
            } catch (final IOException e) {
                throw Util.fail("Failed to download original jar", e);
            }
            baseFile = downloadContext.getOutputFile(repoDir);
        } else {
            baseFile = null;
        }

        final Map<String, Map<String, URL>> classpathUrls = extractAndApplyPatches(baseFile, patches, repoDir, downloader);

        // Exits for Paper userdev setup
        if (PATCH_ONLY) {
            System.exit(0);
        }

        // Keep versions and libraries separate as the versions must come first
        // This is due to change we make to some library classes inside the versions jar
        final Collection<URL> versionUrls = classpathUrls.get("versions").values();
        final Collection<URL> libraryUrls = classpathUrls.get("libraries").values();

        final URL[] emptyArray = new URL[0];
        final URL[] urls = new URL[versionUrls.size() + libraryUrls.size()];
        System.arraycopy(versionUrls.toArray(emptyArray), 0, urls, 0, versionUrls.size());
        System.arraycopy(libraryUrls.toArray(emptyArray), 0, urls, versionUrls.size(), libraryUrls.size());
        return urls;
    }

    private static PatchEntry[] findPatches() {
        try (final InputStream patchListStream = resources.open("/META-INF/patches.list")) {
            if (patchListStream == null) {
                return new PatchEntry[0];
            }
            return PatchEntry.parse(new BufferedReader(new InputStreamReader(patchListStream)));
        } catch (final IOException e) {
            throw Util.fail("Failed to read patches.list file", e);
        }
    }

    private static DownloadContext findDownloadContext() {
        final String line;
        try {
            line = resources.readText("/META-INF/download-context");
        } catch (final IOException e) {
            throw Util.fail("Failed to read download-context file", e);
        }

        return DownloadContext.parseLine(line);
    }

    private static FileEntry[] findVersionEntries() {
        return findFileEntries("versions.list");
    }
    private static FileEntry[] findLibraryEntries() {
        return findFileEntries("libraries.list");
    }
    private static FileEntry[] findFileEntries(final String fileName) {
        try (final InputStream libListStream = resources.open("/META-INF/" + fileName)) {
            if (libListStream == null) {
                return null;
            }
            return FileEntry.parse(new BufferedReader(new InputStreamReader(libListStream)));
        } catch (final IOException e) {
            throw Util.fail("Failed to read " + fileName + " file", e);
        }
    }

    public static String findMainClass() {
        final String mainClassName = System.getProperty("bundlerMainClass");
        if (mainClassName != null) {
            return mainClassName;
        }

        try {
            return resources.readText("/META-INF/main-class");
        } catch (final IOException e) {
            throw Util.fail("Failed to read main-class file", e);
        }
    }

    private static Map<String, Map<String, URL>> extractAndApplyPatches(
        final Path originalJar,
        final PatchEntry[] patches,
        final Path repoDir,
        final LibraryDownloader downloader
    ) {
        if (originalJar == null && patches.length > 0) {
            throw new IllegalArgumentException("Patch data found without patch target");
        }

        // First extract any non-patch files
        final Map<String, Map<String, URL>> urls = extractFiles(patches, originalJar, repoDir, downloader);

        // Next apply any patches that we have
        applyPatches(urls, patches, originalJar, repoDir);

        return urls;
    }

    private static Map<String, Map<String, URL>> extractFiles(
        final PatchEntry[] patches,
        final Path originalJar,
        final Path repoDir,
        final LibraryDownloader downloader
    ) {
        final var urls = new HashMap<String, Map<String, URL>>();

        try {
            final FileSystem originalJarFs;
            if (originalJar == null) {
                originalJarFs = null;
            } else {
                originalJarFs = FileSystems.newFileSystem(originalJar);
            }

            try {
                final Path originalRootDir;
                if (originalJarFs == null) {
                    originalRootDir = null;
                } else {
                    originalRootDir = originalJarFs.getPath("/");
                }

                final var versionsMap = new HashMap<String, URL>();
                urls.putIfAbsent("versions", versionsMap);
                final FileEntry[] versionEntries = findVersionEntries();
                extractEntries(versionsMap, patches, originalRootDir, repoDir, versionEntries, "versions", null);

                final FileEntry[] libraryEntries = findLibraryEntries();
                final var librariesMap = new HashMap<String, URL>();
                urls.putIfAbsent("libraries", librariesMap);
                // Libraries not bundled in our jar or the original jar are downloaded from Maven repositories
                extractEntries(librariesMap, patches, originalRootDir, repoDir, libraryEntries, "libraries", downloader);
            } finally {
                if (originalJarFs != null) {
                    originalJarFs.close();
                }
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to extract jar files", e);
        }

        return urls;
    }

    private static void extractEntries(
        final Map<String, URL> urls,
        final PatchEntry[] patches,
        final Path originalRootDir,
        final Path repoDir,
        final FileEntry[] entries,
        final String targetName,
        final LibraryDownloader downloader
    ) throws IOException {
        if (entries == null) {
            return;
        }

        final String targetPath = "/META-INF/" + targetName;
        final Path targetDir = repoDir.resolve(targetName);
        // First start: nothing is cached yet, skip checking every file
        final boolean checkCache = Files.isDirectory(targetDir);

        for (final FileEntry entry : entries) {
            entry.extractFile(resources, urls, patches, targetName, originalRootDir, targetPath, targetDir, checkCache, downloader);
        }
    }

    private static void applyPatches(
        final Map<String, Map<String, URL>> urls,
        final PatchEntry[] patches,
        final Path originalJar,
        final Path repoDir
    ) {
        if (patches.length == 0) {
            return;
        }
        if (originalJar == null) {
            throw new IllegalStateException("Patches provided without patch target");
        }

        try (final FileSystem originalFs = FileSystems.newFileSystem(originalJar)) {
            final Path originalRootDir = originalFs.getPath("/");

            for (final PatchEntry patch : patches) {
                patch.applyPatch(resources, urls, originalRootDir, repoDir);
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to apply patches", e);
        }
    }

    private static int getJavaVersion() {
        final String version = System.getProperty("java.specification.version");
        final String[] parts = version.split("\\.");

        final String errorMsg = "Could not determine version of the current JVM";
        if (parts.length == 0) {
            throw new IllegalStateException(errorMsg);
        }

        if (parts[0].equals("1")) {
            if (parts.length < 2) {
                throw new IllegalStateException(errorMsg);
            }
            return Integer.parseInt(parts[1]);
        } else {
            return Integer.parseInt(parts[0]);
        }
    }
}
