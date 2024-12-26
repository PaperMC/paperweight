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
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLauncher

@CacheableTask
abstract class DecompileJar : JavaLauncherTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:Input
    abstract val decompilerArgs: ListProperty<String>

    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val decompiler: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Input
    abstract val memory: Property<String>

    override fun init() {
        super.init()
        memory.convention("4G")
    }

    @TaskAction
    fun run() {
        macheDecompileJar(
            outputJar.path,
            minecraftClasspath.files.map { it.toPath() },
            decompilerArgs.get(),
            inputJar.path,
            launcher.get(),
            decompiler,
            temporaryDir.toPath(),
            memory.get()
        )
    }
}

fun macheDecompileJar(
    outputJar: Path,
    minecraftClasspath: List<Path>,
    decompilerArgs: List<String>,
    inputJar: Path,
    launcher: JavaLauncher,
    decompiler: FileCollection,
    workDir: Path,
    memory: String = "4G"
) {
    val out = outputJar.cleanFile()

    val cfgFile = workDir.resolve("${out.name}.cfg")
    val cfgText = buildString {
        for (file in minecraftClasspath) {
            append("-e=")
            append(file.absolutePathString())
            append(System.lineSeparator())
        }
    }
    cfgFile.writeText(cfgText)

    val logs = workDir.resolve("${out.name}.log")

    val args = mutableListOf<String>()

    args += decompilerArgs

    args += "-cfg"
    args += cfgFile.absolutePathString()

    args += inputJar.convertToPath().absolutePathString()
    args += out.absolutePathString()

    launcher.runJar(
        decompiler,
        workDir,
        logs,
        jvmArgs = listOf("-Xmx$memory"),
        args = args.toTypedArray()
    )

    out.openZip().use { root ->
        root.getPath("META-INF", "MANIFEST.MF").deleteIfExists()
    }
}
