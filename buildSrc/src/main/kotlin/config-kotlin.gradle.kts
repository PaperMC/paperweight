import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    id("org.gradle.kotlin.kotlin-dsl")
    id("org.cadixdev.licenser")
    id("org.jlleitschuh.gradle.ktlint")
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
            includeGroup("net.minecraftforge")
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

ktlint {
    enableExperimentalRules.set(true)

    disabledRules.addAll(
        "no-wildcard-imports",
        "filename",
        "trailing-comma-on-call-site",
        "trailing-comma-on-declaration-site",
        "experimental:function-signature",
    )
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
