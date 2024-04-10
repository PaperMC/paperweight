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

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLauncher

val vineFlowerArgList: List<String> = listOf(
    "--synthetic-not-set=true",
    "--ternary-constant-simplification=true",
    "--verify-merges=true",
    "--include-runtime=current",
    "--decompile-complex-constant-dynamic=true",
    "--indent-string=    ",
    "--decompile-inner=true", // is default
    "--remove-bridge=true", // is default
    "--decompile-generics=true", // is default
    "--ascii-strings=false", // is default
    "--remove-synthetic=true", // is default
    "--include-classpath=true",
    "--inline-simple-lambdas=true", // is default
    "--ignore-invalid-bytecode=false", // is default
    "--bytecode-source-mapping=true",
    "--dump-code-lines=true",
    "--override-annotation=false", // We add override annotations ourselves. Vineflower's impl doesn't work as well yet and conflicts
    "-cfg", // Pass the libraries as an argument file to avoid command line length limits
    "{libraries}",
    "{input}",
    "{output}"
)

private fun List<String>.createDecompilerArgs(
    libraries: String,
    input: String,
    output: String,
): List<String> = map {
    when (it) {
        "{libraries}" -> libraries
        "{input}" -> input
        "{output}" -> output
        else -> it
    }
}

fun runDecompiler(
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
    val libs = ArrayList(libraries)
    libs.sort()
    val tempFile = createTempFile("paperweight", "txt")

    try {
        val vineflower = isVineflower(executable)
        tempFile.bufferedWriter().use { writer ->
            for (lib in libs) {
                if (lib.isLibraryJar) {
                    if (vineflower) {
                        writer.appendLine("--add-external=${lib.absolutePathString()}")
                    } else {
                        writer.appendLine("-e=${lib.absolutePathString()}")
                    }
                }
            }
        }

        val argList = argsList.createDecompilerArgs(
            tempFile.absolutePathString(),
            inputJar.absolutePathString(),
            outputJar.absolutePathString(),
        )

        outputJar.deleteForcefully()
        logFile.deleteForcefully()
        outputJar.parent.createDirectories()

        javaLauncher.runJar(executable, workingDir, logFile, jvmArgs = jvmArgs, args = argList.toTypedArray())
    } finally {
        tempFile.deleteForcefully()
    }
}

private fun isVineflower(executable: FileCollection) = executable.files.any {
    it.toPath().openZip().use { fs ->
        val manifest = fs.getPath("META-INF/MANIFEST.MF").takeIf { f -> f.isRegularFile() }?.inputStream()?.buffered()?.use { reader ->
            Manifest(reader)
        }
        manifest != null &&
            manifest.mainAttributes.containsKey(Attributes.Name("Implementation-Name")) &&
            manifest.mainAttributes.getValue("Implementation-Name").equals("Vineflower", ignoreCase = true)
    }
}

@CacheableTask
abstract class RunVineFlower : JavaLauncherTask() {

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
        runDecompiler(
            vineFlowerArgList,
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
