package cn.dreeam.leaper.update;

import cn.dreeam.leaper.LoaderConfig;
import cn.dreeam.leaper.Resources;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateApiTest {

    static final Updater.ApiType BIB = Updater.ApiType.BIBLIOTHEK;

    static final String BASE = "https://api.example.com/v2/projects/leaf/versions/26.3";

    static String build(final int number, final String channel, final String name) {
        return "{\"build\":" + number + ",\"time\":\"2026-10-02T11:07:19.133Z\",\"channel\":\"" + channel + "\","
            + "\"promoted\":false,\"changes\":[{\"commit\":\"81e98b1\",\"summary\":\"Fix a\\/b \\u00e9\","
            + "\"message\":\"line 1\\nline 2 \\\"quoted\\\" back\\\\slash\"}],"
            + "\"downloads\":{\"primary\":{\"name\":\"" + name + "\",\"sha256\":\"" + "ab".repeat(32) + "\"}}}";
    }

    static String builds(final String... builds) {
        return "{\"project_id\":\"leaf\",\"project_name\":\"Leaf\",\"version\":\"26.3\",\"builds\":[" + String.join(",", builds) + "]}";
    }

    @Test
    void parsesBuilds() throws IOException {
        final List<UpdateApi.Build> parsed = UpdateApi.parseBuilds(BIB, 
            builds(build(17, "experimental", "leaf-26.3-17.jar"), build(9, "default", "leaf-26.3-9.jar")), BASE);

        assertEquals(2, parsed.size());
        // Sorted by build number
        final UpdateApi.Build build = parsed.get(0);
        assertEquals(9, build.build());
        assertEquals(true, build.stable());
        assertEquals("leaf-26.3-9.jar", build.fileName());
        assertEquals("ab".repeat(32), build.sha256());
        assertEquals(BASE + "/builds/9/downloads/leaf-26.3-9.jar", build.downloadUrl().toString());
    }

    @Test
    void parsesJsonEscapes() {
        final Map<?, ?> parsed = (Map<?, ?>) UpdateApi.parseJson("{\"a\":\"x\\/y \\u00e9 \\\\/ \\\"q\\\"\"}");
        assertEquals("x/y é \\/ \"q\"", parsed.get("a"));
    }

    @Test
    void latestPerChannel() throws IOException {
        final List<UpdateApi.Build> parsed = UpdateApi.parseBuilds(BIB, builds(
            build(1, "default", "a.jar"),
            build(2, "default", "b.jar"),
            build(3, "experimental", "c.jar")
        ), BASE);

        assertEquals(2, UpdateApi.latest(parsed, LoaderConfig.UpdateChannel.STABLE).build());
        assertEquals(3, UpdateApi.latest(parsed, LoaderConfig.UpdateChannel.EXPERIMENTAL).build());
        assertNull(UpdateApi.latest(parsed.subList(2, 3), LoaderConfig.UpdateChannel.STABLE));
    }

    @Test
    void rejectsUnsafeFileNames() {
        assertThrows(IOException.class, () -> UpdateApi.parseBuilds(BIB, builds(build(1, "default", "../../evil.jar")), BASE));
    }

    @Test
    void rejectsUnexpectedResponses() {
        assertThrows(IOException.class, () -> UpdateApi.parseBuilds(BIB, "{\"error\":\"Endpoint not found.\"}", BASE));
        assertThrows(IOException.class, () -> UpdateApi.parseBuilds(BIB, "<html>", BASE));
    }

    @Test
    void parsesFillV3() throws IOException {
        final String json = "[{\"id\":5,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"paper-1.21-5.jar\","
            + "\"checksums\":{\"sha256\":\"" + "cd".repeat(32) + "\"},\"size\":1,\"url\":\"https://fill-data.example/paper-1.21-5.jar\"}}},"
            + "{\"id\":6,\"channel\":\"BETA\",\"downloads\":{\"server:default\":{\"name\":\"paper-1.21-6.jar\","
            + "\"checksums\":{\"sha256\":\"" + "cd".repeat(32) + "\"},\"url\":\"https://fill-data.example/paper-1.21-6.jar\"}}}]";
        final List<UpdateApi.Build> parsed = UpdateApi.parseBuilds(Updater.ApiType.FILLV3, json, BASE);

        assertEquals(2, parsed.size());
        assertEquals(5, parsed.get(0).build());
        assertEquals(true, parsed.get(0).stable());
        assertEquals(false, parsed.get(1).stable());
        assertEquals("https://fill-data.example/paper-1.21-5.jar", parsed.get(0).downloadUrl().toString());
    }
}
