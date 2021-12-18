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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.util.buildHashFunction
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import java.nio.file.Path

fun fixMinecraftServerJar(
    context: SetupHandler.Context,
    inputJar: Path,
    outputJar: Path,
    vanillaServerJar: Path,
    useLegacyParameterAnnotationFixer: Boolean = false,
) {
    val hashFile = outputJar.siblingHashesFile()
    val hashFunction = buildHashFunction(inputJar, outputJar, vanillaServerJar)
    if (hashFunction.upToDate(hashFile)) {
        return
    }

    UserdevSetup.LOGGER.lifecycle(":fixing minecraft server jar")
    fixJar(
        workerExecutor = context.workerExecutor,
        launcher = context.defaultJavaLauncher,
        vanillaJarPath = vanillaServerJar,
        inputJarPath = inputJar,
        outputJarPath = outputJar,
        useLegacyParameterAnnotationFixer = useLegacyParameterAnnotationFixer
    ).await()
    hashFunction.writeHash(hashFile)
}
