import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    eclipse
    maven
    kotlin("jvm") version "1.2.61"
    `kotlin-dsl`
    id("net.minecrell.licenser") version "0.4.1"
}

val jar by tasks.existing(Jar::class)

group = "io.papermc"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://files.minecraftforge.net/maven/")
}

dependencies {
    compileOnly(gradleApi())
    compileOnly(gradleKotlinDsl())
    compile(kotlin("stdlib-jdk8"))

    compile("net.sf.opencsv:opencsv:2.3")

    compile("org.cadixdev:lorenz:0.5.0-SNAPSHOT")
    compile("org.cadixdev:mercury:0.1.0-SNAPSHOT")
    compile("org.cadixdev:survey:0.2.0-SNAPSHOT")

    compile("com.github.salomonbrys.kotson:kotson:2.5.0")

    compile("net.minecraftforge:forgeflower:1.5.380.24")
    compile("de.oceanlabs.mcp:mcinjector:3.7.4")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
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
    header = file("licenses/copyright.txt")

    include("**/*.kt")
    exclude("util/ForgeGradleUtils.kt")
}
