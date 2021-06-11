package io.papermc.paperweight.tasks

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

        val outputMappings = copyFieldMappings(baseMappings, spigotFieldMappings)

        MappingFormats.TINY.write(
            outputMappings,
            reobfMappings.path,
            Constants.SPIGOT_NAMESPACE,
            Constants.DEOBF_NAMESPACE
        )
    }

    private fun filterFieldMappings(mappings: MappingSet): MappingSet {
        val output = MappingSet.create()

        for (topLevelClassMapping in mappings.topLevelClassMappings) {
            val newClassMapping = output.createTopLevelClassMapping(topLevelClassMapping.obfuscatedName, topLevelClassMapping.deobfuscatedName)
            filterFieldMappings(topLevelClassMapping, newClassMapping)
        }

        return output
    }

    private fun filterFieldMappings(originalClassMapping: ClassMapping<*, *>, newClassMapping: ClassMapping<*, *>) {
        for (innerClassMapping in originalClassMapping.innerClassMappings) {
            val newInnerClassMapping = newClassMapping.createInnerClassMapping(innerClassMapping.obfuscatedName, innerClassMapping.deobfuscatedName)
            filterFieldMappings(innerClassMapping, newInnerClassMapping)
        }

        for (methodMapping in originalClassMapping.methodMappings) {
            methodMapping.copy(newClassMapping)
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
}
