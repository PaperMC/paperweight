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

package io.papermc.paperweight.checkstyle.tasks

import io.papermc.paperweight.checkstyle.JavadocTag
import io.papermc.paperweight.util.*
import java.nio.file.Paths
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.resources.TextResourceFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

abstract class PaperCheckstyleTask : Checkstyle() {

    @get:Input
    abstract val rootPath: Property<String>

    @get:Input
    @get:Optional
    abstract val directoriesToSkip: SetProperty<String>

    @get:Input
    abstract val typeUseAnnotations: SetProperty<String>

    @get:Nested
    @get:Optional
    abstract val customJavadocTags: SetProperty<JavadocTag>

    @get:InputFile
    abstract val mergedConfigFile: RegularFileProperty

    @get:Internal
    val textResourceFactory: TextResourceFactory = project.resources.text

    init {
        reports.xml.required.set(true)
        reports.html.required.set(true)
        maxHeapSize.set("2g")
        configDirectory.set(project.rootProject.layout.projectDirectory.dir(".checkstyle"))
    }

    @TaskAction
    override fun run() {
        config = textResourceFactory.fromFile(mergedConfigFile.path.toFile())
        val existingProperties = configProperties?.toMutableMap() ?: mutableMapOf()
        existingProperties["type_use_annotations"] = typeUseAnnotations.get().joinToString("|")
        existingProperties["custom_javadoc_tags"] = customJavadocTags.getOrElse(emptySet()).joinToString("|") { it.toOptionString() }
        configProperties = existingProperties
        exclude {
            if (it.isDirectory) return@exclude false
            val absPath = it.file.toPath().toAbsolutePath().relativeTo(Paths.get(rootPath.get()))
            val parentPath = (absPath.parent?.invariantSeparatorsPathString + "/")
            if (directoriesToSkip.isPresent) {
                return@exclude directoriesToSkip.get().any { pkg -> parentPath == pkg }
            }
            return@exclude false
        }
        if (!source.isEmpty) {
            super.run()
        }
    }
}
