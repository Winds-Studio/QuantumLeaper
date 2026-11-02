package cn.dreeam.leaper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Client for the build download API (PaperMC v2 format), e.g. {@code https://api.leafmc.one/v2/projects/leaf}.
 */
final class UpdateApi {

    static final String DEFAULT_BASE = "https://api.leafmc.one/v2/projects/leaf";

    // API channel names
    static final String CHANNEL_DEFAULT = "default";

    record Build(int build, String channel, String fileName, String sha256, URI downloadUrl) {}

    private final String base;

    UpdateApi(final String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * @return all builds of a Minecraft version, oldest first
     */
    List<Build> builds(final String version) throws IOException {
        final String versionPath = this.base + "/versions/" + encode(version);
        return parseBuilds(Http.getString(URI.create(versionPath + "/builds")), versionPath);
    }

    /**
     * @return the newest build of the channel, or {@code null} if there is none. The experimental channel
     * includes stable builds, so it is always the newest build.
     */
    static Build latest(final List<Build> builds, final LoaderConfig.UpdateChannel channel) {
        return builds.stream()
            .filter(b -> channel == LoaderConfig.UpdateChannel.EXPERIMENTAL || CHANNEL_DEFAULT.equals(b.channel()))
            .max(Comparator.comparingInt(Build::build))
            .orElse(null);
    }

    static List<Build> parseBuilds(final String json, final String versionPath) throws IOException {
        final Object root;
        try {
            root = parseJson(json);
        } catch (final RuntimeException e) {
            throw new IOException("Invalid response: " + e.getMessage(), e);
        }

        final List<Build> result = new ArrayList<>();
        try {
            for (final Object element : (List<?>) ((Map<?, ?>) root).get("builds")) {
                final Map<?, ?> build = (Map<?, ?>) element;
                final int number = ((Number) build.get("build")).intValue();
                final Map<?, ?> primary = (Map<?, ?>) ((Map<?, ?>) build.get("downloads")).get("primary");
                final String fileName = (String) primary.get("name");
                final String sha256 = (String) primary.get("sha256");
                if (fileName == null || sha256 == null || fileName.contains("/") || fileName.contains("\\")) {
                    throw new IOException("Invalid download of build " + number);
                }
                result.add(new Build(
                    number,
                    String.valueOf(build.get("channel")),
                    fileName,
                    sha256,
                    URI.create(versionPath + "/builds/" + number + "/downloads/" + encode(fileName))
                ));
            }
        } catch (final ClassCastException | NullPointerException e) {
            throw new IOException("Unexpected response format", e);
        }
        result.sort(Comparator.comparingInt(Build::build));
        return result;
    }

    /**
     * Parses JSON with SnakeYAML (already bundled, JSON is nearly a subset of YAML),
     * after replacing the one JSON escape YAML doesn't know, {@code \/}.
     */
    static Object parseJson(final String json) {
        final StringBuilder fixed = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                final char next = json.charAt(++i);
                if (next != '/') {
                    fixed.append(c);
                }
                fixed.append(next);
            } else {
                fixed.append(c);
            }
        }

        final LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        return new Yaml(new SafeConstructor(options)).load(fixed.toString());
    }

    private static String encode(final String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
