package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.DEOBF_NAMESPACE
import io.papermc.paperweight.util.constants.OBF_NAMESPACE
import io.papermc.paperweight.util.constants.applyPatchesLock
import io.papermc.paperweight.util.data.PatchMappingType
import io.papermc.paperweight.util.data.PatchSet
import io.papermc.paperweight.util.data.PatchSetType
import org.cadixdev.lorenz.merge.MappingSetMerger
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.stream.Collectors
import kotlin.io.path.*

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

        createMappings()

        Git.checkForGit()

        createDir(workDir.path)

        println("  Importing decompiled vanilla")
        var upstream = resolveWorkDir("vanilla")
        importVanilla(upstream)
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

            val patches = mutableListOf<Path>()
            if (patchSet.mavenCoordinates != null) {
                patchesDir.path.resolve("${patchSet.name}.jar").openZip().use { patchZip ->
                    // find patches
                    val matcher = patchZip.getPathMatcher("glob:*.patch")
                    val pathInArtifact = patchZip.getPath(patchSet.pathInArtifact ?: "patches")
                    val patchesInZip = Files.walk(pathInArtifact).use { stream ->
                        stream.filter {
                            it.isRegularFile() && matcher.matches(it.fileName)
                        }.collect(Collectors.toList())
                    }

                    // copy them out
                    val dir = patchesDir.path.resolve(patchSet.name)
                    createDir(dir)
                    patchesInZip.forEach { path ->
                        val source = pathInArtifact.relativize(path)
                        val target = dir.resolve(source.toString())
                        target.parent?.createDirectories()

                        var patchLines = Files.readAllLines(path)
                        if (patchSet.mappings == PatchMappingType.SRG) {
                            patchLines = remapPatch(patchLines)
                        }
                        Files.write(target, patchLines, StandardOpenOption.CREATE_NEW)
                        patches.add(target)
                    }
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
                            println("Applying $patch...")
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

    private fun remapPatch(patchLines: List<String>): List<String> {
        val srgRegex = Regex("[pfm]_(\\d+)_")
        // TODO obviously horrible
        val srgToMojang = mapOf(
            "193328" to "unpack",
            "193329" to "time",
            "193330" to "subTickOrder",
            "193311" to "type",
            "193312" to "pos",
            "193313" to "delay",
            "193314" to "priority",
            "193335" to "probe",
            "193336" to "type",
            "193337" to "pos"
        )

        return patchLines.map {
            var line = it
            // fix indent and add comment (only need to fix indent for the + line since we ignore whitespace when applying)
            if (line.startsWith("+") && !line.startsWith("++")) {
                line = line.replace(" ".repeat(3), " ".repeat(4)) + " // decomp fix"
            }

            // remap
            line = srgRegex.replace(line) { res -> srgToMojang[res.groupValues[1]]
                ?: throw RuntimeException("missing mapping for " + res.groupValues[1])
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

    // TODO clean, own task, etc
    private fun createMappings() {
        val obfToOurs =  MappingFormats.TINY.read(Path.of("D:\\IdeaProjects\\Paper\\.gradle\\caches\\paperweight\\mappings\\official-mojang+yarn.tiny"), OBF_NAMESPACE, DEOBF_NAMESPACE)
        val obfToSrg = MappingFormats.TSRG.read(Path.of("D:\\IdeaProjects\\Paper\\.gradle\\caches\\paperweight\\mappings\\joined.tsrg"))

        val srgToOurs = MappingSetMerger.create(
            obfToSrg.reverse(),
            obfToOurs
        ).merge()

        MappingFormats.TINY.write(srgToOurs, Path.of("D:\\IdeaProjects\\Paper\\.gradle\\caches\\paperweight\\mappings\\srg_official-mojang+yarn.tiny"), "srg", DEOBF_NAMESPACE)
    }

    private fun createDir(dir: Path): Path {
        dir.deleteRecursively()
        dir.createDirectories()
        return dir
    }
}
