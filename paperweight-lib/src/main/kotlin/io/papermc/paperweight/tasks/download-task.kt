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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor

// Not cached since these are Mojang's files
abstract class DownloadTask : BaseTask() {

    @get:Input
    abstract val url: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Internal
    abstract val downloader: Property<DownloadService>

    @get:Nested
    @get:Optional
    abstract val expectedHash: Property<Hash>

    @TaskAction
    fun run() = downloader.get().download(url, outputFile, expectedHash.orNull)
}

@CacheableTask
abstract class CacheableDownloadTask : DownloadTask()

@CacheableTask
abstract class DownloadMcLibraries : BaseTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mcLibrariesFile: RegularFileProperty

    @get:Input
    abstract val repositories: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val downloader: Property<DownloadService>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val sources: Property<Boolean>

    override fun init() {
        super.init()
        sources.convention(false)
    }

    @TaskAction
    fun run() {
        downloadLibraries(
            downloader,
            workerExecutor,
            outputDir.path,
            repositories.get(),
            mcLibrariesFile.path.readLines(),
            sources.get()
        )
    }
}

@CacheableTask
abstract class DownloadPaperLibraries : BaseTask() {

    @get:Input
    abstract val paperDependencies: ListProperty<String>

    @get:Input
    abstract val repositories: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val downloader: Property<DownloadService>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val sources: Property<Boolean>

    override fun init() {
        super.init()
        sources.convention(false)
    }

    @TaskAction
    fun run() {
        downloadLibraries(
            downloader,
            workerExecutor,
            outputDir.path,
            repositories.get(),
            paperDependencies.get(),
            sources.get()
        )
    }
}

fun downloadLibraries(
    download: Provider<DownloadService>,
    workerExecutor: WorkerExecutor,
    targetDir: Path,
    repositories: List<String>,
    libraries: List<String>,
    sources: Boolean
): WorkQueue {
    val excludes = listOf(targetDir.fileSystem.getPathMatcher("glob:*.etag"))
    targetDir.deleteRecursive(excludes)
    if (!targetDir.exists()) {
        targetDir.createDirectories()
    }

    val queue = workerExecutor.noIsolation()

    for (lib in libraries) {
        if (sources) {
            queue.submit(DownloadSourcesToDirAction::class) {
                repos.set(repositories)
                artifact.set(lib)
                target.set(targetDir)
                downloader.set(download)
            }
        } else {
            queue.submit(DownloadWorker::class) {
                repos.set(repositories)
                artifact.set(lib)
                target.set(targetDir)
                downloadToDir.set(true)
                downloader.set(download)
            }
        }
    }

    return queue
}

interface DownloadParams : WorkParameters {
    val repos: ListProperty<String>
    val artifact: Property<String>
    val target: RegularFileProperty
    val downloadToDir: Property<Boolean>
    val downloader: Property<DownloadService>
}

abstract class DownloadWorker : WorkAction<DownloadParams> {

    override fun execute() {
        val target = parameters.target.path
        val artifact = MavenArtifact.parse(parameters.artifact.get())

        if (parameters.downloadToDir.get()) {
            artifact.downloadToDir(parameters.downloader.get(), target, parameters.repos.get())
        } else {
            artifact.downloadToFile(parameters.downloader.get(), target, parameters.repos.get())
        }
    }
}

abstract class DownloadSourcesToDirAction : WorkAction<DownloadSourcesToDirAction.Params> {

    interface Params : WorkParameters {
        val repos: ListProperty<String>
        val artifact: Property<String>
        val target: RegularFileProperty
        val downloader: Property<DownloadService>
    }

    override fun execute() {
        val sourceArtifact = MavenArtifact.parse(parameters.artifact.get())
            .copy(classifier = "sources")

        try {
            sourceArtifact.downloadToDir(
                parameters.downloader.get(),
                parameters.target.path,
                parameters.repos.get()
            )
        } catch (ignored: Exception) {
            // Ignore failures because not every artifact we attempt to download actually has sources
        }
    }
}
