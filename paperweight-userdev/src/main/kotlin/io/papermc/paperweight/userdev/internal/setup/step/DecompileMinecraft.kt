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

import io.papermc.paperweight.tasks.runForgeFlower
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import io.papermc.paperweight.userdev.internal.setup.util.siblingLogFile
import io.papermc.paperweight.util.constants.DECOMPILER_CONFIG
import java.nio.file.Path
import org.gradle.api.artifacts.Configuration

class DecompileMinecraft(
    @Input private val inputJar: Path,
    @Output private val outputJar: Path,
    private val cache: Path,
    private val minecraftLibraryJars: () -> List<Path>,
    @Input private val decompileArgs: List<String>,
    private val decompiler: Configuration,
) : SetupStep {
    override val name: String = "decompile transformed minecraft server jar"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.Context) {
        runForgeFlower(
            argsList = decompileArgs,
            logFile = outputJar.siblingLogFile(),
            workingDir = cache,
            executable = decompiler,
            inputJar = inputJar,
            libraries = minecraftLibraryJars(),
            outputJar = outputJar,
            javaLauncher = context.defaultJavaLauncher
        )
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.include(minecraftLibraryJars())
        builder.include(decompiler.map { it.toPath() })
        builder.includePaperweightHash = false
    }

    companion object {
        fun create(
            context: SetupHandler.Context,
            inputJar: Path,
            outputJar: Path,
            cache: Path,
            minecraftLibraryJars: () -> List<Path>,
            decompileArgs: List<String>,
        ): DecompileMinecraft {
            val decompiler = context.project.configurations.getByName(DECOMPILER_CONFIG).also { it.resolve() } // resolve decompiler
            return DecompileMinecraft(inputJar, outputJar, cache, minecraftLibraryJars, decompileArgs, decompiler)
        }
    }
}
