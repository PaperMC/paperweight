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
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.asm.LorenzRemapper
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
    // test
    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

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
    // tet
    @get:OutputFile
    abstract val atlasTest: RegularFileProperty

    @TaskAction
    fun run() {
        val classMappingSet = MappingFormats.CSRG.createReader(classMappings.file.toPath()).use { it.read() }
        val memberMappingSet = MappingFormats.CSRG.createReader(memberMappings.file.toPath()).use { it.read() }
        val mergedMappingSet = MappingSetMerger.create(classMappingSet, memberMappingSet).merge()

        for (line in loggerFields.file.readLines(Charsets.UTF_8)) {
            val (className, fieldName) = line.split(' ')
            val classMapping = mergedMappingSet.getClassMapping(className).orElse(null) ?: continue
            classMapping.getOrCreateFieldMapping(fieldName).deobfuscatedName = "LOGGER"
        }

        // Get the new package name
        val newPackage = packageMappings.asFile.get().readLines()[0].split(Regex("\\s+"))[1]

        // We'll use notch->srg to pick up any classes spigot doesn't map for the package mapping
        val notchToSrgSet = MappingFormats.TSRG.createReader(notchToSrg.file.toPath()).use { it.read() }

        val notchToSpigotSet = MappingSetMerger.create(
            mergedMappingSet,
            notchToSrgSet,
            MergeConfig.builder()
                .withMergeHandler(SpigotPackageMergerHandler(newPackage))
                .build()
        ).merge()

        // notch <-> spigot is incomplete here, it would result in inheritance issues to work with this incomplete set.
        // so we use it once to remap some jar
        println("running atlas to complete mappings...")
        Atlas().apply {
            install { ctx -> JarEntryRemappingTransformer(LorenzRemapper(notchToSpigotSet, ctx.inheritanceProvider())) }
            run(vanillaJar.path, atlasTest.path)
            close()
        }

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
        throw IllegalStateException("Unexpectedly add class from Spigot: $left")
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
        strictRight: FieldMapping?,
        looseRight: FieldMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping {
        throw IllegalStateException("Unexpectedly merged field: $left")
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
