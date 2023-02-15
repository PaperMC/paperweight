import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.github.johnrengelman.shadow")
    id("com.gradle.plugin-publish")
}

fun version(): String = version.toString()
val noRelocate = project.hasProperty("disable-relocation")
if (noRelocate) {
    if (version().contains("-SNAPSHOT")) {
        version = version().substringBefore("-SNAPSHOT") + "-NO-RELOCATE-SNAPSHOT"
    } else {
        version = version() + "-NO-RELOCATE"
    }
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

val sourcesJar by tasks.existing(AbstractArchiveTask::class) {
    from(
        zipTree(project(":paperweight-lib").tasks
            .named("sourcesJar", AbstractArchiveTask::class)
            .flatMap { it.archiveFile })
    ) {
        exclude("META-INF/**")
    }
}

val prefix = project.name.substringAfter("paperweight-")

gradlePlugin {
    website.set("https://github.com/PaperMC/paperweight")
    vcsUrl.set("https://github.com/PaperMC/paperweight")
    plugins.create("paperweight-$prefix") {
        id = "io.papermc.paperweight." + prefix
        displayName = "paperweight $prefix"
        description = "paperweight $prefix"
        tags.set(listOf("paper", "minecraft"))
    }
}

val shadowJar by tasks.existing(ShadowJar::class) {
    archiveClassifier.set(null as String?)
    configureStandard()

    if (noRelocate) {
        return@existing
    }

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
        "org.slf4j",
        "codechicken.diffpatch",
        "codechicken.repack"
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}

publishing {
    repositories {
        maven("https://repo.papermc.io/repository/maven-snapshots/") {
            credentials(PasswordCredentials::class)
            name = "paper"
        }
    }

    publications {
        withType(MavenPublication::class).configureEach {
            pom {
                pomConfig()
            }
        }
    }
}

fun MavenPom.pomConfig() {
    val repoPath = "PaperMC/paperweight"
    val repoUrl = "https://github.com/$repoPath"

    name.set("paperweight")
    description.set("Gradle plugin for the PaperMC project")
    url.set(repoUrl)
    inceptionYear.set("2020")

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
