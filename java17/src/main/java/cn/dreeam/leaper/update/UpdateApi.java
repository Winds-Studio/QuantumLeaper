package cn.dreeam.leaper.update;

import cn.dreeam.leaper.Http;
import cn.dreeam.leaper.LoaderConfig;

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
 * Client for the build download API, see {@link AbstractUpdater.ApiType}.
 */
final class UpdateApi {

    record Build(int build, boolean stable, String fileName, String sha256, URI downloadUrl) {}

    private final AbstractUpdater.ApiType type;
    private final String base;

    UpdateApi(final AbstractUpdater.ApiType type, final String base) {
        this.type = type;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * @return all builds of a Minecraft version, oldest first
     */
    List<Build> builds(final String version) throws IOException {
        final String versionPath = this.base + "/versions/" + encode(version);
        return parseBuilds(this.type, Http.getString(URI.create(versionPath + "/builds")), versionPath);
    }

    /**
     * @return the newest build of the channel, or {@code null} if there is none. The experimental channel
     * includes stable builds, so it is always the newest build.
     */
    static Build latest(final List<Build> builds, final LoaderConfig.UpdateChannel channel) {
        Build newest = null;
        for (final Build b : builds) {
            final boolean inChannel = channel == LoaderConfig.UpdateChannel.EXPERIMENTAL || b.stable();
            if (inChannel && (newest == null || b.build() > newest.build())) {
                newest = b;
            }
        }
        return newest;
    }

    static List<Build> parseBuilds(final AbstractUpdater.ApiType type, final String json, final String versionPath) throws IOException {
        final Object root;
        try {
            root = parseJson(json);
        } catch (final RuntimeException e) {
            throw new IOException("Invalid response: " + e.getMessage(), e);
        }

        final List<Build> result = new ArrayList<>();
        try {
            final List<?> elements = type == AbstractUpdater.ApiType.FILLV3 ? (List<?>) root : (List<?>) ((Map<?, ?>) root).get("builds");
            for (final Object element : elements) {
                final Map<?, ?> build = (Map<?, ?>) element;
                result.add(type == AbstractUpdater.ApiType.FILLV3 ? parseFill(build) : parseBibliothek(build, versionPath));
            }
        } catch (final ClassCastException | NullPointerException e) {
            throw new IOException("Unexpected response format", e);
        }
        result.sort(Comparator.comparingInt(Build::build));
        return result;
    }

    private static Build parseBibliothek(final Map<?, ?> build, final String versionPath) throws IOException {
        final int number = ((Number) build.get("build")).intValue();
        final Map<?, ?> primary = (Map<?, ?>) ((Map<?, ?>) build.get("downloads")).get("primary");
        final String fileName = (String) primary.get("name");
        return create(number, "default".equals(build.get("channel")), fileName, (String) primary.get("sha256"),
            URI.create(versionPath + "/builds/" + number + "/downloads/" + encode(fileName)));
    }

    private static Build parseFill(final Map<?, ?> build) throws IOException {
        final int number = ((Number) build.get("id")).intValue();
        final Map<?, ?> downloads = (Map<?, ?>) build.get("downloads");
        Map<?, ?> server = (Map<?, ?>) downloads.get("server:default");
        if (server == null) {
            server = (Map<?, ?>) downloads.values().iterator().next();
        }
        return create(number, "STABLE".equals(build.get("channel")), (String) server.get("name"),
            (String) ((Map<?, ?>) server.get("checksums")).get("sha256"), URI.create((String) server.get("url")));
    }

    private static Build create(final int number, final boolean stable, final String fileName, final String sha256, final URI url) throws IOException {
        if (fileName == null || sha256 == null || fileName.contains("/") || fileName.contains("\\")) {
            throw new IOException("Invalid download of build " + number);
        }
        return new Build(number, stable, fileName, sha256, url);
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
