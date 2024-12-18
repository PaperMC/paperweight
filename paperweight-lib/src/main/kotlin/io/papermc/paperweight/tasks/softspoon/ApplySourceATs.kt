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

package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.jvm.toolchain.JavaLauncher

abstract class ApplySourceATs {
    @get:Classpath
    abstract val jst: ConfigurableFileCollection

    @get:Input
    abstract val memory: Property<String>

    @get:CompileClasspath
    abstract val jstClasspath: ConfigurableFileCollection

    init {
        memory.convention("1G")
    }

    fun run(
        javaLauncher: JavaLauncher,
        input: Path,
        output: Path,
        atFile: Path,
        workDir: Path,
        singleFile: Boolean = false,
    ) {
        workDir.deleteRecursive()
        workDir.createDirectories()
        javaLauncher.runJar(
            jst,
            workDir,
            workDir.resolve("log.txt"),
            jvmArgs = listOf("-Xmx${memory.get()}"),
            args = jstArgs(input, output, atFile, singleFile).toTypedArray()
        )
    }

    private fun jstArgs(
        inputDir: Path,
        outputDir: Path,
        atFile: Path,
        singleFile: Boolean = false,
    ): List<String> {
        val format = if (singleFile) "FILE" else "FOLDER"
        return listOf(
            "--in-format=$format",
            "--out-format=$format",
            "--enable-accesstransformers",
            "--access-transformer=$atFile",
            "--access-transformer-inherit-method=true",
            "--hidden-prefix=.git",
            // "--access-transformer-validation=ERROR",
            *jstClasspath.files.map { "--classpath=${it.absolutePath}" }.toTypedArray(),
            inputDir.absolutePathString(),
            outputDir.absolutePathString(),
        )
    }
}
