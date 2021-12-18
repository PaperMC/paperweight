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

import io.papermc.paperweight.tasks.applyAccessTransform
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.util.buildHashFunction
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import java.nio.file.Path

fun accessTransformMinecraftServerJar(
    context: SetupHandler.Context,
    at: Path,
    inputJar: Path,
    outputJar: Path,
) {
    val hashFile = outputJar.siblingHashesFile()
    val hashFunction = buildHashFunction(inputJar, at, outputJar)
    if (hashFunction.upToDate(hashFile)) {
        return
    }

    UserdevSetup.LOGGER.lifecycle(":access transforming minecraft server jar")
    applyAccessTransform(
        inputJarPath = inputJar,
        outputJarPath = outputJar,
        atFilePath = at,
        workerExecutor = context.workerExecutor,
        launcher = context.defaultJavaLauncher
    ).await()
    hashFunction.writeHash(hashFile)
}
