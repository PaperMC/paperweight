import com.diffplug.gradle.spotless.SpotlessExtension
import net.kyori.indra.licenser.spotless.IndraSpotlessLicenserExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    idea
    id("org.gradle.kotlin.kotlin-dsl")
}

java {
    withSourcesJar()
}

tasks.withType(JavaCompile::class).configureEach {
    options.release = 17
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs = listOf("-Xjvm-default=all", "-Xjdk-release=17", "-opt-in=kotlin.io.path.ExperimentalPathApi")
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
            includeGroup("io.codechicken")
            includeGroup("net.fabricmc")
            includeGroupAndSubgroups("io.papermc")
        }
    }
    maven("https://maven.neoforged.net/releases") {
        name = "NeoForged"
        mavenContent {
            releasesOnly()
            includeGroupAndSubgroups("net.neoforged")
        }
    }
    maven("https://maven.fabricmc.net") {
        name = "FabricMC"
        mavenContent {
            releasesOnly()
            includeGroupAndSubgroups("net.fabricmc")
        }
    }
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly(gradleApi())
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useKotlinTest(embeddedKotlinVersion)
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-engine:5.12.2")
                implementation("org.junit.jupiter:junit-jupiter-params:5.12.2")
                implementation("org.junit.platform:junit-platform-launcher:1.12.2")
            }

            targets.configureEach {
                testTask {
                    testLogging {
                        events(TestLogEvent.FAILED)
                        exceptionFormat = TestExceptionFormat.FULL
                    }
                }
            }
        }
    }
}

configurations.all {
    if (name == "compileOnly") {
        return@all
    }
    dependencies.remove(project.dependencies.gradleApi())
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
