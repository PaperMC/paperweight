/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

package io.papermc.paperweight.tasks.sourceremap

import io.papermc.paperweight.tasks.ZippedTask
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.path
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.extra.AccessAnalyzerProcessor
import org.cadixdev.mercury.extra.BridgeMethodRewriter
import org.cadixdev.mercury.remapper.MercuryRemapper
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import java.io.File

abstract class RemapSources : ZippedTask() {

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty
    @get:InputFile
    abstract val vanillaRemappedSpigotJar: RegularFileProperty // Required for pre-remap pass
    @get:InputFile
    abstract val mappings: RegularFileProperty

    @get:InputDirectory
    abstract val spigotApiDeps: DirectoryProperty
    @get:InputDirectory
    abstract val spigotServerDeps: DirectoryProperty

    @get:InputFile
    abstract val constructors: RegularFileProperty
    @get:InputDirectory
    abstract val spigotServerDir: DirectoryProperty
    @get:InputDirectory
    abstract val spigotApiDir: DirectoryProperty

    @get:OutputFile
    abstract val generatedAt: RegularFileProperty
    @get:OutputFile
    abstract val parameterNames: RegularFileProperty

    override fun init() {
        super.init()
        generatedAt.convention(defaultOutput("at"))
        parameterNames.convention(defaultOutput("params"))
    }

    override fun run(rootDir: File) {
        val constructorsData = parseConstructors(constructors.file)

        val paramNames: ParamNames = newParamNames()

        val srcDir = spigotServerDir.file.resolve("src/main/java")

        val mappingSet = MappingFormats.TSRG.read(mappings.path)
        val processAt = AccessTransformSet.create()

        // Remap any references Spigot maps to SRG
        Mercury().let { merc ->
            merc.classPath.addAll(listOf(
                vanillaJar.path,
                vanillaRemappedSpigotJar.path,
                spigotApiDir.path.resolve("src/main/java"),
                *spigotApiDeps.get().asFileTree.files.map { it.toPath() }.toTypedArray(),
                *spigotServerDeps.get().asFileTree.files.map { it.toPath() }.toTypedArray()
            ))

            merc.processors += AccessAnalyzerProcessor.create(processAt, mappingSet)

            merc.process(srcDir.toPath())

            merc.processors.clear()
            merc.processors.addAll(listOf(
                MercuryRemapper.create(mappingSet),
                BridgeMethodRewriter.create(),
                AccessTransformerRewriter.create(processAt),
                SrgParameterRemapper(mappingSet, constructorsData, paramNames)
            ))

            merc.rewrite(srcDir.toPath(), rootDir.toPath())
        }

        AccessTransformFormats.FML.write(generatedAt.path, processAt)
        writeParamNames(paramNames, parameterNames.file)
    }
}
