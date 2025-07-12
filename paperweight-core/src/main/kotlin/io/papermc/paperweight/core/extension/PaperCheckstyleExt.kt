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

package io.papermc.paperweight.core.extension

import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.SetProperty

@Suppress("LeakingThis")
abstract class PaperCheckstyleExt {

    @get:Inject
    abstract val layout: ProjectLayout

    abstract val typeUseAnnotations: SetProperty<String>
    abstract val directoriesToSkip: SetProperty<String>
    abstract val projectLocalCheckstyleConfig: DirectoryProperty

    init {
        projectLocalCheckstyleConfig.convention(layout.projectDirectory.dir(".checkstyle"))
    }
}
