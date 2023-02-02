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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class SpigotRemapJar : JavaLauncherTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classMappings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val memberMappings: RegularFileProperty

    @get:Input
    abstract val mcVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val accessTransformers: RegularFileProperty

    @get:Input
    abstract val workDirName: Property<String>

    @get:Classpath
    abstract val specialSourceJar: RegularFileProperty

    @get:Classpath
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
        super.init()

        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val inputJarPath = inputJar.path.absolutePathString()

        val outputJarFile = outputJar.path
        val outputJarPath = outputJarFile.absolutePathString()

        val classJarFile = outputJarFile.resolveSibling(outputJarFile.name + ".classes")
        val membersJarFile = outputJarFile.resolveSibling(outputJarFile.name + ".members")
        val classJarPath = classJarFile.absolutePathString()
        val membersJarPath = membersJarFile.absolutePathString()

        val classMappingPath = classMappings.path.absolutePathString()
        val accessTransformersPath = accessTransformers.path.absolutePathString()

        val spigotMembersPath = memberMappings.path.absolutePathString()

        val work = layout.projectDirectory.file(workDirName.get())

        val spigotEmptyMappings = layout.cache.resolve("spigot-empty-package-mappings.csrg")
        spigotEmptyMappings.writeText("")

        try {
            try {
                val logFile = layout.cache.resolve(paperTaskOutput("class.log"))
                logFile.deleteForcefully()
                launcher.runJar(
                    objects.fileCollection().from(specialSource2Jar),
                    workingDir = work,
                    logFile = logFile,
                    args = doReplacements(classMapCommand.get(), inputJarPath, classMappingPath, classJarPath) {
                        // ignore excludes, we actually want to map every class
                        it != "-e"
                    }
                )
            } catch (e: Exception) {
                throw PaperweightException("Failed to apply class mappings", e)
            }

            try {
                val logFile = layout.cache.resolve(paperTaskOutput("member.log"))
                logFile.deleteForcefully()
                launcher.runJar(
                    objects.fileCollection().from(specialSource2Jar),
                    workingDir = work,
                    logFile = logFile,
                    args = doReplacements(memberMapCommand.get(), classJarPath, spigotMembersPath, membersJarPath)
                )
            } catch (e: Exception) {
                throw PaperweightException("Failed to apply member mappings", e)
            }

            try {
                val logFile = layout.cache.resolve(paperTaskOutput("final.log"))
                logFile.deleteForcefully()
                launcher.runJar(
                    objects.fileCollection().from(specialSourceJar),
                    workingDir = work,
                    logFile = logFile,
                    args = doReplacements(
                        finalMapCommand.get(),
                        membersJarPath,
                        accessTransformersPath,
                        spigotEmptyMappings.absolutePathString(),
                        outputJarPath
                    )
                )
            } catch (e: Exception) {
                throw PaperweightException("Failed to create remapped jar", e)
            }
        } finally {
            classJarFile.deleteForcefully()
            membersJarFile.deleteForcefully()
            spigotEmptyMappings.deleteForcefully()
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
