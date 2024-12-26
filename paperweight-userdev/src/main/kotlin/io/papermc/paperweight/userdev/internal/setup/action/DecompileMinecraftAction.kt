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

package io.papermc.paperweight.userdev.internal.setup.action

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.action.DirectoryValue
import io.papermc.paperweight.userdev.internal.action.FileCollectionValue
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.ListValue
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.Value
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.util.jars
import io.papermc.paperweight.userdev.internal.util.siblingLogFile
import kotlin.io.path.*
import org.gradle.jvm.toolchain.JavaLauncher

class DecompileMinecraftAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    @Input private val inputJar: FileValue,
    @Output val outputJar: FileValue,
    @Input private val minecraftLibraryJars: DirectoryValue,
    @Input val decompileArgs: ListValue<String>,
    @Input val decompiler: FileCollectionValue,
) : WorkDispatcher.Action {
    override fun execute() {
        runDecompiler(
            argsList = decompileArgs.get(),
            logFile = outputJar.get().siblingLogFile(),
            workingDir = outputJar.get().parent.createDirectories(),
            executable = decompiler.get(),
            inputJar = inputJar.get(),
            libraries = minecraftLibraryJars.get().jars(),
            outputJar = outputJar.get(),
            javaLauncher = javaLauncher.get()
        )
    }
}
