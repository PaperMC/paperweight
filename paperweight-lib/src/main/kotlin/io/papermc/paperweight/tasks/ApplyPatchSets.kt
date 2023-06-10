package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyPatchSets : ControllableOutputTask() {

    @get:Input
    abstract val patchSets: ListProperty<PatchSet>

    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:InputDirectory
    abstract val patchesDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val workDir: DirectoryProperty

    @TaskAction
    fun runLocking() {
        val lockFile = layout.cache.resolve(applyPatchesLock(outputDir.path))
        acquireProcessLockWaiting(lockFile)
        try {
            run()
        } finally {
            lockFile.deleteForcefully()
        }
    }

    private fun run() {
        Git.checkForGit()

        createDir(workDir.path)

        println("  Importing decompiled vanilla")
        var upstream = resolveWorkDir("vanilla")
        importVanilla(upstream)
        // TODO I think we also need to clone Paper-Server into here?

        Git(upstream).let { git ->
            git("init", "--quiet").executeSilently(silenceErr = true)
            git.disableAutoGpgSigningInRepo()

            git(*Git.add(false, ".")).setupOut().run()
            git("commit", "-m", "Vanilla", "--author=Mojang <auto@mated.null>").setupOut().run()

            git("tag", "-d", "vanilla").runSilently(silenceErr = true)
            git("tag", "vanilla").executeSilently(silenceErr = true)
        }

        patchSets.get().forEach { patchSet ->
            println("  Applying patch set ${patchSet.name}...")

            val patches = mutableListOf<Path>()
            if (patchSet.mavenCoordinates != null) {
                patchesDir.path.resolve("${patchSet.name}.jar").openZip().use { patchZip ->
                    val matcher = patchZip.getPathMatcher("glob:*.patch")
                    // todo we prolly need to extract the zip
                    patches.addAll(Files.walk(patchZip.getPath(patchSet.pathInArtifact?: "patches")).use { stream ->
                        stream.filter {
                            it.isRegularFile() && matcher.matches(it.fileName)
                        }.collect(Collectors.toList())
                    })
                }
            } else if (patchSet.folder != null) {
                val patchFolder = patchSet.folder.path
                if (Files.isDirectory(patchFolder)) {
                    patches.addAll(patchFolder.filesMatchingRecursive("*.patch"))
                }
            } else {
                throw RuntimeException("No input for patch set ${patchSet.name}?!")
            }

            println("    Found ${patches.size} patches")

            val input = resolveWorkDir(patchSet.name)
            createDir(input)
            Git(input).let { git ->
                git.checkoutRepoFromUpstream(upstream)

                when (patchSet.type) {
                    PatchSetType.FEATURE -> {
                        println("    Applying feature patches...")
                        // TODO
                    }
                    PatchSetType.FILE_BASED -> {
                        println("    Applying file based patches...")

                        for (patch in patches) {
                            git("apply", "--ignore-whitespace", patch.absolutePathString()).executeOut()
                        }

                        git(*Git.add(false, ".")).setupOut().run()
                        git("commit", "-m", patchSet.name, "--author=${patchSet.name} <auto@mated.null>").setupOut().run()
                    }
                }

                git("tag", "-d", patchSet.name).runSilently(silenceErr = true)
                git("tag", patchSet.name).executeSilently(silenceErr = true)
            }

            upstream = input
        }
    }

    private fun resolveWorkDir(name: String): Path = workDir.get().path.resolve(name)

    private fun importVanilla(targetDir: Path) {
        sourceMcDevJar.path.openZip().use { zipFile ->
            zipFile.walk().use { stream ->
                for (zipEntry in stream) {
                    // substring(1) trims the leading /
                    val path = zipEntry.invariantSeparatorsPathString.substring(1)

                    // pull in all classes
                    // TODO allow including other stuff?
                    if (zipEntry.toString().endsWith(".java")) {
                        val targetFile = targetDir.resolve(path)
                        if (targetFile.exists()) {
                            continue
                        }
                        if (!targetFile.parent.exists()) {
                            targetFile.parent.createDirectories()
                        }
                        zipEntry.copyTo(targetFile)
                    }
                }
            }
        }
    }

    private fun createDir(dir: Path): Path {
        dir.deleteRecursively()
        dir.createDirectories()
        return dir
    }
}
