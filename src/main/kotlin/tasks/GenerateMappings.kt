/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.emptyMergeResult
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.path
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.inject.Inject
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.asm.LorenzRemapper
import org.cadixdev.lorenz.merge.FieldMergeStrategy
import org.cadixdev.lorenz.merge.MappingSetMerger
import org.cadixdev.lorenz.merge.MappingSetMergerHandler
import org.cadixdev.lorenz.merge.MergeConfig
import org.cadixdev.lorenz.merge.MergeContext
import org.cadixdev.lorenz.merge.MergeResult
import org.cadixdev.lorenz.merge.MethodMergeStrategy
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
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

abstract class GenerateMappings : DefaultTask() {

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:InputFile
    abstract val vanillaMappings: RegularFileProperty
    @get:InputFile
    abstract val paramMappings: RegularFileProperty
    @get:InputFile
    abstract val methodOverrides: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun run() {
        val vanillaMappings = MappingFormats.PROGUARD.createReader(vanillaMappings.path).use { it.read() }.reverse()

        val paramMappings = FileSystems.newFileSystem(paramMappings.path, null).use { fs ->
            val path = fs.getPath("mappings", "mappings.tiny")
            MappingFormats.TINY.read(path, "official", "named")
        }

        val merged = MappingSetMerger.create(
            vanillaMappings,
            paramMappings,
            MergeConfig.builder()
                .withMethodMergeStrategy(MethodMergeStrategy.STRICT)
                .withFieldMergeStrategy(FieldMergeStrategy.STRICT)
                .withMergeHandler(ParamsMergeHandler())
                .build()
        ).merge()

        ensureParentExists(outputMappings)

        // Fill out any missing inheritance info in the mappings
        val tempMappingsFile = Files.createTempFile("mappings", "tiny")
        try {
            MappingFormats.TINY.write(merged, tempMappingsFile, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)

            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs("-Xmx1G")
            }

            queue.submit(AtlasAction::class) {
                inputJar.set(vanillaJar.file)
                mappingsFile.set(tempMappingsFile.toFile())
                outputMappingsFile.set(outputMappings.file)
            }

            queue.await()
        } finally {
            Files.deleteIfExists(tempMappingsFile)
        }
    }

    /*
    private fun copyOverridenParamMappings(mappings: MappingSet) {
        val methods = hashMapOf<String, String>()
        methodOverrides.file.reader(Charsets.UTF_8).useLines { lines ->
            lines.map { line ->
                val (method, superMethod) = line.split("|")
                methods[method] = superMethod
            }
        }

        for (classMapping in mappings.topLevelClassMappings) {
            copyOverridenParamMappings(mappings, classMapping, methods)
        }
    }

    private fun copyOverridenParamMappings(mappingSet: MappingSet, classMapping: ClassMapping<*, *>, methods: Map<String, String>) {
        methodLoop@for (mapping in classMapping.methodMappings) {
            if (mapping.parameterMappings.isNotEmpty()) {
                continue
            }

            var baseClassName = classMapping.obfuscatedName
            var baseMethod = mapping.obfuscatedName
            var baseDesc = mapping.obfuscatedDescriptor

            while (true) {
                val superMethod = methods["$baseClassName,$baseMethod,$baseDesc"] ?: continue@methodLoop
                val (className, methodName, methodDesc) = superMethod.split(",")

                val superMethodMapping = mappingSet.getClassMapping(className).orNull?.getMethodMapping(methodName, methodDesc)?.orNull
                if (superMethodMapping != null) {
                    superMethodMapping.parameterMappings.forEach { it.copy(mapping) }
                    break
                }

                // prevent possible infinite loops
                if (baseClassName == className) {
                    break
                }

                baseClassName = className
                baseMethod = methodName
                baseDesc = methodDesc
            }
        }

        classMapping.innerClassMappings.forEach { copyOverridenParamMappings(mappingSet, it, methods) }
    }
     */

    abstract class AtlasAction : WorkAction<AtlasParameters> {
        override fun execute() {
            val mappings = MappingFormats.TINY.read(parameters.mappingsFile.path, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)

            val tempOut = Files.createTempFile("remapped", "jar")
            try {
                Atlas().let { atlas ->
                    atlas.install { ctx -> JarEntryRemappingTransformer(LorenzRemapper(mappings, ctx.inheritanceProvider())) }
                    atlas.run(parameters.inputJar.path, tempOut)
                }

                MappingFormats.TINY.write(mappings, parameters.outputMappingsFile.path, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)
            } finally {
                Files.deleteIfExists(tempOut)
            }
        }
    }

    interface AtlasParameters : WorkParameters {
        val inputJar: RegularFileProperty
        val mappingsFile: RegularFileProperty
        val outputMappingsFile: RegularFileProperty
    }
}

class ParamsMergeHandler : MappingSetMergerHandler {

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
            listOfNotNull(standardRightDuplicate)
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

    // Don't take anything from yarn
    override fun addRightTopLevelClassMapping(
        right: TopLevelClassMapping?,
        target: MappingSet?,
        context: MergeContext?
    ): MergeResult<TopLevelClassMapping?> {
        return emptyMergeResult()
    }
    override fun addRightInnerClassMapping(
        right: InnerClassMapping?,
        target: ClassMapping<*, *>?,
        context: MergeContext?
    ): MergeResult<InnerClassMapping?> {
        return emptyMergeResult()
    }
    override fun addRightFieldMapping(
        right: FieldMapping?,
        target: ClassMapping<*, *>?,
        context: MergeContext?
    ): FieldMapping? {
        return null
    }
    override fun addRightMethodMapping(
        right: MethodMapping?,
        target: ClassMapping<*, *>?,
        context: MergeContext?
    ): MergeResult<MethodMapping?> {
        return emptyMergeResult()
    }
}
