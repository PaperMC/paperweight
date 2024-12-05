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

import io.papermc.paperweight.extension.PaperweightServerExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.ide.idea.model.IdeaModel

fun Project.setupServerProject(
    parent: Project,
    remappedJar: Provider<*>,
    remappedJarSources: Any,
    mcDevSourceDir: Path,
    libsFile: Any,
    packagesToFix: Provider<List<String>?>,
    relocatedReobfMappings: TaskProvider<GenerateRelocatedReobfMappings>,
    serverJar: TaskProvider<Zip>
): ServerTasks? {
    if (!projectDir.exists()) {
        return null
    }

    plugins.apply("java")

    val serverExt = extensions.create<PaperweightServerExtension>(PAPERWEIGHT_EXTENSION, objects)
    relocatedReobfMappings {
        craftBukkitPackageVersion.set(serverExt.craftBukkitPackageVersion)
    }

    exportRuntimeClasspathTo(parent)

    @Suppress("UNUSED_VARIABLE", "KotlinRedundantDiagnosticSuppress")
    val filterPatchedFiles by tasks.registering<FilterPatchedFiles> {
        inputSrcDir.set(file("src/main/java"))
        inputResourcesDir.set(file("src/main/resources"))
        vanillaJar.set(
            // unlink dependency on upstream clone task for patcher (hack); it's implicitly handled when we get upstream data
            parent.layout.file(parent.files(remappedJar).elements.map { it.single().asFile })
        )
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

                add(create(parent.files(filterPatchedFiles.flatMap { it.outputJar })))
            }
        }
    }

    configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
        extendsFrom(vanillaServer)
        withDependencies {
            dependencies {
                val libs = libsFile.convertToPathOrNull()
                if (libs != null && libs.exists()) {
                    libs.forEachLine { line ->
                        add(create(line) {
                            isTransitive = false
                        })
                    }
                }
            }
        }
    }

    addMcDevSourcesRoot(mcDevSourceDir)

    return createBuildTasks(parent, serverExt, serverJar, vanillaServer, packagesToFix, relocatedReobfMappings)
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
    serverExt: PaperweightServerExtension,
    serverJar: TaskProvider<Zip>,
    vanillaServer: Configuration,
    packagesToFix: Provider<List<String>?>,
    relocatedReobfMappings: TaskProvider<GenerateRelocatedReobfMappings>
): ServerTasks {
    serverJar {
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        archiveExtension.set("jar")
        archiveClassifier.set("mojang-mapped")
        from(zipTree(tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { it.asFile }))
        from(vanillaServer.elements.map { it.map { f -> zipTree(f.asFile) } }) {
            exclude("META-INF/MANIFEST.MF")
        }
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

    val fixJarForReobf by tasks.registering<FixJarForReobf> {
        inputJar.set(serverJar.flatMap { it.archiveFile })
        packagesToProcess.set(packagesToFix)
    }

    val includeMappings by tasks.registering<IncludeMappings> {
        inputJar.set(fixJarForReobf.flatMap { it.outputJar })
        mappings.set(relocatedReobfMappings.flatMap { it.outputMappings })
        mappingsDest.set("META-INF/mappings/reobf.tiny")
    }

    // We only need to manually relocate references to CB class names in string constants, the actual relocation is handled by the mappings
    val relocateConstants by tasks.registering<RelocateClassNameConstants> {
        inputJar.set(includeMappings.flatMap { it.outputJar })
    }
    afterEvaluate {
        relocateConstants {
            relocate("org.bukkit.craftbukkit", "org.bukkit.craftbukkit.${serverExt.craftBukkitPackageVersion.get()}") {
                // This is not actually needed as there are no string constant references to Main
                // exclude("org.bukkit.craftbukkit.Main*")
            }
        }
    }

    val reobfJar by tasks.registering<RemapJar> {
        group = "paperweight"
        description = "Re-obfuscate the built jar to obf mappings"

        inputJar.set(relocateConstants.flatMap { it.outputJar })

        mappingsFile.set(relocatedReobfMappings.flatMap { it.outputMappings })

        fromNamespace.set(DEOBF_NAMESPACE)
        toNamespace.set(SPIGOT_NAMESPACE)

        remapper.from(parent.configurations.named(REMAPPER_CONFIG))
        remapperArgs.set(TinyRemapper.minecraftRemapArgs)

        outputJar.set(layout.buildDirectory.map { it.dir("libs").file("${project.name}-${project.version}-reobf.jar") })
    }

    return ServerTasks(includeMappings, reobfJar)
}

data class ServerTasks(
    val includeMappings: TaskProvider<IncludeMappings>,
    val reobfJar: TaskProvider<RemapJar>,
)

private fun Project.addMcDevSourcesRoot(mcDevSourceDir: Path) {
    plugins.apply("idea")

    val dir = mcDevSourceDir.toFile()

    extensions.getByType<JavaPluginExtension>().sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
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
