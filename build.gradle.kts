plugins {
    java
    application
    `maven-publish`
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

repositories {
    mavenCentral()
    // Origami, only used by the end-to-end tests
    maven("https://repo.xenondevs.xyz/releases/") {
        content { includeGroup("xyz.xenondevs.origami") }
    }
    maven("https://maven.fabricmc.net/") {
        content { includeGroup("net.fabricmc") }
    }
}

// Origami's real PaperclipPatcher, put into a test java agent by the end-to-end tests
val origamiAgent: Configuration by configurations.creating

// End-to-end tests: run the built launcher jar in a separate JVM against a fake server
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.sigpipe:jbsdiff:1.0")
    origamiAgent("xyz.xenondevs.origami:origami-jit:0.6.1")
}

val java21 = JavaLanguageVersion.of(21)

tasks.compileTestJava {
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(java21) })
}

tasks.test {
    useJUnitPlatform()
    // The launcher requires Java 21+, and the tests start it with the same JVM
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(java21) })
    val launcherJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(launcherJar)
    inputs.files(origamiAgent)
    // Origami (like current Leaf) is compiled for Java 25
    val java25 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    doFirst {
        systemProperty("leaper.test.java25", java25.get().executablePath.asFile.absolutePath)
        systemProperty("leaper.test.launcherJar", launcherJar.get().asFile.absolutePath)
        systemProperty("leaper.test.origamiClasspath", origamiAgent.files.joinToString(File.pathSeparator))
    }
}

tasks.jar {
    val java6Jar = project(":java6").tasks.named("jar")
    val java17Jar = project(":java17").tasks.named("shadowJar")
    dependsOn(java6Jar, java17Jar)

    from(zipTree(java6Jar.map { it.outputs.files.singleFile }))
    from(zipTree(java17Jar.map { it.outputs.files.singleFile }))

    manifest {
        attributes(
            "Main-Class" to "cn.dreeam.leaper.Main",
            "Implementation-Title" to "QuantumLeaper",
            "Implementation-Version" to project.version
        )
    }

    from(file("LICENSE.txt")) {
        into("META-INF/license")
        rename { "paperclip-LICENSE.txt" }
    }
    rename { name ->
        if (name.endsWith("-LICENSE.txt")) {
            "META-INF/license/$name"
        } else {
            name
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    val java6Sources = project(":java6").tasks.named("sourcesJar")
    val java17Sources = project(":java17").tasks.named("sourcesJar")
    dependsOn(java6Sources, java17Sources)

    from(zipTree(java6Sources.map { it.outputs.files.singleFile }))
    from(zipTree(java17Sources.map { it.outputs.files.singleFile }))

    archiveClassifier.set("sources")
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["java"])
            artifact(sourcesJar)
            withoutBuildIdentifier()

            pom {
                val repoPath = "Winds-Studio/QuantumLeaper"
                val repoUrl = "https://github.com/$repoPath"

                name.set("QuantumLeaper")
                description.set(project.description)
                url.set(repoUrl)
                packaging = "jar"

                licenses {
                    license {
                        name.set("MIT")
                        url.set("$repoUrl/blob/main/LICENSE.txt")
                        distribution.set("repo")
                    }
                }

                issueManagement {
                    system.set("GitHub")
                    url.set("$repoUrl/issues")
                }

                developers {
                    developer {
                        id.set("DemonWav")
                        name.set("Kyle Wood")
                        email.set("demonwav@gmail.com")
                        url.set("https://github.com/DemonWav")
                    }
                    developer {
                        id.set("Dreeam-qwq")
                        name.set("Dreeam")
                        email.set("dreeam_charlie@163.com")
                        url.set("https://github.com/Dreeam-qwq")
                    }
                }

                scm {
                    url.set(repoUrl)
                    connection.set("scm:git:$repoUrl.git")
                    developerConnection.set("scm:git:git@github.com:$repoPath.git")
                }
            }
        }

        repositories {
            val url = "https://maven.leafmc.one/snapshots/"

            maven(url) {
                credentials(PasswordCredentials::class)
                name = "quantumleaper"
            }
        }
    }
}

tasks.register("printVersion") {
    doFirst {
        println(version)
    }
}
