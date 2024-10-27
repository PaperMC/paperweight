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

package io.papermc.paperweight.tasks.mache

import io.papermc.paperweight.util.*
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

@CacheableTask
abstract class RemapJar : DefaultTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val serverJar: RegularFileProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val serverMappings: RegularFileProperty

    @get:Input
    abstract val remapperArgs: ListProperty<String>

    @get:Classpath
    abstract val codebookClasspath: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val remapperClasspath: ConfigurableFileCollection

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFiles
    abstract val paramMappings: ConfigurableFileCollection

    @get:Classpath
    abstract val constants: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val layout: ProjectLayout

    @TaskAction
    fun run() {
        if (minecraftClasspath.files.isEmpty()) {
            // TODO this shouldn't happen, ideally we somehow make the minecraft classpath available on the first run too
            throw RuntimeException("Could not resolve minecraft dependencies, try again")
        }

        val out = outputJar.convertToPath().ensureClean()

        val logFile = out.resolveSibling("${out.name}.log")

        try {
            logFile.outputStream().buffered().use { log ->
                exec.javaexec {
                    classpath(codebookClasspath.singleFile)

                    maxHeapSize = "2G"

                    remapperArgs.get().forEach { arg ->
                        args(
                            arg
                                .replace(Regex("\\{tempDir}")) { layout.buildDirectory.dir(".tmp_codebook").get().asFile.absolutePath }
                                .replace(Regex("\\{remapperFile}")) { remapperClasspath.singleFile.absolutePath }
                                .replace(Regex("\\{mappingsFile}")) { serverMappings.get().asFile.absolutePath }
                                .replace(Regex("\\{paramsFile}")) { paramMappings.singleFile.absolutePath }
                                .replace(Regex("\\{constantsFile}")) { constants.singleFile.absolutePath }
                                .replace(Regex("\\{output}")) { outputJar.get().asFile.absolutePath }
                                .replace(Regex("\\{input}")) { serverJar.get().asFile.absolutePath }
                                .replace(Regex("\\{inputClasspath}")) { minecraftClasspath.files.joinToString(":") { it.absolutePath } }
                        )
                    }

                    standardOutput = log
                    errorOutput = log
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Error while running codebook, see ${logFile.pathString} for details", e)
        }
    }
}
