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

package io.papermc.paperweight.userdev.internal.setup.step

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import java.nio.file.Path

class FixMinecraftJar(
    @Input private val inputJar: Path,
    @Output private val outputJar: Path,
    @Input private val vanillaServerJar: Path,
    private val useLegacyParameterAnnotationFixer: Boolean = false,
) : SetupStep {
    override val name: String = "fix minecraft server jar"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.Context) {
        fixJar(
            workerExecutor = context.workerExecutor,
            launcher = context.javaLauncher,
            vanillaJarPath = vanillaServerJar,
            inputJarPath = inputJar,
            outputJarPath = outputJar,
            useLegacyParameterAnnotationFixer = useLegacyParameterAnnotationFixer
        ).await()
    }
}
