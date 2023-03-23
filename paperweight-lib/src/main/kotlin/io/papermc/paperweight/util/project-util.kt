/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.util

import io.papermc.paperweight.extension.RelocationExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.ide.idea.model.IdeaModel

fun Project.setupServerProject(
    parent: Project,
    remappedJar: Any,
    remappedJarSources: Any,
    mcDevSourceDir: Path,
    libsFile: Any,
    packagesToFix: Provider<List<String>?>,
    reobfMappings: Provider<RegularFile>,
): ServerTasks? {
    if (!projectDir.exists()) {
        return null
    }

    plugins.apply("java")

    extensions.create<RelocationExtension>(RELOCATION_EXTENSION, objects)

    exportRuntimeClasspathTo(parent)

    @Suppress("UNUSED_VARIABLE")
    val filterProjectDir by tasks.registering<FilterProjectDir> {
        inputSrcDir.set(file("src/main/java"))
        inputResourcesDir.set(file("src/main/resources"))
        vanillaJar.set(parent.file(remappedJar))
        outputJar.set(parent.layout.cache.resolve(FINAL_FILTERED_REMAPPED_JAR))
    }

    val vanillaServer: Configuration by configurations.creating {
        withDependencies {
            dependencies {
                // update mc-dev sources on dependency resolution
                makeMcDevSrc(
                    parent.layout.cache,
                    remappedJarSources.convertToPath(),
                    mcDevSourceDir,
                    layout.projectDirectory.path
                )

                add(create(parent.files(remappedJar)))
            }
        }
    }
//
//    @Suppress("UNUSED_VARIABLE")
//    val filteredServer: Configuration by configurations.creating {
//        withDependencies {
//            dependencies {
//                add(create(parent.files(filterProjectDir.flatMap { it.outputJar })))
//            }
//        }
//    }

    configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
        extendsFrom(vanillaServer)
        withDependencies {
            dependencies {
                val libs = libsFile.convertToPathOrNull()
                if (libs != null && libs.exists()) {
                    libs.forEachLine { line ->
                        add(create(line))
                    }
                }
            }
        }
    }

    addMcDevSourcesRoot(mcDevSourceDir)

    plugins.apply("com.github.johnrengelman.shadow")
    return createBuildTasks(parent, packagesToFix, reobfMappings)
}

private fun Project.exportRuntimeClasspathTo(parent: Project) {
    configurations.create(CONSUMABLE_RUNTIME_CLASSPATH) {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        extendsFrom(configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME))
    }
    parent.configurations.create(SERVER_RUNTIME_CLASSPATH) {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
    parent.dependencies {
        add(SERVER_RUNTIME_CLASSPATH, parent.dependencies.project(path, configuration = CONSUMABLE_RUNTIME_CLASSPATH))
    }
    afterEvaluate {
        val old = parent.repositories.toList()
        parent.repositories.clear()
        repositories.filterIsInstance<MavenArtifactRepository>().forEach {
            parent.repositories.maven(it.url) {
                name = "serverRuntimeClasspath repo ${it.url}"
                content { onlyForConfigurations(SERVER_RUNTIME_CLASSPATH) }
            }
        }
        parent.repositories.addAll(old)
    }
}

private fun Project.createBuildTasks(
    parent: Project,
    packagesToFix: Provider<List<String>?>,
    reobfMappings: Provider<RegularFile>
): ServerTasks {
    val shadowJar: TaskProvider<Jar> = tasks.named("shadowJar", Jar::class)

    val fixJarForReobf by tasks.registering<FixJarForReobf> {
        dependsOn(shadowJar)

        inputJar.set(shadowJar.flatMap { it.archiveFile })
        packagesToProcess.set(packagesToFix)
    }

    val reobfJar by tasks.registering<RemapJar> {
        group = "paperweight"
        description = "Re-obfuscate the built jar to obf mappings"

        inputJar.set(fixJarForReobf.flatMap { it.outputJar })

        mappingsFile.set(reobfMappings)

        fromNamespace.set(DEOBF_NAMESPACE)
        toNamespace.set(SPIGOT_NAMESPACE)

        remapper.from(parent.configurations.named(REMAPPER_CONFIG))
        remapperArgs.set(TinyRemapper.minecraftRemapArgs)

        outputJar.set(buildDir.resolve("libs/${project.name}-${project.version}-reobf.jar"))
    }

    return ServerTasks(fixJarForReobf, reobfJar)
}

data class ServerTasks(
    val fixJarForReobf: TaskProvider<FixJarForReobf>,
    val reobfJar: TaskProvider<RemapJar>,
)

private fun Project.addMcDevSourcesRoot(mcDevSourceDir: Path) {
    plugins.apply("idea")

    val dir = mcDevSourceDir.toFile()

    the<JavaPluginExtension>().sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
        java {
            srcDirs(dir)
            val pathString = dir.invariantSeparatorsPath
            exclude {
                it.file.absoluteFile.invariantSeparatorsPath.contains(pathString)
            }
        }
    }

    extensions.configure<IdeaModel> {
        module {
            generatedSourceDirs.add(dir)
        }
    }
}
