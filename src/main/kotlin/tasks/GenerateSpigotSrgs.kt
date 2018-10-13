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

    @get:InputFile lateinit var notchToSrg: Any
    @get:InputFile lateinit var srgToMcp: Any
    @get:InputFile lateinit var classMappings: Any
    @get:InputFile lateinit var memberMappings: Any
    @get:InputFile lateinit var packageMappings: Any

    @get:OutputFile lateinit var spigotToSrg: Any
    @get:OutputFile lateinit var spigotToMcp: Any
    @get:OutputFile lateinit var spigotToNotch: Any
    @get:OutputFile lateinit var srgToSpigot: Any
    @get:OutputFile lateinit var mcpToSpigot: Any
    @get:OutputFile lateinit var notchToSpigot: Any

    @TaskAction
    fun doStuff() {
        // Dirty hack to fix a dirty problem..
        val classMappingSet = project.file(classMappings).reader().use { reader ->
            val filtered = object : BufferedReader(reader) {
                override fun lines() = super.lines()
                    .filter { line -> !line.split(" ").take(2).any { it.contains('#') } }
            }
            MappingFormats.CSRG.createReader(filtered).read()
        }
        val memberMappingSet = project.file(memberMappings).reader().use { MappingFormats.CSRG.createReader(it).read() }

        val notchToSpigotSet = classMappingSet.merge(memberMappingSet)

        // Apply the package mappings (Lorenz doesn't currently support this)
        val newPackage = project.file(packageMappings).readLines()[0].split(Regex("\\s+"))[1]
        for (topLevelClassMapping in notchToSpigotSet.topLevelClassMappings) {
            topLevelClassMapping.deobfuscatedName = newPackage + topLevelClassMapping.deobfuscatedName
        }

        val notchToSrgSet = project.file(notchToSrg).reader().use { MappingFormats.TSRG.createReader(it).read() }
        val srgToMcpSet = project.file(srgToMcp).reader().use { MappingFormats.TSRG.createReader(it).read() }

        val srgToSpigotSet = notchToSrgSet.reverse().merge(notchToSpigotSet)

        val mcpToNotchSet = notchToSrgSet.merge(srgToMcpSet).reverse()
        val mcpToSpigotSet = mcpToNotchSet.merge(notchToSpigotSet)

        val spigotToSrgSet = srgToSpigotSet.reverse()
        val spigotToMcpSet = mcpToSpigotSet.reverse()
        val spigotToNotchSet = notchToSpigotSet.reverse()

        writeMappings(
            MappingFormats.TSRG,
            spigotToSrgSet to project.file(spigotToSrg),
            spigotToMcpSet to project.file(spigotToMcp),
            spigotToNotchSet to project.file(spigotToNotch),
            srgToSpigotSet to project.file(srgToSpigot),
            mcpToSpigotSet to project.file(mcpToSpigot),
            notchToSpigotSet to project.file(notchToSpigot)
        )
    }
}
