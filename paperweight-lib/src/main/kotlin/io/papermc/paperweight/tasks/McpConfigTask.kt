package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.paperTaskOutput
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

fun buildArgs(args: List<String>, props: Map<String, Any>): MutableList<String> {
    val result = mutableListOf<String>()
    args.forEach { arg ->
        if (!arg.startsWith('{') || !arg.endsWith('}') || !props.containsKey(arg.substring(1, arg.length - 1))) {
            result.add(arg)
        } else {
            val value = props[arg.substring(1, arg.length - 1)]
            if (value is Path) {
                result.add(value.toAbsolutePath().toString())
            } else {
                result.add(value.toString())
            }
        }
    }
    return result
}

abstract class McpConfigTask : JavaLauncherTask() {

    @get:Classpath
    abstract val executable: ConfigurableFileCollection

    @get:Input
    abstract val args: ListProperty<String>

    @get:Input
    abstract val jvmargs: ListProperty<String>
}

@CacheableTask
abstract class RunMcpConfigDecompile : McpConfigTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val input: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraries: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val logFile = layout.cache.resolve(paperTaskOutput("log"))
        val patchedArgs = args.get().toMutableList()
        patchedArgs.add(0, "-ind=    ") // we want 4 spaces, not 3 like some maniac!
        launcher.get().runJar(
            executable,
            layout.cache,
            logFile,
            jvmArgs = jvmargs.get(),
            args = buildArgs(
                patchedArgs, mapOf(
                    "libraries" to libraries.get().path,
                    "input" to input.get().path,
                    "output" to output.get().path
                )
            ).toTypedArray()
        )
        logFile.deleteForcefully()
    }
}

@CacheableTask
abstract class RunMcpConfigRename : McpConfigTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val input: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraries: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val logFile = layout.cache.resolve(paperTaskOutput("log"))
        launcher.get().runJar(
            executable,
            layout.cache,
            logFile,
            jvmArgs = jvmargs.get(),
            args = buildArgs(
                args.get(), mapOf(
                    "mappings" to mappings.get().path,
                    "libraries" to libraries.get().path,
                    "input" to input.get().path,
                    "output" to output.get().path
                )
            ).toTypedArray()
        )
        logFile.deleteForcefully()
    }
}

@CacheableTask
abstract class RunMcpConfigMerge : McpConfigTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val official: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val logFile = layout.cache.resolve(paperTaskOutput("log"))
        launcher.get().runJar(
            executable,
            layout.cache,
            logFile,
            jvmArgs = jvmargs.get(),
            args = buildArgs(
                args.get(), mapOf(
                    "mappings" to mappings.get().path,
                    "official" to official.get().path,
                    "output" to output.get().path
                )
            ).toTypedArray()
        )
        logFile.deleteForcefully()
    }
}

@CacheableTask
abstract class PrepareBase : ControllableOutputTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val input: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patches: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        println("Cleanup")
        output.path.deleteRecursively()
        val vanillaDir = output.path.resolve("src/vanilla")

        println("Unzipping...")
        input.path.openZip().use { zipFile ->
            zipFile.walk().use { stream ->
                stream.parallel().forEach { zipEntry ->
                    // substring(1) trims the leading /
                    val path = zipEntry.invariantSeparatorsPathString.substring(1)

                    val f = if (path.startsWith("com") || path.startsWith("net")) "java" else "resources"

                    val targetFile = vanillaDir.resolve(f).resolve(path)
                    if (!targetFile.parent.exists()) {
                        targetFile.parent.createDirectories()
                    }
                    if (!Files.isDirectory(zipEntry)) {
                        zipEntry.copyTo(targetFile, true)
                    }
                }
            }
        }

        println("Patching...")
        val patches = this.patches.path.filesMatchingRecursive("*.patch")
        Git(vanillaDir).let { git ->
            git("init", "--quiet").executeSilently(silenceErr = true)
            git.disableAutoGpgSigningInRepo()

            git(*Git.add(false, ".")).run()
            git("commit", "-m", "Vanilla", "--author=Mojang <auto@mated.null>").run()

            git("tag", "-d", "vanilla").runSilently(silenceErr = true)
            git("tag", "vanilla").executeSilently(silenceErr = true)

            patches.parallelStream().forEach { patch ->
                try {
                    git("apply", "--ignore-whitespace", "--directory", "java", patch.absolutePathString()).executeOut()
                } catch (ex: Exception) {
                }
            }

            git(*Git.add(false, ".")).run()
            git("commit", "-m", "MCP Config Patches", "--author=MCPConfig <auto@mated.null>").run()
            git("tag", "-d", "mcp-config").runSilently(silenceErr = true)
            git("tag", "mcp-config").executeSilently(silenceErr = true)
        }

        println("Renaming")
        val regex = Regex("[pfm]_\\d+_")
        val srgToMojang = mutableMapOf<String, String>()
        Files.readAllLines(mappings.path).forEach {
            val split = it.split(",")
            srgToMojang[split[0]] = split[1]
        }

        val missedMappings = mutableSetOf<String>()
        vanillaDir.filesMatchingRecursive("*.java").parallelStream().forEach { file ->
            var content = Files.readString(file)
            content = regex.replace(content) { res ->
                val mapping = srgToMojang[res.groupValues[0]]
                if (mapping != null) {
                    if (res.groupValues[0].startsWith("p")) {
                        return@replace "_$mapping"
                    } else {
                        return@replace mapping
                    }
                } else {
                    missedMappings.add(res.groupValues[0])
                    return@replace res.groupValues[0]
                }
            }
            Files.writeString(file, content, StandardOpenOption.TRUNCATE_EXISTING)
        }
        missedMappings.forEach {
            println("missed mapping $it")
        }
        println("${missedMappings.size} in total")

        Git(vanillaDir).let { git ->
            git(*Git.add(false, ".")).run()
            git("commit", "-m", "Remap to Mojang+Yarn", "--author=Remap <auto@mated.null>").run()
            git("tag", "-d", "base").runSilently(silenceErr = true)
            git("tag", "base").executeSilently(silenceErr = true)
        }

        println("done")
    }
}

@CacheableTask
abstract class CreateFernflowerLibraries : BaseTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val input: DirectoryProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val libs = input.path.filesMatchingRecursive("*.jar")
        val lines = libs.map { "-e=" + it.toAbsolutePath().toString() }
        Files.write(output.path, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
}
