plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
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

repositories {
    mavenCentral()
    maven("https://maven.leafmc.one/snapshots/") {
        content { includeGroup("com.github.thatsmusic99") }
    }
}

dependencies {
    implementation("io.sigpipe:jbsdiff:1.0")
    implementation("com.github.thatsmusic99:ConfigurationMaster-API:v2.0.0-rc.3")
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    val prefix = "cn.dreeam.leaper.libs"
    listOf("org.apache", "org.tukaani", "io.sigpipe", "io.github.thatsmusic99", "org.yaml").forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    // Paperclip uses META-INF/versions/ for server jars, drop the multi-release classes of dependencies.
    // The jar is not Multi-Release, so they would never be loaded anyway.
    exclude("META-INF/versions/**")
    exclude("META-INF/maven/**")
    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
}
