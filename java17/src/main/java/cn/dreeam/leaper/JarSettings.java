package cn.dreeam.leaper;

import cn.dreeam.leaper.update.Updater;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Server software setting, written to {@value #PATH} by the QuantumLeaper Gradle plugin.
 * Without the file there is no auto update.
 *
 * @param updateApiType Type of download API
 * @param updateApiUrl  URL of download API, or null if there is none
 */
record JarSettings(Updater.ApiType updateApiType, String updateApiUrl) {

    static final String PATH = "/META-INF/leaper.properties";

    static JarSettings read(final Resources resources) {
        final Properties properties = new Properties();
        try (InputStream in = resources.open(PATH)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to read " + PATH + " of " + resources, e);
        }

        final String type = properties.getProperty("update-api-type", Updater.ApiType.BIBLIOTHEK.name());
        final Updater.ApiType apiType;
        try {
            apiType = Updater.ApiType.valueOf(type.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw Util.fail("Invalid update-api-type in " + PATH + " of " + resources + ": " + type, e);
        }

        return new JarSettings(apiType, properties.getProperty("update-api-url"));
    }
}
