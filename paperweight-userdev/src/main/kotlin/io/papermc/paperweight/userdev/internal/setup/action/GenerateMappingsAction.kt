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
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.Value
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.util.jars
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.workers.WorkerExecutor

class GenerateMappingsAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    private val workerExecutor: WorkerExecutor,
    @Input private val serverMappings: FileValue,
    @Input private val filteredVanillaJar: FileValue,
    @Input val paramMappings: FileCollectionValue,
    @Input private val minecraftLibraryJars: DirectoryValue,
    @Output val outputMappings: FileValue,
) : WorkDispatcher.Action {
    override fun execute() {
        generateMappings(
            vanillaJarPath = filteredVanillaJar.get(),
            libraryPaths = minecraftLibraryJars.get().jars(),
            vanillaMappingsPath = serverMappings.get(),
            paramMappingsPath = paramMappings.get().singleFile.toPath(),
            outputMappingsPath = outputMappings.get(),
            workerExecutor = workerExecutor,
            launcher = javaLauncher.get()
        ).await()
    }
}
