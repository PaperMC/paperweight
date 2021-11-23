import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    id("org.gradle.kotlin.kotlin-dsl")
    id("org.cadixdev.licenser")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvmToolchain {
        (this as JavaToolchainSpec).apply {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    }
}

repositories {
    mavenCentral()
    //maven("https://oss.sonatype.org/content/repositories/snapshots/") {
    maven("https://papermc.io/repo/repository/maven-snapshots/") {
        mavenContent {
            includeModule("org.cadixdev", "mercury")
        }
    }
    maven("https://maven.quiltmc.org/repository/release/") {
        mavenContent {
            includeGroup("org.quiltmc")
        }
    }
}

configurations.all {
    if (name == "compileOnly") {
        return@all
    }
    dependencies.remove(project.dependencies.gradleApi())
    dependencies.removeIf { it.group == "org.jetbrains.kotlin" }
}

dependencies {
    compileOnly(gradleApi())
    compileOnly(kotlin("stdlib-jdk8"))
}

gradlePlugin {
    isAutomatedPublishing = false
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf(
            "-Xjvm-default=all",
            "-Xopt-in=kotlin.io.path.ExperimentalPathApi"
        )
    }
}

ktlint {
    enableExperimentalRules.set(true)

    disabledRules.add("no-wildcard-imports")
}

tasks.register("format") {
    group = "formatting"
    description = "Formats source code according to project style"
    dependsOn(tasks.licenseFormat, tasks.ktlintFormat)
}

license {
    header.set(resources.text.fromFile(rootProject.file("license/copyright.txt")))
    include("**/*.kt")
}

idea {
    module {
        isDownloadSources = true
    }
}
