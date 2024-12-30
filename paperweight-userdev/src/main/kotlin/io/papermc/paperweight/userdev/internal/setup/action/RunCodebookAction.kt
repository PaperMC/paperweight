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

import io.papermc.paperweight.tasks.mache.runCodebook
import io.papermc.paperweight.userdev.internal.action.DirectoryValue
import io.papermc.paperweight.userdev.internal.action.FileCollectionValue
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.ListValue
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.Value
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.util.jars
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.jvm.toolchain.JavaLauncher

class RunCodebookAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    @Input val minecraftRemapArgs: ListValue<String>,
    @Input val vanillaJar: FileValue,
    @Input private val minecraftLibraryJars: DirectoryValue,
    @Input val mappings: FileValue,
    @Input val paramMappings: FileCollectionValue,
    @Input val constants: FileCollectionValue,
    @Input val codebook: FileCollectionValue,
    @Input val remapper: FileCollectionValue,
    @Output val outputJar: FileValue,
) : WorkDispatcher.Action {
    override fun execute() {
        val temp = outputJar.get().parent.resolve("work").createDirectories()
        runCodebook(
            javaLauncher.get(),
            codebook.get(),
            outputJar.get(),
            minecraftRemapArgs.get(),
            temp,
            remapper.get(),
            mappings.get(),
            paramMappings.get().singleFile.toPath(),
            constants.get().singleOrNull()?.toPath(),
            vanillaJar.get(),
            minecraftLibraryJars.get().jars(),
        )
        temp.filesMatchingRecursive("*.jar").forEach { it.deleteIfExists() } // TODO: Codebook leaves a jar laying around
    }
}
