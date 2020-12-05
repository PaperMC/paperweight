package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.runJar
import javax.inject.Inject
import net.fabricmc.lorenztiny.TinyMappingFormat
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.cadixdev.lorenz.asm.LorenzRemapper
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

abstract class RemapJar : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    abstract val mappingsFile: RegularFileProperty
    @get:Classpath
    abstract val remapper: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val logFile = layout.cache.resolve(paperTaskOutput("log"))
        logFile.delete()

        val args = arrayOf(
            inputJar.file.absolutePath,
            outputJar.file.absolutePath,
            mappingsFile.file.absolutePath,
            "official",
            "named"
        )
        runJar(remapper, layout.cache, logFile, jvmArgs = listOf("-Xmx512m"), args = *args);
    }
}
