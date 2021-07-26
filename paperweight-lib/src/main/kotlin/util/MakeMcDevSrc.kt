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

package io.papermc.paperweight.util

import java.nio.file.Path
import kotlin.io.path.*

fun makeMcDevSrc(decompileJar: Path, paperSource: Path, target: Path) {
    ensureDeleted(target)

    decompileJar.openZip().use { fs ->
        fs.walk().use { stream ->
            stream.forEach { sourceFile ->
                if (sourceFile.isRegularFile()) {
                    val sourceFilePath = sourceFile.pathString.removePrefix("/")

                    if (!paperSource.resolve(sourceFilePath).isRegularFile()) {
                        val targetFile = target.resolve(sourceFilePath)
                        targetFile.parent.createDirectories()
                        sourceFile.copyTo(targetFile)
                    }
                }
            }
        }
    }
}
