package io.papermc.paperweight.checkstyle

import io.papermc.paperweight.core.extension.PaperCheckstyleExt
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.kotlin.dsl.*

abstract class PaperCheckstyle : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        val ext = extensions.create(PAPER_CHECKSTYLE_EXTENSION, PaperCheckstyleExt::class)
        plugins.apply(PaperCheckstylePlugin::class.java)

        extensions.configure(CheckstyleExtension::class.java) {
            toolVersion = "10.21.0"
            configDirectory.set(ext.projectLocalCheckstyleConfig)
        }

        tasks.withType(PaperCheckstyleTask::class.java) {
            rootPath.set(project.rootDir.path)
            directoriesToSkip.set(ext.directoriesToSkip)
            typeUseAnnotations.set(ext.typeUseAnnotations)
        }
        Unit
    }
}

