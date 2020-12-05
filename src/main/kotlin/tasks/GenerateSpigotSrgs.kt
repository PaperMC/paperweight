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

import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.fileOrNull
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.writeMappings
import net.fabricmc.lorenztiny.TinyMappingFormat
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.merge.MappingSetMerger
import org.cadixdev.lorenz.merge.MappingSetMergerHandler
import org.cadixdev.lorenz.merge.MergeConfig
import org.cadixdev.lorenz.merge.MergeContext
import org.cadixdev.lorenz.merge.MergeResult
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.FieldMapping
import org.cadixdev.lorenz.model.InnerClassMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateSpigotSrgs : DefaultTask() {

    @get:InputFile
    abstract val notchToSrg: RegularFileProperty
    @get:InputFile
    abstract val srgToMcp: RegularFileProperty

    @get:InputFile
    abstract val classMappings: RegularFileProperty
    @get:InputFile
    abstract val memberMappings: RegularFileProperty
    @get:InputFile
    abstract val packageMappings: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val extraSpigotSrgMappings: RegularFileProperty
    @get:InputFile
    abstract val loggerFields: RegularFileProperty
    @get:InputFile
    abstract val paramIndexes: RegularFileProperty

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:InputFile
    abstract val mergedMappings: RegularFileProperty

    @get:OutputFile
    abstract val spigotToSrg: RegularFileProperty
    @get:OutputFile
    abstract val spigotToMcp: RegularFileProperty
    @get:OutputFile
    abstract val spigotToNotch: RegularFileProperty
    @get:OutputFile
    abstract val srgToSpigot: RegularFileProperty
    @get:OutputFile
    abstract val mcpToSpigot: RegularFileProperty
    @get:OutputFile
    abstract val notchToSpigot: RegularFileProperty

    @get:OutputFile
    abstract val spigotToNamed: RegularFileProperty

    @TaskAction
    fun run() {
        val classMappingSet = MappingFormats.CSRG.createReader(classMappings.file.toPath()).use { it.read() }
        val memberMappingSet = MappingFormats.CSRG.createReader(memberMappings.file.toPath()).use { it.read() }
        val mergedMappingSet = MappingSetMerger.create(classMappingSet, memberMappingSet).merge()

        for (line in loggerFields.file.readLines(Charsets.UTF_8)) {
            val (className, fieldName) = line.split(' ')
            val classMapping = mergedMappingSet.getClassMapping(className).orElse(null) ?: continue
            classMapping.getOrCreateFieldMapping(fieldName, "Lorg/apache/logging/log4j/Logger;").deobfuscatedName = "LOGGER"
        }

        // Get the new package name
        val newPackage = packageMappings.asFile.get().readLines()[0].split(Regex("\\s+"))[1]

        // We'll use notch->srg to pick up any classes spigot doesn't map for the package mapping
        val notchToSrgSet = MappingFormats.TSRG.createReader(notchToSrg.file.toPath()).use { it.read() }

        val mergedMojangMappings = TinyMappingFormat.STANDARD.read(mergedMappings.path, "official", "named")

        val notchToSpigotSet = MappingSetMerger.create(
            mergedMappingSet,
            mergedMojangMappings,
            MergeConfig.builder()
                .withMergeHandler(SpigotPackageMergerHandler(newPackage))
                .build()
        ).merge()

        val srgToMcpSet = MappingFormats.TSRG.createReader(srgToMcp.file.toPath()).use { it.read() }

        val spigotToSrgSet = MappingSetMerger.create(notchToSpigotSet.reverse(), notchToSrgSet).merge()
        extraSpigotSrgMappings.fileOrNull?.toPath()?.let { path ->
            MappingFormats.TSRG.createReader(path).use { it.read(spigotToSrgSet) }
        }

        val mcpToNotchSet = MappingSetMerger.create(notchToSrgSet, srgToMcpSet).merge().reverse()
        val mcpToSpigotSet = MappingSetMerger.create(mcpToNotchSet, notchToSpigotSet).merge()

        val srgToSpigotSet = spigotToSrgSet.reverse()
        val spigotToMcpSet = mcpToSpigotSet.reverse()
        val spigotToNotchSet = notchToSpigotSet.reverse()

        writeMappings(
            MappingFormats.TSRG,
            spigotToSrgSet to spigotToSrg.file,
            spigotToMcpSet to spigotToMcp.file,
            spigotToNotchSet to spigotToNotch.file,
            srgToSpigotSet to srgToSpigot.file,
            mcpToSpigotSet to mcpToSpigot.file,
            notchToSpigotSet to notchToSpigot.file
        )

        val adjustedMergedMojangMappings = adjustParamIndexes(mergedMojangMappings)
        val spigotToNamedSet = MappingSetMerger.create(
            notchToSpigotSet.reverse(),
            adjustedMergedMojangMappings
        ).merge()
        TinyMappingFormat.STANDARD.write(spigotToNamedSet, spigotToNamed.path, "spigot", "named")
    }

    private fun adjustParamIndexes(mappings: MappingSet): MappingSet {
        val indexes = hashMapOf<String, HashMap<String, HashMap<Int, Int>>>()

        paramIndexes.file.useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(" ")
                val (className, methodName, descriptor) = parts

                val paramMap = indexes.computeIfAbsent(className) { hashMapOf() }
                    .computeIfAbsent(methodName + descriptor) { hashMapOf() }

                for (i in 3 until parts.size step 2) {
                    paramMap[parts[i].toInt()] = parts[i + 1].toInt()
                }
            }
        }

        val result = MappingSet.create()
        for (old in mappings.topLevelClassMappings) {
            val new = result.createTopLevelClassMapping(old.obfuscatedName, old.deobfuscatedName)
            copyClass(old, new, indexes)
        }

        return result
    }

    private fun copyClass(
        from: ClassMapping<*, *>,
        to: ClassMapping<*, *>,
        params: Map<String, Map<String, Map<Int, Int>>>
    ) {
        for (mapping in from.fieldMappings) {
            to.createFieldMapping(mapping.signature, mapping.deobfuscatedName)
        }
        for (mapping in from.innerClassMappings) {
            val newMapping = to.createInnerClassMapping(mapping.obfuscatedName, mapping.deobfuscatedName)
            copyClass(mapping, newMapping, params)
        }

        val classMap = params[from.fullObfuscatedName]
        for (mapping in from.methodMappings) {
            val newMapping = to.createMethodMapping(mapping.signature, mapping.deobfuscatedName)

            val paramMappings = mapping.parameterMappings
            if (paramMappings.isEmpty() || classMap == null) {
                continue
            }

            val methodMap = classMap[mapping.signature.toJvmsIdentifier()] ?: continue
            for (paramMapping in paramMappings) {
                val i = methodMap[paramMapping.index] ?: paramMapping.index
                newMapping.createParameterMapping(i, paramMapping.deobfuscatedName)
            }
        }
    }
}

class SpigotPackageMergerHandler(private val newPackage: String) : MappingSetMergerHandler {

    override fun mergeTopLevelClassMappings(
        left: TopLevelClassMapping,
        right: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        throw IllegalStateException("Unexpectedly merged class: ${left.fullObfuscatedName}")
    }

    override fun mergeDuplicateTopLevelClassMappings(
        left: TopLevelClassMapping,
        right: TopLevelClassMapping,
        rightContinuation: TopLevelClassMapping?,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        // If both are provided, keep spigot
        return MergeResult(
            target.createTopLevelClassMapping(left.obfuscatedName, prependPackage(left.deobfuscatedName)),
            right
        )
    }

    override fun addLeftTopLevelClassMapping(
        left: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        throw IllegalStateException("Unexpected added class from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}")
    }

    override fun addRightTopLevelClassMapping(
        right: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        // This is a mapping Spigot is totally missing, but Spigot maps all classes without a package to
        // /net/minecraft regardless if there are mappings for the classes or not
        return MergeResult(
            target.createTopLevelClassMapping(right.obfuscatedName, prependPackage(right.obfuscatedName)),
            right
        )
    }

    override fun mergeInnerClassMappings(
        left: InnerClassMapping,
        right: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        throw IllegalStateException("Unexpectedly merged class: ${left.fullObfuscatedName}")
//        return MergeResult(target.createInnerClassMapping(left.obfuscatedName, left.deobfuscatedName), right)
    }

    override fun mergeDuplicateInnerClassMappings(
        left: InnerClassMapping,
        right: InnerClassMapping,
        rightContinuation: InnerClassMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        return MergeResult(
            target.createInnerClassMapping(left.obfuscatedName, left.deobfuscatedName),
            right
        )
    }

    override fun addLeftInnerClassMapping(
        left: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping> {
        throw IllegalStateException("Unexpected added class from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}")
    }

    override fun addRightInnerClassMapping(
        right: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        // We want to get all of the inner classes from SRG, but not the SRG names
        return MergeResult(target.createInnerClassMapping(right.obfuscatedName, right.obfuscatedName), right)
    }

    override fun mergeFieldMappings(
        left: FieldMapping,
        strictRight: FieldMapping?,
        looseRight: FieldMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping {
        throw IllegalStateException("Unexpectedly merged field: ${left.fullObfuscatedName}")
    }

    override fun mergeDuplicateFieldMappings(
        left: FieldMapping,
        strictRightDuplicate: FieldMapping?,
        looseRightDuplicate: FieldMapping?,
        strictRightContinuation: FieldMapping?,
        looseRightContinuation: FieldMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping {
        val right = strictRightDuplicate ?: looseRightDuplicate ?: strictRightContinuation ?: looseRightContinuation ?: left
        return target.createFieldMapping(right.signature, left.deobfuscatedName)
    }

    override fun mergeMethodMappings(
        left: MethodMapping,
        standardRight: MethodMapping?,
        wiggledRight: MethodMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        throw IllegalStateException("Unexpectedly merged method: $left")
    }

    override fun mergeDuplicateMethodMappings(
        left: MethodMapping,
        standardRightDuplicate: MethodMapping?,
        wiggledRightDuplicate: MethodMapping?,
        standardRightContinuation: MethodMapping?,
        wiggledRightContinuation: MethodMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        return MergeResult(target.createMethodMapping(left.signature, left.deobfuscatedName))
    }

    // Disable srg member mappings
    override fun addRightFieldMapping(
        right: FieldMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping? {
        return null
    }

    override fun addRightMethodMapping(
        right: MethodMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        return MergeResult(null)
    }

    private fun prependPackage(name: String): String {
        return if (name.contains('/')) {
            name
        } else {
            newPackage + name
        }
    }
}
