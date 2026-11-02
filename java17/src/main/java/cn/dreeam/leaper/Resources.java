package cn.dreeam.leaper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the paperclip data ({@code META-INF/*.list}, patches, bundled files) is read from:
 * our own jar, or a newer core jar downloaded by the auto updater.
 */
abstract class Resources {

    static final Resources OWN_JAR = new Resources() {
        @Override
        InputStream open(final String path) {
            return Resources.class.getResourceAsStream(absolute(path));
        }

        @Override
        public String toString() {
            return "own jar";
        }
    };

    /**
     * Reads from another jar, which stays open for the lifetime of the process.
     */
    static Resources ofJar(final Path jar) throws IOException {
        final FileSystem fs = FileSystems.newFileSystem(jar);
        return new Resources() {
            @Override
            InputStream open(final String path) throws IOException {
                final Path file = fs.getPath(absolute(path));
                return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
            }

            @Override
            public String toString() {
                return jar.toString();
            }
        };
    }

    /**
     * @param path an absolute path in the jar, e.g. {@code /META-INF/patches.list}
     * @return the content, or {@code null} if it doesn't exist
     */
    abstract InputStream open(String path) throws IOException;

    /**
     * @return the content as UTF-8 text, or {@code null} if it doesn't exist
     */
    String readText(final String path) throws IOException {
        final InputStream stream = open(path);
        if (stream == null) {
            return null;
        }

        final StringWriter writer = new StringWriter();
        try (stream) {
            final Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            reader.transferTo(writer);
        }
        return writer.toString();
    }

    private static String absolute(final String path) {
        return path.startsWith("/") ? path : "/" + path;
    }
}
