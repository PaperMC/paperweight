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

import io.papermc.paperweight.shared.PaperweightException
import io.papermc.paperweight.shared.RemapConfig
import io.papermc.paperweight.shared.RemapOps
import io.papermc.paperweight.tasks.ZippedTask
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.mcpConfig
import io.papermc.paperweight.util.mcpFile
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.property
import java.io.File

open class RemapSources : ZippedTask() {

    @InputFile
    val vanillaJar: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val vanillaRemappedSpigotJar: RegularFileProperty = project.objects.fileProperty() // Required for pre-remap pass
    @InputFile
    val mappings: RegularFileProperty = project.objects.fileProperty()
    @Input
    val configuration: Property<String> = project.objects.property()
    @InputFile
    val configFile: RegularFileProperty = project.objects.fileProperty()
    @InputDirectory
    val spigotServerDir: DirectoryProperty = project.objects.directoryProperty()
    @InputDirectory
    val spigotApiDir: DirectoryProperty = project.objects.directoryProperty()

    @OutputFile
    val generatedAt: RegularFileProperty = defaultOutput("at")
    @OutputFile
    val parameterNames: RegularFileProperty = defaultOutput("params")

    override fun run(rootDir: File) {
        val config = mcpConfig(configFile)
        val constructors = mcpFile(configFile, config.data.constructors)

        val srcDir = spigotServerDir.file.resolve("src/main/java")

        val configuration = project.configurations[configuration.get()]

        val totalClasspath = arrayListOf<File>()
        totalClasspath.addAll(listOf(
            vanillaJar.file,
            vanillaRemappedSpigotJar.file,
            spigotApiDir.file.resolve("src/main/java")
        ))
        configuration.resolvedConfiguration.files.mapTo(totalClasspath) { it }

        // Remap any references Spigot maps to SRG
        val remapOutput = MercuryExecutor.exec(RemapConfig(
            inDir = srcDir,
            outDir = rootDir,
            classpath = totalClasspath,
            mappingsFile = mappings.file,
            constructorsFile = constructors,
            operations = listOf(
                RemapOps.PROCESS_AT,
                RemapOps.REMAP,
                RemapOps.REWRITE_BRIDGE_METHODS,
                RemapOps.APPLY_AT,
                RemapOps.REMAP_PARAMS_SRG
            )
        ))

        val atFile = remapOutput.atFile ?: throw PaperweightException("No atFile returned from Mercury")
        atFile.copyTo(generatedAt.file)
        atFile.delete()

        val paramMapFile = remapOutput.paramMapFile ?: throw PaperweightException("No paramMapFile returned from Mercury")
        paramMapFile.copyTo(parameterNames.file)
        paramMapFile.delete()
    }
}
