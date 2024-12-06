package io.papermc.paperweight.util

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

interface MacheOutput : Named {
    companion object {
        val ATTRIBUTE = Attribute.of(
            "io.papermc.mache.output",
            MacheOutput::class.java
        )

        const val ZIP = "zip"
        const val SERVER_DEPENDENCIES = "serverDependencies"
    }
}
