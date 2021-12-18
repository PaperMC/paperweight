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

import io.papermc.paperweight.tasks.runForgeFlower
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.util.buildHashFunction
import io.papermc.paperweight.userdev.internal.setup.util.siblingLogAndHashesFiles
import io.papermc.paperweight.util.constants.DECOMPILER_CONFIG
import java.nio.file.Path

fun decompileMinecraftServerJar(
    context: SetupHandler.Context,
    inputJar: Path,
    outputJar: Path,
    cache: Path,
    minecraftLibraryJars: () -> List<Path>,
    decompileArgs: List<String>,
) {
    val (logFile, hashFile) = outputJar.siblingLogAndHashesFiles()

    val decompiler = context.project.configurations.getByName(DECOMPILER_CONFIG)
    // resolve decompiler
    val decompilerFiles = decompiler.resolve().map { it.toPath() }

    val hashFunction = buildHashFunction(inputJar, outputJar, decompileArgs, decompilerFiles) {
        include(minecraftLibraryJars())
    }
    if (hashFunction.upToDate(hashFile)) {
        return
    }

    UserdevSetup.LOGGER.lifecycle(":decompiling transformed minecraft server jar")
    runForgeFlower(
        argsList = decompileArgs,
        logFile = logFile,
        workingDir = cache,
        executable = decompiler,
        inputJar = inputJar,
        libraries = minecraftLibraryJars(),
        outputJar = outputJar,
        javaLauncher = context.defaultJavaLauncher
    )
    hashFunction.writeHash(hashFile)
}
