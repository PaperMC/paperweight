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

import java.nio.file.Paths
import kotlin.io.path.relativeTo
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class PaperCheckstyleTask : Checkstyle() {

    @get:Input
    abstract val rootPath: Property<String>

    @get:Input
    abstract val directoriesToSkip: SetProperty<String>

    @get:Input
    abstract val typeUseAnnotations: SetProperty<String>

    @TaskAction
    override fun run() {
        val existingProperties = configProperties?.toMutableMap() ?: mutableMapOf()
        existingProperties["type_use_annotations"] = typeUseAnnotations.get().joinToString("|")
        configProperties = existingProperties
        exclude {
            if (it.isDirectory) return@exclude false
            val absPath = it.file.toPath().toAbsolutePath().relativeTo(Paths.get(rootPath.get()))
            val parentPath = (absPath.parent?.toString() + "/")
            directoriesToSkip.get().any { pkg -> parentPath == pkg }
        }
        if (!source.isEmpty) {
            super.run()
        }
    }
}
