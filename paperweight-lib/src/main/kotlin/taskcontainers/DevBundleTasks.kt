/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class DevBundleTasks(
    private val project: Project,
    tasks: TaskContainer = project.tasks,
) {
    val serverBundlerForDevBundle by tasks.registering<CreateBundlerJar> {
        paperclip.from(project.configurations.named(PAPERCLIP_CONFIG))
    }

    val generateDevelopmentBundle by tasks.registering<GenerateDevBundle> {
        group = "paperweight"

        remapperConfig.set(project.configurations.named(REMAPPER_CONFIG))
        decompilerConfig.set(project.configurations.named(DECOMPILER_CONFIG))

        devBundleFile.set(project.layout.buildDirectory.file("libs/paperweight-development-bundle-${project.version}.zip"))
    }

    fun configure(
        bundlerJarName: String,
        mainClassName: Property<String>,
        serverProj: Provider<Project>,
        minecraftVer: Provider<String>,
        decompileJar: Provider<Path?>,
        serverLibrariesTxt: Provider<Path?>,
        serverLibrariesListFile: Provider<Path?>,
        vanillaBundlerJarFile: Provider<Path?>,
        accessTransformFile: Provider<Path?>,
        versionJsonFile: Provider<Path>,
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
                    serverProj.flatMap { proj ->
                        proj.tasks.named<FixJarForReobf>("fixJarForReobf").flatMap { it.inputJar }
                    }
                )
            }
        }

        generateDevelopmentBundle {
            sourceDir.set(serverProj.map { it.layout.projectDirectory.dir("src/main/java") })
            minecraftVersion.set(minecraftVer)
            mojangMappedPaperclipFile.set(serverBundlerForDevBundle.flatMap { it.outputZip })
            vanillaServerLibraries.set(
                serverLibrariesTxt.map { txt ->
                    txt.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                }
            )
            serverProject.set(serverProj)
            relocations.set(serverProj.flatMap { proj -> proj.the<RelocationExtension>().relocations.map { gson.toJson(it) } })
            decompiledJar.pathProvider(decompileJar)
            atFile.pathProvider(accessTransformFile)

            devBundleConfiguration(this)
        }

        project.afterEvaluate {
            configureAfterEvaluate()
        }
    }

    private fun configureAfterEvaluate() {
        generateDevelopmentBundle {
            remapperUrl.set(project.repositories.named<MavenArtifactRepository>(REMAPPER_REPO_NAME).map { it.url.toString() })
            decompilerUrl.set(project.repositories.named<MavenArtifactRepository>(DECOMPILER_REPO_NAME).map { it.url.toString() })
        }
    }
}
