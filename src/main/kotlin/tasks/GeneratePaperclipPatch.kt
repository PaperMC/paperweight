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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.path
import io.sigpipe.jbsdiff.Diff
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Properties
import javax.inject.Inject
import kotlin.experimental.and
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

abstract class GeneratePaperclipPatch : ZippedTask() {

    @get:InputFile
    abstract val originalJar: RegularFileProperty
    @get:InputFile
    abstract val patchedJar: RegularFileProperty
    @get:Input
    abstract val mcVersion: Property<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun run(rootDir: File) {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs("-Xmx2G")
        }

        queue.submit(PaperclipAction::class) {
            zipRootDir.set(rootDir)
            originalJar.set(this@GeneratePaperclipPatch.originalJar.file)
            patchedJar.set(this@GeneratePaperclipPatch.patchedJar.file)
            mcVersion.set(this@GeneratePaperclipPatch.mcVersion.get())
        }

        queue.await()
    }

    abstract class PaperclipAction : WorkAction<PaperclipParameters> {
        override fun execute() {
            val rootDir = parameters.zipRootDir.path
            val originalJarPath = parameters.originalJar.path
            val patchedJarPath = parameters.patchedJar.path

            val patchFile = rootDir.resolve("paperMC.patch")
            val propFile = rootDir.resolve("patch.properties")
            val protocol = rootDir.resolve("META-INF/$PROTOCOL_FILE")

            val zipUri = try {
                val jarUri = patchedJarPath.toUri()
                URI("jar:${jarUri.scheme}", jarUri.path, null)
            } catch (e: URISyntaxException) {
                throw PaperweightException("Failed to create jar URI for $patchedJarPath", e)
            }

            try {
                FileSystems.newFileSystem(zipUri, mapOf<String, Any>()).use { zipFs ->
                    val protocolPath = zipFs.getPath("META-INF", PROTOCOL_FILE)
                    if (Files.notExists(protocolPath)) {
                        Files.deleteIfExists(protocol)
                        return@use
                    }

                    Files.createDirectories(protocol.parent)
                    Files.copy(protocolPath, protocol, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                throw PaperweightException("Failed to read $patchedJarPath contents", e)
            }

            // Read the files into memory
            println("Reading jars into memory")
            val originalBytes = Files.readAllBytes(originalJarPath)
            val patchedBytes = Files.readAllBytes(patchedJarPath)

            println("Creating Paperclip patch")
            try {
                Files.newOutputStream(patchFile).use { patchOutput ->
                    Diff.diff(originalBytes, patchedBytes, patchOutput)
                }
            } catch (e: Exception) {
                throw PaperweightException("Error creating patch between $originalJarPath and $patchedJarPath", e)
            }

            // Add the SHA-256 hashes for the files
            val digestSha256 = try {
                MessageDigest.getInstance("SHA-256")
            } catch (e: NoSuchAlgorithmException) {
                throw PaperweightException("Could not create SHA-256 hasher", e)
            }

            // Vanilla's URL uses a SHA1 hash of the vanilla server jar
            val digestSha1 = try {
                MessageDigest.getInstance("SHA1")
            } catch (e: NoSuchAlgorithmException) {
                throw PaperweightException("Could not create SHA1 hasher", e)
            }

            println("Hashing files")
            val originalSha1 = digestSha1.digest(originalBytes)
            val originalSha256 = digestSha256.digest(originalBytes)
            val patchedSha256 = digestSha256.digest(patchedBytes)

            val prop = Properties()
            prop["originalHash"] = toHex(originalSha256)
            prop["patchedHash"] = toHex(patchedSha256)
            prop["patch"] = "paperMC.patch"
            prop["sourceUrl"] = "https://launcher.mojang.com/v1/objects/" + toHex(originalSha1).toLowerCase() + "/server.jar"
            prop["version"] = parameters.mcVersion.get()

            println("Writing properties file")
            Files.newBufferedWriter(propFile).use { writer ->
                prop.store(
                    writer,
                    "Default Paperclip launch values. Can be overridden by placing a paperclip.properties file in the server directory."
                )
            }
        }

        private fun toHex(hash: ByteArray): String {
            val sb: StringBuilder = StringBuilder(hash.size * 2)
            for (aHash in hash) {
                sb.append("%02X".format(aHash and 0xFF.toByte()))
            }
            return sb.toString()
        }

        companion object {
            private const val PROTOCOL_FILE = "io.papermc.paper.daemon.protocol"
        }
    }

    interface PaperclipParameters : WorkParameters {
        val zipRootDir: RegularFileProperty
        val originalJar: RegularFileProperty
        val patchedJar: RegularFileProperty
        val mcVersion: Property<String>
    }
}
