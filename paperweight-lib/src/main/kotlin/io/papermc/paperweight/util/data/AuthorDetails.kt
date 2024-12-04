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

package io.papermc.paperweight.util.data

import java.io.File

data class AuthorDetails(
    val oldDetails: List<String>,
    val newName: String,
    val newEmail: String
) {
    companion object {
        fun readAuthorChanges(): List<AuthorDetails> {
            val userUpdates = mutableListOf<AuthorDetails>()
            File("author_changes.tsv").useLines { lines ->
                val iterator = lines.iterator()
                iterator.next() // Header
                iterator.forEachRemaining { line ->
                    val parts = line.split("\t")
                    val name = parts[1].trim()
                    val email = parts[2].trim()
                    val oldAttributions = parts[3].split(",").map { it.trim() }
                    userUpdates.add(AuthorDetails(oldAttributions, name, email))
                }
            }
            return userUpdates
        }
    }
}


