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

import dev.denwav.hypo.asm.AsmClassDataProvider
import dev.denwav.hypo.asm.hydrate.BridgeMethodHydrator
import dev.denwav.hypo.asm.hydrate.SuperConstructorHydrator
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.ChangeRegistry
import dev.denwav.hypo.mappings.LorenzUtil
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.MergeResult
import dev.denwav.hypo.mappings.MergeableMappingsChange
import dev.denwav.hypo.mappings.changes.AbstractMappingsChange
import dev.denwav.hypo.mappings.changes.MemberReference
import dev.denwav.hypo.mappings.changes.RemoveMappingChange
import dev.denwav.hypo.mappings.contributors.ChangeContributor
import dev.denwav.hypo.mappings.contributors.CopyMappingsDown
import dev.denwav.hypo.mappings.contributors.PropagateMappingsUp
import dev.denwav.hypo.mappings.contributors.RemoveUnusedMappings
import dev.denwav.hypo.model.ClassProviderRoot
import dev.denwav.hypo.model.data.ClassData
import dev.denwav.hypo.model.data.types.PrimitiveType
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.isLibraryJar
import io.papermc.paperweight.util.path
import kotlin.io.path.*
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class CleanupMappings : DefaultTask() {

    @get:InputFile
    abstract val sourceJar: RegularFileProperty

    @get:InputFiles
    abstract val librariesDir: DirectoryProperty

    @get:InputFile
    abstract val inputMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @TaskAction
    fun run() {
        val libs = librariesDir.path.useDirectoryEntries {
            it.filter { p -> p.isLibraryJar }
                .map { p -> ClassProviderRoot.fromJar(p) }
                .toList()
        }

        val mappings = MappingFormats.TINY.read(
            inputMappings.path,
            Constants.SPIGOT_NAMESPACE,
            Constants.DEOBF_NAMESPACE
        )

        val cleanedMappings = HypoContext.builder()
            .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(sourceJar.path)))
            .withContextProviders(AsmClassDataProvider.of(libs))
            .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
            .build().use { hypoContext ->
                HydrationManager.createDefault()
                    .register(BridgeMethodHydrator.create())
                    .register(SuperConstructorHydrator.create())
                    .hydrate(hypoContext)

                ChangeChain.create()
                    .addLink(RemoveUnusedMappings.create(), RemoveLambdaMappings)
                    .addLink(PropagateMappingsUp.create())
                    .addLink(CopyMappingsDown.create())
                    .addLink(ParamIndexesForSource)
                    .applyChain(mappings, MappingsCompletionManager.create(hypoContext))
            }

        MappingFormats.TINY.write(
            cleanedMappings,
            outputMappings.path,
            Constants.SPIGOT_NAMESPACE,
            Constants.DEOBF_NAMESPACE
        )
    }

    object ParamIndexesForSource : ChangeContributor {

        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (currentClass == null || classMapping == null) {
                return
            }

            for (methodMapping in classMapping.methodMappings) {
                val method = LorenzUtil.findMethod(currentClass, methodMapping) ?: continue

                var methodRef: MemberReference? = null

                var lvtIndex = if (method.isStatic) 0 else 1
                for ((sourceIndex, param) in method.params().withIndex()) {
                    if (methodMapping.hasParameterMapping(lvtIndex)) {
                        if (methodRef == null) {
                            methodRef = MemberReference.of(methodMapping)
                        }
                        registry.submitChange(ParamIndexChange(methodRef, lvtIndex, sourceIndex))
                    }
                    lvtIndex++
                    if (param === PrimitiveType.LONG || param === PrimitiveType.DOUBLE) {
                        lvtIndex++
                    }
                }
            }
        }

        override fun name(): String = "ParamIndexesForSource"

        class ParamIndexChange(
            target: MemberReference,
            fromIndex: Int,
            toIndex: Int
        ) : AbstractMappingsChange(target), MergeableMappingsChange<ParamIndexChange> {

            private val indexMap: MutableMap<Int, Int> = HashMap()

            init {
                indexMap[fromIndex] = toIndex
            }

            override fun applyChange(input: MappingSet, ref: MemberReference) {
                val classMapping = input.getOrCreateClassMapping(ref.className())
                val methodMapping = classMapping.getOrCreateMethodMapping(ref.name(), ref.desc())

                val paramsMap = LorenzUtil.getParamsMap(methodMapping)
                val params = paramsMap.values.toList()
                paramsMap.clear()

                for (param in params) {
                    methodMapping.createParameterMapping(indexMap[param.index] ?: param.index, param.deobfuscatedName)
                }
            }

            override fun mergeWith(that: ParamIndexChange): MergeResult<ParamIndexChange> {
                for (fromIndex in this.indexMap.keys) {
                    if (that.indexMap.containsKey(fromIndex)) {
                        return MergeResult.failure("Cannot merge 2 param mappings changes with matching fromIndexes")
                    }
                }
                for (toIndex in this.indexMap.values) {
                    if (that.indexMap.containsValue(toIndex)) {
                        return MergeResult.failure("Cannot merge 2 param mappings changes with matching toIndex")
                    }
                }

                this.indexMap += that.indexMap
                return MergeResult.success(this)
            }

            override fun toString(): String {
                return "Move param mappings for ${target()} for index pairs [${indexMap.entries.joinToString(", ") { "${it.key}:${it.value}" }}]"
            }
        }
    }

    object RemoveLambdaMappings : ChangeContributor {

        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (currentClass == null || classMapping == null) {
                return
            }

            for (methodMapping in classMapping.methodMappings) {
                if (methodMapping.deobfuscatedName.startsWith("lambda$")) {
                    registry.submitChange(RemoveMappingChange.of(MemberReference.of(methodMapping)))
                }
            }
        }

        override fun name(): String = "RemoveLambdaMappings"
    }
}
