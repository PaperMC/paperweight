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
abstract class RemapJar : JavaLauncherTask(), RemapJarParams {
    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        super.init()

        outputJar.convention(defaultOutput())
        jvmArgs.convention(listOf("-Xmx1G"))
        remapperArgs.convention(TinyRemapper.createArgsList())
    }

    @TaskAction
    fun run() {
        TinyRemapper.run(
            inputJar.path,
            outputJar.path,
            this,
            launcher.get(),
            layout.cache.resolve(paperTaskOutput("log")),
            layout.cache
        )
    }
}

interface RemapJarParams {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val mappingsFile: RegularFileProperty

    @get:Input
    val fromNamespace: Property<String>

    @get:Input
    val toNamespace: Property<String>

    @get:CompileClasspath
    val remapClasspath: ConfigurableFileCollection

    @get:Classpath
    val remapper: ConfigurableFileCollection

    @get:Input
    val remapperArgs: ListProperty<String>

    @get:Internal
    val jvmArgs: ListProperty<String>
}

object TinyRemapper {
    private const val minecraftLvPattern = "\\$\\$\\d+"
    private const val fixPackageAccessArg = "--fixpackageaccess"
    private const val rebuildSourceFileNamesArg = "--rebuildsourcefilenames"
    private const val renameInvalidLocalsArg = "--renameinvalidlocals"
    private fun invalidLvNamePatternArg(pattern: String) = "--invalidlvnamepattern=$pattern"
    private fun threadsArg(num: Int) = "--threads=$num"

    private val baseArgs: List<String> = listOf(
        "{input}",
        "{output}",
        "{mappings}",
        "{from}",
        "{to}",
        "{classpath}",
    )

    val minecraftRemapArgs: List<String> = createArgsList(
        fixPackageAccess = true,
        renameInvalidLocals = true,
        invalidLvNamePattern = minecraftLvPattern,
        rebuildSourceFileNames = true,
    )

    val pluginRemapArgs: List<String> = createArgsList()

    fun createArgsList(
        fixPackageAccess: Boolean = false,
        renameInvalidLocals: Boolean = false,
        invalidLvNamePattern: String? = null,
        threads: Int = 1,
        rebuildSourceFileNames: Boolean = false,
    ): List<String> {
        val args = baseArgs.toMutableList()

        args += threadsArg(threads)

        if (fixPackageAccess) {
            args += fixPackageAccessArg
        }
        if (renameInvalidLocals) {
            args += renameInvalidLocalsArg
        }
        invalidLvNamePattern?.let { pattern ->
            args += invalidLvNamePatternArg(pattern)
        }
        if (rebuildSourceFileNames) {
            args += rebuildSourceFileNamesArg
        }

        return args
    }

    private fun List<String>.expandArgs(
        input: String,
        output: String,
        mappings: String,
        fromNamespace: String,
        toNamespace: String,
        classpath: Array<String>,
    ): List<String> {
        val args = mutableListOf<String>()

        for (arg in this) {
            val mapped = when (arg) {
                "{input}" -> input
                "{output}" -> output
                "{mappings}" -> mappings
                "{from}" -> fromNamespace
                "{to}" -> toNamespace
                "{classpath}" -> classpath
                else -> arg
            }
            when (mapped) {
                is String -> args += mapped
                is Array<*> -> mapped.mapTo(args) { it as? String ?: throw PaperweightException("Expected String! Got: '$it'.") }
                else -> throw PaperweightException("Don't know what to do with '$mapped'!")
            }
        }

        return args
    }

    fun run(
        inputJar: Path,
        outputJar: Path,
        params: RemapJarParams,
        launcher: JavaLauncher,
        logFile: Path,
        workingDir: Path,
    ) {
        run(
            argsList = params.remapperArgs.get(),
            logFile = logFile,
            inputJar = inputJar,
            mappingsFile = params.mappingsFile.path,
            fromNamespace = params.fromNamespace.get(),
            toNamespace = params.toNamespace.get(),
            remapClasspath = params.remapClasspath.files.map { it.toPath() },
            remapper = params.remapper,
            outputJar = outputJar,
            launcher = launcher,
            workingDir = workingDir,
            jvmArgs = params.jvmArgs.get()
        )
    }

    fun run(
        argsList: List<String>,
        logFile: Path,
        inputJar: Path,
        mappingsFile: Path,
        fromNamespace: String,
        toNamespace: String,
        remapClasspath: List<Path>,
        remapper: FileCollection,
        outputJar: Path,
        launcher: JavaLauncher,
        workingDir: Path,
        jvmArgs: List<String> = listOf("-Xmx1G"),
    ) {
        ensureDeleted(logFile)
        ensureDeleted(outputJar)

        val args = argsList.expandArgs(
            input = inputJar.absolutePathString(),
            output = outputJar.absolutePathString(),
            mappings = mappingsFile.absolutePathString(),
            fromNamespace = fromNamespace,
            toNamespace = toNamespace,
            classpath = remapClasspath.map { it.absolutePathString() }.toTypedArray(),
        )

        ensureParentExists(logFile)
        ensureParentExists(outputJar)
        launcher.runJar(remapper, workingDir, logFile, jvmArgs = jvmArgs, args = args.toTypedArray())
    }
}
