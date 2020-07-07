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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.writeMappings
import org.cadixdev.bombe.type.ArrayType
import org.cadixdev.bombe.type.BaseType
import org.cadixdev.bombe.type.FieldType
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.ObjectType
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
        val classMappingSet = classMappings.file.reader().use { MappingFormats.CSRG.createReader(it).read() }
        val memberMappingSet = memberMappings.file.reader().use { MappingFormats.CSRG.createReader(it).read() }

        val mergedMappingSet = MappingSet.create()

        // Merge the member mappings into the class mappings
        val reverseLookup = classMappingSet.reverse()
        for (mapping in memberMappingSet.topLevelClassMappings) {
            val classMappingSetSource = classMappingSet.topLevelClassMappings.firstOrNull { it.deobfuscatedName == mapping.obfuscatedName }
            val target = classMappingSetSource?.let { source ->
                mergedMappingSet.createTopLevelClassMapping(source.obfuscatedName, source.deobfuscatedName)
            } ?: mergedMappingSet.createTopLevelClassMapping(mapping.obfuscatedName, mapping.deobfuscatedName)
            addMemberMappings(reverseLookup, classMappingSetSource, mapping, target)
        }

        // Add back any class mappings which contain no member mappings
        for (mapping in classMappingSet.topLevelClassMappings) {
            val otherMapping = mergedMappingSet.getOrCreateTopLevelClassMapping(mapping.obfuscatedName)
            otherMapping.deobfuscatedName = mapping.deobfuscatedName
            addMissingClasses(mapping, otherMapping)
        }

        // Merge the package name mapping
        val newPackage = packageMappings.asFile.get().readLines()[0].split(Regex("\\s+"))[1]
        val packageMappingSet = MappingSet.create()
        for (mapping in mergedMappingSet.topLevelClassMappings) {
            val deobfName = mapping.deobfuscatedName.let { deobf ->
                if (deobf.contains('/')) {
                    deobf
                } else {
                    newPackage + deobf
                }
            }
            packageMappingSet.createTopLevelClassMapping(mapping.deobfuscatedName, deobfName)
        }

        val notchToSpigotSet = mergedMappingSet.merge(packageMappingSet)

        val notchToSrgSet = notchToSrg.file.reader().use { MappingFormats.TSRG.createReader(it).read() }
        val srgToMcpSet = srgToMcp.file.reader().use { MappingFormats.TSRG.createReader(it).read() }

        val srgToSpigotSet = notchToSrgSet.reverse().merge(notchToSpigotSet)

        val mcpToNotchSet = notchToSrgSet.merge(srgToMcpSet).reverse()
        val mcpToSpigotSet = mcpToNotchSet.merge(notchToSpigotSet)

        val spigotToSrgSet = srgToSpigotSet.reverse()
        val spigotToMcpSet = mcpToSpigotSet.reverse()
        val spigotToNotchSet = notchToSpigotSet.reverse()

        val spigotNotchToSrgSet = MappingSet.create()
        val prepend = "net/minecraft/server/"
        for (topLevelClassMapping in notchToSrgSet.topLevelClassMappings) {
            val obf = if (topLevelClassMapping.obfuscatedName.contains('/')) {
                topLevelClassMapping.obfuscatedName
            } else {
                prepend + topLevelClassMapping.obfuscatedName
            }
            val newMapping = spigotNotchToSrgSet.createTopLevelClassMapping(obf, topLevelClassMapping.deobfuscatedName)
            prependToObf(topLevelClassMapping, newMapping, prepend)
        }

        writeMappings(
            MappingFormats.TSRG,
            spigotToSrgSet to spigotToSrg.file,
            spigotToMcpSet to spigotToMcp.file,
            spigotToNotchSet to spigotToNotch.file,
            srgToSpigotSet to srgToSpigot.file,
            mcpToSpigotSet to mcpToSpigot.file,
            notchToSpigotSet to notchToSpigot.file
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

    private fun addMissingClasses(source: ClassMapping<*, *>, target: ClassMapping<*, *>) {
        for (innerClassMapping in source.innerClassMappings) {
            val otherMapping = target.getOrCreateInnerClassMapping(innerClassMapping.obfuscatedName)
            otherMapping.deobfuscatedName = innerClassMapping.deobfuscatedName
            addMissingClasses(innerClassMapping, otherMapping)
        }
    }

    private fun prependToObf(source: ClassMapping<*, *>, target: ClassMapping<*, *>, text: String) {
        for (innerClassMapping in source.innerClassMappings) {
            val newMapping = target.createInnerClassMapping(innerClassMapping.obfuscatedName, innerClassMapping.deobfuscatedName)
            prependToObf(innerClassMapping, newMapping, text)
        }

        for (fieldMapping in source.fieldMappings) {
            val newMapping = target.createFieldMapping(fieldMapping.obfuscatedName)
            newMapping.deobfuscatedName = fieldMapping.deobfuscatedName
        }

        for (methodMapping in source.methodMappings) {
            val sourceDescriptor = methodMapping.signature.descriptor

            val mappedParams = sourceDescriptor.paramTypes.map { prependToObfField(it, text) }
            val mappedReturn = when (val returnType = sourceDescriptor.returnType) {
                is FieldType -> prependToObfField(returnType, text)
                else -> returnType
            }

            val mappedDescriptor = MethodDescriptor(mappedParams, mappedReturn)
            target.createMethodMapping(MethodSignature(methodMapping.obfuscatedName, mappedDescriptor), methodMapping.deobfuscatedName)
        }
    }

    private fun prependToObfField(field: FieldType, text: String): FieldType {
        return when (field) {
            is ArrayType -> ArrayType(field.dimCount, prependToObfField(field.component, text))
            is BaseType -> field
            is ObjectType -> {
                if (field.className.contains('/')) {
                    field
                } else {
                    ObjectType(text + field.className)
                }
            }
            else -> throw PaperweightException("Unexpected FieldType: $field")
        }
    }
}
