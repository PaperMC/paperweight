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

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val spigotRecompiledClasses: RegularFileProperty

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
            spigotRecompiles.set(spigotRecompiledClasses.path)

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

    class RemoveRecompiledSyntheticMemberMappings(private val recompiledClasses: Set<String>) : ChangeContributor {
        override fun contribute(
            currentClass: ClassData?,
            classMapping: ClassMapping<*, *>?,
            context: HypoContext,
            registry: ChangeRegistry
        ) {
            if (currentClass == null || classMapping == null) {
                return
            }
            if (currentClass.rootClass().name() !in recompiledClasses) {
                return
            }

            for (method in currentClass.methods()) {
                if (method.isSynthetic) {
                    registry.submitChange(RemoveMappingChange.of(MemberReference.of(method)))
                }
            }

            for (field in currentClass.fields()) {
                if (field.isSynthetic) {
                    registry.submitChange(RemoveMappingChange.of(MemberReference.of(field)))
                }
            }
        }

        private fun ClassData.rootClass(): ClassData =
            allOuterClasses().getOrNull(0) ?: this

        private fun ClassData.allOuterClasses(list: MutableList<ClassData> = ArrayList()): List<ClassData> {
            val outer = outerClass() ?: return list.reversed()
            list.add(outer)
            return outer.allOuterClasses(list)
        }

        override fun name(): String = "RemoveRecompiledSyntheticMemberMappings"
    }

    interface GenerateReobfMappingsParams : WorkParameters {
        val inputMappings: RegularFileProperty
        val notchToSpigotMappings: RegularFileProperty
        val sourceMappings: RegularFileProperty
        val inputJar: RegularFileProperty
        val spigotRecompiles: RegularFileProperty

        val reobfMappings: RegularFileProperty
    }

    abstract class GenerateReobfMappingsAction : WorkAction<GenerateReobfMappingsParams> {

        override fun execute() {
            val spigotToMojang = MappingFormats.TINY.read(
                parameters.inputMappings.path,
                SPIGOT_NAMESPACE,
                DEOBF_NAMESPACE
            )
            val obfToSpigot = MappingFormats.TINY.read(
                parameters.notchToSpigotMappings.path,
                OBF_NAMESPACE,
                SPIGOT_NAMESPACE
            )
            val obfToMojang = MappingFormats.TINY.read(
                parameters.sourceMappings.path,
                OBF_NAMESPACE,
                DEOBF_NAMESPACE
            )

            val spigotFieldMappings = filterFieldMappings(obfToSpigot).reverse().merge(obfToMojang)
            val outputMappings = copyFieldMappings(spigotToMojang, spigotFieldMappings).reverse()

            val spigotRecompiles = parameters.spigotRecompiles.path.readLines().toSet()

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
                        .addLink(RemoveRecompiledSyntheticMemberMappings(spigotRecompiles))
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

            val names = (fieldMappings.topLevelClassMappings + baseMappings.topLevelClassMappings).map { it.obfuscatedName }.toSet()

            for (className in names) {
                val fieldClassMapping = fieldMappings.getTopLevelClassMapping(className).orNull
                val baseClassMapping = baseMappings.getTopLevelClassMapping(className).orNull
                val newClassMapping = output.createTopLevelClassMapping(
                    baseClassMapping?.obfuscatedName ?: fieldClassMapping?.obfuscatedName,
                    baseClassMapping?.deobfuscatedName ?: fieldClassMapping?.deobfuscatedName
                )
                copyFieldMappings(baseClassMapping, fieldClassMapping, newClassMapping)
            }

            return output
        }

        private fun copyFieldMappings(
            baseClassMapping: ClassMapping<*, *>?,
            fieldClassMapping: ClassMapping<*, *>?,
            targetMappings: ClassMapping<*, *>
        ) {
            val innerNames = ((baseClassMapping?.innerClassMappings ?: emptyList()) + (fieldClassMapping?.innerClassMappings ?: emptyList()))
                .map { it.obfuscatedName }.toSet()

            for (innerName in innerNames) {
                val fieldInnerClassMapping = fieldClassMapping?.getInnerClassMapping(innerName)?.orNull
                val baseInnerClassMapping = baseClassMapping?.getInnerClassMapping(innerName)?.orNull
                val newInnerClassMapping = targetMappings.createInnerClassMapping(
                    baseInnerClassMapping?.obfuscatedName ?: fieldInnerClassMapping?.obfuscatedName,
                    baseInnerClassMapping?.deobfuscatedName ?: fieldInnerClassMapping?.deobfuscatedName
                )
                copyFieldMappings(baseInnerClassMapping, fieldInnerClassMapping, newInnerClassMapping)
            }

            if (baseClassMapping != null) {
                for (methodMapping in baseClassMapping.methodMappings) {
                    methodMapping.copy(targetMappings)
                }
            }

            if (fieldClassMapping != null) {
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
}
