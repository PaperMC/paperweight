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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.fileOrNull
import io.papermc.paperweight.util.getCsvReader
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.writeMappings
import java.nio.file.Files
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.asm.LorenzRemapper
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.merge.MappingSetMerger
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.InnerClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateSrgs : DefaultTask() {

    @get:InputFile
    abstract val methodsCsv: RegularFileProperty
    @get:InputFile
    abstract val fieldsCsv: RegularFileProperty
    @get:Optional
    @get:InputFile
    abstract val extraNotchSrgMappings: RegularFileProperty

    @get:InputFile
    abstract val inSrg: RegularFileProperty

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:OutputFile
    abstract val notchToSrg: RegularFileProperty
    @get:OutputFile
    abstract val notchToMcp: RegularFileProperty
    @get:OutputFile
    abstract val mcpToNotch: RegularFileProperty
    @get:OutputFile
    abstract val mcpToSrg: RegularFileProperty
    @get:OutputFile
    abstract val srgToNotch: RegularFileProperty
    @get:OutputFile
    abstract val srgToMcp: RegularFileProperty

    @TaskAction
    fun run() {
        val methods = HashMap<String, String>()
        val fields = HashMap<String, String>()
        readCsvs(methods, fields)

        val inSet = MappingFormats.TSRG.createReader(inSrg.file.toPath()).use { it.read() }
        extraNotchSrgMappings.fileOrNull?.toPath()?.let { path ->
            MappingFormats.TSRG.createReader(path).use { it.read(inSet) }
        }

        // Fill out any missing inheritance info in the mappings
        val atlasOut = Files.createTempFile("paperweight", "jar")
        try {
            Atlas().use { atlas ->
                atlas.install { ctx -> JarEntryRemappingTransformer(LorenzRemapper(inSet, ctx.inheritanceProvider())) }
                atlas.run(vanillaJar.path, atlasOut)
            }
        } finally {
            Files.deleteIfExists(atlasOut)
        }

        ensureParentExists(notchToSrg, notchToMcp, mcpToNotch, mcpToSrg, srgToNotch, srgToMcp)
        createMappings(inSet, methods, fields)
    }

    private fun readCsvs(methods: MutableMap<String, String>, fields: MutableMap<String, String>) {
        getCsvReader(methodsCsv.asFile.get()).use { reader ->
            for (line in reader.readAll()) {
                methods[line[0]] = line[1]
            }
        }

        getCsvReader(fieldsCsv.asFile.get()).use { reader ->
            for (line in reader.readAll()) {
                fields[line[0]] = line[1]
            }
        }
    }

    private fun createMappings(inSet: MappingSet, methods: Map<String, String>, fields: Map<String, String>) {
        val notchToSrgSet = MappingSet.create()

        for (mapping in inSet.topLevelClassMappings) {
            val newMapping = notchToSrgSet.createTopLevelClassMapping(mapping.obfuscatedName, mapping.deobfuscatedName)
            remapMembers(mapping, newMapping)
        }
        handleKeywordMappings(notchToSrgSet)

        // We have Notch -> SRG
        val srgToNotchSet = notchToSrgSet.reverse()

        val notchToMcpSet: MappingSet
        val mcpToNotchSet: MappingSet
        val mcpToSrgSet: MappingSet

        val srgToMcpSet = MappingSet.create()

        // Create SRG -> MCP from Notch -> SRG and the CSVs
        for (topLevelClassMapping in inSet.topLevelClassMappings) {
            // MCP and SRG have the same class names
            val srgToMcpClass = srgToMcpSet.createTopLevelClassMapping(
                topLevelClassMapping.deobfuscatedName,
                topLevelClassMapping.deobfuscatedName
            )

            mapClass(topLevelClassMapping, srgToMcpClass, methods, fields)
        }

        // We have Notch->SRG and SRG->MCP mapping sets now
        // All other sets can be generated from these two
        notchToMcpSet = MappingSetMerger.create(notchToSrgSet, srgToMcpSet).merge()
        mcpToNotchSet = notchToMcpSet.reverse()
        mcpToSrgSet = srgToMcpSet.reverse()

        writeMappings(
            MappingFormats.TSRG,
            notchToSrgSet to notchToSrg.asFile.get(),
            notchToMcpSet to notchToMcp.asFile.get(),
            mcpToNotchSet to mcpToNotch.asFile.get(),
            mcpToSrgSet to mcpToSrg.asFile.get(),
            srgToNotchSet to srgToNotch.asFile.get(),
            srgToMcpSet to srgToMcp.asFile.get()
        )
    }

    private fun handleKeywordMappings(mappings: MappingSet) {
        for (classMapping in mappings.topLevelClassMappings) {
            handleKeywordMappingClass(classMapping)
        }
    }

    private fun handleKeywordMappingClass(classMapping: ClassMapping<*, *>) {
        for (innerClassMapping in classMapping.innerClassMappings) {
            handleKeywordMappingClass(innerClassMapping)
        }
        for (fieldMapping in classMapping.fieldMappings) {
            if (fieldMapping.obfuscatedName in javaKeywords) {
                val sourceName = fieldMapping.obfuscatedName + '_'
                if (classMapping.hasFieldMapping(sourceName)) {
                    // If the "source name" of the mapping already exists, just skip it. I don't even know what would
                    // happen in this case at decompile time to be honest
                    continue
                }
                classMapping.createFieldMapping(sourceName, fieldMapping.deobfuscatedName)
            }
        }
        for (methodMapping in classMapping.methodMappings) {
            if (methodMapping.obfuscatedName in javaKeywords) {
                val sourceName = methodMapping.obfuscatedName + "_"
                val sourceSignature = MethodSignature(sourceName, methodMapping.signature.descriptor)
                if (classMapping.hasMethodMapping(sourceSignature)) {
                    continue
                }
                classMapping.createMethodMapping(sourceSignature, methodMapping.deobfuscatedName)
            }
        }
    }

    private fun mapClass(inClass: ClassMapping<*, *>, outClass: ClassMapping<*, *>, methods: Map<String, String>, fields: Map<String, String>) {
        for (fieldMapping in inClass.fieldMappings) {
            val mcpName = fields[fieldMapping.deobfuscatedName] ?: fieldMapping.deobfuscatedName
            val mapping = outClass.createFieldMapping(fieldMapping.deobfuscatedSignature, mcpName)
            mapping.obfuscatedName
        }
        for (methodMapping in inClass.methodMappings) {
            val mcpName = methods[methodMapping.deobfuscatedName] ?: methodMapping.deobfuscatedName
            outClass.createMethodMapping(methodMapping.deobfuscatedSignature, mcpName)
        }
        for (innerClassMapping in inClass.innerClassMappings) {
            val innerOutClass = outClass.createInnerClassMapping(innerClassMapping.deobfuscatedName)
            mapClass(innerClassMapping, innerOutClass, methods, fields)
        }
    }

    private fun remapAnonymousClasses(mapping: InnerClassMapping, target: ClassMapping<*, *>) {
        val newMapping = target.createInnerClassMapping(mapping.obfuscatedName, mapping.deobfuscatedName)
        remapMembers(mapping, newMapping)
    }

    private fun <T : ClassMapping<*, *>> remapMembers(mapping: T, newMapping: T) {
        for (fieldMapping in mapping.fieldMappings) {
            newMapping.createFieldMapping(fieldMapping.obfuscatedName, fieldMapping.deobfuscatedName)
        }
        for (methodMapping in mapping.methodMappings) {
            newMapping.createMethodMapping(methodMapping.signature, methodMapping.deobfuscatedName)
        }
        for (innerClassMapping in mapping.innerClassMappings) {
            remapAnonymousClasses(innerClassMapping, newMapping)
        }
    }
}

private val javaKeywords: HashSet<String> = hashSetOf(
    "abstract",
    "continue",
    "for",
    "new",
    "switch",
    "assert",
    "default",
    "goto",
    "package",
    "synchronized",
    "boolean",
    "do",
    "if",
    "private",
    "this",
    "break",
    "double",
    "implements",
    "protected",
    "throw",
    "byte",
    "else",
    "import",
    "public",
    "throws",
    "case",
    "enum",
    "instanceof",
    "return",
    "transient",
    "catch",
    "extends",
    "int",
    "short",
    "try",
    "char",
    "final",
    "interface",
    "static",
    "void",
    "class",
    "finally",
    "long",
    "strictfp",
    "volatile",
    "const",
    "float",
    "native",
    "super",
    "while"
)
