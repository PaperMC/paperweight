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

import io.papermc.paperweight.util.writeMappings
import org.cadixdev.lorenz.io.MappingFormats
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.BufferedReader

open class GenerateSpigotSrgs : DefaultTask() {

    @InputFile
    val notchToSrg = project.objects.fileProperty()
    @InputFile
    val srgToMcp = project.objects.fileProperty()
    @InputFile
    val classMappings = project.objects.fileProperty()
    @InputFile
    val memberMappings = project.objects.fileProperty()
    @InputFile
    val packageMappings = project.objects.fileProperty()

    @OutputFile
    val spigotToSrg = project.objects.fileProperty()
    @OutputFile
    val spigotToMcp = project.objects.fileProperty()
    @OutputFile
    val spigotToNotch = project.objects.fileProperty()
    @OutputFile
    val srgToSpigot = project.objects.fileProperty()
    @OutputFile
    val mcpToSpigot = project.objects.fileProperty()
    @OutputFile
    val notchToSpigot = project.objects.fileProperty()

    @TaskAction
    fun doStuff() {
        // Dirty hack to fix a dirty problem..
        val classMappingSet = classMappings.asFile.get().reader().use { reader ->
            val filtered = object : BufferedReader(reader) {
                override fun lines() = super.lines()
                    .filter { line -> !line.split(" ").take(2).any { it.contains('#') } }
            }
            MappingFormats.CSRG.createReader(filtered).read()
        }
        val memberMappingSet = memberMappings.asFile.get().reader().use { MappingFormats.CSRG.createReader(it).read() }

        val notchToSpigotSet = classMappingSet.merge(memberMappingSet)

        // Apply the package mappings (Lorenz doesn't currently support this)
        val newPackage = packageMappings.asFile.get().readLines()[0].split(Regex("\\s+"))[1]
        for (topLevelClassMapping in notchToSpigotSet.topLevelClassMappings) {
            topLevelClassMapping.deobfuscatedName = newPackage + topLevelClassMapping.deobfuscatedName
        }

        val notchToSrgSet = notchToSrg.asFile.get().reader().use { MappingFormats.TSRG.createReader(it).read() }
        val srgToMcpSet = srgToMcp.asFile.get().reader().use { MappingFormats.TSRG.createReader(it).read() }

        val srgToSpigotSet = notchToSrgSet.reverse().merge(notchToSpigotSet)

        val mcpToNotchSet = notchToSrgSet.merge(srgToMcpSet).reverse()
        val mcpToSpigotSet = mcpToNotchSet.merge(notchToSpigotSet)

        val spigotToSrgSet = srgToSpigotSet.reverse()
        val spigotToMcpSet = mcpToSpigotSet.reverse()
        val spigotToNotchSet = notchToSpigotSet.reverse()

        writeMappings(
            MappingFormats.TSRG,
            spigotToSrgSet to spigotToSrg.asFile.get(),
            spigotToMcpSet to spigotToMcp.asFile.get(),
            spigotToNotchSet to spigotToNotch.asFile.get(),
            srgToSpigotSet to srgToSpigot.asFile.get(),
            mcpToSpigotSet to mcpToSpigot.asFile.get(),
            notchToSpigotSet to notchToSpigot.asFile.get()
        )
    }
}
