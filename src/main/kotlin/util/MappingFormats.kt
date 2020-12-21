package io.papermc.paperweight.util

import net.fabricmc.lorenztiny.TinyMappingFormat
import org.cadixdev.lorenz.io.proguard.ProGuardFormat
import org.cadixdev.lorenz.io.srg.csrg.CSrgMappingFormat

/*
 * Since we shade our dependencies into a single jar the service locator doens't work,
 * so we just have our own references to the formats
 */
object MappingFormats {

    val TINY = TinyMappingFormat.STANDARD
    val CSRG = CSrgMappingFormat()
    val PROGUARD = ProGuardFormat()
}