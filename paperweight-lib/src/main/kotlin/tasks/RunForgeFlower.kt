/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLauncher

private const val quiltflowerArgumentPrefix = "quiltflower:"
private fun quiltflowerOnly(arg: String) = quiltflowerArgumentPrefix + arg

val forgeFlowerArgList: List<String> = listOf(
    "-ind=    ",
    "-din=1",
    "-rbr=1",
    "-dgs=1",
    "-asc=1",
    "-rsy=1",
    "-iec=1",
    "-jvn=0",
    "-isl=0",
    "-iib=1",
    quiltflowerOnly("-ovr=0"), // We add override annotations ourselves. Quiltflower's impl doesn't work as well yet and conflicts
    quiltflowerOnly("-pll=999999"), // High line length to effectively disable formatting
    "-log=TRACE",
    "-cfg",
    "{libraries}",
    "{input}",
    "{output}"
)

private fun List<String>.createForgeFlowerArgs(
    libraries: String,
    input: String,
    output: String,
    quiltflower: Boolean,
): List<String> = mapNotNull {
    var s = it
    if (s.startsWith(quiltflowerArgumentPrefix)) {
        if (!quiltflower) {
            return@mapNotNull null
        }
        s = s.substringAfter(quiltflowerArgumentPrefix)
    }
    when (s) {
        "{libraries}" -> libraries
        "{input}" -> input
        "{output}" -> output
        else -> s
    }
}

fun runForgeFlower(
    argsList: List<String>,
    logFile: Path,
    workingDir: Path,
    executable: FileCollection,
    inputJar: Path,
    libraries: List<Path>,
    outputJar: Path,
    javaLauncher: JavaLauncher,
    jvmArgs: List<String> = listOf("-Xmx4G")
) {
    val quiltflower = isQuiltflower(executable)

    val outputSiblingDir = outputJar.resolveSibling("${outputJar.name}.dir")
    if (!quiltflower) {
        if (outputSiblingDir.exists()) {
            outputSiblingDir.deleteRecursively()
        }
        outputSiblingDir.createDirectories()
    }

    val libs = ArrayList(libraries)
    libs.sort()
    val tempFile = createTempFile("paperweight", "txt")

    try {
        tempFile.bufferedWriter().use { writer ->
            for (lib in libs) {
                if (lib.isLibraryJar) {
                    writer.appendLine("-e=${lib.absolutePathString()}")
                }
            }
        }

        val output = if (quiltflower) outputJar else outputSiblingDir
        val argList = argsList.createForgeFlowerArgs(
            tempFile.absolutePathString(),
            inputJar.absolutePathString(),
            output.absolutePathString(),
            quiltflower,
        )

        outputJar.deleteForcefully()
        logFile.deleteForcefully()

        javaLauncher.runJar(executable, workingDir, logFile, jvmArgs = jvmArgs, args = argList.toTypedArray())

        if (!quiltflower) {
            // FernFlower is weird with how it does directory output
            outputSiblingDir.resolve(inputJar.name).moveTo(outputJar, overwrite = true)
        }
    } finally {
        tempFile.deleteForcefully()
        outputSiblingDir.deleteRecursively()
    }
}

private fun isQuiltflower(fileCollection: FileCollection): Boolean = fileCollection.files.asSequence()
    .map(File::toPath)
    .filter { f -> f.name.endsWith(".jar") && f.isQuiltflowerJar() }
    .any()

private fun Path.isQuiltflowerJar(): Boolean {
    if (!Files.isRegularFile(this)) {
        return false
    }

    JarFile(toFile()).use { jar ->
        if (jar.manifest.mainAttributes.getValue("Implementation-Name") == "Quiltflower") {
            return true
        }
    }

    return false
}

@CacheableTask
abstract class RunForgeFlower : JavaLauncherTask() {

    @get:Classpath
    abstract val executable: ConfigurableFileCollection

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx4G"))
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        runForgeFlower(
            forgeFlowerArgList,
            layout.cache.resolve(paperTaskOutput("log")),
            layout.cache,
            executable,
            inputJar.path,
            libraries.files.map { it.toPath() },
            outputJar.path,
            launcher.get(),
            jvmargs.get()
        )
    }
}
