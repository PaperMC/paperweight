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
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.model.ClassMapping
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

        val mergedMappingSet = MappingSet.create()

        val reverseLookup = classMappingSet.reverse()
        for (topLevelClassMapping in memberMappingSet.topLevelClassMappings) {
            val classMappingSetSource = classMappingSet.topLevelClassMappings.firstOrNull { it.deobfuscatedName == topLevelClassMapping.obfuscatedName }
            val target = classMappingSetSource?.let { source ->
                mergedMappingSet.createTopLevelClassMapping(source.obfuscatedName, source.deobfuscatedName)
            } ?: mergedMappingSet.createTopLevelClassMapping(topLevelClassMapping.obfuscatedName, topLevelClassMapping.deobfuscatedName)
            addMemberMappings(reverseLookup, classMappingSetSource, topLevelClassMapping, target)
        }

        for (topLevelClassMapping in classMappingSet.topLevelClassMappings) {
            val otherMapping = mergedMappingSet.getOrCreateTopLevelClassMapping(topLevelClassMapping.obfuscatedName)
            otherMapping.deobfuscatedName = topLevelClassMapping.deobfuscatedName
            addMissingClasses(topLevelClassMapping, otherMapping)
        }

        val newPackage = packageMappings.asFile.get().readLines()[0].split(Regex("\\s+"))[1]
        val packageMappingSet = MappingSet.create()
        for (topLevelClassMapping in mergedMappingSet.topLevelClassMappings) {
            val newMapping = if (!topLevelClassMapping.deobfuscatedName.startsWith(newPackage)) {
                newPackage + topLevelClassMapping.deobfuscatedName
            } else {
                topLevelClassMapping.deobfuscatedName
            }
            packageMappingSet.createTopLevelClassMapping(
                topLevelClassMapping.deobfuscatedName,
                newMapping
            )
        }

        val notchToSpigotSet = mergedMappingSet.merge(packageMappingSet)

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

    private fun addMemberMappings(
        classMappings: MappingSet,
        classSource: ClassMapping<*, *>?,
        memberSource: ClassMapping<*, *>,
        target: ClassMapping<*, *>
    ) {
        if (classSource != null) {
            for (innerClassMapping in memberSource.innerClassMappings) {
                val classSourceInnerClass = classSource.innerClassMappings.first { it.deobfuscatedName == innerClassMapping.obfuscatedName }
                val newMapping = target.getOrCreateInnerClassMapping(classSourceInnerClass.obfuscatedName)
                newMapping.deobfuscatedName = classSourceInnerClass.deobfuscatedName
                addMemberMappings(classMappings, classSourceInnerClass, innerClassMapping, newMapping)
            }
        }

        for (fieldMapping in memberSource.fieldMappings) {
            val newMapping = target.createFieldMapping(fieldMapping.obfuscatedName)
            newMapping.deobfuscatedName = fieldMapping.deobfuscatedName
        }

        for (methodMapping in memberSource.methodMappings) {
            target.createMethodMapping(MethodSignature(methodMapping.obfuscatedName, classMappings.deobfuscate(methodMapping.signature.descriptor)), methodMapping.deobfuscatedName)
        }
    }

    private fun addMissingClasses(
        source: ClassMapping<*, *>,
        target: ClassMapping<*, *>
    ) {
        for (innerClassMapping in source.innerClassMappings) {
            val otherMapping = target.getOrCreateInnerClassMapping(innerClassMapping.obfuscatedName)
            otherMapping.deobfuscatedName = innerClassMapping.deobfuscatedName
            addMissingClasses(innerClassMapping, otherMapping)
        }
    }
}
