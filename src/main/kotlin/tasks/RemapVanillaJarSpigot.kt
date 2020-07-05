/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
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

import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.runJar
import io.papermc.paperweight.util.wrapException
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property

open class RemapVanillaJarSpigot : DefaultTask() {

    @InputFile
    val inputJar: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val classMappings: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val memberMappings: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val packageMappings: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val accessTransformers: RegularFileProperty = project.objects.fileProperty()

    @Input
    val workDirName: Property<String> = project.objects.property()

    @InputFile
    val specialSourceJar: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val specialSource2Jar: RegularFileProperty = project.objects.fileProperty()

    @Input
    val classMapCommand: Property<String> = project.objects.property()

    @Input
    val memberMapCommand: Property<String> = project.objects.property()

    @Input
    val finalMapCommand: Property<String> = project.objects.property()

    @OutputFile
    val outputJar: RegularFileProperty = defaultOutput()

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

        try {
            println("Applying class mappings...")
            wrapException("Failed to apply class mappings") {
                val logFile = project.cache.resolve(paperTaskOutput("class.log"))
                logFile.delete()
                runJar(
                    specialSource2Jar,
                    workingDir = workDirName.get(),
                    logFile = logFile,
                    args = *doReplacements(classMapCommand.get(), inputJarPath, classMappingPath, classJarPath) {
                        // ignore excludes, we actually want to map every class
                        it != "-e"
                    }
                )
            }
            println("Applying member mappings...")
            wrapException("Failed to apply member mappings") {
                val logFile = project.cache.resolve(paperTaskOutput("member.log"))
                logFile.delete()
                runJar(
                    specialSource2Jar,
                    workingDir = workDirName.get(),
                    logFile = logFile,
                    args = *doReplacements(memberMapCommand.get(), classJarPath, memberMappingsPath, membersJarPath)
                )
            }
            println("Creating remapped jar...")
            wrapException("Failed to create remapped jar") {
                val logFile = project.cache.resolve(paperTaskOutput("final.log"))
                logFile.delete()
                runJar(
                    specialSourceJar,
                    workingDir = workDirName.get(),
                    logFile = logFile,
                    args = *doReplacements(finalMapCommand.get(), membersJarPath, accessTransformersPath, packageMappingsPath, outputJarPath)
                )
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
