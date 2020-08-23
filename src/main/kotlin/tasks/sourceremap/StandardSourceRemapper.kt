package io.papermc.paperweight.tasks.sourceremap

import io.papermc.paperweight.shared.ConstructorsData
import io.papermc.paperweight.shared.PaperweightException
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.extra.AccessAnalyzerProcessor
import org.cadixdev.mercury.extra.BridgeMethodRewriter
import org.cadixdev.mercury.remapper.MercuryRemapper
import java.nio.file.Path

class SourceRemapper {

    private val mercury: Mercury = Mercury()

    private var inDir: Path? = null
    private var outDir: Path? = null

    private var classPath: Collection<Path>? = null
    private var mappings: MappingSet? = null
    private var at: AccessTransformSet? = null
    private var constructorsData: ConstructorsData? = null
    private var paramMap: MutableMap<String, Array<String?>>? = null

    fun setup(
        inDir: Path?,
        outDir: Path?,
        classpath: Collection<Path>?,
        mappings: MappingSet?,
        at: AccessTransformSet?,
        constructorsData: ConstructorsData?,
        paramMap: MutableMap<String, Array<String?>>?
    ) {
        this.inDir = inDir
        this.outDir = outDir
        this.classPath = classpath
        this.mappings = mappings
        this.at = at
        this.constructorsData = constructorsData
        this.paramMap = paramMap
    }

    fun process(processAt: Boolean) {
        val inDir = this.inDir ?: throw PaperweightException("inDir must be set before calling process()")

        this.classPath?.let { path -> mercury.classPath.addAll(path) }

        mercury.processors.clear()
        if (processAt) {
            val at = this.at ?: throw PaperweightException(
                "Requested processAt operation requires `at` to be set"
            )
            val mappings = this.mappings ?: throw PaperweightException(
                "Requested processAt operation requires `mappings` to be set"
            )
            mercury.processors.add(AccessAnalyzerProcessor.create(at, mappings))
        }

        mercury.process(inDir)
    }

    fun remap(
        remap: Boolean,
        rewriteBridgeMethods: Boolean,
        applyAt: Boolean,
        remapParamsSrg: Boolean,
        remapParamsPatch: Boolean
    ) {
        val inDir = this.inDir ?: throw PaperweightException("`inDir` must be set before calling remap()")
        val outDir = this.outDir ?: throw PaperweightException("`outDir` must be set before calling remap()")

        this.classPath?.let { path -> mercury.classPath.addAll(path) }

        mercury.processors.clear()

        if (remap || remapParamsSrg) {
            if (this.mappings == null) {
                throw PaperweightException(
                    "Requested remap or remapParamsSrg operation requires `mappings` to be set"
                )
            }
        }

        if (applyAt) {
            if (this.at == null) {
                throw PaperweightException(
                    "Requested applyAt operation requires `at` to be set"
                )
            }
        }

        if (remapParamsSrg || remapParamsPatch) {
            if (this.constructorsData == null) {
                throw PaperweightException(
                    "Requested remapParamsSrg or remapParamsPatch operation requires `constructorsData` to be set"
                )
            }
            if (this.paramMap == null) {
                throw PaperweightException(
                    "Requested remapParamsSrg or remapParamsPatch operation requires `paramMap` to be set"
                )
            }
        }

        if (remap) {
            mercury.processors.add(MercuryRemapper.create(this.mappings))
        }
        if (rewriteBridgeMethods) {
            mercury.processors.add(BridgeMethodRewriter.create())
        }
        if (applyAt) {
            mercury.processors.add(AccessTransformerRewriter.create(this.at))
        }
        if (remapParamsSrg) {
            mercury.processors.add(SrgParameterRemapper(this.mappings!!, this.constructorsData!!, this.paramMap!!))
        }
        if (remapParamsPatch) {
            mercury.processors.add(PatchParameterRemapper(this.paramMap!!, this.constructorsData!!))
        }

        mercury.rewrite(inDir, outDir)
    }
}
