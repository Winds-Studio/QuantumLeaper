package xyz.xenondevs.origami;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Test stand-in for Origami's {@code OrigamiAgent}: the methods that Origami's {@code PaperclipPatcher} makes
 * {@code io.papermc.paperclip.Paperclip.main} call, with the same signatures. Prints markers instead of
 * starting Origami.
 */
public final class OrigamiAgent {

    private OrigamiAgent() {}

    public static final class PatchingClassLoader extends URLClassLoader {
        PatchingClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }
    }

    public static ClassLoader createClassLoader(final URL[] urls, final ClassLoader parent) {
        System.out.println("origami:createClassLoader urls=" + urls.length);
        return new PatchingClassLoader(urls, parent);
    }

    public static void initOrigami(final URL[] urls, final ClassLoader classLoader, final String[] args) {
        System.out.println("origami:initOrigami urls=" + urls.length
            + " loader=" + classLoader.getClass().getSimpleName() + " args=" + String.join(",", args));
    }

    public static void startMain(final String className, final ClassLoader classLoader, final String[] args) {
        System.out.println("origami:startMain " + className);
        try {
            final Method main = Class.forName(className, true, classLoader).getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
