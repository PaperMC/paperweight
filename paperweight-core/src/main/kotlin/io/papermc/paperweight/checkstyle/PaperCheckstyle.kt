/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.checkstyle

import io.papermc.paperweight.checkstyle.tasks.MergeCheckstyleConfigs
import io.papermc.paperweight.checkstyle.tasks.PaperCheckstyleTask
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Paths
import javax.inject.Inject
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileTreeElement
import org.gradle.api.file.ProjectLayout
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.specs.Spec
import org.gradle.kotlin.dsl.*

private class SkippedCheckstyleFiles(
    private val rootPath: Provider<String>,
    private val directoriesToSkip: Provider<Set<String>>,
) : Spec<FileTreeElement> {
    override fun isSatisfiedBy(element: FileTreeElement): Boolean {
        if (element.isDirectory) return false
        val relativePath = element.file.toPath().toAbsolutePath().relativeTo(Paths.get(rootPath.get()))
        val parentPath = relativePath.parent?.invariantSeparatorsPathString + "/"
        return parentPath in directoriesToSkip.getOrElse(emptySet())
    }
}

abstract class PaperCheckstyle : Plugin<Project> {

    @get:Inject
    abstract val layout: ProjectLayout

    @get:Inject
    abstract val providers: ProviderFactory

    override fun apply(target: Project) {
        val ext = target.extensions.create<PaperCheckstyleExt>(PAPER_CHECKSTYLE_EXTENSION)
        target.plugins.apply(PaperCheckstylePlugin::class.java)

        target.extensions.configure(CheckstyleExtension::class.java) {
            toolVersion = LibraryVersions.CHECKSTYLE
        }

        val mergeCheckstyleConfigs = target.tasks.register<MergeCheckstyleConfigs>("mergeCheckstyleConfigs")

        target.tasks.withType(PaperCheckstyleTask::class.java).configureEach {
            rootPath.convention(layout.settingsDirectory.asFile.path)
            directoriesToSkip.convention(
                providers.fileContents(ext.directoriesToSkipFile).asText.map {
                    it.trim().lines().map { line -> line.trim() }
                }
            )
            exclude(SkippedCheckstyleFiles(rootPath, directoriesToSkip))
            typeUseAnnotations.convention(
                providers.fileContents(ext.typeUseAnnotationsFile).asText.map {
                    it.trim().lines().map { line -> line.trim() }
                }
            )
            customJavadocTags.convention(ext.customJavadocTags)
            configOverride.convention(mergeCheckstyleConfigs.flatMap { it.mergedConfigFile })
            reports.xml.required.convention(true)
            reports.html.required.convention(true)
            maxHeapSize.convention("2g")
            configDirectory.convention(layout.settingsDirectory.dir(".checkstyle"))
        }
    }
}
