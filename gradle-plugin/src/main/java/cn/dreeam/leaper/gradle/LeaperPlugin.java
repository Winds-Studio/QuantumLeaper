package cn.dreeam.leaper.gradle;

import java.io.File;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Uses the QuantumLeaper as the paperclip and adds the {@code createLeafclipJar} task.
 */
public final class LeaperPlugin implements Plugin<Project> {

    private static final String VERSION = LeaperPlugin.class.getPackage().getImplementationVersion();

    @Override
    public void apply(final Project project) {
        final LeaperExtension extension = project.getExtensions().create("leaper", LeaperExtension.class);
        if (VERSION != null) {
            extension.getLoaderVersion().convention(VERSION);
        }

        project.getPluginManager().withPlugin("io.papermc.paperweight.core", plugin ->
            project.getDependencies().addProvider("paperclip",
                extension.getLoaderVersion().map(version -> "cn.dreeam:quantumleaper:" + version)));

        final File license = project.getRootProject().file("paper-server/LICENSE.txt");
        project.getTasks().register("createLeafclipJar", CreateLeafclipJar.class, task -> {
            task.setGroup("bundling");
            task.setDescription("Build a runnable paperclip jar without the libraries downloadable from Maven repositories");
            task.getPaperclipJar().from(project.getTasks().named("createPaperclipJar"));
            if (license.isFile()) {
                task.getLicenseFile().set(license);
            }
            task.getUpdateApiType().set(extension.getUpdater().getApiType());
            task.getUpdateApiUrl().set(extension.getUpdater().getApiUrl());
            // Same name scheme as createPaperclipJar
            task.getOutputZip().set(project.getLayout().getBuildDirectory().file(project.provider(() ->
                "libs/" + project.getRootProject().getName() + "-leafclip-" + project.getVersion() + ".jar")));
            task.getCacheDir().set(project.getLayout().getBuildDirectory().dir("leafclip/cache"));
        });
    }
}
