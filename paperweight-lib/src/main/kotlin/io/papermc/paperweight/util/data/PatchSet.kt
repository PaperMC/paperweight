package io.papermc.paperweight.util.data

import java.io.IOException
import java.io.ObjectOutputStream
import java.io.Serializable
import org.gradle.api.file.RegularFile

data class PatchSet(
    val name: String,
    val type: PatchSetType,
    val folder: RegularFile? = null,
    val mavenCoordinates: String? = null,
    val repo: String? = null,
    val pathInArtifact: String? = null,
    val mappings: PatchMappingType = PatchMappingType.MOJANG
): Serializable {

    // TODO this is stupid, make this a proper @Nested input or something
    @Throws(IOException::class)
    private fun writeObject(s: ObjectOutputStream) {
        s.write(hashCode())
    }
}

enum class PatchSetType {
    FILE_BASED,
    FEATURE
}

enum class PatchMappingType {
    MOJANG,
    SRG
}
