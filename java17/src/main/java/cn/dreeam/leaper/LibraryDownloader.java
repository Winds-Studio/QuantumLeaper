package cn.dreeam.leaper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Downloads libraries that are not bundled in the jar from Maven repositories, trying the repositories in order.
 * The library path in {@code libraries.list} is already the Maven repository layout path.
 */
final class LibraryDownloader {

    /**
     * Repositories of libraries not on Maven Central, tried after the Maven Central mirrors.
     * Can be replaced with {@code -Dleaper.extraRepositories=url1,url2} (used by tests).
     */
    static final String[] EXTRA_REPOSITORIES = {
            "https://repo.papermc.io/repository/maven-public/",
            "https://maven.leafmc.one/snapshots/"
    };

    static String[] extraRepositories() {
        final String property = System.getProperty("leaper.extraRepositories");
        return property == null ? EXTRA_REPOSITORIES : property.split(",");
    }

    private final String[] repositories;
    private final int retries;
    private final Duration retryDelay;

    LibraryDownloader(final String[] repositories, final int retries) {
        this(repositories, retries, Duration.ofSeconds(1));
    }

    LibraryDownloader(final String[] repositories, final int retries, final Duration retryDelay) {
        this.repositories = normalizeRepositories(repositories);
        this.retries = retries;
        this.retryDelay = retryDelay;
    }

    /**
     * Adds a trailing slash and removes duplicates, keeping the order.
     */
    static String[] normalizeRepositories(final String[] repositories) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        for (final String repository : repositories) {
            final String trimmed = repository.strip();
            if (!trimmed.isEmpty()) {
                result.add(Util.endingSlash(trimmed));
            }
        }
        return result.toArray(String[]::new);
    }

    String[] repositories() {
        return this.repositories;
    }

    /**
     * Maps a timestamped snapshot path to its {@code -SNAPSHOT} directory, or null if not a timestamped snapshot.
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
     * Downloads {@code entry} to {@code outputFile}, verifying its SHA-256.
     *
     * @throws IOException with the failure of every repository if none of them provided a valid file
     */
    void download(final FileEntry entry, final Path outputFile) throws IOException {
        final List<String> failures = new ArrayList<>();

        String snapshotPath = snapshotPath(entry.path());
        String[] paths = snapshotPath == null ? new String[]{entry.path()} : new String[]{entry.path(), snapshotPath};

        for (final String path : paths) {
            for (final String repository : this.repositories) {
                final URI uri;
                try {
                    uri = URI.create(repository + path);
                } catch (final IllegalArgumentException e) {
                    failures.add(repository + path + ": invalid URL (" + e.getMessage() + ")");
                    continue;
                }

                for (int attempt = 0; attempt <= this.retries; attempt++) {
                    if (attempt > 0) {
                        sleep(this.retryDelay.multipliedBy(attempt));
                    }

                    System.out.println("Downloading " + uri
                            + (attempt > 0 ? " (retry " + attempt + "/" + this.retries + ")" : ""));
                    try {
                        Http.download(uri, entry.hash(), outputFile, false);
                        return;
                    } catch (final Http.NoRetryException e) {
                        // Not found, rate limited or blocked: don't retry
                        failures.add(uri + ": " + e.getMessage());
                        System.out.println("  " + e.getMessage());
                        break;
                    } catch (final IOException e) {
                        final String reason = Http.describe(e);
                        System.out.println("  Failed: " + reason);
                        if (attempt == this.retries) {
                            failures.add(uri + ": " + reason);
                        }
                    }
                }
            }
        }

        throw new IOException("Failed to download library " + entry.id() + " (" + entry.path() + ") from all repositories:\n  "
                + String.join("\n  ", failures));
    }

    private static void sleep(final Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
