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

import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.hashDirectory
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import io.papermc.paperweight.util.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

class ApplyDevBundlePatches(
    @Input private val decompiledJar: Path,
    private val devBundlePatches: Path,
    @Output private val outputJar: Path,
) : SetupStep {
    override val name: String = "apply patches to decompiled jar"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.Context) {
        Git.checkForGit()

        val workDir = findOutputDir(outputJar)

        try {
            unzip(decompiledJar, workDir)
            val git = Git(workDir)

            Files.walk(devBundlePatches).use { stream ->
                stream.forEach {
                    if (it.name.endsWith(".patch")) {
                        git("apply", "--ignore-whitespace", it.absolutePathString()).executeOut()
                    } else if (it.isRegularFile()) {
                        val destination = workDir.resolve(it.relativeTo(devBundlePatches))
                        destination.parent.createDirectories()
                        it.copyTo(destination, overwrite = true)
                    }
                }
            }

            ensureDeleted(outputJar)
            zip(workDir, outputJar)
        } finally {
            workDir.deleteRecursively()
        }
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.include(hashDirectory(devBundlePatches))
    }
}
