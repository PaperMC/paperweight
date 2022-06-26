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
import io.papermc.paperweight.util.data.*
import io.sigpipe.jbsdiff.Diff
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.StringJoiner
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class CreatePaperclipJar : JavaLauncherZippedTask() {

    @get:Classpath
    abstract val originalBundlerJar: RegularFileProperty

    @get:Classpath
    abstract val bundlerJar: RegularFileProperty

    @get:Input
    abstract val mcVersion: Property<String>

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val libraryChangesJson: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx1G"))
    }

    override fun run(rootDir: Path) {
        // Vanilla's URL uses a SHA1 hash of the vanilla server jar
        val patchEntries: List<PatchEntry>
        bundlerJar.path.openZip().use { newBundlerFs ->
            originalBundlerJar.path.openZip().use { originalBundlerFs ->
                val originalBundlerRoot = originalBundlerFs.getPath("/")
                val newBundlerRoot = newBundlerFs.getPath("/")

                patchEntries = createPatches(rootDir, newBundlerRoot, originalBundlerRoot)
            }
        }

        rootDir.resolve(PatchEntry.PATCHES_LIST).bufferedWriter().use { writer ->
            for (entry in patchEntries) {
                writer.append(entry.toString()).append('\n')
            }
        }

        val digestSha1 = try {
            MessageDigest.getInstance("SHA1")
        } catch (e: NoSuchAlgorithmException) {
            throw PaperweightException("Could not create SHA1 digest", e)
        }

        val originalJar = originalBundlerJar.path
        val vanillaSha256Hash = originalJar.sha256asHex()
        val vanillaSha1Hash = toHex(originalJar.hashFile(digestSha1))
        val vanillaUrl = "https://piston-data.mojang.com/v1/objects/$vanillaSha1Hash/server.jar"
        val vanillaFileName = "mojang_${mcVersion.get()}.jar"

        val context = DownloadContext(vanillaSha256Hash, vanillaUrl, vanillaFileName)
        rootDir.resolve(DownloadContext.FILE).writeText(context.toString())
    }

    private fun createPatches(rootDir: Path, newBundlerRoot: Path, originalBundlerRoot: Path): List<PatchEntry> {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        val patchJobs = mutableListOf<PatchJob>()

        val originalVersions = FileEntry.parse(originalBundlerRoot.resolve(FileEntry.VERSIONS_LIST))
        val originalLibraries = FileEntry.parse(originalBundlerRoot.resolve(FileEntry.LIBRARIES_LIST), ModuleId::parse)

        // Copy all files, we will only replace the files which need to be patched
        newBundlerRoot.copyRecursivelyTo(rootDir)

        // We will generate patches for library versions which have changed, assuming the changes will be small
        val libraryChanges = gson.fromJson<List<LibraryChange>>(libraryChangesJson)

        val newVersions = FileEntry.parse(newBundlerRoot.resolve(FileEntry.VERSIONS_LIST))
        val newLibraries = FileEntry.parse(newBundlerRoot.resolve(FileEntry.LIBRARIES_LIST), ModuleId::parse)

        // First, create paperclip patches for any changed versions
        for (newVersion in newVersions) {
            // If there is no original version, then we have nothing to do
            val originalVersion = originalVersions.firstOrNull { it.id == newVersion.id } ?: continue
            // If the hashes match we'll be able to pull this file from the original jar
            if (newVersion.hash == originalVersion.hash) {
                EntryLocation.VERSION.removeEntry(rootDir, newVersion.path)
                continue
            }

            // Both jars have these versions, but they are different, so we need to create a patch
            patchJobs += queue.submitPatchJob(rootDir, originalBundlerRoot, newBundlerRoot, originalVersion, newVersion, EntryLocation.VERSION)
        }

        // Remove library jars we don't need
        for (newLibrary in newLibraries) {
            val originalLibrary = originalLibraries.firstOrNull { it.id == newLibrary.id } ?: continue
            if (newLibrary.path != originalLibrary.path && newLibrary.hash == originalLibrary.hash) {
                throw PaperweightException("Paperclip cannot currently handle non-patch libraries with new paths")
            }

            if (newLibrary.hash != originalLibrary.hash) {
                // Create patch for this library as well
                patchJobs += queue.submitPatchJob(rootDir, originalBundlerRoot, newBundlerRoot, originalLibrary, newLibrary, EntryLocation.LIBRARY)
            } else {
                // The original bundler contains the right file, we don't need ours
                EntryLocation.LIBRARY.removeEntry(rootDir, newLibrary.path)
            }
        }

        // Now check for any library changes
        for (libraryChange in libraryChanges) {
            val originalLibrary = originalLibraries.firstOrNull { it.id == libraryChange.inputId }
                ?: throw PaperweightException("Unmatched library change, original id: ${libraryChange.inputId}")
            val newLibrary = newLibraries.firstOrNull { it.id == libraryChange.outputId }
                ?: throw PaperweightException("Unmatched library change, new id: ${libraryChange.outputId}")

            patchJobs += queue.submitPatchJob(rootDir, originalBundlerRoot, newBundlerRoot, originalLibrary, newLibrary, EntryLocation.LIBRARY)
        }

        queue.await()

        // Find the patch files so we can hash them
        return patchJobs.map { job ->
            val patchLocation = job.entryLocation.resolve(rootDir)

            val patchHash = job.patchFile.sha256asHex()
            PatchEntry(
                job.entryLocation,
                job.originalEntry.hash,
                patchHash,
                job.newEntry.hash,
                job.originalEntry.path,
                job.patchFile.relativeTo(patchLocation).invariantSeparatorsPathString,
                job.newEntry.path
            )
        }
    }

    private fun WorkQueue.submitPatchJob(
        rootDir: Path,
        originalRoot: Path,
        newRoot: Path,
        originalEntry: FileEntry<*>,
        newEntry: FileEntry<*>,
        location: EntryLocation
    ): PatchJob {
        val outputFile = location.resolve(rootDir, newEntry.path)
        outputFile.deleteForcefully()
        val patchFile = outputFile.resolveSibling(Paths.get(originalEntry.path).name + ".patch")

        // The original files are in a zip file system, which can't be serialized as that is going outside the JVM
        // So we copy it out to a real file
        val originalFile = location.resolve(originalRoot, originalEntry.path)
        val tempOriginal = createTempFile()
        originalFile.copyTo(tempOriginal, overwrite = true)

        val newFile = location.resolve(newRoot, newEntry.path)
        val tempNew = createTempFile()
        newFile.copyTo(tempNew, overwrite = true)

        submit(PaperclipAction::class) {
            this.originalFile.set(tempOriginal)
            this.patchedFile.set(tempNew)
            this.outputFile.set(patchFile)
        }

        return PatchJob(originalEntry, newEntry, patchFile, location)
    }

    abstract class PaperclipAction : WorkAction<PaperclipParameters> {
        override fun execute() {
            val outputFile = parameters.outputFile.path
            val originalFile = parameters.originalFile.path
            val patchedFile = parameters.patchedFile.path

            // Read the files into memory
            val originalBytes = parameters.originalFile.path.readBytes()
            val patchedBytes = parameters.patchedFile.path.readBytes()

            try {
                outputFile.parent.createDirectories()
                outputFile.outputStream().use { patchOutput ->
                    Diff.diff(originalBytes, patchedBytes, patchOutput)
                }
            } catch (e: Exception) {
                throw PaperweightException("Error creating patch between $originalFile and $patchedFile", e)
            } finally {
                runCatching { originalFile.deleteForcefully() }
                runCatching { patchedFile.deleteForcefully() }
            }
        }
    }

    interface PaperclipParameters : WorkParameters {
        val originalFile: RegularFileProperty
        val patchedFile: RegularFileProperty
        val outputFile: RegularFileProperty
    }

    data class PatchJob(
        val originalEntry: FileEntry<*>,
        val newEntry: FileEntry<*>,
        val patchFile: Path,
        val entryLocation: EntryLocation
    )

    data class PatchEntry(
        val location: EntryLocation,
        val originalHash: String,
        val patchHash: String,
        val outputHash: String,
        val originalPath: String,
        val patchPath: String,
        val outputPath: String
    ) {
        override fun toString(): String {
            val joiner = StringJoiner("\t")
            joiner.add(location.value)
            joiner.add(originalHash)
            joiner.add(patchHash)
            joiner.add(outputHash)
            joiner.add(originalPath)
            joiner.add(patchPath)
            joiner.add(outputPath)
            return joiner.toString()
        }

        companion object {
            const val PATCHES_LIST = "META-INF/patches.list"
        }
    }

    enum class EntryLocation(val value: String) {
        VERSION("versions") {
            override fun resolve(dir: Path, path: String?): Path {
                val base = dir.resolve(FileEntry.VERSIONS_DIR)
                if (path == null) {
                    return base
                }
                return base.resolve(path)
            }
        },
        LIBRARY("libraries") {
            override fun resolve(dir: Path, path: String?): Path {
                val base = dir.resolve(FileEntry.LIBRARIES_DIR)
                if (path == null) {
                    return base
                }
                return base.resolve(path)
            }
        };

        abstract fun resolve(dir: Path, path: String? = null): Path

        fun removeEntry(dir: Path, path: String) {
            val entryDir = resolve(dir)

            var file = entryDir.resolve(path)
            while (file.exists() && file != entryDir) {
                file.deleteForcefully()
                file = file.parent

                if (file.listDirectoryEntries().isNotEmpty()) {
                    break
                }
            }
        }
    }

    data class DownloadContext(val hash: String, val url: String, val fileName: String) {
        override fun toString(): String {
            return "$hash\t$url\t$fileName"
        }

        companion object {
            const val FILE = "META-INF/download-context"
        }
    }
}
