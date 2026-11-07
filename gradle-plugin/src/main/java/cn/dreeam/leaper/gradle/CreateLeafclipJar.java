package cn.dreeam.leaper.gradle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Creates the leafclip jar:
 * <ul>
 *     <li>Exclude the libraries downloadable from the repositories of the launcher, it downloads them on server start.</li>
 *     <li>Writes {@code META-INF/leaper.properties}, defining the update API for the launcher.</li>
 *     <li>Adds {@code LICENSE.txt}.</li>
 * </ul>
 */
@DisableCachingByDefault(because = "The result depends on the repositories, not only on the inputs")
public abstract class CreateLeafclipJar extends DefaultTask {

    // Same as the launcher, in the same order
    private static final List<String> REPOSITORIES = List.of(
        "https://repo1.maven.org/maven2/",
        "https://repo.papermc.io/repository/maven-public/",
        "https://maven.leafmc.one/snapshots/"
    );
    private static final String LIBRARIES = "META-INF/libraries/";
    private static final String LIBRARIES_LIST = "META-INF/libraries.list";
    private static final String SETTINGS = "META-INF/leaper.properties";

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getPaperclipJar();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLicenseFile();

    @Input
    @Optional
    public abstract Property<String> getUpdateApiType();

    @Input
    @Optional
    public abstract Property<String> getUpdateApiUrl();

    @OutputFile
    public abstract RegularFileProperty getOutputZip();

    @Internal
    public abstract DirectoryProperty getCacheDir();

    /**
     * @param line the line of {@code libraries.list}
     * @param path also where the file is in the jar, below {@value #LIBRARIES}
     */
    private record Lib(String sha256, String id, String path, String line) {}

    /**
     * @param url the url of the file, or "cached"
     */
    private record Source(String repository, String url) {}

    @TaskAction
    public void create() throws IOException {
        final Path input = getPaperclipJar().getSingleFile().toPath();
        final Path output = getOutputZip().get().getAsFile().toPath();
        final Path temp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.createDirectories(output.getParent());

        final Map<Lib, Source> removed = new LinkedHashMap<>();
        final List<Lib> kept = new ArrayList<>(); // no repository has a file with the same SHA-256

        try (ZipFile zip = new ZipFile(input.toFile())) {
            final List<Lib> libs = readLibraries(zip, input);

            // Only libraries bundled as complete jars; the others are from the original jar or created from patches
            final List<Lib> bundled = new ArrayList<>();
            for (final Lib lib : libs) {
                if (zip.getEntry(LIBRARIES + lib.path()) != null) {
                    bundled.add(lib);
                }
            }
            findSources(bundled, removed, kept);

            final Set<String> removedNames = new HashSet<>();
            for (final Lib lib : removed.keySet()) {
                removedNames.add(LIBRARIES + lib.path());
            }
            // Directories that still contain a file
            final Set<String> keptDirs = new HashSet<>();
            for (final ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory() || removedNames.contains(entry.getName())) {
                    continue;
                }
                int index = entry.getName().lastIndexOf('/');
                while (index > 0 && keptDirs.add(entry.getName().substring(0, index + 1))) {
                    index = entry.getName().lastIndexOf('/', index - 1);
                }
            }

            final ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            final long fixedTime = manifest != null ? manifest.getTime() : 0L;
            boolean hasLicense = false;
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (final ZipEntry entry : Collections.list(zip.entries())) {
                    final String name = entry.getName();
                    if (removedNames.contains(name) || name.equals(SETTINGS)
                        || (entry.isDirectory() && name.startsWith(LIBRARIES) && !keptDirs.contains(name))) {
                        continue;
                    }
                    hasLicense |= name.equals("LICENSE.txt");

                    out.putNextEntry(entry(name, entry.getTime()));
                    if (!entry.isDirectory()) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            in.transferTo(out);
                        }
                    }
                    out.closeEntry();
                }

                final String settings = settings();
                if (!settings.isEmpty()) {
                    add(out, SETTINGS, fixedTime, settings.getBytes(StandardCharsets.UTF_8));
                }
                if (!hasLicense && getLicenseFile().isPresent()) {
                    add(out, "LICENSE.txt", fixedTime, Files.readAllBytes(getLicenseFile().get().getAsFile().toPath()));
                }
            }
        }
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);

        removed.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Lib::id)))
            .forEach(e -> getLogger().info("createLeafclipJar: removed {} <- {}", e.getKey().id(), e.getValue().url()));
        kept.stream().sorted(Comparator.comparing(Lib::id))
            .forEach(lib -> getLogger().info("createLeafclipJar: kept {}", lib.id()));
        getLogger().lifecycle("createLeafclipJar: removed {} libraries, kept {}; {} -> {}",
            removed.size(), kept.size(), mb(Files.size(input)), mb(Files.size(output)));
    }

    private static List<Lib> readLibraries(final ZipFile zip, final Path jar) throws IOException {
        final ZipEntry list = zip.getEntry(LIBRARIES_LIST);
        if (list == null) {
            throw new GradleException(LIBRARIES_LIST + " not found in " + jar);
        }
        final List<Lib> libs = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(list), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final String[] parts = line.split("\t");
                if (parts.length != 3) {
                    throw new GradleException("Malformed " + LIBRARIES_LIST + " line: " + line);
                }
                libs.add(new Lib(parts[0], parts[1], parts[2], line));
            }
        }
        return libs;
    }

    private String settings() {
        final StringBuilder settings = new StringBuilder();
        if (getUpdateApiUrl().isPresent()) {
            settings.append("update-api-type=").append(getUpdateApiType().getOrElse("BIBLIOTHEK")).append('\n');
            settings.append("update-api-url=").append(getUpdateApiUrl().get()).append('\n');
        }
        return settings.toString();
    }

    private void findSources(final List<Lib> libs, final Map<Lib, Source> removed, final List<Lib> kept) throws IOException {
        final Path cache = getCacheDir().get().getAsFile().toPath();
        Files.createDirectories(cache);
        final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

        final ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            final Map<Lib, Future<Source>> futures = new LinkedHashMap<>();
            for (final Lib lib : libs) {
                futures.put(lib, executor.submit(() -> findSource(client, lib, cache)));
            }
            for (final Map.Entry<Lib, Future<Source>> entry : futures.entrySet()) {
                final Source source;
                try {
                    source = entry.getValue().get();
                } catch (final ExecutionException e) {
                    throw e.getCause() instanceof RuntimeException r ? r : new GradleException(e.getCause().toString(), e.getCause());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GradleException("Interrupted", e);
                }
                if (source != null) {
                    removed.put(entry.getKey(), source);
                } else {
                    kept.add(entry.getKey());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Same order as the launcher: the path of libraries.list in every repository, then the {@code -SNAPSHOT} one.
     * The cache remembers the repository of a SHA-256.
     *
     * @return where the file with this SHA-256 can be downloaded from, or null if nowhere
     */
    private static Source findSource(final HttpClient client, final Lib lib, final Path cache) throws IOException, InterruptedException {
        final Path cached = cache.resolve(lib.sha256());
        if (Files.isRegularFile(cached)) {
            final String repository = Files.readString(cached).strip();
            if (REPOSITORIES.contains(repository)) {
                return new Source(repository, "cached " + repository);
            }
        }

        final List<String> paths = new ArrayList<>();
        paths.add(lib.path());
        final String snapshotPath = snapshotPath(lib.path());
        if (snapshotPath != null) {
            paths.add(snapshotPath);
        }

        final Path temp = Files.createTempFile(cache, "download", ".tmp");
        try {
            for (final String path : paths) {
                for (final String repository : REPOSITORIES) {
                    final String url = repository + path;
                    if (lib.sha256().equals(download(client, url, temp))) {
                        Files.writeString(cached, repository);
                        return new Source(repository, url);
                    }
                }
            }
            return null;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Same as the launcher: maps a timestamped snapshot path to its {@code -SNAPSHOT} directory, or null if not a
     * timestamped snapshot.
     */
    static String snapshotPath(final String path) {
        final int fileStart = path.lastIndexOf('/');
        final int dirStart = path.lastIndexOf('/', fileStart - 1);
        if (dirStart < 0) {
            return null;
        }
        final String version = path.substring(dirStart + 1, fileStart);
        // <base>-yyyyMMdd.HHmmss-<build>
        final int buildDash = version.lastIndexOf('-');
        final int stampDash = version.lastIndexOf('-', buildDash - 1);
        if (stampDash <= 0 || buildDash - stampDash != 16 || version.charAt(stampDash + 9) != '.') {
            return null;
        }
        return path.substring(0, dirStart + 1) + version.substring(0, stampDash) + "-SNAPSHOT" + path.substring(fileStart);
    }

    /**
     * @return the SHA-256 of the downloaded file written to {@code target}, or null if the repository doesn't have it
     */
    private static String download(final HttpClient client, final String url, final Path target) throws InterruptedException {
        Exception failure = null;
        for (int attempt = 0; attempt <= 2; attempt++) {
            if (attempt > 0) {
                Thread.sleep(1000L * attempt);
            }
            try {
                final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
                final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (response.statusCode() == 404) {
                        return null;
                    }
                    if (response.statusCode() != 200) {
                        throw new IOException("HTTP " + response.statusCode());
                    }
                    final MessageDigest digest = sha256Digest();
                    try (OutputStream out = Files.newOutputStream(target)) {
                        final byte[] buffer = new byte[65536];
                        int read;
                        while ((read = body.read(buffer)) >= 0) {
                            digest.update(buffer, 0, read);
                            out.write(buffer, 0, read);
                        }
                    }
                    return HexFormat.of().formatHex(digest.digest());
                }
            } catch (final IOException e) {
                failure = e;
            }
        }
        throw new GradleException("Failed to check " + url + ": " + failure, failure);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ZipEntry entry(final String name, final long time) {
        final ZipEntry entry = new ZipEntry(name);
        entry.setTime(time);
        return entry;
    }

    private static void add(final ZipOutputStream out, final String name, final long time, final byte[] content) throws IOException {
        out.putNextEntry(entry(name, time));
        out.write(content);
        out.closeEntry();
    }

    private static String mb(final long bytes) {
        return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
    }
}
