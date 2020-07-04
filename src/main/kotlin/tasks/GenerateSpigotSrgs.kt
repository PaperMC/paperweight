/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.writeMappings
import org.cadixdev.lorenz.io.MappingFormats
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

open class GenerateSpigotSrgs : DefaultTask() {

    @InputFile
    val notchToSrg: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val srgToMcp: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val classMappings: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val memberMappings: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val packageMappings: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val spigotToSrg: RegularFileProperty = project.objects.fileProperty()
    @OutputFile
    val spigotToMcp: RegularFileProperty = project.objects.fileProperty()
    @OutputFile
    val spigotToNotch: RegularFileProperty = project.objects.fileProperty()
    @OutputFile
    val srgToSpigot: RegularFileProperty = project.objects.fileProperty()
    @OutputFile
    val mcpToSpigot: RegularFileProperty = project.objects.fileProperty()
    @OutputFile
    val notchToSpigot: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        val classMappingSet = classMappings.asFile.get().reader().use { MappingFormats.CSRG.createReader(it).read() }
        val memberMappingSet = memberMappings.asFile.get().reader().use { MappingFormats.CSRG.createReader(it).read() }

        val notchToSpigotSet = classMappingSet.merge(memberMappingSet)

        // Apply the package mappings (Lorenz doesn't currently support this)
        val newPackage = packageMappings.asFile.get().readLines()[0].split(Regex("\\s+"))[1]
        for (topLevelClassMapping in notchToSpigotSet.topLevelClassMappings) {
            topLevelClassMapping.deobfuscatedName = newPackage + topLevelClassMapping.deobfuscatedName
        }

        val notchToSrgSet = notchToSrg.asFile.get().reader().use { MappingFormats.TSRG.createReader(it).read() }
        val srgToMcpSet = srgToMcp.asFile.get().reader().use { MappingFormats.TSRG.createReader(it).read() }

        val srgToSpigotSet = notchToSrgSet.reverse().merge(notchToSpigotSet)

        val mcpToNotchSet = notchToSrgSet.merge(srgToMcpSet).reverse()
        val mcpToSpigotSet = mcpToNotchSet.merge(notchToSpigotSet)

        val spigotToSrgSet = srgToSpigotSet.reverse()
        val spigotToMcpSet = mcpToSpigotSet.reverse()
        val spigotToNotchSet = notchToSpigotSet.reverse()

        writeMappings(
            MappingFormats.TSRG,
            spigotToSrgSet to spigotToSrg.asFile.get(),
            spigotToMcpSet to spigotToMcp.asFile.get(),
            spigotToNotchSet to spigotToNotch.asFile.get(),
            srgToSpigotSet to srgToSpigot.asFile.get(),
            mcpToSpigotSet to mcpToSpigot.asFile.get(),
            notchToSpigotSet to notchToSpigot.asFile.get()
        )
    }
}
