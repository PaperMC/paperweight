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

        val srgToMojang = mutableMapOf<String, String>()
        //Files.readAllLines(srgCsv.path).forEach {
        //    val split = it.split(",")
        //    srgToMojang[split[0]] = split[1]
        //}

        Git.checkForGit()

        createDir(workDir.path)

        println("  Importing decompiled vanilla")
        var upstream = resolveWorkDir("vanilla")
        importVanilla(upstream.resolve("src/main/java"))
        // TODO I think we also need to clone Paper-Server into here?

        Git(upstream).let { git ->
            git("init", "--quiet").executeSilently(silenceErr = true)
            git.disableAutoGpgSigningInRepo()

            git(*Git.add(false, ".")).run()
            git("commit", "-m", "Vanilla", "--author=Mojang <auto@mated.null>").run()

            git("tag", "-d", "vanilla").runSilently(silenceErr = true)
            git("tag", "vanilla").executeSilently(silenceErr = true)
        }

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

                        for (patch in patches) {
                            try {
                                println("Applying $patch...")
                                git("apply", "--ignore-whitespace", patch.absolutePathString()).executeOut()
                            } catch (ex: Exception) {
                                println("failed")
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

    private fun remapPatch(patchLines: List<String>, srgToMojang: Map<String, String>): List<String> {
        val srgRegex = Regex("[pfm]_\\d+_")


        return patchLines.map {
            var line = it
            // fix indent and add comment (only need to fix indent for the + line since we ignore whitespace when applying)
            if (line.startsWith("+") && !line.startsWith("++")) {
                line = line.replace(" ".repeat(3), " ".repeat(4)) + " // decomp fix"
            }

            // remap
            line = srgRegex.replace(line) { res ->
                val mapping = srgToMojang[res.groupValues[0]]
                if (mapping != null) {
                    mapping
                } else {
                    println("missing mapping for " + res.groupValues[0])
                    res.groupValues[0]
                }
            }

            return@map line
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
