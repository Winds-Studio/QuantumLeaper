package cn.dreeam.leaper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

class Util {

    private Util() {}

    // MessageDigest is not thread-safe, create a new instance for each use
    static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw fail("Could not create hashing instance", e);
        }
    }

    static byte[] readBytes(final Path file) {
        try {
            return readFully(Files.newInputStream(file));
        } catch (final IOException e) {
            throw fail("Failed to read all of the data from " + file.toAbsolutePath(), e);
        }
    }

    static byte[] readFully(final InputStream in) throws IOException {
        try (in) {
            // In a test this was 12 ms quicker than a ByteBuffer
            // and for some reason that matters here.
            byte[] buffer = new byte[16 * 1024];
            int off = 0;
            int read;
            while ((read = in.read(buffer, off, buffer.length - off)) != -1) {
                off += read;
                if (off == buffer.length) {
                    buffer = Arrays.copyOf(buffer, buffer.length * 2);
                }
            }
            return Arrays.copyOfRange(buffer, 0, off);
        }
    }

    static boolean isDataValid(final byte[] data, final byte[] hash) {
        return Arrays.equals(hash, newSha256Digest().digest(data));
    }

    static boolean isFileValid(final Path file, final byte[] hash) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            return Arrays.equals(hash, sha256(file));
        } catch (final IOException e) {
            throw fail("Failed to read all of the data from " + file.toAbsolutePath(), e);
        }
    }

    static byte[] sha256(final Path file) throws IOException {
        final MessageDigest digest = newSha256Digest();
        try (final InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    /**
     * Creates a temporary file next to {@code target}, so it can later be moved onto {@code target} atomically.
     */
    static Path tempFileFor(final Path target) throws IOException {
        final Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        return Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
    }

    static void moveAtomically(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static byte[] fromHex(final String s) {
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("Length of hex " + s + " must be divisible by two");
        }
        try {
            final byte[] bytes = new byte[s.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                final char left = s.charAt(i * 2);
                final char right = s.charAt(i * 2 + 1);
                final byte b = (byte) ((getHexValue(left) << 4) | (getHexValue(right) & 0xF));
                bytes[i] = b;
            }
            return bytes;
        } catch (final Exception e) {
            throw new IllegalArgumentException("Cannot convert non-hex string: " + s);
        }
    }

    private static int getHexValue(final char c) {
        final int i = Character.digit(c, 16);
        if (i < 0) {
            throw new IllegalArgumentException("Invalid hex char: " + c);
        }
        return i;
    }

    static RuntimeException fail(final String message, final Throwable err) {
        System.err.println(message);
        if (err != null) {
            err.printStackTrace();
        }
        System.exit(1);
        throw new InternalError();
    }

    @SuppressWarnings("unchecked")
    static <X extends Throwable> RuntimeException sneakyThrow(final Throwable ex) throws X {
        throw (X) ex;
    }

    static String endingSlash(final String dir) {
        if (dir.endsWith("/")) {
            return dir;
        }
        return dir + "/";
    }
}
