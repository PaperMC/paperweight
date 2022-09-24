/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

package io.papermc.paperweight.extension

import io.papermc.paperweight.tasks.*
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.*

@Suppress("unused")
class DevBundleExtension(
    private val rootProject: Project,
    objects: ObjectFactory
) {
    val libraryRepositories: ListProperty<String> = objects.listProperty()

    /**
     * Registers a project dependency to have its publication included in the dev bundle, and it's coordinates
     * depended on by the server artifact. Paper registers `paper-api` and `paper-mojangapi` using this method.
     */
    fun registerProjectPublication(project: Project, publicationName: String, coordinates: String) {
        rootProject.registerProjectPublicationForDevBundle(project, publicationName, coordinates)
    }

    private fun Project.registerProjectPublicationForDevBundle(
        project: Project,
        publicationName: String,
        coordinates: String,
    ) {
        val archive = project.archivePublication(publicationName)
        tasks.named<GenerateDevBundle>("generateDevelopmentBundle") {
            projectArchivedPublication(project, archive, coordinates)
        }
    }
}
