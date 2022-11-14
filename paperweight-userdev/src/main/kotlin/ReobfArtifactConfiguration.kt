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

package io.papermc.paperweight.userdev

import io.papermc.paperweight.tasks.*
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.*

/**
 * Configures input/output for `reobfJar` and potentially changes classifiers of other jars.
 */
fun interface ReobfArtifactConfiguration {
    fun configure(project: Project, reobfJar: TaskProvider<RemapJar>)

    companion object {
        /**
         * Used when the reobfJar artifact is the main production output.
         *
         * Sets the `jar` classifier to `dev`, and the `shadowJar` classifier to `dev-all` if it exists.
         * The `reobfJar` will have no classifier. [BasePluginExtension.getArchivesName] is used to name
         * the `reobfJar`, falling back to the project name if it is not configured.
         */
        val REOBF_PRODUCTION: ReobfArtifactConfiguration = ReobfArtifactConfiguration { project, reobfJar ->
            val jar = project.tasks.named<AbstractArchiveTask>(JavaPlugin.JAR_TASK_NAME) {
                archiveClassifier.set("dev")
            }

            val devJarTask = try {
                project.tasks.named<AbstractArchiveTask>("shadowJar") {
                    archiveClassifier.set("dev-all")
                }
            } catch (ex: UnknownTaskException) {
                jar
            }

            reobfJar {
                inputJar.set(devJarTask.flatMap { it.archiveFile })
                outputJar.convention(archivesName(project).flatMap { layout.buildDirectory.file("libs/$it-${project.version}.jar") })
            }
        }

        /**
         * Used when the Mojang-mapped artifact (`jar`/`shadowJar`) is the main production output.
         *
         * Does not modify `jar` or `shadowJar` classifier, [BasePluginExtension.getArchivesName] is used to name
         * the `reobfJar`, falling back to the project name if it is not configured.
         */
        val MOJANG_PRODUCTION: ReobfArtifactConfiguration = ReobfArtifactConfiguration { project, reobfJar ->
            val devJarTask = try {
                project.tasks.named<AbstractArchiveTask>("shadowJar")
            } catch (ex: UnknownTaskException) {
                project.tasks.named<AbstractArchiveTask>(JavaPlugin.JAR_TASK_NAME)
            }
            reobfJar {
                inputJar.set(devJarTask.flatMap { it.archiveFile })
                outputJar.convention(archivesName(project).flatMap { layout.buildDirectory.file("libs/$it-${project.version}-reobf.jar") })
            }
        }

        fun archivesName(project: Project): Provider<String> =
            project.extensions.findByType(BasePluginExtension::class)?.archivesName ?: project.provider { project.name }
    }
}
