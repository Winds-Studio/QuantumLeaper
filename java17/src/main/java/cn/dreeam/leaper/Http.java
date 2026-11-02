package cn.dreeam.leaper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;

/**
 * HTTP downloads shared by the library downloader and the auto updater.
 */
final class Http {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // Time until the response headers are received, the body itself is not limited
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private Http() {}

    // Identifies the loader to repository operators, e.g. Maven Central asks heavy consumers to be reachable
    static final String USER_AGENT = "QuantumLeaper/" + version() + " (+https://github.com/Winds-Studio/QuantumLeaper)";

    /**
     * A failure retrying won't help with, or would make worse (rate limits).
     */
    static class NoRetryException extends IOException {
        NoRetryException(final String message) {
            super(message);
        }
    }

    static final class NotFoundException extends NoRetryException {
        NotFoundException(final String message) {
            super(message);
        }
    }

    static String getString(final URI uri) throws IOException {
        final HttpResponse<String> response = send(uri, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        checkStatus(response.statusCode());
        return response.body();
    }

    /**
     * Downloads to a temp file next to {@code outputFile}, verifies the SHA-256 and moves it into place atomically.
     *
     * @param progress print the progress of large downloads
     */
    static void download(final URI uri, final byte[] expectedHash, final Path outputFile, final boolean progress) throws IOException {
        final Path tempFile = Util.tempFileFor(outputFile);
        try {
            final HttpResponse<InputStream> response = send(uri, HttpResponse.BodyHandlers.ofInputStream());
            final MessageDigest digest = Util.newSha256Digest();
            try (
                final InputStream body = new DigestInputStream(response.body(), digest);
                final OutputStream out = Files.newOutputStream(tempFile)
            ) {
                checkStatus(response.statusCode());
                final long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                copy(body, out, progress && total >= PROGRESS_MIN_SIZE ? total : -1);
            }

            if (!Arrays.equals(expectedHash, digest.digest())) {
                throw new IOException("Hash check failed");
            }

            Util.moveAtomically(tempFile, outputFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static final long PROGRESS_MIN_SIZE = 4 * 1024 * 1024;

    /**
     * Copies, printing a line at every 10% when {@code total} is known and large enough to be worth it.
     */
    private static void copy(final InputStream in, final OutputStream out, final long total) throws IOException {
        final byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        int printed = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            copied += read;
            if (total > 0) {
                final int percent = (int) (copied * 100 / total);
                if (percent >= printed + 10) {
                    printed = percent - percent % 10;
                    System.out.printf("  %d%% (%.1f / %.1f MB)%n", printed, copied / 1048576.0, total / 1048576.0);
                }
            }
        }
    }

    private static String version() {
        final String version = Http.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    private static <T> HttpResponse<T> send(final URI uri, final HttpResponse.BodyHandler<T> handler) throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        try {
            return CLIENT.send(request, handler);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static void checkStatus(final int status) throws IOException {
        if (status == 404) {
            throw new NotFoundException("Not found (HTTP 404)");
        }
        // Retrying a rate limit or a block only makes it worse
        if (status == 429) {
            throw new NoRetryException("Rate limited (HTTP 429), configure another mirror if this persists");
        }
        if (status == 403) {
            throw new NoRetryException("Forbidden (HTTP 403)");
        }
        if (status != 200) {
            throw new IOException("HTTP " + status);
        }
    }

    /**
     * A readable reason, e.g. a ConnectException for an unknown host has no message, only nested causes.
     */
    static String describe(final Throwable e) {
        Throwable current = e;
        while (current.getMessage() == null && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null
            ? current.getMessage()
            : current.getClass().getSimpleName();
    }
}
