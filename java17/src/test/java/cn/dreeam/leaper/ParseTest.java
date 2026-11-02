package cn.dreeam.leaper;

import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParseTest {

    static final String H1 = "11".repeat(32);
    static final String H2 = "22".repeat(32);
    static final String H3 = "33".repeat(32);

    static BufferedReader reader(final String text) {
        return new BufferedReader(new StringReader(text));
    }

    @Test
    void fileEntries() throws Exception {
        final StringBuilder text = new StringBuilder();
        // More than the initial array size of 8
        for (int i = 0; i < 20; i++) {
            text.append(H1).append("\tcom.example:lib").append(i).append(":1.0\tcom/example/lib").append(i).append(".jar\n");
        }

        final FileEntry[] entries = FileEntry.parse(reader(text.toString()));
        assertEquals(20, entries.length);
        assertArrayEquals(Util.fromHex(H1), entries[19].hash());
        assertEquals("com.example:lib19:1.0", entries[19].id());
        assertEquals("com/example/lib19.jar", entries[19].path());
    }

    @Test
    void fileEntryMalformed() {
        assertThrows(IllegalStateException.class, () -> FileEntry.parse(reader(H1 + "\tonly-two\n")));
    }

    @Test
    void patchEntries() throws Exception {
        final String text = "# comment\n\n"
            + "versions\t" + H1 + "\t" + H2 + "\t" + H3 + "\t1.0/server.jar\t1.0/server.jar.patch\t1.0/leaf.jar\n";

        final PatchEntry[] entries = PatchEntry.parse(reader(text));
        assertEquals(1, entries.length);
        final PatchEntry entry = entries[0];
        assertEquals("versions", entry.location());
        assertArrayEquals(Util.fromHex(H1), entry.originalHash());
        assertArrayEquals(Util.fromHex(H2), entry.patchHash());
        assertArrayEquals(Util.fromHex(H3), entry.outputHash());
        assertEquals("1.0/server.jar", entry.originalPath());
        assertEquals("1.0/server.jar.patch", entry.patchPath());
        assertEquals("1.0/leaf.jar", entry.outputPath());
    }

    @Test
    void patchEntryMalformed() {
        assertThrows(IllegalStateException.class, () -> PatchEntry.parse(reader("versions\t" + H1 + "\n")));
    }

    @Test
    void downloadContext() {
        final DownloadContext context =
            DownloadContext.parseLine(H1 + "\thttps://example.com/server.jar\tmojang_1.0.jar");
        assertArrayEquals(Util.fromHex(H1), context.hash());
        assertEquals("https://example.com/server.jar", context.url().toString());
        assertEquals("mojang_1.0.jar", context.fileName());
    }

    @Test
    void downloadContextEmptyOrInvalid() {
        assertNull(DownloadContext.parseLine(null));
        assertNull(DownloadContext.parseLine("  \n"));
        assertThrows(IllegalStateException.class, () -> DownloadContext.parseLine(H1 + "\thttps://example.com"));
    }
}
