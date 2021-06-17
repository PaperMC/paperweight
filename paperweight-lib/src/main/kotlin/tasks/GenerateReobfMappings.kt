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
import dev.denwav.hypo.core.HypoConfig
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.ChangeRegistry
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.changes.MemberReference
import dev.denwav.hypo.mappings.changes.RemoveClassMappingChange
import dev.denwav.hypo.mappings.changes.RemoveMappingChange
import dev.denwav.hypo.mappings.changes.RemoveParameterMappingChange
import dev.denwav.hypo.mappings.contributors.ChangeContributor
import dev.denwav.hypo.mappings.contributors.CopyMappingsDown
import dev.denwav.hypo.mappings.contributors.PropagateMappingsUp
import dev.denwav.hypo.mappings.contributors.RemoveUnusedMappings
import dev.denwav.hypo.model.ClassProviderRoot
import dev.denwav.hypo.model.data.ClassData
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.path
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateReobfMappings : DefaultTask() {

    @get:InputFile
    abstract val inputMappings: RegularFileProperty

    @get:InputFile
    abstract val notchToSpigotMappings: RegularFileProperty

    @get:InputFile
    abstract val sourceMappings: RegularFileProperty

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val reobfMappings: RegularFileProperty

    @TaskAction
    fun run() {
        val baseMappings = MappingFormats.TINY.read(
            inputMappings.path,
            Constants.SPIGOT_NAMESPACE,
            Constants.DEOBF_NAMESPACE
        )

        val notchToSpigot = MappingFormats.TINY.read(
            notchToSpigotMappings.path,
            Constants.OBF_NAMESPACE,
            Constants.SPIGOT_NAMESPACE
        )

        val fieldMappings = MappingFormats.TINY.read(sourceMappings.path, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)
        val spigotFieldMappings = filterFieldMappings(notchToSpigot).reverse().merge(fieldMappings)

        val outputMappings = copyFieldMappings(baseMappings, spigotFieldMappings).reverse()

        val cleanedOutputMappings = HypoContext.builder()
            .withConfig(HypoConfig.builder().setRequireFullClasspath(false).withParallelism(1).build())
            .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(inputJar.path)))
            .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
            .build().use { hypoContext ->
                HydrationManager.createDefault()
                    .register(BridgeMethodHydrator.create())
                    .register(SuperConstructorHydrator.create())
                    .hydrate(hypoContext)

                ChangeChain.create()
                    .addLink(RemoveUnusedMappings.create())
                    .addLink(RemoveAllParameterMappings, RemoveObfSpigotMappings)
                    .addLink(PropagateMappingsUp.create())
                    .addLink(CopyMappingsDown.create())
                    .applyChain(outputMappings, MappingsCompletionManager.create(hypoContext))
            }

        MappingFormats.TINY.write(
            cleanedOutputMappings,
            reobfMappings.path,
            Constants.DEOBF_NAMESPACE,
            Constants.SPIGOT_NAMESPACE
        )
    }

    private fun filterFieldMappings(mappings: MappingSet): MappingSet {
        class RemoveFieldMappings : ChangeContributor {
            override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
                classMapping?.fieldMappings?.forEach { fieldMapping ->
                    registry.submitChange(RemoveMappingChange.of(MemberReference.of(fieldMapping)))
                }
            }
            override fun name(): String = "RemoveFieldMappings"
        }

        return HypoContext.builder().build().use { context ->
            ChangeChain.create().addLink(RemoveFieldMappings()).applyChain(mappings, MappingsCompletionManager.create(context))
        }
    }

    private fun copyFieldMappings(baseMappings: MappingSet, fieldMappings: MappingSet): MappingSet {
        val output = MappingSet.create()

        for (topLevelClassMapping in baseMappings.topLevelClassMappings) {
            val fieldClassMapping = fieldMappings.getTopLevelClassMapping(topLevelClassMapping.obfuscatedName).get()
            val newClassMapping = output.createTopLevelClassMapping(topLevelClassMapping.obfuscatedName, topLevelClassMapping.deobfuscatedName)
            copyFieldMappings(topLevelClassMapping, fieldClassMapping, newClassMapping)
        }

        return output
    }

    private fun copyFieldMappings(baseClassMapping: ClassMapping<*, *>, fieldClassMapping: ClassMapping<*, *>, targetMappings: ClassMapping<*, *>) {
        for (innerClassMapping in baseClassMapping.innerClassMappings) {
            val fieldInnerClassMapping = fieldClassMapping.getInnerClassMapping(innerClassMapping.obfuscatedName).get()
            val newClassMapping = targetMappings.createInnerClassMapping(innerClassMapping.obfuscatedName, innerClassMapping.deobfuscatedName)
            copyFieldMappings(innerClassMapping, fieldInnerClassMapping, newClassMapping)
        }

        for (methodMapping in baseClassMapping.methodMappings) {
            methodMapping.copy(targetMappings)
        }

        for (fieldMapping in fieldClassMapping.fieldMappings) {
            fieldMapping.copy(targetMappings)
        }
    }

    object RemoveAllParameterMappings : ChangeContributor {
        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (classMapping == null) {
                return
            }
            for (methodMapping in classMapping.methodMappings) {
                val params = methodMapping.parameterMappings
                if (params.isEmpty()) {
                    continue
                }
                val ref = MemberReference.of(methodMapping)
                for (parameterMapping in params) {
                    registry.submitChange(RemoveParameterMappingChange.of(ref, parameterMapping.index))
                }
            }
        }

        override fun name() = "RemoveAllParameterMappings"
    }

    object RemoveObfSpigotMappings : ChangeContributor {
        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (classMapping == null) {
                return
            }
            if (!classMapping.fullDeobfuscatedName.contains("/")) {
                registry.submitChange(RemoveClassMappingChange.of(classMapping.fullObfuscatedName))
            }
        }

        override fun name() = "RemoveObfSpigotMappings"
    }
}
