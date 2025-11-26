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

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.kotlin.dsl.*

abstract class PaperCheckstyle : Plugin<Project> {

    override fun apply(target: Project) {
        val ext = target.extensions.create(PAPER_CHECKSTYLE_EXTENSION, PaperCheckstyleExt::class)
        target.plugins.apply(PaperCheckstylePlugin::class.java)

        target.extensions.configure(CheckstyleExtension::class.java) {
            toolVersion = LibraryVersions.CHECKSTYLE
        }

        target.tasks.withType(PaperCheckstyleTask::class.java).configureEach {
            rootPath.set(project.rootDir.path)
            directoriesToSkip.set(ext.directoriesToSkip)
            typeUseAnnotations.set(ext.typeUseAnnotations)
        }
    }
}
