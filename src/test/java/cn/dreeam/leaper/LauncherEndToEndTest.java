package cn.dreeam.leaper;

import io.sigpipe.jbsdiff.Diff;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        entries.put("META-INF/versions/" + PATCH_PATH, patch);
        entries.put("META-INF/libraries/" + LIBRARY_PATH, libraryJar);
        entries.putAll(overrides);

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
