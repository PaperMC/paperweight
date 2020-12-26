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
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.orNull
import io.papermc.paperweight.util.parentClass
import io.papermc.paperweight.util.path
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.merge.MappingSetMerger
import org.cadixdev.lorenz.merge.MappingSetMergerHandler
import org.cadixdev.lorenz.merge.MergeConfig
import org.cadixdev.lorenz.merge.MergeContext
import org.cadixdev.lorenz.merge.MergeResult
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.FieldMapping
import org.cadixdev.lorenz.model.InnerClassMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateSpigotMappings : DefaultTask() {

    @get:InputFile
    abstract val classMappings: RegularFileProperty
    @get:InputFile
    abstract val memberMappings: RegularFileProperty
    @get:InputFile
    abstract val packageMappings: RegularFileProperty

    @get:InputFile
    abstract val loggerFields: RegularFileProperty
    @get:InputFile
    abstract val paramIndexes: RegularFileProperty
    @get:InputFile
    abstract val syntheticMethods: RegularFileProperty

    @get:InputFile
    abstract val sourceMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @TaskAction
    fun run() {
        val classMappingSet = MappingFormats.CSRG.createReader(classMappings.path).use { it.read() }
        val memberMappingSet = MappingFormats.CSRG.createReader(memberMappings.path).use { it.read() }
        val mergedMappingSet = MappingSetMerger.create(classMappingSet, memberMappingSet).merge()

        for (line in loggerFields.file.readLines(Charsets.UTF_8)) {
            val (className, fieldName) = line.split(' ')
            val classMapping = mergedMappingSet.getClassMapping(className).orElse(null) ?: continue
            classMapping.getOrCreateFieldMapping(fieldName, "Lorg/apache/logging/log4j/Logger;").deobfuscatedName = "LOGGER"
        }

        // Get the new package name
        val newPackage = packageMappings.asFile.get().readLines()[0].split(Regex("\\s+"))[1]

        val sourceMappings = MappingFormats.TINY.read(
            sourceMappings.path,
            Constants.OBF_NAMESPACE,
            Constants.DEOBF_NAMESPACE
        )

        val synths = hashMapOf<String, MutableMap<String, MutableMap<String, String>>>()
        syntheticMethods.file.useLines { lines ->
            for (line in lines) {
                val (className, desc, synthName, baseName) = line.split(" ")
                synths.computeIfAbsent(className) { hashMapOf() }
                    .computeIfAbsent(desc) { hashMapOf() }[baseName] = synthName
            }
        }

        val notchToSpigotSet = MappingSetMerger.create(
            mergedMappingSet,
            sourceMappings,
            MergeConfig.builder()
                .withMergeHandler(SpigotMappingsMergerHandler(newPackage, synths))
                .build()
        ).merge()

        val adjustedSourceMappings = adjustParamIndexes(sourceMappings)
        val cleanedSourceMappings = removeLambdaMappings(adjustedSourceMappings)
        val spigotToNamedSet = notchToSpigotSet.reverse().merge(cleanedSourceMappings)

        MappingFormats.TINY.write(
            spigotToNamedSet,
            outputMappings.path,
            Constants.SPIGOT_NAMESPACE,
            Constants.DEOBF_NAMESPACE
        )
    }

    private fun adjustParamIndexes(mappings: MappingSet): MappingSet {
        val indexes = hashMapOf<String, HashMap<String, HashMap<Int, Int>>>()

        paramIndexes.file.useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(" ")
                val (className, methodName, descriptor) = parts

                val paramMap = indexes.computeIfAbsent(className) { hashMapOf() }
                    .computeIfAbsent(methodName + descriptor) { hashMapOf() }

                for (i in 3 until parts.size step 2) {
                    paramMap[parts[i].toInt()] = parts[i + 1].toInt()
                }
            }
        }

        val result = MappingSet.create()
        for (old in mappings.topLevelClassMappings) {
            val new = result.createTopLevelClassMapping(old.obfuscatedName, old.deobfuscatedName)
            copyClassParam(old, new, indexes)
        }

        return result
    }

    private fun copyClassParam(
        from: ClassMapping<*, *>,
        to: ClassMapping<*, *>,
        params: Map<String, Map<String, Map<Int, Int>>>
    ) {
        for (mapping in from.fieldMappings) {
            to.createFieldMapping(mapping.signature, mapping.deobfuscatedName)
        }
        for (mapping in from.innerClassMappings) {
            val newMapping = to.createInnerClassMapping(mapping.obfuscatedName, mapping.deobfuscatedName)
            copyClassParam(mapping, newMapping, params)
        }

        val classMap = params[from.fullObfuscatedName]
        for (mapping in from.methodMappings) {
            val newMapping = to.createMethodMapping(mapping.signature, mapping.deobfuscatedName)

            val paramMappings = mapping.parameterMappings
            if (paramMappings.isEmpty() || classMap == null) {
                continue
            }

            val methodMap = classMap[mapping.signature.toJvmsIdentifier()] ?: continue
            for (paramMapping in paramMappings) {
                val i = methodMap[paramMapping.index] ?: paramMapping.index
                newMapping.createParameterMapping(i, paramMapping.deobfuscatedName)
            }
        }
    }

    private fun removeLambdaMappings(mappings: MappingSet): MappingSet {
        val result = MappingSet.create()

        for (classMapping in mappings.topLevelClassMappings) {
            val newClassMapping = result.createTopLevelClassMapping(
                classMapping.obfuscatedName,
                classMapping.deobfuscatedName
            )
            removeLambdaMappings(classMapping, newClassMapping)
        }

        return result
    }
    private fun removeLambdaMappings(old: ClassMapping<*, *>, new: ClassMapping<*, *>) {
        for (inner in old.innerClassMappings) {
            val newInner = new.createInnerClassMapping(inner.obfuscatedName, inner.deobfuscatedName)
            removeLambdaMappings(inner, newInner)
        }

        for (field in old.fieldMappings) {
            new.createFieldMapping(field.signature, field.deobfuscatedName)
        }

        for (method in old.methodMappings) {
            if (method.deobfuscatedName.startsWith("lambda$")) {
                continue
            }
            val newMethod = new.createMethodMapping(method.signature, method.deobfuscatedName)
            for (param in method.parameterMappings) {
                newMethod.createParameterMapping(param.index, param.deobfuscatedName)
            }
        }
    }
}

typealias Synths = Map<String, Map<String, Map<String, String>>>

class SpigotMappingsMergerHandler(
    private val newPackage: String,
    private val synths: Synths
) : MappingSetMergerHandler {

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
        // If both are provided, keep spigot
        return MergeResult(
            target.createTopLevelClassMapping(left.obfuscatedName, prependPackage(left.deobfuscatedName)),
            right
        )
    }

    override fun addLeftTopLevelClassMapping(
        left: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        throw IllegalStateException(
            "Unexpected added class from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}"
        )
    }

    override fun addRightTopLevelClassMapping(
        right: TopLevelClassMapping,
        target: MappingSet,
        context: MergeContext
    ): MergeResult<TopLevelClassMapping?> {
        // This is a mapping Spigot is totally missing, but Spigot maps all classes without a package to
        // /net/minecraft regardless if there are mappings for the classes or not
        return MergeResult(
            target.createTopLevelClassMapping(right.obfuscatedName, prependPackage(right.obfuscatedName)),
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

    override fun addLeftInnerClassMapping(
        left: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping> {
        throw IllegalStateException(
            "Unexpected added class from Spigot: ${left.fullObfuscatedName} - ${left.fullDeobfuscatedName}"
        )
    }
    override fun addRightInnerClassMapping(
        right: InnerClassMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<InnerClassMapping?> {
        // We want to get all of the inner classes from SRG, but not the SRG names
        return MergeResult(target.createInnerClassMapping(right.obfuscatedName, right.obfuscatedName), right)
    }

    override fun mergeFieldMappings(
        left: FieldMapping,
        strictRight: FieldMapping?,
        looseRight: FieldMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping {
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
    ): FieldMapping {
        val right = strictRightDuplicate ?: looseRightDuplicate ?: strictRightContinuation ?: looseRightContinuation ?: left
        return target.createFieldMapping(right.signature, left.deobfuscatedName)
    }

    override fun mergeMethodMappings(
        left: MethodMapping,
        standardRight: MethodMapping?,
        wiggledRight: MethodMapping?,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        throw IllegalStateException("Unexpectedly merged method: $left")
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
        // Check if Spigot calls this mapping something else
        val synthMethods = synths[left.parent.fullObfuscatedName]?.get(left.obfuscatedDescriptor)
        val newName = synthMethods?.get(left.obfuscatedName)
        return if (newName != null) {
            val newLeftMapping = left.parentClass.getMethodMapping(MethodSignature(newName, left.descriptor)).orNull
            val newMapping = if (newLeftMapping != null) {
                target.getOrCreateMethodMapping(newLeftMapping.signature).also {
                    it.deobfuscatedName = left.deobfuscatedName
                }
            } else {
                target.getOrCreateMethodMapping(left.signature).also {
                    it.deobfuscatedName = newName
                }
            }
            MergeResult(newMapping)
        } else {
            val newMapping = target.getOrCreateMethodMapping(left.signature).also {
                it.deobfuscatedName = left.deobfuscatedName
            }
            return MergeResult(newMapping)
        }
    }
    override fun addLeftMethodMapping(
        left: MethodMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        // Check if Spigot maps this from a synthetic method name
        var obfName = left.obfuscatedName
        val synthMethods = synths[left.parent.fullObfuscatedName]?.get(left.obfuscatedDescriptor)
        if (synthMethods != null) {
            // This is a reverse lookup
            for ((base, synth) in synthMethods) {
                if (left.obfuscatedName == synth) {
                    obfName = base
                    break
                }
            }
        }

        val newMapping = target.getOrCreateMethodMapping(obfName, left.descriptor)
        newMapping.deobfuscatedName = left.deobfuscatedName
        return MergeResult(newMapping)
    }

    // Disable non-spigot mappings
    override fun addRightFieldMapping(
        right: FieldMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): FieldMapping? {
        return null
    }

    override fun addRightMethodMapping(
        right: MethodMapping,
        target: ClassMapping<*, *>,
        context: MergeContext
    ): MergeResult<MethodMapping?> {
        // Check if spigot changes this method automatically
        val synthMethods = synths[right.parentClass.fullObfuscatedName]?.get(right.obfuscatedDescriptor)
        val newName = synthMethods?.get(right.obfuscatedName) ?: return MergeResult(null)

        val newClassMapping = context.left.getClassMapping(right.parentClass.fullObfuscatedName).orNull
        val newMethodMapping = newClassMapping?.getMethodMapping(MethodSignature(newName, right.descriptor))?.orNull
        val newMapping = target.getOrCreateMethodMapping(right.signature)
        if (newMethodMapping != null) {
            newMapping.deobfuscatedName = newMethodMapping.deobfuscatedName
        } else {
            newMapping.deobfuscatedName = newName
        }
        return MergeResult(newMapping)
    }

    private fun prependPackage(name: String): String {
        return if (name.contains('/')) {
            name
        } else {
            newPackage + name
        }
    }
}
