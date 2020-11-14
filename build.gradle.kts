import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    eclipse
    maven
    `kotlin-dsl`
    id("net.minecrell.licenser") version "0.4.1"
    id("com.github.johnrengelman.shadow") version "6.0.0"
}

group = "io.papermc.paperweight"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://files.minecraftforge.net/maven/")
}

dependencies {
    implementation("org.apache.httpcomponents:httpclient:4.5.12")

    // Utils
    implementation("net.sf.opencsv:opencsv:2.3")
    implementation("com.github.salomonbrys.kotson:kotson:2.5.0")

    // ASM for inspection
    implementation("org.ow2.asm:asm:8.0.1")

    // Cadix
    implementation("org.cadixdev:lorenz:0.5.5")
    implementation("org.cadixdev:lorenz-asm:0.5.5")
    implementation("org.cadixdev:atlas:0.2.0")
    implementation("org.cadixdev:at:0.1.0-SNAPSHOT")

    implementation("org.cadixdev:mercury:0.1.0-SNAPSHOT")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
}

tasks.jar {
    archiveBaseName.set("io.papermc.paperweight.gradle.plugin")
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

eclipse {
    classpath {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

license {
    header = file("license/copyright.txt")

    include("**/*.kt")
}
