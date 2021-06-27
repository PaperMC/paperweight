/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

import dev.denwav.hypo.asm.AsmClassDataProvider
import dev.denwav.hypo.asm.hydrate.BridgeMethodHydrator
import dev.denwav.hypo.asm.hydrate.SuperConstructorHydrator
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.contributors.CopyMappingsDown
import dev.denwav.hypo.mappings.contributors.PropagateMappingsUp
import dev.denwav.hypo.mappings.contributors.RemoveUnusedMappings
import dev.denwav.hypo.model.ClassProviderRoot
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.emptyMergeResult
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.isLibraryJar
import io.papermc.paperweight.util.openZip
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.set
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.lorenz.MappingSet
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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

abstract class GenerateMappings : DefaultTask() {

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:InputFiles
    abstract val librariesDir: DirectoryProperty

    @get:InputFile
    abstract val vanillaMappings: RegularFileProperty

    @get:InputFile
    abstract val paramMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    init {
        @Suppress("LeakingThis")
        run {
            jvmargs.convention(listOf("-Xmx1G"))
        }
    }

    @TaskAction
    fun run() {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
        }

        queue.submit(GenerateMappingsAction::class) {
            vanillaJar.set(this@GenerateMappings.vanillaJar.path)
            librariesDir.set(this@GenerateMappings.librariesDir.path)
            vanillaMappings.set(this@GenerateMappings.vanillaMappings.path)
            paramMappings.set(this@GenerateMappings.paramMappings.path)
            outputMappings.set(this@GenerateMappings.outputMappings.path)
        }
    }

    interface GenerateMappingsParams : WorkParameters {
        val vanillaJar: RegularFileProperty
        val librariesDir: DirectoryProperty
        val vanillaMappings: RegularFileProperty
        val paramMappings: RegularFileProperty
        val outputMappings: RegularFileProperty
    }

    abstract class GenerateMappingsAction : WorkAction<GenerateMappingsParams> {

        override fun execute() {
            val vanillaMappings = MappingFormats.PROGUARD.createReader(parameters.vanillaMappings.path).use { it.read() }.reverse()

            val paramMappings = parameters.paramMappings.path.openZip().use { fs ->
                val path = fs.getPath("mappings", "mappings.tiny")
                MappingFormats.TINY.read(path, "official", "named")
            }

            val merged = MappingSetMerger.create(
                vanillaMappings,
                paramMappings,
                MergeConfig.builder()
                    .withFieldMergeStrategy(FieldMergeStrategy.STRICT)
                    .withMergeHandler(ParamsMergeHandler())
                    .build()
            ).merge()

            val libs = parameters.librariesDir.path.useDirectoryEntries {
                it.filter { p -> p.isLibraryJar }
                    .map { p -> ClassProviderRoot.fromJar(p) }
                    .toList()
            }

            val filledMerged = HypoContext.builder()
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(parameters.vanillaJar.path)))
                .withContextProviders(AsmClassDataProvider.of(libs))
                .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
                .build().use { hypoContext ->
                    HydrationManager.createDefault()
                        .register(BridgeMethodHydrator.create())
                        .register(SuperConstructorHydrator.create())
                        .hydrate(hypoContext)

                    ChangeChain.create()
                        .addLink(RemoveUnusedMappings.create())
                        .addLink(PropagateMappingsUp.create())
                        .addLink(CopyMappingsDown.create())
                        .applyChain(merged, MappingsCompletionManager.create(hypoContext))
                }

            ensureParentExists(parameters.outputMappings)
            MappingFormats.TINY.write(filledMerged, parameters.outputMappings.path, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)
        }
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
