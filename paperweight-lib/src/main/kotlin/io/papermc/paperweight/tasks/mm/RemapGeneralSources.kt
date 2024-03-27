package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.RemapSources
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

abstract class RemapGeneralSources : JavaLauncherTask() {

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

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalAts: RegularFileProperty

    @get:OutputDirectory
    abstract val remappedOutputSources: DirectoryProperty

    @get:OutputDirectory
    @get:Optional
    abstract val unmappedCopy: DirectoryProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()
        group = "mm"
    }

    @TaskAction
    fun run() {
        if (unmappedCopy.isPresent) {
            unmappedCopy.path.deleteRecursive()
            inputSources.path.copyRecursivelyTo(unmappedCopy.path)
        }
        val sourcesDir = inputSources.path
        val srcOut = findOutputDir(remappedOutputSources.path).apply { createDirectories() }

        try {
            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs("-Xmx2G")
                forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
            }

            queue.submit(RemapSources.RemapAction::class) {
                classpath.from(vanillaRemappedSpigotJar.path)
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                additionalAts.set(this@RemapGeneralSources.additionalAts.pathOrNull)

                mappings.set(this@RemapGeneralSources.mappings.path)
                inputDir.set(sourcesDir)

                cacheDir.set(this@RemapGeneralSources.layout.cache)

                outputDir.set(srcOut)
            }

            queue.await()
            sourcesDir.deleteRecursive()
            remappedOutputSources.path.deleteRecursive()
            srcOut.copyRecursivelyTo(remappedOutputSources.path)
        } finally {
            srcOut.deleteRecursive()
        }
    }

}
