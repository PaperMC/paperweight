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
    abstract val sourceMappings: RegularFileProperty

    @get:OutputFile
    abstract val notchToSpigotMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:OutputFile
    abstract val spigotMemberMappings: RegularFileProperty

    @TaskAction
    fun run() {
        val spigotClassMappings = MappingFormats.CSRG.createReader(classMappings.path).use { it.read() }

        val sourceMappings = MappingFormats.TINY.read(
            sourceMappings.path,
            OBF_NAMESPACE,
            DEOBF_NAMESPACE
        )

        val notchToSpigotSet = MappingSetMerger.create(
            spigotClassMappings,
            sourceMappings,
            MergeConfig.builder()
                .withMergeHandler(SpigotMappingsMergerHandler)
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

        val spigotMembers = createSpigotMemberMappings(sourceMappings, spigotClassMappings)
        MappingFormats.CSRG.write(spigotMembers, spigotMemberMappings.path)
    }
}

object SpigotMappingsMergerHandler : MappingSetMergerHandler {

    //
    // TOP LEVEL CLASS
    //

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
        // This is a mapping Spigot is totally missing
        return MergeResult(
            target.createTopLevelClassMapping(right.obfuscatedName, right.obfuscatedName),
            right
        )
    }

    //
    // INNER CLASS
    //

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

    //
    // FIELD
    //

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

    override fun addLeftFieldMapping(left: FieldMapping, target: ClassMapping<*, *>, context: MergeContext): FieldMapping? {
        throw IllegalStateException(
            "Unexpected added field from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}"
        )
    }

    //
    // METHOD
    //

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
        strictRightDuplicate: MethodMapping?,
        looseRightDuplicate: MethodMapping?,
        strictRightContinuation: MethodMapping?,
        looseRightContinuation: MethodMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        val right = strictRightDuplicate ?: looseRightDuplicate ?: strictRightContinuation ?: looseRightContinuation ?: left
        return MergeResult(target.createMethodMapping(left.signature, left.deobfuscatedName), right)
    }

    override fun addLeftMethodMapping(
        left: MethodMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        throw IllegalStateException(
            "Unexpected added method from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}"
        )
    }
}

private fun createSpigotMemberMappings(mappings: MappingSet, spigotClassMappings: MappingSet): MappingSet {
    val newMappings = MappingSet.create()

    for (topLevelClassMapping in mappings.topLevelClassMappings) {
        val name = spigotClassMappings.getTopLevelClassMapping(topLevelClassMapping.obfuscatedName).orElse(topLevelClassMapping).deobfuscatedName
        val newClassMappings = newMappings.createTopLevelClassMapping(name, name)
        createSpigotMemberMappings(topLevelClassMapping, newClassMappings, spigotClassMappings)
    }

    return newMappings
}

private fun createSpigotMemberMappings(old: ClassMapping<*, *>, new: ClassMapping<*, *>, spigotClassMappings: MappingSet) {
    for (innerClassMapping in old.innerClassMappings) {
        val name = spigotClassMappings.getClassMapping(innerClassMapping.fullObfuscatedName)
            .map { it.deobfuscatedName }
            .orElse(innerClassMapping.obfuscatedName)
        val newClassMappings = new.createInnerClassMapping(name, name)
        createSpigotMemberMappings(innerClassMapping, newClassMappings, spigotClassMappings)
    }

    for (fieldMapping in old.fieldMappings) {
        new.createFieldMapping(FieldSignature(fieldMapping.obfuscatedName, fieldMapping.type.get()), fieldMapping.deobfuscatedName)
    }

    for (methodMapping in old.methodMappings) {
        if (methodMapping.deobfuscatedName.contains("$") ||
            methodMapping.deobfuscatedName == "<init>" ||
            methodMapping.deobfuscatedName == "<clinit>"
        ) {
            continue
        }

        val desc = spigotClassMappings.deobfuscate(methodMapping.descriptor)
        new.createMethodMapping(MethodSignature(methodMapping.obfuscatedName, desc)).also {
            it.deobfuscatedName = methodMapping.deobfuscatedName
        }
    }
}
