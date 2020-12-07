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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.runJar
import java.nio.file.Paths
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class RemapVanillaJarSpigot : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty
    @get:InputFile
    abstract val classMappings: RegularFileProperty
    @get:InputFile
    abstract val memberMappings: RegularFileProperty
    @get:InputFile
    abstract val packageMappings: RegularFileProperty
    @get:InputFile
    abstract val accessTransformers: RegularFileProperty
    @get:Input
    abstract val workDirName: Property<String>
    @get:InputFile
    abstract val specialSourceJar: RegularFileProperty
    @get:InputFile
    abstract val specialSource2Jar: RegularFileProperty
    @get:Input
    abstract val classMapCommand: Property<String>
    @get:Input
    abstract val memberMapCommand: Property<String>
    @get:Input
    abstract val finalMapCommand: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val inputJarPath = inputJar.asFile.get().canonicalPath

        val outputJarFile = outputJar.asFile.get()
        val outputJarPath = outputJarFile.canonicalPath

        val classJarFile = outputJarFile.resolveSibling(outputJarFile.name + ".classes")
        val membersJarFile = outputJarFile.resolveSibling(outputJarFile.name + ".members")
        val classJarPath = classJarFile.canonicalPath
        val membersJarPath = membersJarFile.canonicalPath

        val classMappingPath = classMappings.asFile.get().canonicalPath
        val memberMappingsPath = memberMappings.asFile.get().canonicalPath
        val packageMappingsPath = packageMappings.asFile.get().canonicalPath
        val accessTransformersPath = accessTransformers.asFile.get().canonicalPath

        val work = layout.projectDirectory.file(workDirName.get())

        try {
            try {
                val logFile = layout.cache.resolve(paperTaskOutput("class.log"))
                logFile.delete()
                runJar(
                    specialSource2Jar,
//                    Paths.get("/home/demonwav/IdeaProjects/SpecialSource2/build/libs/SpecialSource2-2.0.0-PW-SNAPSHOT-all.jar"),
                    workingDir = work,
                    logFile = logFile,
//                    jvmArgs = listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"),
                    args = *doReplacements(classMapCommand.get(), inputJarPath, classMappingPath, classJarPath) {
                        // ignore excludes, we actually want to map every class
                        it != "-e"
                    }
                )
            } catch (e: Exception) {
                throw PaperweightException("Failed to apply class mappings", e)
            }

            try {
                val logFile = layout.cache.resolve(paperTaskOutput("member.log"))
                logFile.delete()
                runJar(
//                    Paths.get("/home/demonwav/IdeaProjects/SpecialSource2/build/libs/SpecialSource2-2.0.0-PW-SNAPSHOT-all.jar"),
                    specialSource2Jar,
                    workingDir = work,
                    logFile = logFile,
//                    jvmArgs = listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"),
                    args = *doReplacements(memberMapCommand.get(), classJarPath, memberMappingsPath, membersJarPath)
                )
            } catch (e: Exception) {
                throw PaperweightException("Failed to apply member mappings", e)
            }

            try {
                val logFile = layout.cache.resolve(paperTaskOutput("final.log"))
                logFile.delete()
                runJar(
                    specialSourceJar,
                    workingDir = work,
                    logFile = logFile,
                    args = *doReplacements(finalMapCommand.get(), membersJarPath, accessTransformersPath, packageMappingsPath, outputJarPath)
                )
            } catch (e: Exception) {
                throw PaperweightException("Failed to create remapped jar", e)
            }
        } finally {
            classJarFile.delete()
            membersJarFile.delete()
        }
    }

    private val indexReg = Regex("\\{(\\d)}")
    private fun doReplacements(command: String, vararg values: String, filter: ((String) -> Boolean)? = null): Array<String> {
        var skipNext = false
        return command.split(" ").let { it.subList(3, it.size) }
            .filter {
                if (skipNext) {
                    skipNext = false
                    return@filter false
                } else if (filter != null && !filter(it)) {
                    skipNext = true
                    return@filter false
                } else {
                    return@filter true
                }
            }
            .map {
                val index = indexReg.matchEntire(it)?.groupValues?.get(1)?.toInt()
                return@map if (index != null) {
                    values[index]
                } else {
                    it
                }
            }.toTypedArray()
    }
}
