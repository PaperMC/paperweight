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

package io.papermc.paperweight.util

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import java.nio.file.Path
import kotlin.io.path.createDirectories

data class MavenArtifact(
    private val group: String,
    private val artifact: String,
    private val version: String,
    private val classifier: String? = null,
    private val extension: String? = null
) {

    private val classifierText: String
        get() = if (classifier != null) "-$classifier" else ""

    private val ext: String
        get() = extension ?: "jar"

    private val path: String
        get() = "${group.replace('.', '/')}/$artifact/$version/$file"
    val file: String
        get() = "$artifact-$version$classifierText.$ext"

    fun downloadToFile(downloadService: DownloadService, targetFile: Path, repos: List<String>) {
        targetFile.parent.createDirectories()

        var thrown: Exception? = null
        for (repo in repos) {
            try {
                downloadService.download(addSlash(repo) + path, targetFile)
                return
            } catch (e: Exception) {
                if (thrown != null) {
                    thrown.addSuppressed(e)
                } else {
                    thrown = e
                }
            }
        }
        thrown?.let { throw PaperweightException("Failed to download artifact: $this. Checked repos: $repos", it) }
    }

    fun downloadToDir(downloadService: DownloadService, targetDir: Path, repos: List<String>): Path {
        val out = targetDir.resolve(file)
        downloadToFile(downloadService, out, repos)
        return out
    }

    override fun toString(): String {
        return buildString(50) {
            append(group).append(':').append(artifact).append(':').append(version)
            if (classifier != null) {
                append(':').append(classifier)
            }
            if (extension != null) {
                append('@').append(extension)
            }
        }
    }

    private fun addSlash(url: String): String {
        return if (url.endsWith('/')) url else "$url/"
    }

    companion object {
        fun parse(text: String): MavenArtifact {
            val (group, groupIndex) = text.nextSubstring(0, charArrayOf(':'))
            val (artifact, artifactIndex) = text.nextSubstring(groupIndex, charArrayOf(':'))
            val (version, versionIndex) = text.nextSubstring(artifactIndex, charArrayOf(':', '@'), goToEnd = true)
            val (classifier, classifierIndex) = text.nextSubstring(versionIndex, charArrayOf(':', '@'), goToEnd = true)
            val (extension, _) = text.nextSubstring(classifierIndex, charArrayOf(), goToEnd = true)

            group ?: throw PaperweightException("Invalid Maven artifact descriptor (no groupId found): $text")
            artifact ?: throw PaperweightException("Invalid Maven artifact descriptor (no artifactId found): $text")
            version ?: throw PaperweightException("Invalid Maven artifact descriptor (no version found): $text")

            return MavenArtifact(group, artifact, version, classifier, extension)
        }

        private fun String.nextSubstring(startIndex: Int, stops: CharArray, goToEnd: Boolean = false): Pair<String?, Int> {
            if (startIndex == this.length) {
                return null to startIndex
            }
            val endIndex = this.indexOfAny(stops, startIndex)
            return when {
                endIndex != -1 -> this.substring(startIndex, endIndex) to endIndex + 1
                goToEnd -> this.substring(startIndex) to this.length
                else -> null to startIndex
            }
        }
    }
}
