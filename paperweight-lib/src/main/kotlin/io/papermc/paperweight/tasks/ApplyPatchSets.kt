package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.applyPatchesLock
import io.papermc.paperweight.util.data.PatchSet
import io.papermc.paperweight.util.data.PatchSetType
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

abstract class ApplyPatchSets : ControllableOutputTask() {

    @get:Input
    abstract val patchSets: ListProperty<PatchSet>

    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    //@get:Optional
    //@get:InputDirectory
    //abstract val patchesDir: DirectoryProperty

    //@get:Optional
    //@get:InputFile
    //abstract val srgCsv: RegularFileProperty

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

        println("  Importing decompiled clean vanilla")
        var upstream = resolveWorkDir("vanilla")

        patchSets.get().forEach { patchSet ->
            println("  Applying patch set ${patchSet.name}...")

            val patchFolder = patchSet.folder.path
            if (!Files.isDirectory(patchFolder)) {
                println("    No patches found")
                return@forEach
            }
            val patches = patchFolder.filesMatchingRecursive("*.patch")
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

                        patches.parallelStream().forEach { patch ->
                            try {
                                git("apply", "--ignore-whitespace", patch.absolutePathString()).executeOut()
                            } catch (ex: Exception) {
                            }
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

        println("worked thru all patch sets, cloneing into paper server")
        //Git(createDir(outputDir.path)).checkoutRepoFromUpstream(upstream)
    }

    private fun resolveWorkDir(name: String): Path = workDir.get().path.resolve(name)

    private fun createDir(dir: Path): Path {
        dir.deleteRecursively()
        dir.createDirectories()
        return dir
    }
}
