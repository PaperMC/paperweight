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

package io.papermc.paperweight.userdev.internal.setup.step

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.hashDirectory
import io.papermc.paperweight.util.copyRecursivelyTo
import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.openZip
import java.nio.file.Path
import kotlin.io.path.createDirectories

class SetupArchivedPublications(
    private val maven: Path,
    @Input private val archivedPublications: List<String>,
    private val archivedPublicationsDir: Path,
    override val hashFile: Path,
) : SetupStep {
    override val name: String = "extract included publications"

    override fun run(context: SetupHandler.Context) {
        maven.deleteRecursively()
        maven.createDirectories()
        for (name in archivedPublications) {
            try {
                archivedPublicationsDir.resolve("$name.zip").openZip().use { fs ->
                    fs.getPath("/").copyRecursivelyTo(maven)
                }
            } catch (ex: Exception) {
                throw PaperweightException("Failed to extract archived publication '$name'", ex)
            }
        }
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.include(hashDirectory(archivedPublicationsDir))
        builder.include(hashDirectory(maven))
    }
}
