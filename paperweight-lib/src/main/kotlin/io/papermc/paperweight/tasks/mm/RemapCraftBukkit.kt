package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

abstract class RemapCraftBukkit : JavaLauncherTask() {

    @get:InputDirectory
    abstract val inputSources: DirectoryProperty

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
    abstract val bukkitApiDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalAts: RegularFileProperty
    
    @get:OutputDirectory
    abstract val remappedOutputSources: DirectoryProperty

    @get:OutputDirectory
    abstract val unmappedCopy: DirectoryProperty

    @get:OutputFile
    abstract val generatedAt: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()
        group = "mm"
        remappedOutputSources.convention(inputSources)
        unmappedCopy.convention(layout.cacheDir(UNMAPPED_CB_SOURCE_COPY))
        generatedAt.convention(defaultOutput("at"))
    }
    
    @TaskAction
    fun run() {
        unmappedCopy.path.deleteRecursive()
        inputSources.path.copyRecursivelyTo(unmappedCopy.path)
        val outputParentDir = remappedOutputSources.path

        val outputSourcesDir = outputParentDir.resolve("src/main/java")
        val outputTestsDir = outputParentDir.resolve("src/test/java")

        val srcOut = findOutputDir(outputSourcesDir).apply { createDirectories() }
        val testOut = findOutputDir(outputTestsDir).apply { createDirectories() }

        try {
            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs("-Xmx2G")
                forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
            }

            val srcDir = inputSources.path.resolve("src/main/java")

            queue.submit(RemapSources.RemapAction::class) {
                classpath.from(vanillaRemappedSpigotJar.path)
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(bukkitApiDir.dir("src/main/java").path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                additionalAts.set(this@RemapCraftBukkit.additionalAts.pathOrNull)

                mappings.set(this@RemapCraftBukkit.mappings.path)
                inputDir.set(srcDir)

                cacheDir.set(this@RemapCraftBukkit.layout.cache)

                outputDir.set(srcOut)
                generatedAtOutput.set(this@RemapCraftBukkit.generatedAt.path)
            }

            val testSrc = inputSources.path.resolve("src/test/java")

            queue.submit(RemapSources.RemapAction::class) {
                classpath.from(vanillaRemappedSpigotJar.path)
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(bukkitApiDir.dir("src/main/java").path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                classpath.from(srcDir)
                additionalAts.set(this@RemapCraftBukkit.additionalAts.pathOrNull)

                mappings.set(this@RemapCraftBukkit.mappings.path)
                inputDir.set(testSrc)

                cacheDir.set(this@RemapCraftBukkit.layout.cache)

                outputDir.set(testOut)
            }

            queue.await()

            outputSourcesDir.deleteRecursive()
            srcOut.copyRecursivelyTo(outputSourcesDir)

            outputTestsDir.deleteRecursive()
            testOut.copyRecursivelyTo(outputTestsDir)
        } finally {
            srcOut.deleteRecursive()
            testOut.deleteRecursive()
        }
    }

}
