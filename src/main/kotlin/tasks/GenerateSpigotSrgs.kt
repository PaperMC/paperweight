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

import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.writeMappings
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
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.lang.IllegalStateException

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
    @InputFile
    val extraSpigotSrgMappings: RegularFileProperty = project.objects.fileProperty()

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
        val classMappingSet = MappingFormats.CSRG.createReader(classMappings.file.toPath()).use { it.read() }
        val memberMappingSet = MappingFormats.CSRG.createReader(memberMappings.file.toPath()).use { it.read() }
        val mergedMappingSet = MappingSetMerger.create(classMappingSet, memberMappingSet).merge()

        // Get the new package name
        val newPackage = packageMappings.asFile.get().readLines()[0].split(Regex("\\s+"))[1]

        // We'll use notch->srg to pick up any classes spigot doesn't map for the package mapping
        val notchToSrgSet = MappingFormats.TSRG.createReader(notchToSrg.file.toPath()).use { it.read() }

        val notchToSpigotSet = MappingSetMerger.create(
            mergedMappingSet,
            notchToSrgSet,
            MergeConfig.builder()
                .withHandler(SpigotPackageMergerHandler(newPackage))
                .build()
        ).merge()

        val srgToMcpSet = MappingFormats.TSRG.createReader(srgToMcp.file.toPath()).use { it.read() }

        val spigotToSrgSet = MappingSetMerger.create(notchToSpigotSet.reverse(), notchToSrgSet).merge()
        MappingFormats.TSRG.createReader(extraSpigotSrgMappings.file.toPath()).use { it.read(spigotToSrgSet) }

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
    }
}

class SpigotPackageMergerHandler(private val newPackage: String) : MappingSetMergerHandler {

    override fun mergeTopLevelClassMappings(
        left: TopLevelClassMapping,
        right: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        throw IllegalStateException("Unexpectedly merged class: $left")
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
        return MergeResult(
            target.createTopLevelClassMapping(left.obfuscatedName, prependPackage(left.deobfuscatedName))
        )
    }
    override fun addRightTopLevelClassMapping(
        right: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        // We know we don't need client mappings
        return if (right.deobfuscatedName.contains("/client/")) {
            MergeResult(null)
        } else {
            MergeResult(
                target.createTopLevelClassMapping(right.obfuscatedName, prependPackage(right.obfuscatedName)),
                right
            )
        }
    }

    override fun mergeInnerClassMappings(
        left: InnerClassMapping,
        right: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        return MergeResult(target.createInnerClassMapping(left.obfuscatedName, left.deobfuscatedName), right)
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
        right: FieldMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping? {
        throw IllegalStateException("Unexpectedly merged field: $left")
    }
    override fun mergeDuplicateFieldMappings(
        left: FieldMapping,
        right: FieldMapping,
        rightContinuation: FieldMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping? {
        return target.createFieldMapping(left.obfuscatedName, left.deobfuscatedName)
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
