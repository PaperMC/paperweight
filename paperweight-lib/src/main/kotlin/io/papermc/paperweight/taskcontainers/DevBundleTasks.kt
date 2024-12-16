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

import io.papermc.paperweight.taskcontainers.BundlerJarTasks.Companion.registerVersionArtifact
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.Directory
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

        devBundleFile.set(project.layout.buildDirectory.file("libs/paperweight-development-bundle-${project.version}.zip"))
    }

    fun configure(
        bundlerJarName: String,
        mainClassName: Property<String>,
        minecraftVer: Provider<String>,
        vanillaJava: Provider<Directory>,
        serverLibrariesListFile: Provider<Path>,
        vanillaBundlerJarFile: Provider<Path>,
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
                    project.tasks.named<IncludeMappings>("includeMappings").flatMap { it.outputJar }
                )
            }
        }

        paperclipForDevBundle {
            originalBundlerJar.pathProvider(vanillaBundlerJarFile)
            mcVersion.set(minecraftVer)
        }

        generateDevelopmentBundle {
            mainJavaDir.set(project.layout.projectDirectory.dir("src/main/java"))
            vanillaJavaDir.set(vanillaJava)
            patchedJavaDir.set(project.layout.projectDirectory.dir("src/vanilla/java"))

            minecraftVersion.set(minecraftVer)
            mojangMappedPaperclipFile.set(paperclipForDevBundle.flatMap { it.outputZip })

            devBundleConfiguration(this)
        }
    }

    fun configureAfterEvaluate() {
        generateDevelopmentBundle {
            pluginRemapperUrl.set(project.repositories.named<MavenArtifactRepository>(REMAPPER_REPO_NAME).map { it.url.toString() })
            macheUrl.set(project.repositories.named<MavenArtifactRepository>(MACHE_REPO_NAME).map { it.url.toString() })
            macheDep.set(determineArtifactCoordinates(project.configurations.getByName(MACHE_CONFIG)).single())
            pluginRemapperDep.set(determineArtifactCoordinates(project.configurations.getByName(REMAPPER_CONFIG)))
        }
    }
}
