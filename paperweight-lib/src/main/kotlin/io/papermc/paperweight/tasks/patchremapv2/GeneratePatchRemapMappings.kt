/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
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

package io.papermc.paperweight.tasks.patchremapv2

import com.google.gson.GsonBuilder
import dev.denwav.hypo.asm.AsmClassDataProvider
import dev.denwav.hypo.asm.hydrate.BridgeMethodHydrator
import dev.denwav.hypo.asm.hydrate.LambdaCallHydrator
import dev.denwav.hypo.asm.hydrate.LocalClassHydrator
import dev.denwav.hypo.asm.hydrate.SuperConstructorHydrator
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.contributors.CopyLambdaParametersDown
import dev.denwav.hypo.mappings.contributors.CopyMappingsDown
import dev.denwav.hypo.mappings.contributors.CopyRecordParameters
import dev.denwav.hypo.mappings.contributors.PropagateMappingsUp
import dev.denwav.hypo.model.ClassProviderRoot
import io.papermc.codebook.exceptions.UnexpectedException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import org.cadixdev.bombe.type.signature.FieldSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.parchmentmc.feather.io.gson.MDCGsonAdapterFactory
import org.parchmentmc.feather.io.gson.SimpleVersionAdapter
import org.parchmentmc.feather.mapping.MappingDataContainer
import org.parchmentmc.feather.mapping.VersionedMappingDataContainer
import org.parchmentmc.feather.util.SimpleVersion

@CacheableTask
abstract class GeneratePatchRemapMappings : BaseTask() {

    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFiles
    abstract val paramMappings: ConfigurableFileCollection

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val serverJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val vanillaMappings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val spigotMappings: RegularFileProperty

    @get:OutputFile
    abstract val patchRemapMappings: RegularFileProperty

    @TaskAction
    fun run() {
        val mergedMappings = merge(vanillaMappings.path, spigotMappings.path, paramMappings.singleFile.toPath())

        // run hypo
        val ctx: HypoContext

        try {
            ctx = HypoContext.builder()
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(serverJar.convertToPath())))
                .withContextProvider(
                    AsmClassDataProvider.of(
                        ClassProviderRoot.fromJars(
                            *minecraftClasspath.files.map { it.toPath() }
                                .toTypedArray()
                        )
                    )
                )
                .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
                .build()
        } catch (e: IOException) {
            throw UnexpectedException("Failed to open jar files", e)
        }

        try {
            HydrationManager.createDefault()
                .register(BridgeMethodHydrator.create())
                .register(SuperConstructorHydrator.create())
                .register(LambdaCallHydrator.create())
                .register(LocalClassHydrator.create())
                .hydrate(ctx)
        } catch (e: IOException) {
            throw UnexpectedException("Failed to hydrate data model", e)
        }

        // Fill in any missing mapping information
        val completedParamMappings = ChangeChain.create()
            .addLink(
                CopyMappingsDown.createWithoutOverwrite(),
                CopyLambdaParametersDown.createWithoutOverwrite(),
                CopyRecordParameters.create(),
                PropagateMappingsUp.create()
            )
            .applyChain(mergedMappings, MappingsCompletionManager.create(ctx))

        try {
            MappingFormats.TINY.write(completedParamMappings, patchRemapMappings.convertToPath(), SPIGOT_NAMESPACE, NEW_DEOBF_NAMESPACE)
        } catch (e: IOException) {
            throw UnexpectedException("Failed to write mappings", e)
        }
    }

    private fun merge(mojangPath: Path, spigotPath: Path, parchmentPath: Path): MappingSet {
        // mojang -> obf
        val mojangMappings = MappingFormats.PROGUARD.createReader(mojangPath).use { it.read() }
        // obf -> spigot
        val spigotMappings = MappingFormats.TINY.read(spigotPath, OBF_NAMESPACE, SPIGOT_NAMESPACE)

        val gson = GsonBuilder()
            .registerTypeAdapterFactory(MDCGsonAdapterFactory())
            .registerTypeAdapter(SimpleVersion::class.java, SimpleVersionAdapter())
            .create()

        val mappings: MappingDataContainer
        try {
            FileSystems.newFileSystem(parchmentPath).use { fs ->
                val jsonFile = fs.getPath("/parchment.json")
                Files.newBufferedReader(jsonFile).use { reader ->
                    mappings = gson.fromJson(reader, VersionedMappingDataContainer::class.java)
                }
            }
        } catch (e: IOException) {
            throw UnexpectedException("Failed to read param mappings file", e)
        }

        // mojang -> mojang+parchment
        val parchmentMappings = this.toLorenz(mappings)

        // result: spigot -> mojang+parchment
        val result = MappingSet.create()

        // merge
        mojangMappings.topLevelClassMappings.filterNot { it.obfuscatedName.endsWith("package-info") }.forEach { mojangClass ->
            val spigotClass = spigotMappings.getTopLevelClassMapping(mojangClass.deobfuscatedName).orElse(null)
            if (spigotClass == null) {
                // println("cant find spigot class for ${mojangClass.deobfuscatedName} - ${mojangClass.obfuscatedName}")
                return@forEach
            }
            val parchmentClass = parchmentMappings.getTopLevelClassMapping(mojangClass.obfuscatedName).orElse(null)
            if (parchmentClass == null) {
                println("cant find parchmentClass class for ${mojangClass.obfuscatedName} - ${mojangClass.deobfuscatedName}")
                return@forEach
            }
            val resultClass = result.createTopLevelClassMapping(spigotClass.deobfuscatedName, mojangClass.obfuscatedName)

            mergeMethodsAndFields(resultClass, mojangClass, spigotClass, parchmentClass)

            mergeInnerClass(mojangClass, spigotClass, resultClass, parchmentClass)
        }

        return result
    }

    private fun mergeInnerClass(
        mojangClass: ClassMapping<*, *>,
        spigotClass: ClassMapping<*, *>,
        resultClass: ClassMapping<*, *>,
        parchmentClass: TopLevelClassMapping
    ) {
        mojangClass.innerClassMappings.forEach { innerMojangClass ->
            val innerSpigotClass = spigotClass.getInnerClassMapping(innerMojangClass.deobfuscatedName).orElse(null)
            if (innerSpigotClass == null) {
                // println("cant find inner spigot class for ${innerMojangClass.deobfuscatedName} - ${innerMojangClass.obfuscatedName}")
                return@forEach
            }
            val innerParchmentClass = parchmentClass.getInnerClassMapping(innerMojangClass.obfuscatedName).orElse(null)
            if (innerParchmentClass == null) {
                println("cant find innerParchmentClass for ${innerMojangClass.obfuscatedName} - ${innerMojangClass.deobfuscatedName}")
                return@forEach
            }
            val innerResultClass = resultClass.createInnerClassMapping(innerSpigotClass.deobfuscatedName, innerMojangClass.obfuscatedName)

            mergeMethodsAndFields(innerResultClass, innerMojangClass, innerSpigotClass, innerParchmentClass)

            mergeInnerClass(innerMojangClass, innerSpigotClass, innerResultClass, parchmentClass)
        }
    }

    private fun mergeMethodsAndFields(
        resultClass: ClassMapping<*, *>,
        mojangClass: ClassMapping<*, *>,
        spigotClass: ClassMapping<*, *>,
        parchmentClass: ClassMapping<*, *>
    ) {
        mojangClass.fieldMappings.forEach { mojangField ->
            val spigotField = spigotClass.getFieldMapping(mojangField.deobfuscatedName).orElse(null)
            if (spigotField == null) {
                // println("cant find spigot field for ${mojangField.deobfuscatedName} - ${mojangField.obfuscatedName}")
                return@forEach
            }
            val resultField = resultClass.createFieldMapping(spigotField.deobfuscatedSignature, mojangField.obfuscatedName)
            resultField.deobfuscatedName = mojangField.obfuscatedName
        }

        mojangClass.methodMappings.forEach { mojangMethod ->
            val spigotMethod = spigotClass.getMethodMapping(mojangMethod.deobfuscatedName, mojangMethod.deobfuscatedDescriptor).orElse(null)
            if (spigotMethod == null) {
                // println("cant find spigot method for ${mojangMethod.deobfuscatedName} - ${mojangMethod.obfuscatedName}")
                return@forEach
            }
            val parchmentMethod = parchmentClass.getMethodMapping(mojangMethod.obfuscatedName, mojangMethod.obfuscatedDescriptor).orElse(null)
            if (parchmentMethod == null) {
                // println("cant find parchmentMethod for ${mojangMethod.deobfuscatedName} - ${mojangMethod.obfuscatedName}")
                return@forEach
            }
            val resultMethod = resultClass.createMethodMapping(spigotMethod.deobfuscatedName, spigotMethod.deobfuscatedDescriptor)
            resultMethod.setDeobfuscatedName(mojangMethod.obfuscatedName)

            parchmentMethod.parameterMappings.forEach {
                resultMethod.createParameterMapping(it.index, it.deobfuscatedName)
            }
        }
    }

    private fun toLorenz(container: MappingDataContainer): MappingSet {
        val mappings = MappingSet.create()

        for (aClass in container.classes) {
            val classMapping = mappings.getOrCreateClassMapping(aClass.name)
            for (method in aClass.methods) {
                val methodMapping = classMapping.getOrCreateMethodMapping(method.name, method.descriptor)
                for (param in method.parameters) {
                    methodMapping.getOrCreateParameterMapping(param.index.toInt()).setDeobfuscatedName(param.name)
                }
            }
            for (field in aClass.fields) {
                classMapping.getOrCreateFieldMapping(FieldSignature.of(field.name, field.descriptor))
            }
        }

        return mappings
    }
}
