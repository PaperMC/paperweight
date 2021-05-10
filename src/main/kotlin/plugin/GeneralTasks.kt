package io.papermc.paperweight.plugin

import com.github.salomonbrys.kotson.fromJson
import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.DownloadServerJar
import io.papermc.paperweight.tasks.FilterJar
import io.papermc.paperweight.util.BuildDataInfo
import io.papermc.paperweight.util.contents
import io.papermc.paperweight.util.download
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.registering
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer

@Suppress("MemberVisibilityCanBePrivate")
open class GeneralTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    extension: PaperweightExtension = project.ext,
    downloadService: Provider<DownloadService> = project.download,
) : InitialTasks(project) {

    val buildDataInfo: Provider<BuildDataInfo> = project.contents(extension.craftBukkit.buildDataInfo) {
        gson.fromJson(it)
    }

    val downloadServerJar by tasks.registering<DownloadServerJar> {
        downloadUrl.set(buildDataInfo.map { it.serverUrl })
        hash.set(buildDataInfo.map { it.minecraftHash })

        downloader.set(downloadService)
    }

    val filterVanillaJar by tasks.registering<FilterJar> {
        inputJar.set(downloadServerJar.flatMap { it.outputJar })
        includes.set(listOf("/*.class", "/net/minecraft/**", "/com/mojang/math/**"))
    }
}
