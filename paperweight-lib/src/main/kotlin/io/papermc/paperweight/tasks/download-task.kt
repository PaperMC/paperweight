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
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.ProjectInternal.DetachedResolver
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.w3c.dom.Document
import org.w3c.dom.Element

// Not cached since these are Mojang's files
abstract class DownloadTask : DefaultTask() {

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

@DisableCachingByDefault(because = "Gradle handles caching")
abstract class DownloadSpigotDependencies : BaseTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mcLibrariesFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputSourcesDir: DirectoryProperty

    @get:Internal
    abstract val downloader: Property<DownloadService>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Inject
    abstract val dependencyFactory: DependencyFactory

    private val detachedResolver: DetachedResolver = (project as ProjectInternal).newDetachedResolver()

    @TaskAction
    fun run() {
        val apiSetup = parsePom(apiPom.path)
        val serverSetup = parsePom(serverPom.path)
        val mcLibraries = mcLibrariesFile.path.readLines()

        val out = outputDir.path
        out.deleteRecursive()

        val outSources = outputSourcesDir.path
        outSources.deleteRecursive()

        val spigotRepos = mutableSetOf<String>()
        spigotRepos += apiSetup.repos
        spigotRepos += serverSetup.repos

        val artifacts = mutableSetOf<MavenArtifact>()
        artifacts += apiSetup.artifacts
        artifacts += serverSetup.artifacts

        val resolver = detachedResolver
        for (repo in spigotRepos) {
            resolver.repositories.maven(repo)
        }
        val deps = mutableListOf<Dependency>()
        for (artifact in artifacts) {
            val gav = artifact.gav.let {
                if (it == "com.google.guava:guava:32.1.2-jre") {
                    // https://github.com/google/guava/issues/6657
                    "com.google.guava:guava:32.1.3-jre"
                } else {
                    it
                }
            }
            deps.add(
                dependencyFactory.create(gav).also {
                    it.artifact {
                        artifact.classifier?.let { s -> classifier = s }
                        artifact.extension?.let { s -> extension = s }
                    }
                }
            )
        }

        val config = resolver.configurations.detachedConfiguration(*deps.toTypedArray())
        config.attributes {
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        }

        // The source variants don't have transitives
        val flatComponents = mutableSetOf<ComponentIdentifier>()

        for (artifact in config.incoming.artifacts.artifacts) {
            artifact.file.toPath().copyTo(outputDir.path.resolve(artifact.file.name).also { it.parent.createDirectories() }, true)
            flatComponents += artifact.id.componentIdentifier
        }

        val sourcesDeps = mutableListOf<Dependency>()
        for (component in flatComponents) {
            sourcesDeps.add(
                dependencyFactory.create(component.displayName).also {
                    it.artifact {
                        classifier = "sources"
                    }
                }
            )
        }
        val sourcesConfig = resolver.configurations.detachedConfiguration(*sourcesDeps.toTypedArray())
        sourcesConfig.attributes {
            // Mojang libs & Guava don't resolve metadata correctly, so we set the classifier below instead...

            // attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            // attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
            // attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            // attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))

            // Needed since we set the classifier instead of using above attributes
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        }
        val sourcesView = sourcesConfig.incoming.artifactView {
            componentFilter {
                mcLibraries.none { l -> l == it.displayName } &&
                    // This is only needed since we don't use variant-aware resolution properly
                    it.displayName != "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava"
            }
        }

        for (artifact in sourcesView.artifacts.artifacts) {
            artifact.file.toPath().copyTo(outputSourcesDir.path.resolve(artifact.file.name).also { it.parent.createDirectories() }, true)
        }
    }

    private fun parsePom(pomFile: Path): MavenSetup {
        val depList = arrayListOf<MavenArtifact>()
        // todo dum
        depList += MavenArtifact(
            "com.google.code.findbugs",
            "jsr305",
            "3.0.2"
        )
        depList += MavenArtifact(
            "org.apache.logging.log4j",
            "log4j-api",
            "2.17.0"
        )
        depList += MavenArtifact(
            "org.jetbrains",
            "annotations",
            "23.0.0"
        )
        val repoList = arrayListOf<String>()
        // Maven Central is implicit
        repoList += MAVEN_CENTRAL_URL

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = pomFile.inputStream().buffered().use { stream ->
            stream.buffered().use { buffered ->
                builder.parse(buffered)
            }
        }

        doc.documentElement.normalize()

        depList += doc.extractDependencies()
        repoList += doc.extractRepos()

        return MavenSetup(repos = repoList, artifacts = depList)
    }

    private fun Document.extractDependencies(): List<MavenArtifact> {
        val depList = arrayListOf<MavenArtifact>()
        val list = getElementsByTagName("dependencies")
        val node = list.item(0) as Element // Only want the first dependencies element
        val depNode = node.getElementsByTagName("dependency")
        for (j in 0 until depNode.length) {
            val dependency = depNode.item(j) as? Element ?: continue
            val artifact = getDependency(dependency) ?: continue
            depList += artifact
        }
        return depList
    }

    private fun Document.extractRepos(): List<String> {
        val repoList = arrayListOf<String>()
        val repos = getElementsByTagName("repositories")
        for (i in 0 until repos.length) {
            val node = repos.item(i) as? Element ?: continue
            val depNode = node.getElementsByTagName("repository")
            for (j in 0 until depNode.length) {
                val repo = depNode.item(j) as? Element ?: continue
                val repoUrl = repo.getElementsByTagName("url").item(0).textContent
                repoList += repoUrl
            }
        }
        return repoList
    }

    private fun getDependency(node: Element): MavenArtifact? {
        val scopeNode = node.getElementsByTagName("scope")
        val scope = if (scopeNode.length == 0) {
            "compile"
        } else {
            scopeNode.item(0).textContent
        }

        if (scope != "compile") {
            return null
        }

        val group = node.getElementsByTagName("groupId").item(0).textContent
        val artifact = node.getElementsByTagName("artifactId").item(0).textContent
        val version = node.getElementsByTagName("version").item(0).textContent

        if (version.contains("\${")) {
            // Don't handle complicated things
            // We don't need to (for now anyways)
            return null
        }

        return MavenArtifact(
            group = group,
            artifact = artifact,
            version = version
        )
    }
}

data class MavenSetup(
    val repos: List<String>,
    val artifacts: List<MavenArtifact>
)

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
