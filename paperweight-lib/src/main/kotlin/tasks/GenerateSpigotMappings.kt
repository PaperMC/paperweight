/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.component3
import kotlin.collections.component4
import kotlin.collections.set
import kotlin.io.path.*
import org.cadixdev.bombe.type.signature.FieldSignature
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
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
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateSpigotMappings : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classMappings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val memberMappings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val loggerFields: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val syntheticMethods: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceMappings: RegularFileProperty

    @get:OutputFile
    abstract val notchToSpigotMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:OutputFile
    abstract val spigotFieldMappings: RegularFileProperty

    @TaskAction
    fun run() {
        val classMappingSet = MappingFormats.CSRG.createReader(classMappings.path).use { it.read() }
        val memberMappingSet = MappingFormats.CSRG.createReader(memberMappings.path).use { it.read() }
        val mergedMappingSet = MappingSetMerger.create(classMappingSet, memberMappingSet).merge()

        for (line in loggerFields.path.readLines(Charsets.UTF_8)) {
            val (className, fieldName) = line.split(' ')
            val classMapping = mergedMappingSet.getClassMapping(className).orElse(null) ?: continue
            classMapping.getOrCreateFieldMapping(fieldName, "Lorg/apache/logging/log4j/Logger;").deobfuscatedName = "LOGGER"
        }

        val sourceMappings = MappingFormats.TINY.read(
            sourceMappings.path,
            OBF_NAMESPACE,
            DEOBF_NAMESPACE
        )

        val synths = hashMapOf<String, MutableMap<String, MutableMap<String, String>>>()
        syntheticMethods.path.useLines { lines ->
            for (line in lines) {
                val (className, desc, synthName, baseName) = line.split(" ")
                synths.computeIfAbsent(className) { hashMapOf() }
                    .computeIfAbsent(desc) { hashMapOf() }[baseName] = synthName
            }
        }

        val notchToSpigotSet = MappingSetMerger.create(
            mergedMappingSet,
            sourceMappings,
            MergeConfig.builder()
                .withMergeHandler(SpigotMappingsMergerHandler(synths))
                .build()
        ).merge()

        val spigotToNamedSet = notchToSpigotSet.reverse().merge(sourceMappings)

        MappingFormats.TINY.write(
            notchToSpigotSet,
            notchToSpigotMappings.path,
            OBF_NAMESPACE,
            SPIGOT_NAMESPACE
        )

        MappingFormats.TINY.write(
            spigotToNamedSet,
            outputMappings.path,
            SPIGOT_NAMESPACE,
            DEOBF_NAMESPACE
        )

        val fieldSourceMappings = extractFieldMappings(sourceMappings, classMappingSet)
        MappingFormats.CSRG.write(fieldSourceMappings, spigotFieldMappings.path)
    }
}

typealias Synths = Map<String, Map<String, Map<String, String>>>

class SpigotMappingsMergerHandler(private val synths: Synths) : MappingSetMergerHandler {

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
            target.createTopLevelClassMapping(left.obfuscatedName, left.deobfuscatedName),
            right
        )
    }

    override fun addLeftTopLevelClassMapping(
        left: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        throw IllegalStateException(
            "Unexpected added class from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}"
        )
    }

    override fun addRightTopLevelClassMapping(
        right: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        // This is a mapping Spigot is totally missing, but Spigot maps all classes without a package to
        // /net/minecraft regardless if there are mappings for the classes or not
        return MergeResult(
            target.createTopLevelClassMapping(right.obfuscatedName, right.obfuscatedName),
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
        throw IllegalStateException(
            "Unexpected added class from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}"
        )
    }

    override fun addRightInnerClassMapping(
        right: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        // We want to get all of the inner classes from mojmap, but not the mojmap names
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
        // Check if Spigot calls this mapping something else
        val synthMethods = synths[left.parent.fullObfuscatedName]?.get(left.obfuscatedDescriptor)
        val newName = synthMethods?.get(left.obfuscatedName)
        return if (newName != null) {
            val newLeftMapping = left.parentClass.getMethodMapping(MethodSignature(newName, left.descriptor)).orNull
            val newMapping = if (newLeftMapping != null) {
                target.getOrCreateMethodMapping(newLeftMapping.signature).also {
                    it.deobfuscatedName = left.deobfuscatedName
                }
            } else {
                target.getOrCreateMethodMapping(left.signature).also {
                    it.deobfuscatedName = newName
                }
            }
            MergeResult(newMapping)
        } else {
            val newMapping = target.getOrCreateMethodMapping(left.signature).also {
                it.deobfuscatedName = left.deobfuscatedName
            }
            MergeResult(newMapping)
        }
    }

    override fun addLeftMethodMapping(
        left: MethodMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        /*
         * Check if Spigot maps this from a synthetic method name
         * What this means is:
         * Spigot has a mapping for
         *     a b()V -> something
         * But in Mojang's mapping there's only
         *     a a()V -> somethingElse
         * The original method is named a, but spigot calls it b, because
         * b is a synthetic method for a. In this case we should create the mapping as
         *     a a()V -> something
         */

        var obfName: String? = null
        val synthMethods = synths[left.parent.fullObfuscatedName]?.get(left.obfuscatedDescriptor)
        if (synthMethods != null) {
            // This is a reverse lookup
            for ((base, synth) in synthMethods) {
                if (left.obfuscatedName == synth) {
                    obfName = base
                    break
                }
            }
        }

        if (obfName == null) {
            return emptyMergeResult()
        }

        val newMapping = target.getOrCreateMethodMapping(obfName, left.descriptor)
        newMapping.deobfuscatedName = left.deobfuscatedName
        return MergeResult(newMapping)
    }

    override fun addLeftFieldMapping(left: FieldMapping, target: ClassMapping<*, *>, context: MergeContext): FieldMapping? {
        // We don't want mappings Spigot thinks exist but don't
        return null
    }

    // Disable non-spigot mappings
    override fun addRightMethodMapping(
        right: MethodMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        // Check if spigot changes this method automatically
        val synthMethods = synths[right.parentClass.fullObfuscatedName]?.get(right.obfuscatedDescriptor)
        val newName = synthMethods?.get(right.obfuscatedName) ?: return emptyMergeResult()

        val newClassMapping = context.left.getClassMapping(right.parentClass.fullObfuscatedName).orNull
        val newMethodMapping = newClassMapping?.getMethodMapping(MethodSignature(newName, right.descriptor))?.orNull
        val newMapping = target.getOrCreateMethodMapping(right.signature)
        if (newMethodMapping != null) {
            newMapping.deobfuscatedName = newMethodMapping.deobfuscatedName
        } else {
            newMapping.deobfuscatedName = newName
        }
        return MergeResult(newMapping)
    }
}

private fun extractFieldMappings(mappings: MappingSet, spigotClassMappings: MappingSet): MappingSet {
    val newMappings = MappingSet.create()

    for (topLevelClassMapping in mappings.topLevelClassMappings) {
        val name = spigotClassMappings.getTopLevelClassMapping(topLevelClassMapping.obfuscatedName).orElse(topLevelClassMapping).deobfuscatedName
        val newClassMappings = newMappings.createTopLevelClassMapping(name, name)
        extractFieldMappings(topLevelClassMapping, newClassMappings, spigotClassMappings)
    }

    return newMappings
}

private fun extractFieldMappings(old: ClassMapping<*, *>, new: ClassMapping<*, *>, spigotClassMappings: MappingSet) {
    for (innerClassMapping in old.innerClassMappings) {
        val name = spigotClassMappings.getClassMapping(innerClassMapping.fullObfuscatedName)
            .map { it.deobfuscatedName }
            .orElse(innerClassMapping.obfuscatedName)
        val newClassMappings = new.createInnerClassMapping(name, name)
        extractFieldMappings(innerClassMapping, newClassMappings, spigotClassMappings)
    }

    for (fieldMapping in old.fieldMappings) {
        val fieldName = when (val name = fieldMapping.obfuscatedName) {
            "if", "do" -> name + "_"
            else -> name
        }
        new.createFieldMapping(FieldSignature(fieldName, fieldMapping.type.get()), fieldMapping.deobfuscatedName)
    }
}
