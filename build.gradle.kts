import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.publish.maven.MavenPom
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    eclipse
    `kotlin-dsl`
    `maven-publish`
    id("org.cadixdev.licenser") version "0.6.0"
    id("com.github.johnrengelman.shadow") version "7.0.0"
    id("org.jlleitschuh.gradle.ktlint") version "10.0.0"
}

group = "io.papermc.paperweight"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
    withSourcesJar()
}

val sourcesJar by tasks.existing

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        useIR = true
        freeCompilerArgs = listOf("-Xopt-in=kotlin.io.path.ExperimentalPathApi")
    }
}

gradlePlugin {
    // we handle publications ourselves
    isAutomatedPublishing = false
}

val shade: Configuration by configurations.creating
configurations.implementation {
    extendsFrom(shade)
}

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/") {
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

dependencies {
    shade(libs.httpclient)
    shade(libs.kotson)

    // ASM for inspection
    shade(libs.bundles.asm)

    shade(libs.bundles.hypo)
    shade(libs.bundles.cadix)

    shade(libs.lorenzTiny)

    shade(libs.jbsdiff)
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
    header.set(resources.text.fromFile(file("license/copyright.txt")))
    include("**/*.kt")
}

idea {
    module {
        isDownloadSources = true
    }
}

fun ShadowJar.configureStandard() {
    configurations = listOf(shade)

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*:.*"))
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    mergeServiceFiles()
}

tasks.shadowJar {
    configureStandard()

    val prefix = "paper.libs"
    listOf(
        "dev.denwav.hypo",
        "com.github.salomonbrys.kotson",
        "com.google.gson",
        "io.sigpipe",
        "me.jamiemansfield",
        "net.fabricmc",
        "org.apache.commons.codec",
        "org.apache.commons.compress",
        "org.apache.commons.logging",
        "org.apache.felix",
        "org.apache.http",
        "org.cadixdev",
        "org.eclipse",
        "org.objectweb",
        "org.osgi",
        "org.tukaani"
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}

val devShadowJar by tasks.registering(ShadowJar::class) {
    configureStandard()

    from(project.sourceSets.main.get().output)

    archiveClassifier.set("dev")
}

val isSnapshot = version().endsWith("-SNAPSHOT")

publishing {
    publications {
        register<MavenPublication>("shadow") {
            pluginConfig(version())
            artifact(tasks.shadowJar) {
                classifier = null
            }
        }
        register<MavenPublication>("maven") {
            standardConfig(version())
        }
        register<MavenPublication>("shadowLocal") {
            pluginConfig(localVersion())
            artifact(devShadowJar) {
                classifier = null
            }
        }
        register<MavenPublication>("mavenLocal") {
            standardConfig(localVersion())
        }

        repositories {
            val url = "https://wav.jfrog.io/artifactory/repo/"
            maven(url) {
                credentials(PasswordCredentials::class)
                name = "wavJfrog"
            }
        }
    }
}

tasks.withType(PublishToMavenRepository::class).configureEach {
    onlyIf {
        !publication.name.endsWith("Local")
    }
}
tasks.withType(PublishToMavenLocal::class).configureEach {
    onlyIf {
        publication.name.endsWith("Local")
    }
}

fun MavenPublication.standardConfig(versionName: String) {
    groupId = project.group.toString()
    artifactId = project.name
    version = versionName

    from(components["java"])
    artifact(devShadowJar)

    withoutBuildIdentifier()
    pom {
        pomConfig()
    }
}

fun MavenPublication.pluginConfig(versionName: String) {
    groupId = project.group.toString()
    artifactId = "io.papermc.paperweight.gradle.plugin"
    version = versionName

    artifact(sourcesJar)

    withoutBuildIdentifier()
    pom {
        pomConfig()
    }
}

fun MavenPom.pomConfig() {
    val repoPath = "PaperMC/paperweight"
    val repoUrl = "https://github.com/$repoPath"

    name.set("paperweight")
    description.set("Gradle plugin for the PaperMC project")
    url.set(repoUrl)
    inceptionYear.set("2020")
    packaging = "jar"

    licenses {
        license {
            name.set("LGPLv2.1")
            url.set("$repoUrl/blob/master/license/LGPLv2.1.txt")
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
    }

    scm {
        url.set(repoUrl)
        connection.set("scm:git:$repoUrl.git")
        developerConnection.set("scm:git:git@github.com:$repoPath.git")
    }
}

fun version(): String {
    return project.version.toString()
}
fun localVersion(): String {
    return if (isSnapshot) {
        version().substringBefore('-') + "-LOCAL-SNAPSHOT"
    } else {
        version() + "-LOCAL"
    }
}
