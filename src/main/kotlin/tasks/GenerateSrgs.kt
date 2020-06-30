/*
 * Copyright 2018 Kyle Wood
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.getReader
import io.papermc.paperweight.util.writeMappings
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.model.ClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

open class GenerateSrgs : DefaultTask() {

    @InputFile
    val inSrg = project.objects.fileProperty() // Notch -> SRG
    @InputFile
    val methodsCsv = project.objects.fileProperty()
    @InputFile
    val fieldsCsv = project.objects.fileProperty()

    @OutputFile
    val notchToSrg = project.objects.fileProperty()
    @OutputFile
    val notchToMcp = project.objects.fileProperty()
    @OutputFile
    val mcpToNotch = project.objects.fileProperty()
    @OutputFile
    val mcpToSrg = project.objects.fileProperty()
    @OutputFile
    val srgToNotch = project.objects.fileProperty()
    @OutputFile
    val srgToMcp = project.objects.fileProperty()

    @TaskAction
    fun doStuff() {
        val methods = HashMap<String, String>()
        val fields = HashMap<String, String>()
        readCsvs(methods, fields)

        val inSet = inSrg.asFile.get().reader().use {
            MappingFormats.TSRG.createReader(it).read()
        }

        ensureParentExists(notchToSrg, notchToMcp, mcpToNotch, mcpToSrg, srgToNotch, srgToMcp)
        createMappings(inSet, methods, fields)
    }

    private fun readCsvs(methods: MutableMap<String, String>, fields: MutableMap<String, String>) {
        getReader(methodsCsv.asFile.get()).use { reader ->
            for (line in reader.readAll()) {
                methods[line[0]] = line[1]
            }
        }

        getReader(fieldsCsv.asFile.get()).use { reader ->
            for (line in reader.readAll()) {
                fields[line[0]] = line[1]
            }
        }
    }

    private fun createMappings(notchToSrgSet: MappingSet, methods: Map<String, String>, fields: Map<String, String>) {
        // We have Notch -> SRG
        val srgToNotchSet = notchToSrgSet.reverse()

        val notchToMcpSet: MappingSet
        val mcpToNotchSet: MappingSet
        val mcpToSrgSet: MappingSet

        val srgToMcpSet = MappingSet.create()

        // Create SRG -> MCP from Notch -> SRG and the CSVs
        for (topLevelClassMapping in notchToSrgSet.topLevelClassMappings) {
            // MCP and SRG have the same class names
            val srgToMcpClass = srgToMcpSet.createTopLevelClassMapping(
                topLevelClassMapping.deobfuscatedName,
                topLevelClassMapping.deobfuscatedName
            )

            mapClass(topLevelClassMapping, srgToMcpClass, methods, fields)
        }

        // We have Notch->SRG and SRG->MCP mapping sets now
        // All other sets can be generated from these two
        notchToMcpSet = notchToSrgSet.merge(srgToMcpSet)
        mcpToNotchSet = notchToMcpSet.reverse()
        mcpToSrgSet = srgToMcpSet.reverse()

        writeMappings(
            MappingFormats.TSRG,
            notchToSrgSet to notchToSrg.asFile.get(),
            notchToMcpSet to notchToMcp.asFile.get(),
            mcpToNotchSet to mcpToNotch.asFile.get(),
            mcpToSrgSet to mcpToSrg.asFile.get(),
            srgToNotchSet to srgToNotch.asFile.get(),
            srgToMcpSet to srgToMcp.asFile.get()
        )
    }

    private fun mapClass(inClass: ClassMapping<*, *>, outClass: ClassMapping<*, *>, methods: Map<String, String>, fields: Map<String, String>) {
        for (fieldMapping in inClass.fieldMappings) {
            val mcpName = fields[fieldMapping.deobfuscatedName] ?: fieldMapping.deobfuscatedName
            val mapping = outClass.createFieldMapping(fieldMapping.deobfuscatedSignature, mcpName)
            mapping.obfuscatedName
        }
        for (methodMapping in inClass.methodMappings) {
            val mcpName = methods[methodMapping.deobfuscatedName] ?: methodMapping.deobfuscatedName
            outClass.createMethodMapping(methodMapping.deobfuscatedSignature, mcpName)
        }
        for (innerClassMapping in inClass.innerClassMappings) {
            val innerOutClass = outClass.createInnerClassMapping(innerClassMapping.deobfuscatedName)
            mapClass(innerClassMapping, innerOutClass, methods, fields)
        }
    }
}
