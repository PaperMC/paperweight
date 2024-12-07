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
import io.papermc.paperweight.util.constants.*
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

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
        val out = outputJar.convertToPath().ensureClean()

        val cfgFile = layout.cache.resolve(DECOMP_CFG).ensureClean()
        val cfgText = buildString {
            for (file in minecraftClasspath.files) {
                append("-e=")
                append(file.toPath().absolutePathString())
                append(System.lineSeparator())
            }
        }
        cfgFile.writeText(cfgText)

        val logs = out.resolveSibling("${out.name}.log")

        val args = mutableListOf<String>()

        args += decompilerArgs.get()

        args += "-cfg"
        args += cfgFile.absolutePathString()

        args += inputJar.convertToPath().absolutePathString()
        args += out.absolutePathString()

        launcher.runJar(
            decompiler,
            temporaryDir,
            logs,
            jvmArgs = listOf("-Xmx${memory.get()}"),
            args = args.toTypedArray()
        )

        out.openZip().use { root ->
            root.getPath("META-INF", "MANIFEST.MF").deleteIfExists()
        }
    }
}
