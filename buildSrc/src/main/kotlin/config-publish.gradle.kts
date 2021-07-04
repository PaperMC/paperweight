import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.kotlin.dsl.credentials
import org.gradle.kotlin.dsl.existing
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.registering
import org.gradle.kotlin.dsl.withType

plugins {
    `maven-publish`
    id("org.jetbrains.kotlin.jvm")
    id("com.github.johnrengelman.shadow")
}

val shade: Configuration by configurations.creating
configurations.implementation {
    extendsFrom(shade)
}

fun ShadowJar.configureStandard() {
    configurations = listOf(shade)

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*:.*"))
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "OSGI-INF/**", "*.profile", "module-info.class", "ant_tasks/**")

    mergeServiceFiles()
}

val sourcesJar by tasks.existing

val shadowJar by tasks.existing(ShadowJar::class) {
    configureStandard()

    val prefix = "paper.libs"
    listOf(
        "com.github.salomonbrys.kotson",
        "com.google.errorprone.annotations",
        "com.google.gson",
        "dev.denwav.hypo",
        "io.sigpipe.jbsdiff",
        "me.jamiemansfield",
        "net.fabricmc",
        "org.apache.commons",
        "org.apache.felix",
        "org.apache.http",
        "org.cadixdev",
        "org.eclipse",
        "org.jgrapht",
        "org.jheaps",
        "org.objectweb.asm",
        "org.osgi",
        "org.tukaani.xz",
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
            artifact(shadowJar) {
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
            val url = if (isSnapshot) {
                "https://papermc.io/repo/repository/maven-snapshots/"
            } else {
                "https://papermc.io/repo/repository/maven-releases/"
            }
            maven(url) {
                credentials(PasswordCredentials::class)
                name = "paper"
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
    val baseName = project.group.toString() + "." + project.name.substringAfter('-')

    groupId = baseName
    artifactId = "$baseName.gradle.plugin"
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
            id.set("DenWav")
            name.set("Kyle Wood")
            email.set("kyle@denwav.dev")
            url.set("https://github.com/DenWav")
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
