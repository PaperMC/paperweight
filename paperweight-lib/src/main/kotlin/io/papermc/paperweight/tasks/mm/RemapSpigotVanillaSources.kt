package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

abstract class RemapSpigotVanillaSources : JavaLauncherTask() {

    @get:InputDirectory
    abstract val decompiledSource: DirectoryProperty

    @get:InputFile
    abstract val decompiledSourceJar: RegularFileProperty

    @get:InputDirectory
    abstract val perFilePatches: DirectoryProperty

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

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalAts: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    abstract val spigotPatchDir: DirectoryProperty

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:OutputFile
    abstract val generatedAt: RegularFileProperty

    @get:OutputDirectory
    abstract val remappedSources: DirectoryProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()
        group = "mm"
        generatedAt.convention(defaultOutput("at"))
        remappedSources.convention(layout.cacheDir(SPIGOT_REMAPPED_VANILLA_SRC))
    }

    @TaskAction
    fun run() {

        val sourcesDir = remappedSources.path
        sourcesDir.deleteRecursive()

        val patchesToApplyDir = perFilePatches.path
        val patchList = Files.walk(patchesToApplyDir).use { it.asSequence().filter { file -> file.isRegularFile() }.toSet() }
        if (patchList.isEmpty()) {
            throw PaperweightException("No patch files found in $patchesToApplyDir")
        }
        // Copy in patch targets
        for (file in patchList) {
            val javaName = javaFileName(patchesToApplyDir, file)
            val out = sourcesDir.resolve(javaName)
            val sourcePath = decompiledSource.path.resolve(javaName)

            out.parent.createDirectories()
            sourcePath.copyTo(out)
        }

        val patches = spigotPatchDir.map { it.path.listDirectoryEntries("*.patch") }.orElse(emptyList()).get()
        McDev.importMcDev(
            patches = patches,
            decompJar = decompiledSourceJar.path,
            importsFile = null,
            targetDir = sourcesDir,
            librariesDirs = listOf(mcLibrariesDir.path),
        )

        val srcOut = findOutputDir(remappedSources.path).apply { createDirectories() }

        try {
            val queue = workerExecutor.noIsolation()/* {
                forkOptions.jvmArgs("-Xmx2G")
                forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
            }*/

            queue.submit(RemapSources.RemapAction::class) {
                classpath.from(vanillaRemappedSpigotJar.path)
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                additionalAts.set(this@RemapSpigotVanillaSources.additionalAts.pathOrNull)

                mappings.set(this@RemapSpigotVanillaSources.mappings.path)
                inputDir.set(sourcesDir)

                cacheDir.set(this@RemapSpigotVanillaSources.layout.cache)

                outputDir.set(srcOut)
                generatedAtOutput.set(this@RemapSpigotVanillaSources.generatedAt.path)
            }

            queue.await()
            sourcesDir.deleteRecursive()
            srcOut.copyRecursivelyTo(sourcesDir)
        } finally {
            srcOut.deleteRecursive()
        }
    }

    private fun javaFileName(rootDir: Path, file: Path): String {
        return file.relativeTo(rootDir).toString().replaceAfterLast('.', "java")
    }
}
