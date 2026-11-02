package cn.dreeam.leaper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    // sha256("abc")
    static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path dir;

    @Test
    void fromHex() {
        assertArrayEquals(new byte[] {0x00, 0x7f, (byte) 0x80, (byte) 0xff}, Util.fromHex("007f80FF"));
        assertThrows(IllegalArgumentException.class, () -> Util.fromHex("abc"));
        assertThrows(IllegalArgumentException.class, () -> Util.fromHex("zz"));
    }

    @Test
    void sha256OfFileAndData() throws Exception {
        final Path file = Files.writeString(dir.resolve("abc"), "abc");
        final byte[] expected = Util.fromHex(ABC_SHA256);

        assertArrayEquals(expected, Util.sha256(file));
        assertTrue(Util.isFileValid(file, expected));
        assertTrue(Util.isDataValid("abc".getBytes(StandardCharsets.UTF_8), expected));

        Files.writeString(file, "abd");
        assertFalse(Util.isFileValid(file, expected));
        assertFalse(Util.isFileValid(dir.resolve("missing"), expected));
    }

    @Test
    void sha256OfLargeFile() throws Exception {
        // Larger than any internal buffer, to make sure the whole file is hashed
        final byte[] data = new byte[5 * 1024 * 1024 + 7];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        final Path file = Files.write(dir.resolve("large"), data);
        assertArrayEquals(Util.newSha256Digest().digest(data), Util.sha256(file));
    }

    @Test
    void tempFileAndAtomicMove() throws Exception {
        final Path target = dir.resolve("sub/dir/target.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");

        final Path temp = Util.tempFileFor(target);
        assertEquals(target.toAbsolutePath().getParent(), temp.getParent());
        Files.writeString(temp, "new");

        Util.moveAtomically(temp, target);
        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(temp));
    }

    @Test
    void tempFileCreatesParentDirectories() throws Exception {
        final Path temp = Util.tempFileFor(dir.resolve("a/b/c.jar"));
        assertTrue(Files.isRegularFile(temp));
    }

    @Test
    void endingSlash() {
        assertEquals("a/", Util.endingSlash("a"));
        assertEquals("a/", Util.endingSlash("a/"));
    }
}
