package cn.dreeam.leaper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Downloads libraries that are not bundled in the jar from Maven repositories, trying the repositories in order.
 * The library path in {@code libraries.list} is already the Maven repository layout path.
 */
final class LibraryDownloader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // Time until the response headers are received, the body itself is not limited
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final List<String> repositories;
    private final int retries;
    private final Duration retryDelay;
    private final HttpClient client;

    LibraryDownloader(final List<String> repositories, final int retries) {
        this(repositories, retries, Duration.ofSeconds(1));
    }

    LibraryDownloader(final List<String> repositories, final int retries, final Duration retryDelay) {
        this.repositories = normalizeRepositories(repositories);
        this.retries = retries;
        this.retryDelay = retryDelay;
        this.client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * Adds a trailing slash and removes duplicates, keeping the order.
     */
    static List<String> normalizeRepositories(final List<String> repositories) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        for (final String repository : repositories) {
            final String trimmed = repository.strip();
            if (!trimmed.isEmpty()) {
                result.add(Util.endingSlash(trimmed));
            }
        }
        return List.copyOf(result);
    }

    List<String> repositories() {
        return this.repositories;
    }

    /**
     * Downloads {@code entry} to {@code outputFile}, verifying its SHA-256.
     *
     * @throws IOException with the failure of every repository if none of them provided a valid file
     */
    void download(final FileEntry entry, final Path outputFile) throws IOException {
        final List<String> failures = new ArrayList<>();

        for (final String repository : this.repositories) {
            final URI uri;
            try {
                uri = URI.create(repository + entry.path());
            } catch (final IllegalArgumentException e) {
                failures.add(repository + ": invalid URL (" + e.getMessage() + ")");
                continue;
            }

            for (int attempt = 0; attempt <= this.retries; attempt++) {
                if (attempt > 0) {
                    sleep(this.retryDelay.multipliedBy(attempt));
                }

                System.out.println("Downloading library " + entry.id() + " from " + repository
                    + (attempt > 0 ? " (retry " + attempt + "/" + this.retries + ")" : ""));
                try {
                    downloadFrom(uri, entry.hash(), outputFile);
                    return;
                } catch (final NotFoundException e) {
                    // Retrying won't help, try the next repository
                    failures.add(repository + ": " + e.getMessage());
                    System.out.println("  " + e.getMessage());
                    break;
                } catch (final IOException e) {
                    final String reason = describe(e);
                    System.out.println("  Failed: " + reason);
                    if (attempt == this.retries) {
                        failures.add(repository + ": " + reason);
                    }
                }
            }
        }

        throw new IOException("Failed to download library " + entry.id() + " (" + entry.path() + ") from all repositories:\n  "
            + String.join("\n  ", failures));
    }

    private void downloadFrom(final URI uri, final byte[] expectedHash, final Path outputFile) throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", "QuantumLeaper")
            .GET()
            .build();

        final Path tempFile = Util.tempFileFor(outputFile);
        try {
            final HttpResponse<InputStream> response;
            try {
                response = this.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }

            final MessageDigest digest = Util.newSha256Digest();
            try (
                final InputStream body = new DigestInputStream(response.body(), digest);
                final OutputStream out = Files.newOutputStream(tempFile)
            ) {
                if (response.statusCode() == 404) {
                    throw new NotFoundException("Not found (HTTP 404)");
                }
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                body.transferTo(out);
            }

            if (!Arrays.equals(expectedHash, digest.digest())) {
                throw new IOException("Hash check failed");
            }

            Util.moveAtomically(tempFile, outputFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static String describe(final Throwable e) {
        // e.g. a ConnectException for an unknown host has no message, only nested causes
        Throwable current = e;
        while (current.getMessage() == null && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null
            ? current.getMessage()
            : current.getClass().getSimpleName();
    }

    private static void sleep(final Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static final class NotFoundException extends IOException {
        NotFoundException(final String message) {
            super(message);
        }
    }
}
