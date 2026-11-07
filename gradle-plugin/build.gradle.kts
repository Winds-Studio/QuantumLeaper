plugins {
    `java-gradle-plugin`
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

gradlePlugin {
    plugins {
        register("leaper") {
            id = "cn.dreeam.leaper"
            implementationClass = "cn.dreeam.leaper.gradle.LeaperPlugin"
            displayName = "QuantumLeaper"
            description = "Builds Leaf server jars with the QuantumLeaper launcher"
        }
    }
}

// The plugin adds the launcher of the same version, see LeaperPlugin
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = "quantumleaper-gradle-plugin"
        }
    }

    repositories {
        maven("https://maven.leafmc.one/snapshots/") {
            credentials(PasswordCredentials::class)
            name = "quantumleaper"
        }
    }
}
