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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.taskcontainers.PaperclipTasks.Companion.registerVersionArtifact
import io.papermc.paperweight.core.util.coreExt
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
class DevBundleTasks(
    project: Project,
    private val coreTasks: CoreTasks,
    tasks: TaskContainer = project.tasks,
) {
    val serverBundlerForDevBundle by tasks.registering<CreateBundlerJar> {
        mainClass.set(project.coreExt.mainClass)
        paperclip.from(project.configurations.named(PAPERCLIP_CONFIG))
        serverLibrariesList.set(coreTasks.extractFromBundler.flatMap { it.serverLibrariesList })
        vanillaBundlerJar.set(coreTasks.downloadServerJar.flatMap { it.outputJar })
    }

    val paperclipForDevBundle by tasks.registering<CreatePaperclipJar> {
        bundlerJar.set(serverBundlerForDevBundle.flatMap { it.outputZip })
        libraryChangesJson.set(serverBundlerForDevBundle.flatMap { it.libraryChangesJson })
        originalBundlerJar.set(coreTasks.downloadServerJar.flatMap { it.outputJar })
        mcVersion.set(project.coreExt.minecraftVersion)
    }

    val generateDevelopmentBundle by tasks.registering<GenerateDevBundle> {
        group = "bundling"

        devBundleFile.set(project.layout.buildDirectory.file("libs/paperweight-development-bundle-${project.version}.zip"))
        sourceDirectories.from(
            project.extensions.getByType(JavaPluginExtension::class).sourceSets
                .getByName("main")
                .allJava
        )
        vanillaJavaDir.set(coreTasks.setupMacheSourcesForDevBundle.flatMap { it.outputDir })

        minecraftVersion.set(project.coreExt.minecraftVersion)
        mojangMappedPaperclipFile.set(paperclipForDevBundle.flatMap { it.outputZip })
        reobfMappingsFile.set(
            project.coreExt.spigot.enabled.flatMap<RegularFile?> {
                if (it) {
                    coreTasks.generateRelocatedReobfMappings.flatMap { it.outputMappings }
                } else {
                    providers.provider { null }
                }
            }
        )
    }

    fun configureAfterEvaluate(serverJar: Provider<RegularFile>) {
        serverBundlerForDevBundle {
            versionArtifacts {
                registerVersionArtifact(
                    project.coreExt.bundlerJarName.get(),
                    coreTasks.extractFromBundler.flatMap { it.versionJson },
                    serverJar,
                )
            }
        }
        generateDevelopmentBundle {
            macheUrl.set(project.repositories.named<MavenArtifactRepository>(MACHE_REPO_NAME).map { it.url.toString() })
            macheDep.set(determineArtifactCoordinates(project.configurations.getByName(MACHE_CONFIG)).single())
        }
    }
}
