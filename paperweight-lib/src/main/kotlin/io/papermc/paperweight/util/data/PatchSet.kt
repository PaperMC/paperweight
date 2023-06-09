package io.papermc.paperweight.util.data

import org.gradle.api.file.RegularFile

data class PatchSet(
    val name: String,
    val type: PatchSetType,
    val folder: RegularFile? = null,
    val mavenCoordinates: String? = null,
)

enum class PatchSetType {
    FILE_BASED,
    FEATURE
}
