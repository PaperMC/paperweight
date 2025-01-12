package io.papermc.paperweight.checkstyle

import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstylePlugin

abstract class PaperCheckstylePlugin : CheckstylePlugin() {

    override fun getTaskType(): Class<Checkstyle> {
        @Suppress("UNCHECKED_CAST")
        return PaperCheckstyleTask::class.java as Class<Checkstyle>
    }
}
