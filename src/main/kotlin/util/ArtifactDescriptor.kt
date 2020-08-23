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

import io.papermc.paperweight.shared.PaperweightException

data class ArtifactDescriptor(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String?,
    val extension: String
) {
    override fun toString(): String {
        val path = group.replace('.', '/')
        val classifierText = classifier?.let { "-$it" } ?: ""
        val file = "$artifact-$version$classifierText.$extension"
        return "$path/$artifact/$version/$file"
    }

    companion object {
        fun parse(text: String): ArtifactDescriptor {
            val (group, groupIndex) = text.nextSubstring(0, charArrayOf(':'))
            val (artifact, artifactIndex) = text.nextSubstring(groupIndex, charArrayOf(':'))
            val (version, versionIndex) = text.nextSubstring(artifactIndex, charArrayOf(':', '@'), goToEnd = true)
            val (classifier, classifierIndex) = text.nextSubstring(versionIndex, charArrayOf(':', '@'), goToEnd = true)
            val (extension, _) = text.nextSubstring(classifierIndex, charArrayOf(), goToEnd = true)

            group ?: throw PaperweightException("Invalid Maven artifact descriptor (no groupId found): $text")
            artifact ?: throw PaperweightException("Invalid Maven artifact descriptor (no artifactId found): $text")
            version ?: throw PaperweightException("Invalid Maven artifact descriptor (no version found): $text")

            return ArtifactDescriptor(group, artifact, version, classifier, extension ?: "jar")
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
