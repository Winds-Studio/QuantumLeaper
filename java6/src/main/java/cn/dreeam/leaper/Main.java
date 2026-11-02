/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paperclip
 *
 * MIT License
 */

package cn.dreeam.leaper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Main {

    // Used when the jar has no version.json (or it has no java_version)
    private static final int DEFAULT_JAVA_VERSION = 21;
    // The launcher itself is compiled for Java 17
    private static final int MIN_JAVA_VERSION = 17;

    public static void main(final String[] args) {
        final int requiredJava = getRequiredJavaVersion();
        if (getJavaVersion() < requiredJava) {
            System.err.println("Minecraft " + getMinecraftVersion() + " requires running the server with Java " + requiredJava
                + " or above. For information on how to update Java, see https://docs.papermc.io/misc/java-install");
            System.exit(1);
        }

        try {
            final Class<?> paperclipClass = Class.forName("io.papermc.paperclip.Paperclip");
            final Method mainMethod = paperclipClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (final Exception e) {
            e.printStackTrace();
            // Setup failed before the server was started, don't exit with a success code
            System.exit(1);
        }
    }

    /**
     * @return {@code java_version} of {@code version.json} (written by Mojang, copied into the jar by paperweight)
     */
    static int getRequiredJavaVersion() {
        final String value = readVersionJson("java_version", "(\\d+)");
        return Math.max(MIN_JAVA_VERSION, value == null ? DEFAULT_JAVA_VERSION : Integer.parseInt(value));
    }

    private static String getMinecraftVersion() {
        final String value = readVersionJson("id", "\"([^\"]*)\"");
        return value == null ? "1.17.1+" : value;
    }

    // Compiled for Java 6, so no JSON library and no try-with-resources
    private static String readVersionJson(final String key, final String valuePattern) {
        final InputStream in = Main.class.getResourceAsStream("/version.json");
        if (in == null) {
            return null;
        }
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            final Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*" + valuePattern).matcher(out.toString("UTF-8"));
            return matcher.find() ? matcher.group(1) : null;
        } catch (final IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (final IOException ignored) {
            }
        }
    }

    private static int getJavaVersion() {
        final String version = System.getProperty("java.specification.version");
        final String[] parts = version.split("\\.");

        final String errorMsg = "Could not determine version of the current JVM";
        if (parts.length == 0) {
            throw new IllegalStateException(errorMsg);
        }

        if (parts[0].equals("1")) {
            if (parts.length < 2) {
                throw new IllegalStateException(errorMsg);
            }
            return Integer.parseInt(parts[1]);
        } else {
            return Integer.parseInt(parts[0]);
        }
    }
}
