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
import io.sigpipe.jbsdiff.Diff
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Properties
import javax.inject.Inject
import kotlin.collections.set
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class GeneratePaperclipPatch : JavaLauncherZippedTask() {

    data class PatchPair(
        @get:Input
        val kind: Property<String>,
        @get:Input
        val inputPath: Property<String>,
        @get:Classpath
        val originalJar: RegularFileProperty,
        @get:Classpath
        val patchedJar: RegularFileProperty
    )

    @get:Nested
    abstract val patches: ListProperty<PatchPair>

    @get:Input
    abstract val mcVersion: Property<String>

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx1G"))
    }

    override fun run(rootDir: Path) {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        for (patchPair in patches.get()) {
            queue.submit(PaperclipAction::class) {
                dir.set(rootDir.toAbsolutePath().toString())
                patchedJar.set(patchPair.patchedJar.path)
                originalJar.set(patchPair.originalJar.path)
                mcVersion.set(this@GeneratePaperclipPatch.mcVersion)
            }
        }

        queue.await()
    }

    abstract class PaperclipAction : WorkAction<PaperclipParameters> {
        override fun execute() {
            val rootDir = Path(parameters.dir.get())
            val patchFile = rootDir.resolve("paperMC.patch")

            // Read the files into memory
            val originalBytes = parameters.originalJar.path.readBytes()
            val patchedBytes = parameters.patchedJar.path.readBytes()

            try {
                patchFile.outputStream().use { patchOutput ->
                    Diff.diff(originalBytes, patchedBytes, patchOutput)
                }
            } catch (e: Exception) {
                throw PaperweightException("Error creating patch between ${parameters.originalJar.path} and ${parameters.patchedJar.path}", e)
            }

            // Vanilla's URL uses a SHA1 hash of the vanilla server jar
            val digestSha1 = try {
                MessageDigest.getInstance("SHA1")
            } catch (e: NoSuchAlgorithmException) {
                throw PaperweightException("Could not create SHA1 hasher", e)
            }

            val originalSha1 = digestSha1.digest(originalBytes)
            // Add the SHA-256 hashes for the files
            val originalSha256 = digestSha256.digest(originalBytes)
            val patchedSha256 = digestSha256.digest(patchedBytes)

            val prop = Properties()
            prop["originalHash"] = toHex(originalSha256)
            prop["patchedHash"] = toHex(patchedSha256)
            prop["patch"] = "paperMC.patch"
            prop["sourceUrl"] = "https://launcher.mojang.com/v1/objects/" + toHex(originalSha1).toLowerCase() + "/server.jar"
            prop["version"] = parameters.mcVersion.get()

//            propFile.bufferedWriter().use { writer ->
//                prop.store(
//                    writer,
//                    "Default Paperclip launch values. Can be overridden by placing a paperclip.properties file in the server directory."
//                )
//            }
        }
    }

    interface PaperclipParameters : WorkParameters {
        val dir: Property<String>
        val patchedJar: RegularFileProperty
        val originalJar: RegularFileProperty
        val mcVersion: Property<String>
    }
}
