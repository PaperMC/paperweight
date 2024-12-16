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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLauncher

@CacheableTask
abstract class RemapJar : JavaLauncherTask() {

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

    @TaskAction
    fun run() {
        macheRemapJar(
            launcher.get(),
            codebookClasspath,
            outputJar.path,
            remapperArgs.get(),
            temporaryDir.toPath(),
            remapperClasspath,
            serverMappings.path,
            paramMappings.singleFile.toPath(),
            constants.files.singleOrNull()?.toPath(),
            serverJar.path,
            minecraftClasspath.files.map { it.toPath() }
        )
    }
}

fun macheRemapJar(
    launcher: JavaLauncher,
    codebookClasspath: FileCollection,
    outputJar: Path,
    remapperArgs: List<String>,
    tempDir: Path,
    remapperClasspath: FileCollection,
    serverMappings: Path,
    paramMappings: Path,
    constants: Path?,
    serverJar: Path,
    minecraftClasspath: List<Path>,
) {
    val out = outputJar.cleanFile()

    val logFile = out.resolveSibling("${out.name}.log")

    val args = mutableListOf<String>()

    remapperArgs.forEach { arg ->
        args += arg
            .replace(Regex("\\{tempDir}")) { tempDir.absolutePathString() }
            .replace(Regex("\\{remapperFile}")) { remapperClasspath.singleFile.absolutePath }
            .replace(Regex("\\{mappingsFile}")) { serverMappings.absolutePathString() }
            .replace(Regex("\\{paramsFile}")) { paramMappings.absolutePathString() }
            .replace(Regex("\\{constantsFile}")) { constants!!.absolutePathString() }
            .replace(Regex("\\{output}")) { outputJar.absolutePathString() }
            .replace(Regex("\\{input}")) { serverJar.absolutePathString() }
            .replace(Regex("\\{inputClasspath}")) { minecraftClasspath.joinToString(":") { it.absolutePathString() } }
    }

    launcher.runJar(
        codebookClasspath,
        tempDir,
        logFile,
        jvmArgs = listOf("-Xmx2G"),
        args = args.toTypedArray()
    )
}
