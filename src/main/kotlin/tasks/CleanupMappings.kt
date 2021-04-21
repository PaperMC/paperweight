package io.papermc.paperweight.tasks

import com.demonwav.hypo.asm.AsmClassDataProvider
import com.demonwav.hypo.asm.hydrate.BridgeMethodHydrator
import com.demonwav.hypo.asm.hydrate.SuperConstructorHydrator
import com.demonwav.hypo.core.HypoContext
import com.demonwav.hypo.hydrate.HydrationManager
import com.demonwav.hypo.mappings.ChangeChain
import com.demonwav.hypo.mappings.ChangeRegistry
import com.demonwav.hypo.mappings.MappingsCompletionManager
import com.demonwav.hypo.mappings.MappingsUtil
import com.demonwav.hypo.mappings.MergeableMappingsChange
import com.demonwav.hypo.mappings.UnableToMergeException
import com.demonwav.hypo.mappings.changes.AbstractMappingsChange
import com.demonwav.hypo.mappings.changes.LorenzUtil
import com.demonwav.hypo.mappings.changes.MemberReference
import com.demonwav.hypo.mappings.changes.RemoveMappingChange
import com.demonwav.hypo.mappings.contributors.ChangeContributor
import com.demonwav.hypo.mappings.contributors.CopyMappingsDown
import com.demonwav.hypo.mappings.contributors.PropagateMappingsUp
import com.demonwav.hypo.mappings.contributors.RemoveUnusedMappings
import com.demonwav.hypo.model.ClassProviderRoot
import com.demonwav.hypo.model.data.ClassData
import com.demonwav.hypo.model.data.types.PrimitiveTypes
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.isLibraryJar
import io.papermc.paperweight.util.path
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
        val libs = librariesDir.file.listFiles()?.filter { it.isLibraryJar } ?: emptyList()

        val mappings = MappingFormats.TINY.read(
            inputMappings.path,
            Constants.SPIGOT_NAMESPACE,
            Constants.DEOBF_NAMESPACE
        )

        val cleanedMappings = HypoContext.builder()
            .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(sourceJar.path)))
            .withContextProviders(AsmClassDataProvider.of(ClassProviderRoot.fromJars(*libs.map { it.toPath() }.toTypedArray())))
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
                val method = MappingsUtil.findMethod(currentClass, methodMapping) ?: continue

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
                    if (param === PrimitiveTypes.LONG || param === PrimitiveTypes.DOUBLE) {
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

            override fun applyChange(input: MappingSet, className: String, name: String, desc: String?) {
                val classMapping = input.getOrCreateClassMapping(className)
                val methodMapping = classMapping.getOrCreateMethodMapping(name, desc)

                val paramsMap = LorenzUtil.getParamsMap(methodMapping)
                val params = paramsMap.values.toList()
                paramsMap.clear()

                for (param in params) {
                    methodMapping.createParameterMapping(indexMap[param.index] ?: param.index, param.deobfuscatedName)
                }
            }

            override fun mergeWith(that: ParamIndexChange): ParamIndexChange {
                for (fromIndex in this.indexMap.keys) {
                    if (that.indexMap.containsKey(fromIndex)) {
                        throw UnableToMergeException("Cannot merge 2 param mappings changes with matching fromIndexes")
                    }
                }
                for (toIndex in this.indexMap.values) {
                    if (that.indexMap.containsValue(toIndex)) {
                        throw UnableToMergeException("Cannot merge 2 param mappings changes with matching toIndex")
                    }
                }

                this.indexMap += that.indexMap
                return this
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
