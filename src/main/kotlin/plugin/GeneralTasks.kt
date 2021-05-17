/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

package io.papermc.paperweight.plugin

import com.github.salomonbrys.kotson.fromJson
import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.DownloadServerJar
import io.papermc.paperweight.tasks.FilterJar
import io.papermc.paperweight.tasks.InitSubmodules
import io.papermc.paperweight.util.BuildDataInfo
import io.papermc.paperweight.util.contents
import io.papermc.paperweight.util.download
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.registering
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class GeneralTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    extension: PaperweightExtension = project.ext,
    downloadService: Provider<DownloadService> = project.download,
) : InitialTasks(project) {

    // Configuration won't necessarily always run, so do it as the first task when it's needed as well
    val initSubmodules by tasks.registering<InitSubmodules>()

    val buildDataInfo: Provider<BuildDataInfo> = project.contents(extension.craftBukkit.buildDataInfo) {
        gson.fromJson(it)
    }

    val downloadServerJar by tasks.registering<DownloadServerJar> {
        dependsOn(initSubmodules)
        downloadUrl.set(buildDataInfo.map { it.serverUrl })
        hash.set(buildDataInfo.map { it.minecraftHash })

        downloader.set(downloadService)
    }

    val filterVanillaJar by tasks.registering<FilterJar> {
        inputJar.set(downloadServerJar.flatMap { it.outputJar })
        includes.set(listOf("/*.class", "/net/minecraft/**", "/com/mojang/math/**"))
    }
}
