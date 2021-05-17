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

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.path
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class DownloadServerJar : BaseTask() {

    @get:Input
    abstract val downloadUrl: Property<String>

    @get:Input
    abstract val hash: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val downloader: Property<DownloadService>

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val file = outputJar.asFile.get()
        outputJar.path.parent.createDirectories()

        downloader.get().download(downloadUrl, outputJar)

        val digest = MessageDigest.getInstance("MD5")

        val data = file.readBytes()
        val hashResult = digest.digest(data)
        val hashText = String.format("%032x", BigInteger(1, hashResult))

        if (hash.get() != hashText) {
            throw PaperweightException("Checksum failed, expected ${hash.get()}, actually got $hashText")
        }
    }
}
