package cn.dreeam.leaper;

import java.lang.instrument.Instrumentation;

/**
 * Java agent used by the end-to-end tests: applies Origami's real {@code PaperclipPatcher} (from origami-jit)
 * the same way Origami's agent does in its premain, i.e. redefines {@code io.papermc.paperclip.Paperclip}
 * before the launcher's main runs.
 */
public final class OrigamiTestAgent {

    private OrigamiTestAgent() {}

    public static void premain(final String agentArgs, final Instrumentation instrumentation) throws Exception {
        final Class<?> patcher = Class.forName("xyz.xenondevs.origami.jit.PaperclipPatcher");
        final Object instance = patcher.getField("INSTANCE").get(null);
        patcher.getMethod("patch", Instrumentation.class, ClassLoader.class)
            .invoke(instance, instrumentation, OrigamiTestAgent.class.getClassLoader());
        System.out.println("origami:patched");
    }
}
