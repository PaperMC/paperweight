import net.octyl.levelheadered.HeaderApplyTask
import net.octyl.levelheadered.HeaderVerifyTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    idea
    id("org.gradle.kotlin.kotlin-dsl")
    id("org.jlleitschuh.gradle.ktlint")
    id("net.octyl.level-headered")
}

java {
    withSourcesJar()
}

tasks.withType(JavaCompile::class).configureEach {
    options.release = 21
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs = listOf("-Xjvm-default=all", "-Xjdk-release=21")
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        mavenContent {
            includeGroup("codechicken")
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
    mavenCentral {
        mavenContent { releasesOnly() }
    }
    gradlePluginPortal()
}

dependencies {
    compileOnly(gradleApi())
}

testing {
    suites {
        val test = getByName<JvmTestSuite>("test") {
            useKotlinTest(embeddedKotlinVersion)
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-engine:6.0.3")
                implementation("org.junit.jupiter:junit-jupiter-params:6.0.3")
                implementation("org.junit.platform:junit-platform-launcher:6.0.3")
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

ktlint {
    version.set("1.8.0")
}

levelHeadered {
    headerTemplate(rootProject.file("license/copyright.txt"))
}

tasks.named<HeaderApplyTask>("applyTestHeader") {
    source.setFrom(sourceSets.test.get().allSource.minus(sourceSets.test.get().resources))
}
tasks.named<HeaderVerifyTask>("verifyTestHeader") {
    source.setFrom(sourceSets.test.get().allSource.minus(sourceSets.test.get().resources))
}

tasks.named("applyHeaderToAll") {
    mustRunAfter(tasks.named("ktlintFormat"))
}

tasks.register("format") {
    group = "formatting"
    description = "Formats source code according to project style"
    dependsOn(tasks.named("ktlintFormat"), tasks.named("applyHeaderToAll"))
}

idea {
    module {
        isDownloadSources = true
    }
}
