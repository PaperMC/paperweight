package io.papermc.paperweight.tasks.patchremap

import io.papermc.paperweight.shared.PaperweightException
import io.papermc.paperweight.shared.RemapConfig
import io.papermc.paperweight.shared.RemapOps
import io.papermc.paperweight.tasks.sourceremap.MercuryExecutor
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import java.io.File

class PatchSourceRemapWorker(
    private val mappings: MappingSet,
    classpath: Collection<File>,
    apiDir: File,
    private val paramMapFile: File,
    private val constructorsFile: File,
    private val inputDir: File,
    private val outputDir: File
) : AutoCloseable {

    private val totalClasspath = listOf(apiDir, *classpath.toTypedArray())
    private val reverseMappings: MappingSet = mappings.reverse()

    private val mappingsFile: File = createTempFile("mappings", "tsrg")
    private val reverseMappingsFile: File = createTempFile("reverse_mappings", "tsrg")

    init {
        mappingsFile.bufferedWriter().use { writer ->
            MappingFormats.TSRG.createWriter(writer).write(mappings)
        }
        reverseMappingsFile.bufferedWriter().use { writer ->
            MappingFormats.TSRG.createWriter(writer).write(reverseMappings)
        }
    }

    fun remap() {
        setup()

        MercuryExecutor.exec(RemapConfig(
            inDir = inputDir,
            outDir = outputDir,
            classpath = totalClasspath,
            mappingsFile = reverseMappingsFile,
            constructorsFile = constructorsFile,
            paramMapFile = paramMapFile,
            operations = listOf(RemapOps.REMAP, RemapOps.REMAP_PARAMS_PATCH)
        ))

        cleanup()
    }

    fun remapBack() {
        setup()

        val remapOutput = MercuryExecutor.exec(RemapConfig(
            inDir = inputDir,
            outDir = outputDir,
            classpath = totalClasspath,
            mappingsFile = mappingsFile,
            constructorsFile = constructorsFile,
            operations = listOf(RemapOps.REMAP, RemapOps.REMAP_PARAMS_SRG)
        ))

        val paramMapFile = remapOutput.paramMapFile ?: throw PaperweightException("No paramMapFile returned from Mercury")
        paramMapFile.delete()

        cleanup()
    }

    private fun setup() {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
    }

    private fun cleanup() {
        inputDir.deleteRecursively()
        outputDir.copyRecursively(inputDir, overwrite = true)
    }

    override fun close() {
        mappingsFile.delete()
        reverseMappingsFile.delete()
    }
}
