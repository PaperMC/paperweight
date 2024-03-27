package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

abstract class RemapSpigot : JavaLauncherTask() {

    @get:InputDirectory
    abstract val remappedCraftBukkitSource: DirectoryProperty

    @get:InputDirectory
    abstract val unmappedCraftBukkitSource: DirectoryProperty

    @get:InputDirectory
    abstract val spigotPerCommitPatches: DirectoryProperty

    @get:InputDirectory
    abstract val unmappedCbCopy: DirectoryProperty

    @get:InputDirectory
    abstract val spigotDecompiledSource: DirectoryProperty

    @get:Input
    abstract val directoriesToPatch: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    @get:CompileClasspath
    abstract val vanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val mojangMappedVanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val vanillaRemappedSpigotJar: RegularFileProperty

    @get:CompileClasspath
    abstract val spigotDeps: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spigotApiDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalAts: RegularFileProperty

    @get:Optional
    @get:Input
    abstract val unneededFiles: ListProperty<String>

    @get:OutputFile
    abstract val generatedAt: RegularFileProperty

    @get:OutputDirectory
    abstract val sourcePatchDir: DirectoryProperty

    @get:OutputDirectory
    abstract val remappedSpigotSource: DirectoryProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()
        group = "mm"
        generatedAt.convention(defaultOutput("at"))
        remappedSpigotSource.convention(remappedCraftBukkitSource)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val unmappedCbSourceWork = layout.cacheDir(UNMAPPED_CB_SOURCE_WORK).path
        unmappedCbSourceWork.deleteRecursive()
        val remappedCbSpigotSourceWork = layout.cacheDir(REMAPPED_CB_SPIGOT_SOURCE_WORK).path
        remappedCbSpigotSourceWork.deleteRecursive()
        Git(unmappedCraftBukkitSource).let { git ->
            git("clone", ".", unmappedCbSourceWork.absolutePathString()).execute()
        }
        unmappedCbCopy.path.copyRecursivelyTo(unmappedCbSourceWork, true)

        val mappedGit = Git(remappedCraftBukkitSource)
        val unmappedGit = Git(unmappedCbSourceWork)
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs("-Xmx2G")
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

//        var count = 0
        spigotPerCommitPatches.asFileTree.files.sorted().forEach { patch ->
//            count++
//            if (count > 3) return@forEach
            val patchName = patch.name.split("-", limit = 2)[1]
            println("Applying Spigot Patch: $patchName")
            mappedGit("am", "--exclude=src/*/java/**", patch.toPath().absolutePathString()).execute() // apply empty commit to keep commit info
            unmappedGit("apply", "--ignore-whitespace", "--include=src/*/java/**", patch.toPath().absolutePathString()).execute() // apply full commit to unmapped work cb dir

            val outputSourcesDir = remappedCbSpigotSourceWork.resolve("src/main/java")
            val outputTestsDir = remappedCbSpigotSourceWork.resolve("src/test/java")

            val srcOut = findOutputDir(outputSourcesDir).apply { createDirectories() }
            val testOut = findOutputDir(outputTestsDir).apply { createDirectories() }
            try {
                val srcDir = unmappedCbSourceWork.resolve("src/main/java")

                queue.submit(RemapSources.RemapAction::class) {
                    classpath.from(vanillaRemappedSpigotJar.path)
                    classpath.from(mojangMappedVanillaJar.path)
                    classpath.from(vanillaJar.path)
                    classpath.from(spigotApiDir.dir("src/main/java").path)
                    classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                    additionalAts.set(this@RemapSpigot.additionalAts.pathOrNull)

                    mappings.set(this@RemapSpigot.mappings.path)
                    inputDir.set(srcDir)

                    cacheDir.set(this@RemapSpigot.layout.cache)

                    outputDir.set(srcOut)
                    generatedAtOutput.set(this@RemapSpigot.generatedAt.path)
                }

                val testSrc = unmappedCbSourceWork.resolve("src/test/java")

                queue.submit(RemapSources.RemapAction::class) {
                    classpath.from(vanillaRemappedSpigotJar.path)
                    classpath.from(mojangMappedVanillaJar.path)
                    classpath.from(vanillaJar.path)
                    classpath.from(spigotApiDir.dir("src/main/java").path)
                    classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                    classpath.from(srcDir)
                    additionalAts.set(this@RemapSpigot.additionalAts.pathOrNull)

                    mappings.set(this@RemapSpigot.mappings.path)
                    inputDir.set(testSrc)

                    cacheDir.set(this@RemapSpigot.layout.cache)

                    outputDir.set(testOut)
                }

                queue.await()

                srcOut.copyRecursivelyTo(outputSourcesDir, true)
                srcOut.deleteRecursive()
                testOut.copyRecursivelyTo(outputTestsDir, true)
                testOut.deleteRecursive()
            } finally {
                srcOut.deleteRecursive()
                testOut.deleteRecursive()
            }

            rebuildSource("main", remappedCbSpigotSourceWork, mappedGit)
            rebuildSource("test", remappedCbSpigotSourceWork, mappedGit)
            mappedGit("add", sourcePatchDir.path.absolutePathString(), "src/test", "src/main/java/org").execute()
            mappedGit("commit", "--amend", "--no-edit").execute()
        }

        if (unneededFiles.isPresent && unneededFiles.get().size > 0) {
            unneededFiles.get().forEach { path ->
                remappedSpigotSource.path.resolve(path).deleteRecursive()
                mappedGit(*Git.add(false, path)).executeSilently()
            }
            mappedGit("commit", "-m", "Removed unneeded files", "--author=Initial Source <auto@mated.null>").executeSilently()
        }
    }

    private fun rebuildSource(type: String, outputDir: Path, git: Git) {
        val commandText = listOf("diff", "-ruN", remappedCraftBukkitSource.path.resolve("src/$type/java").absolutePathString(), outputDir.resolve("src/$type/java").absolutePathString())
        val cmd = Command(ProcessBuilder(commandText), commandText.joinToString(" "), arrayOf(0, 1))
        val output = cmd.getText()
            .replace(Regex("^--- ${remappedCraftBukkitSource.path.absolutePathString()}/", RegexOption.MULTILINE), "--- a/")
            .replace(Regex("^\\+\\+\\+ ${outputDir.absolutePathString()}/", RegexOption.MULTILINE), "+++ b/")
        val tempPatch = Files.createTempFile("spigot", "patch")
        tempPatch.bufferedWriter().use {
            it.write(output)
        }
        git("apply", "--allow-empty", tempPatch.absolutePathString()).execute()
        RebuildPerFilePatches.rebuildPerFilePatches(
            remappedCraftBukkitSource.asFileTree
                .matching { directoriesToPatch.get().forEach { include("$it/**/*.java") } },
            spigotDecompiledSource,
            sourcePatchDir.path
        )
        tempPatch.deleteRecursive()
    }
}
