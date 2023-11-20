import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    id("org.gradle.kotlin.kotlin-dsl")
    id("net.kyori.indra.licenser.spotless")
}

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
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

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs = listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.io.path.ExperimentalPathApi"
        )
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version
        )
    }
}

tasks.register("format") {
    group = "formatting"
    description = "Formats source code according to project style"
    dependsOn(tasks.spotlessApply)
}

indraSpotlessLicenser {
    licenseHeaderFile(rootProject.file("license/copyright.txt"))
    newLine(true)
}

spotless {
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

idea {
    module {
        isDownloadSources = true
    }
}
