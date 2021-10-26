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
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.ChangeRegistry
import dev.denwav.hypo.mappings.ClassMappingsChange
import dev.denwav.hypo.mappings.LorenzUtil
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.MergeResult
import dev.denwav.hypo.mappings.MergeableMappingsChange
import dev.denwav.hypo.mappings.changes.AbstractMappingsChange
import dev.denwav.hypo.mappings.changes.MemberReference
import dev.denwav.hypo.mappings.changes.RemoveMappingChange
import dev.denwav.hypo.mappings.contributors.ChangeContributor
import dev.denwav.hypo.model.ClassProviderRoot
import dev.denwav.hypo.model.data.ClassData
import dev.denwav.hypo.model.data.types.PrimitiveType
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.util.Collections
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class CleanupSourceMappings : JavaLauncherTask() {

    @get:Classpath
    abstract val sourceJar: RegularFileProperty

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:OutputFile
    abstract val caseOnlyNameChanges: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx1G"))
        caseOnlyNameChanges.convention(defaultOutput("caseOnlyClassNameChanges", "json"))
    }

    @TaskAction
    fun run() {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        queue.submit(CleanupSourceMappingsAction::class) {
            inputMappings.set(this@CleanupSourceMappings.inputMappings.path)
            libraries.from(this@CleanupSourceMappings.libraries.files)
            sourceJar.set(this@CleanupSourceMappings.sourceJar.path)

            outputMappings.set(this@CleanupSourceMappings.outputMappings.path)
            caseOnlyNameChanges.set(this@CleanupSourceMappings.caseOnlyNameChanges.path)
        }
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
                if (method.isConstructor && currentClass.outerClass() != null && !currentClass.isStaticInnerClass) {
                    lvtIndex += 1
                }
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

    class FindCaseOnlyClassNameChanges(private val changes: MutableList<ClassNameChange>) : ChangeContributor {
        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (classMapping !is TopLevelClassMapping) {
                return
            }
            val obfName = classMapping.obfuscatedName
            val deobfName = classMapping.deobfuscatedName

            if (obfName != deobfName && obfName.equals(deobfName, ignoreCase = true)) {
                changes += ClassNameChange(obfName, deobfName)
            }
        }

        override fun name(): String = "FindCaseOnlyClassNameChanges"
    }

    class ChangeObfClassName(
        private val targetClass: String,
        private val newFullObfuscatedName: String
    ) : ClassMappingsChange {
        override fun targetClass(): String = targetClass

        override fun applyChange(input: MappingSet) {
            val classMapping = LorenzUtil.getClassMapping(input, targetClass) ?: return
            LorenzUtil.removeClassMapping(classMapping)

            val newMap = input.getOrCreateClassMapping(newFullObfuscatedName)
            copyMapping(classMapping, newMap)
        }

        private fun copyMapping(from: ClassMapping<*, *>, to: ClassMapping<*, *>) {
            to.deobfuscatedName = from.deobfuscatedName

            for (methodMapping in from.methodMappings) {
                methodMapping.copy(to)
            }
            for (fieldMapping in from.fieldMappings) {
                fieldMapping.copy(to)
            }
            for (innerClassMapping in from.innerClassMappings) {
                innerClassMapping.copy(to)
            }
        }
    }

    companion object {
        const val TEMP_SUFFIX = "paperweight-remove-anon-renames-temp-suffix"
    }

    object RemoveAnonymousClassRenames : ChangeContributor {
        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (classMapping == null) return

            val obf = classMapping.obfuscatedName.toIntOrNull()
            val deobf = classMapping.deobfuscatedName.toIntOrNull()

            if (obf != null && deobf != null && obf != deobf) {
                val newName = classMapping.fullObfuscatedName.substringBeforeLast('$') + '$' + classMapping.deobfuscatedName + TEMP_SUFFIX
                registry.submitChange(ChangeObfClassName(classMapping.fullObfuscatedName, newName))
            }
        }

        override fun name(): String = "RemoveAnonymousClassRenames"
    }

    object CleanupAfterRemoveAnonymousClassRenames : ChangeContributor {
        override fun contribute(currentClass: ClassData?, classMapping: ClassMapping<*, *>?, context: HypoContext, registry: ChangeRegistry) {
            if (classMapping == null) return

            if (classMapping.fullObfuscatedName.endsWith(TEMP_SUFFIX)) {
                val newName = classMapping.fullObfuscatedName.substringBefore(TEMP_SUFFIX)
                registry.submitChange(ChangeObfClassName(classMapping.fullObfuscatedName, newName))
            }
        }

        override fun name(): String = "CleanupAfterRemoveAnonymousClassRenames"
    }

    abstract class CleanupSourceMappingsAction : WorkAction<CleanupSourceMappingsAction.Parameters> {

        interface Parameters : WorkParameters {
            val inputMappings: RegularFileProperty
            val libraries: ConfigurableFileCollection
            val sourceJar: RegularFileProperty

            val outputMappings: RegularFileProperty
            val caseOnlyNameChanges: RegularFileProperty
        }

        override fun execute() {
            val mappings = MappingFormats.TINY.read(
                parameters.inputMappings.path,
                SPIGOT_NAMESPACE,
                DEOBF_NAMESPACE
            )

            val caseOnlyChanges = Collections.synchronizedList(mutableListOf<ClassNameChange>())

            val cleanedMappings = HypoContext.builder()
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(parameters.sourceJar.path)))
                .withContextProvider(AsmClassDataProvider.of(parameters.libraries.toJarClassProviderRoots()))
                .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
                .build().use { hypoContext ->
                    HydrationManager.createDefault()
                        .register(BridgeMethodHydrator.create())
                        .register(SuperConstructorHydrator.create())
                        .hydrate(hypoContext)

                    ChangeChain.create()
                        .addLink(RemoveLambdaMappings)
                        .addLink(ParamIndexesForSource)
                        .addLink(FindCaseOnlyClassNameChanges(caseOnlyChanges))
                        .addLink(RemoveAnonymousClassRenames)
                        .addLink(CleanupAfterRemoveAnonymousClassRenames)
                        .applyChain(mappings, MappingsCompletionManager.create(hypoContext))
                }

            MappingFormats.TINY.write(
                cleanedMappings,
                parameters.outputMappings.path,
                SPIGOT_NAMESPACE,
                DEOBF_NAMESPACE
            )

            parameters.caseOnlyNameChanges.path.bufferedWriter(Charsets.UTF_8).use { writer ->
                gson.toJson(caseOnlyChanges, writer)
            }
        }
    }
}
