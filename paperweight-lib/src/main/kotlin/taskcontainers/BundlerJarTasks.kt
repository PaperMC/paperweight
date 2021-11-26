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

import com.google.gson.JsonObject
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.*

class BundlerJarTasks(
    project: Project,
    private val bundlerJarName: Provider<String>,
    private val mainClassString: Provider<String>,
) {
    val createBundlerJar = project.createBundlerJarTask()
    val createReobfBundlerJar = project.createBundlerJarTask("reobf")

    fun configureBundlerTasks(
        extractFromBundler: TaskProvider<ExtractFromBundler>,
        downloadServerJar: TaskProvider<DownloadServerJar>,
        serverProject: Project,
        shadowJar: TaskProvider<out AbstractArchiveTask>,
        reobfJar: TaskProvider<RemapJar>,
    ) {
        createBundlerJar.configureWith(
            extractFromBundler,
            downloadServerJar,
            serverProject,
            shadowJar.flatMap { it.archiveFile },
        )
        createReobfBundlerJar.configureWith(
            extractFromBundler,
            downloadServerJar,
            serverProject,
            reobfJar.flatMap { it.outputJar },
        )
    }

    private fun Project.createBundlerJarTask(
        classifier: String = "",
    ): TaskProvider<CreateBundlerJar> {
        val taskName = "create${classifier.capitalize()}BundlerJar"
        return tasks.register<CreateBundlerJar>(taskName) {
            group = "paperweight"
            paperclip.from(configurations.named(PAPERCLIP_CONFIG))
            mainClass.set(mainClassString)

            val jarName = listOfNotNull(
                project.name,
                "bundler",
                classifier.takeIf { it.isNotBlank() },
                project.version,
            ).joinToString("-") + ".jar"
            outputZip.set(layout.buildDirectory.file("libs/$jarName"))
        }
    }

    private fun TaskProvider<CreateBundlerJar>.configureWith(
        extractFromBundler: TaskProvider<ExtractFromBundler>,
        downloadServerJar: TaskProvider<DownloadServerJar>,
        serverProject: Project,
        serverJar: Provider<RegularFile>,
    ) = this {
        libraryArtifacts.set(serverProject.configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME))
        serverLibrariesList.set(extractFromBundler.flatMap { it.serverLibrariesList })
        vanillaBundlerJar.set(downloadServerJar.flatMap { it.outputJar })

        versionArtifacts {
            register(bundlerJarName.get()) {
                id.set(extractFromBundler.flatMap { it.versionJson }.map { gson.fromJson<JsonObject>(it)["id"].asString })
                file.set(serverJar)
            }
        }
    }
}
