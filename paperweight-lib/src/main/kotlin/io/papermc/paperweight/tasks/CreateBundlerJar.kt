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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*

@CacheableTask
abstract class CreateBundlerJar : ZippedTask() {

    interface VersionArtifact {
        @get:Input
        val name: String

        @get:Input
        val id: Property<String>

        @get:Classpath
        val file: RegularFileProperty
    }

    @get:Classpath
    abstract val paperclip: ConfigurableFileCollection

    @get:Input
    abstract val mainClass: Property<String>

    @get:Nested
    val versionArtifacts: NamedDomainObjectContainer<VersionArtifact> = createVersionArtifactContainer()

    @get:Nested
    @get:Optional
    abstract val libraryArtifacts: ListProperty<Artifact>

    // Gradle wants us to split the file inputs from the metadata inputs, but then we would lose association.
    // So we include the file input (not properly tracked as produced by the configuration) in Artifact, but also
    // depend on the configuration normally without meta to ensure the file's dependencies run.
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryArtifactsFiles: ConfigurableFileCollection

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val serverLibrariesList: RegularFileProperty

    @get:Classpath
    abstract val vanillaBundlerJar: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val extraManifestMainAttributes: MapProperty<String, String>

    @get:OutputFile
    abstract val libraryChangesJson: RegularFileProperty

    private fun createVersionArtifactContainer(): NamedDomainObjectContainer<VersionArtifact> =
        objects.domainObjectContainer(VersionArtifact::class) { objects.newInstance(it) }

    override fun init() {
        super.init()

        libraryChangesJson.convention(defaultOutput("$name-library-changes", "json"))
    }

    override fun run(rootDir: Path) {
        paperclip.singleFile.toPath().openZip().use { zip ->
            zip.getPath("/").copyRecursivelyTo(rootDir)
        }

        val versions = handleVersions(rootDir)
        val libraries = handleServerDependencies(rootDir)

        val versionsFile = rootDir.resolve(FileEntry.VERSIONS_LIST).also { it.parent.createDirectories() }
        val librariesFile = rootDir.resolve(FileEntry.LIBRARIES_LIST).also { it.parent.createDirectories() }

        versionsFile.bufferedWriter().use { writer ->
            for (v in versions.sortedBy { it.id }) {
                writer.append(v.toString()).append('\n')
            }
        }
        librariesFile.bufferedWriter().use { writer ->
            for (l in libraries.sortedBy { it.id }) {
                writer.append(l.toString()).append('\n')
            }
        }

        if (extraManifestMainAttributes.isPresent) {
            modifyManifest(rootDir.resolve("META-INF/MANIFEST.MF")) {
                extraManifestMainAttributes.get().forEach { (k, v) -> mainAttributes.putValue(k, v) }
            }
        }

        rootDir.resolve("META-INF/main-class").writeText(mainClass.get())

        // copy version.json file
        vanillaBundlerJar.path.openZip().use { fs ->
            fs.getPath("/").resolve(FileEntry.VERSION_JSON).copyTo(rootDir.resolve("version.json"))
        }
    }

    private fun handleServerDependencies(rootDir: Path): List<FileEntry<ModuleId>> {
        val libraries = mutableListOf<FileEntry<ModuleId>>()
        val changedLibraries = mutableListOf<LibraryChange>()

        val serverLibraryEntries = FileEntry.parse(serverLibrariesList.path, ModuleId::parse)

        val outputDir = rootDir.resolve("META-INF/libraries")

        for (dep in libraryArtifacts.get()) {
            val serverLibrary = serverLibraryEntries.firstOrNull {
                it.id.group == dep.module.group &&
                    it.id.name == dep.module.name &&
                    it.id.classifier == dep.module.classifier
            }
            if (serverLibrary != null) {
                if (serverLibrary.id.version == dep.module.version) {
                    // nothing to do
                    libraries += serverLibrary

                    dep.copyTo(outputDir.resolve(dep.module.toPath()))
                } else {
                    // we have a different version of this library
                    val newId = dep.module
                    val newPath = newId.toPath()
                    changedLibraries += LibraryChange(serverLibrary.id, serverLibrary.path, newId, newPath)

                    val jarFile = dep.copyTo(outputDir.resolve(newPath))

                    libraries += FileEntry(jarFile.sha256asHex(), newId, newPath)
                }
            } else {
                // New dependency
                val id = dep.module
                val path = id.toPath()
                val jarFile = dep.copyTo(outputDir.resolve(path))

                libraries += FileEntry(jarFile.sha256asHex(), id, path)
            }
        }

        // This file will be used to check library changes in the generatePaperclipPatches step
        ensureParentExists(libraryChangesJson)
        libraryChangesJson.path.bufferedWriter().use { writer ->
            gson.toJson(changedLibraries, writer)
        }

        return libraries
    }

    private fun handleVersions(rootDir: Path): List<FileEntry<String>> {
        val outputDir = rootDir.resolve("META-INF/versions")

        return versionArtifacts.map { versionArtifact ->
            val id = versionArtifact.id.get()
            val versionPath = "$id/${versionArtifact.name}-$id.jar"

            val inputFile = versionArtifact.file.path

            val outputFile = outputDir.resolve(versionPath)
            ensureParentExists(outputFile)
            inputFile.copyTo(outputFile)

            FileEntry(inputFile.sha256asHex(), id, versionPath)
        }
    }

    abstract class Artifact {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val path: RegularFileProperty

        @get:Input
        abstract val id: Property<ComponentArtifactIdentifier>

        @get:Input
        abstract val variant: Property<ResolvedVariantResult>

        fun copyTo(path: Path): Path {
            ensureParentExists(path)
            return this.path.path.copyTo(path, overwrite = true)
        }

        @get:Internal
        val module: ModuleId
            get() {
                return when (val ident = id.get().componentIdentifier) {
                    is ModuleComponentIdentifier -> ModuleId.fromIdentifier(id.get())
                    is ProjectComponentIdentifier -> {
                        val mainCap = variant.get().attributes.getAttribute(mainCapabilityAttribute)
                        if (mainCap != null) {
                            ModuleId.parse(mainCap)
                        } else {
                            val capability = variant.get().capabilities.first()
                            val version = capability.version ?: throw PaperweightException("Unknown version for ${capability.group}:${capability.name}")
                            ModuleId(capability.group, capability.name, version)
                        }
                    }

                    else -> throw PaperweightException("Unknown artifact result type: ${ident::class.java.name}")
                }
            }
    }
}
