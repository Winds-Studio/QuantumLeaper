/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paperclip
 *
 * MIT License
 */

package io.papermc.paperclip;

import cn.dreeam.leaper.QuantumLeaper;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Keeps for compatibility, e.g. Origami's {@code PaperclipPatcher}, which rewrites the bytecode
 * of {@link #main(String[])} in place.
 *
 * <p>Keep this method exactly in this shape, Origami silently does nothing if it doesn't match:
 * <ul>
 *     <li>local slot 0: {@code args}, slot 1: the classpath {@code URL[]}, slot 3: the new class loader;</li>
 *     <li>a single {@code new URLClassLoader(URL[], ClassLoader)}, stored right away;</li>
 *     <li>a single lambda, capturing {@code (String mainClassName, URLClassLoader classLoader, String[] args)}
 *     in this order. No other lambdas or local variables.</li>
 * </ul>
 * All other work belongs in {@link QuantumLeaper}.
 */
public final class Paperclip {

    private Paperclip() {}

    public static void main(final String[] args) {
        final URL[] classpathUrls = QuantumLeaper.prepare();

        final ClassLoader parentClassLoader = Paperclip.class.getClassLoader().getParent();
        final URLClassLoader classLoader = new URLClassLoader(classpathUrls, parentClassLoader);

        final String mainClassName = QuantumLeaper.findMainClass();
        System.out.println("Starting " + mainClassName);

        final Thread runThread = new Thread(() -> QuantumLeaper.runMain(mainClassName, classLoader, args), "ServerMain");
        runThread.setContextClassLoader(classLoader);
        runThread.start();
    }
}
