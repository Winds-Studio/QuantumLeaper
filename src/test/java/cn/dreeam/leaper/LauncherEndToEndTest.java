package cn.dreeam.leaper;

import com.sun.net.httpserver.HttpServer;
import io.sigpipe.jbsdiff.Diff;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds a fake vanilla server (bundler layout), a bsdiff patch to a fake Leaf server and a bundled library,
 * injects them into the built launcher jar like paperweight does, then runs it in a separate JVM.
 */
class LauncherEndToEndTest {

    static final String SERVER_PATH = "1.0/server-1.0.jar";
    static final String PATCH_PATH = SERVER_PATH + ".patch";
    static final String LIBRARY_PATH = "lib/hello/1.0/hello-1.0.jar";

    @TempDir
    static Path fixtures;

    static byte[] vanillaJar;
    static byte[] patchedJar;
    static byte[] libraryJar;
    static byte[] patch;
    // A newer build, for auto updates
    static byte[] patchedJarV2;
    static byte[] patchV2;
    static Path mojangJar;

    @TempDir
    Path workDir;

    @BeforeAll
    static void buildFixtures() throws Exception {
        final Path src = fixtures.resolve("src");
        write(src.resolve("lib/lib/Hello.java"),
            "package lib; public class Hello { public static String msg() { return \"hello from lib\"; } }");
        write(src.resolve("vanilla/test/Main.java"),
            "package test; public class Main { public static void main(String[] a) { System.out.println(\"vanilla\"); } }");
        write(src.resolve("patched/test/Main.java"),
            "package test; public class Main { public static void main(String[] a) {"
                + " System.out.println(\"patched: \" + lib.Hello.msg() + \" args=\" + String.join(\",\", a)"
                + " + \" thread=\" + Thread.currentThread().getName()"
                + " + \" loader=\" + Main.class.getClassLoader().getClass().getSimpleName()); } }");

        final Path libClasses = compile(src.resolve("lib/lib/Hello.java"), null);
        libraryJar = jar(libClasses);
        vanillaJar = jar(compile(src.resolve("vanilla/test/Main.java"), null));
        patchedJar = jar(compile(src.resolve("patched/test/Main.java"), libClasses));

        final ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        Diff.diff(vanillaJar, patchedJar, patchOut);
        patch = patchOut.toByteArray();

        write(src.resolve("patched2/test/Main.java"),
            "package test; public class Main { public static void main(String[] a) {"
                + " System.out.println(\"patched v2: \" + lib.Hello.msg()); } }");
        patchedJarV2 = jar(compile(src.resolve("patched2/test/Main.java"), libClasses));
        final ByteArrayOutputStream patchV2Out = new ByteArrayOutputStream();
        Diff.diff(vanillaJar, patchedJarV2, patchV2Out);
        patchV2 = patchV2Out.toByteArray();

        // Since 1.18 the vanilla server jar is itself a bundler, with the server in META-INF/versions/
        mojangJar = fixtures.resolve("mojang.jar");
        Files.write(mojangJar, zip(Map.of("META-INF/versions/" + SERVER_PATH, vanillaJar)));
    }

    // ---- tests ----

    @Test
    void launchesPatchedServer() throws Exception {
        final Result result = run(loaderJar(Map.of()), "a", "b");

        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("Downloading mojang_1.0.jar"), result.output);
        assertTrue(result.output.contains("Applying patches"), result.output);
        assertTrue(result.output.contains("patched: hello from lib args=a,b thread=ServerMain loader=URLClassLoader"), result.output);

        assertArrayEquals(patchedJar, Files.readAllBytes(workDir.resolve("versions/" + SERVER_PATH)));
        assertArrayEquals(libraryJar, Files.readAllBytes(workDir.resolve("libraries/" + LIBRARY_PATH)));
        assertTrue(Files.isRegularFile(workDir.resolve("config/leaf-loader.yaml")));
        assertNoTempFiles();
    }

    @Test
    void origamiPatcherRewritesLaunch() throws Exception {
        final Result result = run(System.getProperty("leaper.test.java25"),
            List.of("-javaagent:" + origamiAgentJar()), loaderJar(Map.of()), "a", "b");

        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("origami:patched"), result.output);
        // new URLClassLoader(...) replaced by OrigamiAgent.createClassLoader(...)
        assertTrue(result.output.contains("origami:createClassLoader urls=2"), result.output);
        // initOrigami(aload 1, aload 3, aload 0) inserted after the class loader is stored
        assertTrue(result.output.contains("origami:initOrigami urls=2 loader=PatchingClassLoader args=a,b"), result.output);
        // the ServerMain lambda redirected to OrigamiAgent.startMain(...)
        assertTrue(result.output.contains("origami:startMain test.Main"), result.output);
        assertTrue(result.output.contains("patched: hello from lib args=a,b thread=ServerMain loader=PatchingClassLoader"), result.output);
    }

    @Test
    void downloadsLibrariesNotBundled() throws Exception {
        final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final HttpServer server = mavenRepository(requests, Map.of("/maven2/" + LIBRARY_PATH, libraryJar));
        try {
            final String repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2";
            write(workDir.resolve("config/leaf-loader.yaml"), "maven-repositories:\n- " + repository + "\n");
            final Path loader = loaderJar(Map.of(), List.of("META-INF/libraries/" + LIBRARY_PATH));

            final Result first = run(loader, "a");
            assertEquals(0, first.exitCode, first.output);
            assertTrue(first.output.contains("Downloading library lib:hello:1.0 from " + repository + "/"), first.output);
            assertTrue(first.output.contains("patched: hello from lib args=a"), first.output);
            assertArrayEquals(libraryJar, Files.readAllBytes(workDir.resolve("libraries/" + LIBRARY_PATH)));
            assertEquals(List.of("/maven2/" + LIBRARY_PATH), requests);

            final Result second = run(loader);
            assertEquals(0, second.exitCode, second.output);
            assertFalse(second.output.contains("Downloading library"), second.output);
            assertEquals(1, requests.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsWhenLibraryCannotBeDownloaded() throws Exception {
        final HttpServer server = mavenRepository(new java.util.concurrent.CopyOnWriteArrayList<>(), Map.of());
        try {
            final String repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2";
            write(workDir.resolve("config/leaf-loader.yaml"), "maven-repositories:\n- " + repository + "\ndownload-retries: 0\n");

            final Result result = run(loaderJar(Map.of(), List.of("META-INF/libraries/" + LIBRARY_PATH)));
            assertNotEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("Failed to download library lib:hello:1.0"), result.output);
            assertTrue(result.output.contains(repository + "/: Not found (HTTP 404)"), result.output);
            assertFalse(result.output.contains("patched:"), result.output);
        } finally {
            server.stop(0);
        }
    }

    /**
     * A minimal Maven repository serving {@code files} (request path to content), 404 otherwise.
     */
    static HttpServer mavenRepository(final List<String> requests, final Map<String, byte[]> files) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            requests.add(path);
            final byte[] body = files.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    // ---- auto update ----

    /**
     * A fake build download API: build 1 is {@code own}, build 2 is {@code update} (both stable),
     * build 3 is {@code experimental} if given.
     */
    static HttpServer updateApi(final List<String> requests, final Path own, final Path update, final String updateSha256,
                                final Path experimental) throws IOException {
        final StringBuilder builds = new StringBuilder("{\"project_id\":\"leaf\",\"version\":\"1.0\",\"builds\":[");
        builds.append(buildJson(1, "default", "leaf-1.0-1.jar", sha256(Files.readAllBytes(own))));
        builds.append(',').append(buildJson(2, "default", "leaf-1.0-2.jar", updateSha256));
        if (experimental != null) {
            builds.append(',').append(buildJson(3, "experimental", "leaf-1.0-3.jar", sha256(Files.readAllBytes(experimental))));
        }
        builds.append("]}");

        final Map<String, byte[]> files = new java.util.HashMap<>();
        files.put("/api/versions/1.0/builds", builds.toString().getBytes(StandardCharsets.UTF_8));
        files.put("/api/versions/1.0/builds/1/downloads/leaf-1.0-1.jar", Files.readAllBytes(own));
        files.put("/api/versions/1.0/builds/2/downloads/leaf-1.0-2.jar", Files.readAllBytes(update));
        if (experimental != null) {
            files.put("/api/versions/1.0/builds/3/downloads/leaf-1.0-3.jar", Files.readAllBytes(experimental));
        }
        return mavenRepository(requests, files);
    }

    static String buildJson(final int build, final String channel, final String name, final String sha256) {
        // An escaped slash, which YAML doesn't know, to make sure it is handled
        return "{\"build\":" + build + ",\"channel\":\"" + channel + "\",\"changes\":[{\"summary\":\"Fix a\\/b\"}],"
            + "\"downloads\":{\"primary\":{\"name\":\"" + name + "\",\"sha256\":\"" + sha256 + "\"}}}";
    }

    List<String> updateArgs(final HttpServer api) {
        return List.of("-Dleaper.update.api=http://127.0.0.1:" + api.getAddress().getPort() + "/api");
    }

    void writeUpdateConfig(final String channel) throws IOException {
        write(workDir.resolve("config/leaf-loader.yaml"), "update-channel: " + channel + "\n");
    }

    @Test
    void autoUpdateUsesNewerBuild() throws Exception {
        final Path own = loaderJar(Map.of());
        final Path update = loaderJarV2(Map.of());
        final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final HttpServer api = updateApi(requests, own, update, sha256(Files.readAllBytes(update)), null);
        try {
            writeUpdateConfig("stable");

            final Result first = run(updateArgs(api), own);
            assertEquals(0, first.exitCode, first.output);
            assertTrue(first.output.contains("Checking for updates (stable, 1.0)"), first.output);
            assertTrue(first.output.contains("Downloading build 2 (leaf-1.0-2.jar)"), first.output);
            assertTrue(first.output.contains("Using build 2 from"), first.output);
            assertTrue(first.output.contains("patched v2: hello from lib"), first.output);
            assertArrayEquals(Files.readAllBytes(update), Files.readAllBytes(workDir.resolve("updates/leaf-1.0-2.jar")));
            assertArrayEquals(patchedJarV2, Files.readAllBytes(workDir.resolve("versions/" + SERVER_PATH)));

            final Result second = run(updateArgs(api), own);
            assertEquals(0, second.exitCode, second.output);
            assertFalse(second.output.contains("Downloading build"), second.output);
            assertTrue(second.output.contains("Up to date (build 2"), second.output);
            assertTrue(second.output.contains("patched v2: hello from lib"), second.output);
            assertEquals(1, requests.stream().filter(r -> r.endsWith(".jar")).count());

            // The user replaces the server jar with the newer build: the downloaded update is no longer used
            final Result replaced = run(updateArgs(api), workDir.resolve("updates/leaf-1.0-2.jar"));
            assertEquals(0, replaced.exitCode, replaced.output);
            assertTrue(replaced.output.contains("Up to date (build 2)"), replaced.output);
            assertFalse(replaced.output.contains("Using build"), replaced.output);
            assertTrue(replaced.output.contains("patched v2: hello from lib"), replaced.output);
        } finally {
            api.stop(0);
        }
    }

    @Test
    void autoUpdateChannels() throws Exception {
        final Path own = loaderJar(Map.of());
        final Path update = loaderJarV2(Map.of());
        // Build 3 (experimental) has the original server, to tell it apart from build 2
        final Path experimental = loaderJar(Map.of("META-INF/leaper-format", "2".getBytes(StandardCharsets.UTF_8)));
        final HttpServer api = updateApi(new java.util.concurrent.CopyOnWriteArrayList<>(), own, update,
            sha256(Files.readAllBytes(update)), experimental);
        try {
            writeUpdateConfig("experimental");
            final Result result = run(updateArgs(api), own);
            assertEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("Downloading build 3 (leaf-1.0-3.jar)"), result.output);
            assertTrue(result.output.contains("patched: hello from lib"), result.output);
        } finally {
            api.stop(0);
        }
    }

    @Test
    void autoUpdateUpToDate() throws Exception {
        final Path own = loaderJarV2(Map.of());
        // Build 2 (the newest) is the same jar as the server jar
        final HttpServer api = updateApi(new java.util.concurrent.CopyOnWriteArrayList<>(), own, own, sha256(Files.readAllBytes(own)), null);
        try {
            writeUpdateConfig("stable");
            final Result result = run(updateArgs(api), own);
            assertEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("Up to date (build 2)"), result.output);
            assertFalse(result.output.contains("Downloading build"), result.output);
        } finally {
            api.stop(0);
        }
    }

    @Test
    void autoUpdateDisabledByDefault() throws Exception {
        final Path own = loaderJar(Map.of());
        final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final HttpServer api = updateApi(requests, own, loaderJarV2(Map.of()), "00".repeat(32), null);
        try {
            final Result result = run(updateArgs(api), own);
            assertEquals(0, result.exitCode, result.output);
            assertFalse(result.output.contains("Checking for updates"), result.output);
            assertTrue(result.output.contains("patched: hello from lib"), result.output);
            assertTrue(requests.isEmpty(), requests.toString());
        } finally {
            api.stop(0);
        }
    }

    @Test
    void autoUpdateFailuresKeepStarting() throws Exception {
        final Path own = loaderJar(Map.of());
        writeUpdateConfig("stable");

        // Unreachable API
        final HttpServer stopped = updateApi(new java.util.concurrent.CopyOnWriteArrayList<>(), own, own, "00".repeat(32), null);
        stopped.stop(0);
        final Result unreachable = run(updateArgs(stopped), own);
        assertEquals(0, unreachable.exitCode, unreachable.output);
        assertTrue(unreachable.output.contains("Failed to check for updates"), unreachable.output);
        assertTrue(unreachable.output.contains("Continuing with this server jar"), unreachable.output);
        assertTrue(unreachable.output.contains("patched: hello from lib"), unreachable.output);

        // Downloaded jar doesn't match the hash from the API
        final HttpServer badHash = updateApi(new java.util.concurrent.CopyOnWriteArrayList<>(), own, loaderJarV2(Map.of()), "00".repeat(32), null);
        try {
            final Result result = run(updateArgs(badHash), own);
            assertEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("Hash check failed"), result.output);
            assertTrue(result.output.contains("patched: hello from lib"), result.output);
            assertFalse(Files.exists(workDir.resolve("updates/leaf-1.0-2.jar")));
            assertNoTempFiles();
        } finally {
            badHash.stop(0);
        }
    }

    @Test
    void autoUpdateRequiringNewerLoaderIsNotUsed() throws Exception {
        final Path own = loaderJar(Map.of());
        final Path update = loaderJarV2(Map.of("META-INF/leaper-format", "99".getBytes(StandardCharsets.UTF_8)));
        final HttpServer api = updateApi(new java.util.concurrent.CopyOnWriteArrayList<>(), own, update, sha256(Files.readAllBytes(update)), null);
        try {
            writeUpdateConfig("stable");
            final Result result = run(updateArgs(api), own);
            assertEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("Build 2 requires a newer loader, replace the server jar with"), result.output);
            assertTrue(result.output.contains("patched: hello from lib"), result.output);
        } finally {
            api.stop(0);
        }
    }

    @Test
    void autoUpdateRequiringNewerJavaIsNotUsed() throws Exception {
        final Path own = loaderJar(Map.of());
        final Path update = loaderJarV2(Map.of("version.json", versionJson(99)));
        final HttpServer api = updateApi(new java.util.concurrent.CopyOnWriteArrayList<>(), own, update, sha256(Files.readAllBytes(update)), null);
        try {
            writeUpdateConfig("stable");
            final Result result = run(updateArgs(api), own);
            assertEquals(0, result.exitCode, result.output);
            assertTrue(result.output.contains("Build 2 requires Java 99 or above, not using it."), result.output);
            assertTrue(result.output.contains("patched: hello from lib"), result.output);
        } finally {
            api.stop(0);
        }
    }

    @Test
    void autoUpdateRejectsInvalidChannel() throws Exception {
        writeUpdateConfig("beta");
        final Result result = run(loaderJar(Map.of()));
        assertNotEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("update-channel must be empty, stable or experimental, got: beta"), result.output);
    }

    @Test
    void requiresJavaVersionOfVersionJson() throws Exception {
        final Result result = run(loaderJar(Map.of("version.json", versionJson(99))));
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("Minecraft 1.0 requires running the server with Java 99 or above."), result.output);
        assertFalse(result.output.contains("Downloading"), result.output);
    }

    static byte[] versionJson(final int javaVersion) {
        return ("{\n  \"id\": \"1.0\",\n  \"series_id\": \"main\",\n  \"java_version\": " + javaVersion + "\n}")
            .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void reusesValidCache() throws Exception {
        final Path loader = loaderJar(Map.of());
        assertEquals(0, run(loader).exitCode);

        final Result second = run(loader);
        assertEquals(0, second.exitCode, second.output);
        assertFalse(second.output.contains("Downloading"), second.output);
        assertFalse(second.output.contains("Applying patches"), second.output);
        assertTrue(second.output.contains("patched: hello from lib"), second.output);
    }

    @Test
    void repairsModifiedCache() throws Exception {
        final Path loader = loaderJar(Map.of());
        assertEquals(0, run(loader).exitCode);

        Files.writeString(workDir.resolve("versions/" + SERVER_PATH), "tampered");
        Files.writeString(workDir.resolve("libraries/" + LIBRARY_PATH), "tampered");

        final Result result = run(loader);
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("Applying patches"), result.output);
        assertTrue(result.output.contains("patched: hello from lib"), result.output);
        assertArrayEquals(patchedJar, Files.readAllBytes(workDir.resolve("versions/" + SERVER_PATH)));
        assertArrayEquals(libraryJar, Files.readAllBytes(workDir.resolve("libraries/" + LIBRARY_PATH)));
    }

    @Test
    void patchOnlyDoesNotStartServer() throws Exception {
        final Result result = run(List.of("-Dpaperclip.patchonly=true"), loaderJar(Map.of()));

        assertEquals(0, result.exitCode, result.output);
        assertFalse(result.output.contains("Starting"), result.output);
        assertArrayEquals(patchedJar, Files.readAllBytes(workDir.resolve("versions/" + SERVER_PATH)));
        assertTrue(Files.isRegularFile(workDir.resolve("config/leaf-loader.yaml")));
    }

    @Test
    void failsWhenOriginalJarHashMismatches() throws Exception {
        final Path loader = loaderJar(Map.of("META-INF/download-context",
            downloadContext("00".repeat(32)).getBytes(StandardCharsets.UTF_8)));

        final Result result = run(loader);
        assertNotEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("Hash check failed for downloaded file"), result.output);
        assertEmptyDir(workDir.resolve("cache"));
    }

    @Test
    void failsOnCorruptPatch() throws Exception {
        final byte[] corrupt = patch.clone();
        corrupt[corrupt.length / 2] ^= 0x55;
        final Path loader = loaderJar(Map.of("META-INF/versions/" + PATCH_PATH, corrupt));

        final Result result = run(loader);
        assertNotEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("Hash check of patch file failed"), result.output);
        assertFalse(result.output.contains("patched:"), result.output);
        assertFalse(Files.exists(workDir.resolve("versions/" + SERVER_PATH)));
        assertNoTempFiles();
    }

    @Test
    void failsOnInvalidConfig() throws Exception {
        write(workDir.resolve("config/leaf-loader.yaml"), "download-retries: -1\n");

        final Result result = run(loaderJar(Map.of()));
        assertNotEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("download-retries must not be negative"), result.output);
        assertFalse(result.output.contains("patched:"), result.output);
    }

    // ---- launcher jar ----

    /**
     * Copies the built launcher jar and adds the paperclip data, like paperweight does.
     * {@code overrides} replaces or adds entries on top of the valid data.
     */
    Path loaderJar(final Map<String, byte[]> overrides) throws IOException {
        return loaderJar(overrides, List.of());
    }

    /**
     * Like {@link #loaderJar(Map)}, without the {@code removed} entries (e.g. libraries to download instead).
     */
    Path loaderJar(final Map<String, byte[]> overrides, final List<String> removed) throws IOException {
        return loaderJar(patchedJar, patch, overrides, removed);
    }

    /**
     * A core jar of the newer build.
     */
    Path loaderJarV2(final Map<String, byte[]> overrides) throws IOException {
        return loaderJar(patchedJarV2, patchV2, overrides, List.of());
    }

    Path loaderJar(final byte[] patchedJar, final byte[] patch, final Map<String, byte[]> overrides, final List<String> removed) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile launcher = new JarFile(System.getProperty("leaper.test.launcherJar"))) {
            for (final JarEntry entry : launcher.stream().filter(e -> !e.isDirectory()).toList()) {
                entries.put(entry.getName(), launcher.getInputStream(entry).readAllBytes());
            }
        }

        entries.put("META-INF/download-context", downloadContext(sha256(Files.readAllBytes(mojangJar))).getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/patches.list", String.join("\t", "versions", sha256(vanillaJar), sha256(patch),
            sha256(patchedJar), SERVER_PATH, PATCH_PATH, SERVER_PATH).getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/versions.list", (sha256(patchedJar) + "\t1.0\t" + SERVER_PATH).getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/libraries.list", (sha256(libraryJar) + "\tlib:hello:1.0\t" + LIBRARY_PATH).getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/main-class", "test.Main".getBytes(StandardCharsets.UTF_8));
        entries.put("version.json", versionJson(21));
        entries.put("META-INF/versions/" + PATCH_PATH, patch);
        entries.put("META-INF/libraries/" + LIBRARY_PATH, libraryJar);
        entries.putAll(overrides);
        removed.forEach(entries::remove);

        final Path jar = Files.createTempFile(fixtures, "launcher", ".jar");
        Files.write(jar, zip(entries));
        return jar;
    }

    /**
     * A java agent jar with {@link OrigamiTestAgent}, the {@code OrigamiAgent} stand-in and origami-jit with its
     * dependencies merged in.
     */
    static Path origamiAgentJar() throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\n"
            + "Premain-Class: " + OrigamiTestAgent.class.getName() + "\n"
            + "Can-Redefine-Classes: true\n").getBytes(StandardCharsets.UTF_8));
        for (final Class<?> clazz : List.of(OrigamiTestAgent.class, xyz.xenondevs.origami.OrigamiAgent.class,
            xyz.xenondevs.origami.OrigamiAgent.PatchingClassLoader.class)) {
            final String name = clazz.getName().replace('.', '/') + ".class";
            try (var in = LauncherEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
                entries.put(name, in.readAllBytes());
            }
        }
        for (final String dependency : System.getProperty("leaper.test.origamiClasspath").split(java.io.File.pathSeparator)) {
            try (JarFile jar = new JarFile(dependency)) {
                for (final JarEntry entry : jar.stream().filter(e -> !e.isDirectory()).toList()) {
                    final String name = entry.getName();
                    if (name.startsWith("META-INF/") && !name.startsWith("META-INF/services/")) {
                        continue;
                    }
                    entries.putIfAbsent(name, jar.getInputStream(entry).readAllBytes());
                }
            }
        }

        final Path jar = Files.createTempFile(fixtures, "origami-agent", ".jar");
        Files.write(jar, zip(entries));
        return jar;
    }

    static String downloadContext(final String hash) {
        return hash + "\t" + mojangJar.toUri() + "\tmojang_1.0.jar";
    }

    // ---- process ----

    record Result(int exitCode, String output) {}

    Result run(final Path loader, final String... args) throws Exception {
        return run(List.of(), loader, args);
    }

    Result run(final List<String> jvmArgs, final Path loader, final String... args) throws Exception {
        final String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        return run(java, jvmArgs, loader, args);
    }

    Result run(final String java, final List<String> jvmArgs, final Path loader, final String... args) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(loader.toString());
        command.addAll(List.of(args));

        final Process process = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start();
        process.getOutputStream().close();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Launcher did not exit:\n" + output);
        }
        return new Result(process.exitValue(), output);
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }

    // ---- helpers ----

    void assertNoTempFiles() throws IOException {
        try (Stream<Path> files = Files.walk(workDir)) {
            final List<Path> temps = files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            assertTrue(temps.isEmpty(), "Leftover temp files: " + temps);
        }
    }

    static void assertEmptyDir(final Path dir) throws IOException {
        if (Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            final List<Path> left = files.toList();
            assertTrue(left.isEmpty(), "Leftover files: " + left);
        }
    }

    static Path compile(final Path source, final Path classpath) throws IOException {
        final Path out = Files.createTempDirectory(fixtures, "classes");
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> args = new ArrayList<>(List.of("--release", "17", "-d", out.toString()));
        if (classpath != null) {
            args.addAll(List.of("-cp", classpath.toString()));
        }
        args.add(source.toString());
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "Failed to compile " + source);
        return out;
    }

    static byte[] jar(final Path classes) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                entries.put(classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return zip(entries);
    }

    static byte[] zip(final Map<String, byte[]> entries) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
                final JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0);
                out.putNextEntry(jarEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static String sha256(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}
