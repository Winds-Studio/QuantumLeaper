package cn.dreeam.leaper;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryDownloaderTest {

    static final String PATH = "com/example/lib/1.0/lib-1.0.jar";
    static final byte[] CONTENT = "library content".getBytes(StandardCharsets.UTF_8);
    static final FileEntry ENTRY = new FileEntry(Util.newSha256Digest().digest(CONTENT), "com.example:lib:1.0", PATH);

    @TempDir
    Path dir;

    HttpServer server;
    /** Response per repository name: a status code, or {@code 200} with the given body */
    final Map<String, List<Object>> responses = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            this.requests.add(path);
            final String repo = path.split("/")[1];
            final List<Object> queue = this.responses.getOrDefault(repo, List.of(404));
            // Each repository answers with its responses in order, repeating the last one
            final Object response = queue.size() > 1 ? queue.remove(0) : queue.get(0);
            if (response instanceof Integer status) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                final byte[] body = (byte[]) response;
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        this.server.start();
    }

    @AfterEach
    void stopServer() {
        this.server.stop(0);
    }

    String repo(final String name) {
        return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/" + name;
    }

    void respond(final String repo, final Object... responses) {
        this.responses.put(repo, new CopyOnWriteArrayList<>(responses));
    }

    LibraryDownloader downloader(final int retries, final String... repos) {
        return new LibraryDownloader(Stream.of(repos).map(this::repo).toList(), retries, Duration.ZERO);
    }

    Path output() {
        return this.dir.resolve("libraries").resolve(PATH);
    }

    @Test
    void downloadsFromFirstRepository() throws Exception {
        respond("a", CONTENT);

        downloader(0, "a", "b").download(ENTRY, output());

        assertArrayEquals(CONTENT, Files.readAllBytes(output()));
        assertEquals(List.of("/a/" + PATH), this.requests);
        assertNoTempFiles();
    }

    @Test
    void fallsBackToNextRepositoryWithoutRetryingNotFound() throws Exception {
        respond("a", 404);
        respond("b", CONTENT);

        downloader(2, "a", "b").download(ENTRY, output());

        assertArrayEquals(CONTENT, Files.readAllBytes(output()));
        assertEquals(List.of("/a/" + PATH, "/b/" + PATH), this.requests);
    }

    @Test
    void retriesServerErrors() throws Exception {
        respond("a", 503, 500, CONTENT);

        downloader(2, "a").download(ENTRY, output());

        assertArrayEquals(CONTENT, Files.readAllBytes(output()));
        assertEquals(3, this.requests.size());
    }

    @Test
    void hashMismatchFallsBackAndNeverKeepsBadFile() throws Exception {
        respond("a", "tampered".getBytes(StandardCharsets.UTF_8));
        respond("b", CONTENT);

        downloader(0, "a", "b").download(ENTRY, output());

        assertArrayEquals(CONTENT, Files.readAllBytes(output()));
        assertNoTempFiles();
    }

    @Test
    void failsWithEveryRepositoryReason() {
        respond("a", 404);
        respond("b", 500);
        respond("c", "tampered".getBytes(StandardCharsets.UTF_8));

        final IOException e = assertThrows(IOException.class, () -> downloader(1, "a", "b", "c").download(ENTRY, output()));

        final String message = e.getMessage();
        assertTrue(message.contains("com.example:lib:1.0"), message);
        assertTrue(message.contains(repo("a") + "/: Not found (HTTP 404)"), message);
        assertTrue(message.contains(repo("b") + "/: HTTP 500"), message);
        assertTrue(message.contains(repo("c") + "/: Hash check failed"), message);
        // 404 is not retried, the others are retried once
        assertEquals(1 + 2 + 2, this.requests.size());
        assertFalse(Files.exists(output()));
    }

    @Test
    void rateLimitsAndBlocksAreNotRetried() throws Exception {
        respond("a", 429);
        respond("b", 403);
        respond("c", CONTENT);

        downloader(2, "a", "b", "c").download(ENTRY, output());

        assertArrayEquals(CONTENT, Files.readAllBytes(output()));
        assertEquals(List.of("/a/" + PATH, "/b/" + PATH, "/c/" + PATH), this.requests);
    }

    @Test
    void sendsUserAgent() throws Exception {
        final List<String> agents = new CopyOnWriteArrayList<>();
        this.server.createContext("/ua/", exchange -> {
            agents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.sendResponseHeaders(200, CONTENT.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(CONTENT);
            }
        });

        downloader(0, "ua").download(ENTRY, output());

        assertEquals(1, agents.size());
        assertTrue(agents.get(0).startsWith("QuantumLeaper/"), agents.get(0));
        assertTrue(agents.get(0).contains("+https://github.com/Winds-Studio/QuantumLeaper"), agents.get(0));
    }

    @Test
    void connectionFailureFallsBack() throws Exception {
        respond("b", CONTENT);
        final AtomicInteger unusedPort = new AtomicInteger();
        try (var socket = new java.net.ServerSocket(0)) {
            unusedPort.set(socket.getLocalPort());
        }

        final LibraryDownloader downloader = new LibraryDownloader(
            List.of("http://127.0.0.1:" + unusedPort.get() + "/a", repo("b")), 0, Duration.ZERO);
        downloader.download(ENTRY, output());

        assertArrayEquals(CONTENT, Files.readAllBytes(output()));
    }

    @Test
    void replacesExistingInvalidFile() throws Exception {
        Files.createDirectories(output().getParent());
        Files.writeString(output(), "old");
        respond("a", CONTENT);

        downloader(0, "a").download(ENTRY, output());

        assertArrayEquals(CONTENT, Files.readAllBytes(output()));
    }

    @Test
    void normalizesRepositories() {
        assertEquals(
            List.of("https://a.example/maven2/", "https://b.example/"),
            LibraryDownloader.normalizeRepositories(List.of(
                " https://a.example/maven2", "https://a.example/maven2/", "", "https://b.example/"))
        );
    }

    void assertNoTempFiles() throws IOException {
        if (Files.notExists(this.dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(this.dir)) {
            final List<Path> temps = files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            assertTrue(temps.isEmpty(), "Leftover temp files: " + temps);
        }
    }
}
