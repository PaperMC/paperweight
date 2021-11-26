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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.kotlin.dsl.*

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

    @get:Classpath
    abstract val libraryArtifacts: Property<Configuration>

    @get:InputFile
    abstract val serverLibrariesList: RegularFileProperty

    @get:Classpath
    abstract val vanillaBundlerJar: RegularFileProperty

    private fun createVersionArtifactContainer(): NamedDomainObjectContainer<VersionArtifact> =
        objects.domainObjectContainer(VersionArtifact::class) { objects.newInstance(it) }

    override fun run(rootDir: Path) {
        paperclip.singleFile.toPath().openZip().use { zip ->
            zip.getPath("/").copyRecursivelyTo(rootDir)
        }

        val versions = handleVersions(rootDir)
        val libraries = handleServerDependencies(rootDir)

        val versionsFile = rootDir.resolve("META-INF/versions.list").also { it.parent.createDirectories() }
        val librariesFile = rootDir.resolve("META-INF/libraries.list").also { it.parent.createDirectories() }

        versionsFile.bufferedWriter().use { writer ->
            for (v in versions.sortedBy { it.id }) {
                writer.append(v.toString())
                writer.newLine()
            }
        }
        librariesFile.bufferedWriter().use { writer ->
            for (l in libraries.sortedBy { it.id }) {
                writer.append(l.toString())
                writer.newLine()
            }
        }

        rootDir.resolve("META-INF/main-class").writeText(mainClass.get())

        // copy version.json file
        vanillaBundlerJar.path.openZip().use { fs ->
            fs.getPath("/version.json").copyTo(rootDir.resolve("version.json"))
        }
    }

    private fun handleServerDependencies(rootDir: Path): List<FileEntry<ModuleId>> {
        val libraries = mutableListOf<FileEntry<ModuleId>>()
        val changedLibraries = mutableListOf<LibraryChange>()

        val serverLibraryEntries = FileEntry.parse(serverLibrariesList.path, ModuleId.Companion::parse)

        val outputDir = rootDir.resolve("META-INF/libraries")

        val dependencies = collectDependencies()
        for (dep in dependencies) {
            val serverLibrary = serverLibraryEntries.firstOrNull { it.id.group == dep.module.group && it.id.name == dep.module.name }
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
        rootDir.resolve("library-changes.json").bufferedWriter().use { writer ->
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
            outputFile.parent.createDirectories()
            inputFile.copyTo(outputFile)

            FileEntry(inputFile.sha256asHex(), id, versionPath)
        }
    }

    private fun collectDependencies(): Set<ResolvedArtifactResult> {
        return libraryArtifacts.get().incoming.artifacts.artifacts.filterTo(HashSet()) {
            val id = it.id.componentIdentifier
            id is ModuleComponentIdentifier || id is ProjectComponentIdentifier
        }
    }

    private fun ResolvedArtifactResult.copyTo(path: Path): Path {
        path.parent.createDirectories()
        return file.toPath().copyTo(path, overwrite = true)
    }

    private val ResolvedArtifactResult.module: ModuleId
        get() {
            return when (val ident = id.componentIdentifier) {
                is ModuleComponentIdentifier -> ModuleId.fromIdentifier(ident)
                is ProjectComponentIdentifier -> {
                    val capability = variant.capabilities.single()
                    val version = capability.version ?: throw PaperweightException("Unknown version for ${capability.group}:${capability.name}")
                    ModuleId(capability.group, capability.name, version)
                }
                else -> throw PaperweightException("Unknown artifact result type: ${ident::class.java.name}")
            }
        }

    data class LibraryChange(
        val inputId: ModuleId,
        val inputPath: String,
        val outputId: ModuleId,
        val outputPath: String
    )

    data class FileEntry<T>(
        val hash: String,
        val id: T,
        val path: String
    ) {
        override fun toString(): String {
            return "$hash\t$id\t$path"
        }

        companion object {
            fun <T> parse(file: Path, transform: (String) -> T): List<FileEntry<T>> {
                return file.readLines().mapNotNull { line ->
                    if (line.isBlank() || line.startsWith("#")) {
                        return@mapNotNull null
                    }

                    val (hash, id, path) = line.split("\t")
                    FileEntry(hash, transform(id), path)
                }
            }
        }
    }

    data class ModuleId(val group: String, val name: String, val version: String) : Comparable<ModuleId> {
        fun toPath(): String {
            val fileName = "$name-$version.jar"
            return "${group.replace('.', '/')}/$name/$version/$fileName"
        }

        override fun compareTo(other: ModuleId): Int {
            return comparator.compare(this, other)
        }

        override fun toString(): String {
            return "$group:$name:$version"
        }

        companion object {
            private val comparator = compareBy<ModuleId>({ it.group }, { it.name }, { it.version })

            fun parse(text: String): ModuleId {
                val (group, name, version) = text.split(":")
                return ModuleId(group, name, version)
            }

            fun fromIdentifier(id: ModuleComponentIdentifier): ModuleId {
                return ModuleId(id.group, id.module, id.version)
            }
        }
    }
}
