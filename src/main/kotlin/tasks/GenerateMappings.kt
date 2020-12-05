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

import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.path
import java.nio.file.FileSystems
import java.nio.file.Files
import net.fabricmc.lorenztiny.TinyMappingFormat
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.asm.LorenzRemapper
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.merge.FieldMergeStrategy
import org.cadixdev.lorenz.merge.MappingSetMerger
import org.cadixdev.lorenz.merge.MappingSetMergerHandler
import org.cadixdev.lorenz.merge.MergeConfig
import org.cadixdev.lorenz.merge.MergeContext
import org.cadixdev.lorenz.merge.MergeResult
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.FieldMapping
import org.cadixdev.lorenz.model.InnerClassMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.cadixdev.lorenz.model.MethodParameterMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateMappings : DefaultTask() {

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:InputFile
    abstract val vanillaMappings: RegularFileProperty
    @get:InputFile
    abstract val yarnMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @TaskAction
    fun run() {
        val vanillaMappings = MappingFormats.byId("proguard").createReader(vanillaMappings.path).use { it.read() }.reverse()

        val yarnMappings = FileSystems.newFileSystem(yarnMappings.path, null).use { fs ->
            val path = fs.getPath("mappings", "mappings.tiny")
            TinyMappingFormat.STANDARD.read(path, "official", "named")
        }

        val merged = MappingSetMerger.create(
            vanillaMappings,
            yarnMappings,
            MergeConfig.builder()
                .withFieldMergeStrategy(FieldMergeStrategy.STRICT)
                .withMergeHandler(MergeHandler())
                .build()
        ).merge()

        // Fill out any missing inheritance info in the mappings
        val atlasOut = Files.createTempFile("paperweight", "jar")
        try {
            Atlas().use { atlas ->
                atlas.install { ctx -> JarEntryRemappingTransformer(LorenzRemapper(merged, ctx.inheritanceProvider())) }
                atlas.run(vanillaJar.path, atlasOut)
            }
        } finally {
            Files.deleteIfExists(atlasOut)
        }

        ensureParentExists(outputMappings)
        TinyMappingFormat.STANDARD.write(merged, outputMappings.path, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)
    }

    /*
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
     */

    /*
    private fun mergeClass(vanillaMapping: ClassMapping<*, *>, yarnMapping: ClassMapping<*, *>) {
        for (vanillaMethod in vanillaMapping.methodMappings) {
            val yarnMethod = yarnMapping.getMethodMapping(vanillaMethod.signature).orNull ?: continue
            for (yarnParam in yarnMethod.parameterMappings) {
                vanillaMethod.getOrCreateParameterMapping(yarnParam.index).deobfuscatedName = yarnParam.deobfuscatedName
            }
        }

        for (vanillaClass in vanillaMapping.innerClassMappings) {
            val yarnClass = yarnMapping.getInnerClassMapping(vanillaClass.obfuscatedName).orNull ?: continue
            mergeClass(vanillaClass, yarnClass)
        }
    }
     */
}

class MergeHandler : MappingSetMergerHandler {

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
        return MergeResult(
            target.createTopLevelClassMapping(left.obfuscatedName, left.deobfuscatedName),
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

    override fun mergeFieldMappings(
        left: FieldMapping,
        strictRight: FieldMapping?,
        looseRight: FieldMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping? {
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
    ): FieldMapping? {
        return target.createFieldMapping(left.signature, left.deobfuscatedName)
    }
    override fun addLeftFieldMapping(
        left: FieldMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping? {
        return target.createFieldMapping(left.signature, left.deobfuscatedName)
    }

    override fun mergeMethodMappings(
        left: MethodMapping,
        standardRight: MethodMapping?,
        wiggledRight: MethodMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        throw IllegalStateException("Unexpectedly merged method: ${left.fullObfuscatedName}")
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
        return MergeResult(
            target.createMethodMapping(left.signature, left.deobfuscatedName),
            listOfNotNull(standardRightDuplicate, wiggledRightDuplicate)
        )
    }

    override fun mergeParameterMappings(
        left: MethodParameterMapping,
        right: MethodParameterMapping,
        target: MethodMapping,
        context: MergeContext
    ): MethodParameterMapping? {
        throw IllegalStateException("Unexpectedly merged method: ${left.fullObfuscatedName}")
    }
}
