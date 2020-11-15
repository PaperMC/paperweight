/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

import io.papermc.paperweight.PaperweightException
import java.io.File

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

    fun downloadToFile(targetFile: File, repos: List<String>) {
        targetFile.parentFile.mkdirs()

        var thrown: Exception? = null
        for (repo in repos) {
            try {
                download(addSlash(repo) + path, targetFile)
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

    fun downloadToDir(targetDir: File, repos: List<String>): File {
        val out = targetDir.resolve(file)
        downloadToFile(targetDir.resolve(file), repos)
        return out
    }

    override fun toString(): String {
        return if (classifier == null) {
            "$group:$artifact:$version"
        } else {
            "$group:$artifact:$version:$classifier"
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
