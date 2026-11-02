package cn.dreeam.leaper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateApiTest {

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
        final List<UpdateApi.Build> parsed = UpdateApi.parseBuilds(
            builds(build(17, "experimental", "leaf-26.3-17.jar"), build(9, "default", "leaf-26.3-9.jar")), BASE);

        assertEquals(2, parsed.size());
        // Sorted by build number
        final UpdateApi.Build build = parsed.get(0);
        assertEquals(9, build.build());
        assertEquals("default", build.channel());
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
        final List<UpdateApi.Build> parsed = UpdateApi.parseBuilds(builds(
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
        assertThrows(IOException.class, () -> UpdateApi.parseBuilds(builds(build(1, "default", "../../evil.jar")), BASE));
    }

    @Test
    void rejectsUnexpectedResponses() {
        assertThrows(IOException.class, () -> UpdateApi.parseBuilds("{\"error\":\"Endpoint not found.\"}", BASE));
        assertThrows(IOException.class, () -> UpdateApi.parseBuilds("<html>", BASE));
    }
}
