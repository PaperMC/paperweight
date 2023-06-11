package io.papermc.paperweight.util

import java.io.Reader
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.TextMappingsReader
import org.cadixdev.lorenz.io.srg.SrgConstants
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.MethodMapping

class TSrg2Reader(reader: Reader?) : TextMappingsReader(reader, { mappings: MappingSet? -> TSrg2Processor(mappings) })

// mostly copied from tsrg
class TSrg2Processor(mappings: MappingSet? = MappingSet.create()) : TextMappingsReader.Processor(mappings) {
    private var currentClass: ClassMapping<*, *>? = null
    private var currentMethod: MethodMapping? = null
    override fun accept(rawLine: String) {
        val line = SrgConstants.removeComments(rawLine)
        if (line.isEmpty() || line.startsWith("tsrg2")) return
        require(line.length >= 3) { "Faulty TSRG2 mapping encountered: `$line`!" }

        // Split up the line, for further processing
        val split = SPACE.split(line)
        val len = split.size

        // Process class/package mappings
        if (!split[0].startsWith("\t") && len == CLASS_MAPPING_ELEMENT_COUNT) {
            val obfuscatedName = split[0]
            val deobfuscatedName = split[1]

            // Package mappings
            if (obfuscatedName.endsWith("/")) {
                // Lorenz doesn't currently support package mappings, though they are an SRG feature.
                // For now, Lorenz will just silently ignore those mappings.
            } else {
                // Get mapping, and set de-obfuscated name
                currentClass = mappings.getOrCreateClassMapping(obfuscatedName)
                currentClass!!.setDeobfuscatedName(deobfuscatedName)
            }
        } else if (split[0].startsWith("\t\t") && currentMethod != null && len == PARAM_MAPPING_ELEMENT_COUNT) {
            val index = split[0].replace("\t\t", "").toInt()
            val deobfuscatedName = split[2]

            currentMethod!!.createParameterMapping(index, deobfuscatedName)
        } else if (split[0].startsWith("\t") && currentClass != null) {
            val obfuscatedName = split[0].replace("\t", "")

            // Process field mapping
            if (len == FIELD_MAPPING_ELEMENT_COUNT) {
                val deobfuscatedName = split[1]

                // Get mapping, and set de-obfuscated name
                currentClass!!
                    .getOrCreateFieldMapping(obfuscatedName)
                    .setDeobfuscatedName(deobfuscatedName)
            } else if (len == METHOD_MAPPING_ELEMENT_COUNT) {
                val obfuscatedSignature = split[1]
                val deobfuscatedName = split[2]

                // Get mapping, and set de-obfuscated name
                currentMethod = currentClass!!
                    .getOrCreateMethodMapping(obfuscatedName, obfuscatedSignature)
                currentMethod!!.setDeobfuscatedName(deobfuscatedName)
            }
        } else {
            throw IllegalArgumentException("Failed to process line: `$line`!")
        }
    }

    companion object {
        private const val CLASS_MAPPING_ELEMENT_COUNT = 3
        private const val FIELD_MAPPING_ELEMENT_COUNT = 3
        private const val METHOD_MAPPING_ELEMENT_COUNT = 4
        private const val PARAM_MAPPING_ELEMENT_COUNT = 4
    }
}
