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

package io.papermc.paperweight.taskcontainers

import io.papermc.paperweight.extension.RelocationExtension
import io.papermc.paperweight.taskcontainers.BundlerJarTasks.Companion.registerVersionArtifact
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
class DevBundleTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
) {
    val serverBundlerForDevBundle by tasks.registering<CreateBundlerJar> {
        paperclip.from(project.configurations.named(PAPERCLIP_CONFIG))
    }

    val paperclipForDevBundle by tasks.registering<CreatePaperclipJar> {
        bundlerJar.set(serverBundlerForDevBundle.flatMap { it.outputZip })
        libraryChangesJson.set(serverBundlerForDevBundle.flatMap { it.libraryChangesJson })
    }

    val generateDevelopmentBundle by tasks.registering<GenerateDevBundle> {
        group = "paperweight"

        remapperConfig.set(project.configurations.named(REMAPPER_CONFIG))
        decompilerConfig.set(project.configurations.named(DECOMPILER_CONFIG))

        devBundleFile.set(project.layout.buildDirectory.file("libs/paperweight-development-bundle-${project.version}.zip"))

        ignoreUnsupportedEnvironment.set(project.providers.gradleProperty(GenerateDevBundle.unsupportedEnvironmentPropName).map { it.toBoolean() })
    }

    fun configure(
        serverProj: Project,
        bundlerJarName: String,
        mainClassName: Property<String>,
        minecraftVer: Provider<String>,
        decompileJar: Provider<Path>,
        serverLibrariesTxt: Provider<Path>,
        serverLibrariesListFile: Provider<Path>,
        vanillaBundlerJarFile: Provider<Path>,
        accessTransformFile: Provider<Path>,
        versionJsonFile: Provider<RegularFile>,
        devBundleConfiguration: GenerateDevBundle.() -> Unit
    ) {
        serverBundlerForDevBundle {
            mainClass.set(mainClassName)
            serverLibrariesList.pathProvider(serverLibrariesListFile)
            vanillaBundlerJar.pathProvider(vanillaBundlerJarFile)
            versionArtifacts {
                registerVersionArtifact(
                    bundlerJarName,
                    versionJsonFile,
                    serverProj.tasks.named<FixJarForReobf>("fixJarForReobf").flatMap { it.inputJar }
                )
            }
        }

        paperclipForDevBundle {
            originalBundlerJar.pathProvider(vanillaBundlerJarFile)
            mcVersion.set(minecraftVer)
        }

        generateDevelopmentBundle {
            sourceDir.set(serverProj.layout.projectDirectory.dir("src/main/java"))
            minecraftVersion.set(minecraftVer)
            mojangMappedPaperclipFile.set(paperclipForDevBundle.flatMap { it.outputZip })
            vanillaServerLibraries.set(
                serverLibrariesTxt.map { txt ->
                    txt.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                }
            )

            serverVersion.set(serverProj.version.toString())
            serverCoordinates.set(GenerateDevBundle.createCoordinatesFor(serverProj))
            serverProject.set(serverProj)
            runtimeConfiguration.set(project.configurations.named(SERVER_RUNTIME_CLASSPATH))

            relocations.set(serverProj.extensions.getByType<RelocationExtension>().relocations.map { gson.toJson(it) })
            decompiledJar.pathProvider(decompileJar)
            atFile.pathProvider(accessTransformFile)

            devBundleConfiguration(this)
        }
    }

    fun configureAfterEvaluate() {
        generateDevelopmentBundle {
            remapperUrl.set(project.repositories.named<MavenArtifactRepository>(REMAPPER_REPO_NAME).map { it.url.toString() })
            decompilerUrl.set(project.repositories.named<MavenArtifactRepository>(DECOMPILER_REPO_NAME).map { it.url.toString() })
        }
    }
}
