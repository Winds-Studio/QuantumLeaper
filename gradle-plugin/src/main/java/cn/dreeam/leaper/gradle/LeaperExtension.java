package cn.dreeam.leaper.gradle;

import org.gradle.api.Action;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;

/**
 * Example:
 *
 * <pre>{@code
 * leaper {
 *     updater {
 *         bibliothek("https://api.leafmc.one/v2/projects/leaf")
 *     }
 * }
 * }</pre>
 */
public abstract class LeaperExtension {

    /**
     * Version of the {@code cn.dreeam:quantumleaper} launcher, the version of this plugin by default.
     * Optional, only for local troubleshooting usage, e.g. {@code 1.0.2-SNAPSHOT}.
     */
    public abstract Property<String> getLoaderVersion();

    /**
     * The download API for auto updates.
     */
    @Nested
    public abstract UpdaterSpec getUpdater();

    public void updater(final Action<? super UpdaterSpec> action) {
        action.execute(getUpdater());
    }

    public abstract static class UpdaterSpec {

        public abstract Property<String> getApiType();
        public abstract Property<String> getApiUrl();

        public void bibliothek(final String projectUrl) {
            getApiType().set("BIBLIOTHEK");
            getApiUrl().set(projectUrl);
        }

        public void fill(final String projectUrl) {
            getApiType().set("FILLV3");
            getApiUrl().set(projectUrl);
        }
    }
}
