import com.diffplug.gradle.spotless.SpotlessExtension
import net.kyori.indra.licenser.spotless.IndraSpotlessLicenserExtension

plugins {
    idea
    id("org.gradle.kotlin.kotlin-dsl")
}

java {
    withSourcesJar()
}

tasks.withType(JavaCompile::class).configureEach {
    options.release = 11
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    target {
        compilations.configureEach {
            kotlinOptions {
                jvmTarget = "11"
                freeCompilerArgs = listOf("-Xjvm-default=all", "-Xjdk-release=11")
            }
        }
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-snapshots/") {
        mavenContent {
            includeModule("org.cadixdev", "mercury")
        }
    }
    maven("https://repo.papermc.io/repository/maven-public/") {
        mavenContent {
            includeGroup("codechicken")
            includeGroup("net.fabricmc")
        }
    }
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly(gradleApi())
    compileOnly(kotlin("stdlib-jdk8"))
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useKotlinTest(embeddedKotlinVersion)
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
            }
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

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version
        )
    }
}

// The following is to work around https://github.com/diffplug/spotless/issues/1599
// Ensure the ktlint step is before the license header step

plugins.apply("com.diffplug.spotless")
extensions.configure<SpotlessExtension> {
    val overrides = mapOf(
        "ktlint_standard_no-wildcard-imports" to "disabled",
        "ktlint_standard_filename" to "disabled",
        "ktlint_standard_trailing-comma-on-call-site" to "disabled",
        "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
    )

    val ktlintVer = "0.50.0"

    kotlin {
        ktlint(ktlintVer).editorConfigOverride(overrides)
    }
    kotlinGradle {
        ktlint(ktlintVer).editorConfigOverride(overrides)
    }
}

plugins.apply("net.kyori.indra.licenser.spotless")
extensions.configure<IndraSpotlessLicenserExtension> {
    licenseHeaderFile(rootProject.file("license/copyright.txt"))
    newLine(true)
}

tasks.register("format") {
    group = "formatting"
    description = "Formats source code according to project style"
    dependsOn(tasks.named("spotlessApply"))
}

idea {
    module {
        isDownloadSources = true
    }
}
