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

import dev.denwav.hypo.asm.AsmClassDataProvider
import dev.denwav.hypo.asm.hydrate.BridgeMethodHydrator
import dev.denwav.hypo.asm.hydrate.SuperConstructorHydrator
import dev.denwav.hypo.core.HypoConfig
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.ChangeRegistry
import dev.denwav.hypo.mappings.ClassMappingsChange
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
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.bombe.type.signature.FieldSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class GenerateReobfMappings : JavaLauncherTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputMappings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notchToSpigotMappings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceMappings: RegularFileProperty

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val reobfMappings: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx2G"))
    }

    @TaskAction
    fun run() {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        queue.submit(GenerateReobfMappingsAction::class) {
            inputMappings.set(this@GenerateReobfMappings.inputMappings)
            notchToSpigotMappings.set(this@GenerateReobfMappings.notchToSpigotMappings)
            sourceMappings.set(this@GenerateReobfMappings.sourceMappings)
            inputJar.set(this@GenerateReobfMappings.inputJar)

            reobfMappings.set(this@GenerateReobfMappings.reobfMappings)
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

    class PropagateOuterClassMappings(private val mappings: MappingSet) : ChangeContributor {

        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (currentClass == null || classMapping != null) {
                return
            }

            val name = currentClass.name().substringAfterLast("$")

            val outer = currentClass.outerClass() ?: return
            val outerMappings = mappings.getClassMapping(outer.name()).orNull ?: return
            if (outerMappings.innerClassMappings.any { it.deobfuscatedName == name }) {
                return
            }

            registry.submitChange(AddClassMappingChange(currentClass.name(), name))
        }

        override fun name(): String = "PropagateOuterClassMappings"
    }

    class AddClassMappingChange(private val target: String, private val deobfName: String) : ClassMappingsChange {
        override fun targetClass(): String = target

        override fun applyChange(input: MappingSet) {
            input.getOrCreateClassMapping(target).deobfuscatedName = deobfName
        }
    }

    interface GenerateReobfMappingsParams : WorkParameters {
        val inputMappings: RegularFileProperty
        val notchToSpigotMappings: RegularFileProperty
        val sourceMappings: RegularFileProperty
        val inputJar: RegularFileProperty

        val reobfMappings: RegularFileProperty
    }

    abstract class GenerateReobfMappingsAction : WorkAction<GenerateReobfMappingsParams> {

        override fun execute() {
            val baseMappings = MappingFormats.TINY.read(
                parameters.inputMappings.path,
                SPIGOT_NAMESPACE,
                DEOBF_NAMESPACE
            )

            val notchToSpigot = MappingFormats.TINY.read(
                parameters.notchToSpigotMappings.path,
                OBF_NAMESPACE,
                SPIGOT_NAMESPACE
            )

            val fieldMappings = MappingFormats.TINY.read(parameters.sourceMappings.path, OBF_NAMESPACE, DEOBF_NAMESPACE)
            val spigotFieldMappings = filterFieldMappings(notchToSpigot).reverse().merge(fieldMappings)

            val outputMappings = copyFieldMappings(baseMappings, spigotFieldMappings).reverse()

            val cleanedOutputMappings = HypoContext.builder()
                .withConfig(HypoConfig.builder().setRequireFullClasspath(false).withParallelism(1).build())
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(parameters.inputJar.path)))
                .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
                .build().use { hypoContext ->
                    HydrationManager.createDefault()
                        .register(BridgeMethodHydrator.create())
                        .register(SuperConstructorHydrator.create())
                        .hydrate(hypoContext)

                    ChangeChain.create()
                        .addLink(RemoveUnusedMappings.create())
                        .addLink(RemoveAllParameterMappings, RemoveObfSpigotMappings)
                        .addLink(PropagateOuterClassMappings(outputMappings))
                        .addLink(PropagateMappingsUp.create())
                        .addLink(CopyMappingsDown.create())
                        .applyChain(outputMappings, MappingsCompletionManager.create(hypoContext))
                }

            MappingFormats.TINY.write(
                cleanedOutputMappings,
                parameters.reobfMappings.path,
                DEOBF_NAMESPACE,
                SPIGOT_NAMESPACE
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

        private fun copyFieldMappings(
            baseClassMapping: ClassMapping<*, *>,
            fieldClassMapping: ClassMapping<*, *>,
            targetMappings: ClassMapping<*, *>
        ) {
            for (innerClassMapping in baseClassMapping.innerClassMappings) {
                val fieldInnerClassMapping = fieldClassMapping.getInnerClassMapping(innerClassMapping.obfuscatedName).get()
                val newClassMapping = targetMappings.createInnerClassMapping(innerClassMapping.obfuscatedName, innerClassMapping.deobfuscatedName)
                copyFieldMappings(innerClassMapping, fieldInnerClassMapping, newClassMapping)
            }

            for (methodMapping in baseClassMapping.methodMappings) {
                methodMapping.copy(targetMappings)
            }

            for (fieldMapping in fieldClassMapping.fieldMappings) {
                when (val name = fieldMapping.obfuscatedName) {
                    "if", "do" -> targetMappings.createFieldMapping(
                        FieldSignature(name + "_", fieldMapping.type.orNull),
                        fieldMapping.deobfuscatedName
                    )
                    else -> fieldMapping.copy(targetMappings)
                }
            }
        }
    }
}
