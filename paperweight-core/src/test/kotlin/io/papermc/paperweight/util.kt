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

package io.papermc.paperweight

import io.papermc.paperweight.util.*
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*
import org.gradle.testkit.runner.GradleRunner

fun Path.copyProject(resourcesProjectName: String): ProjectFiles {
    Paths.get("src/test/resources/$resourcesProjectName")
        .copyToRecursively(this, followLinks = false)
    Git(this)("init").executeSilently()
    return ProjectFiles(this)
}

class ProjectFiles(val projectDir: Path) {
    val gradleProperties: Path = resolve("gradle.properties")
    val buildGradle: Path = resolve("build.gradle")
    val buildGradleKts: Path = resolve("build.gradle.kts")
    val settingsGradle: Path = resolve("settings.gradle")
    val settingsGradleKts: Path = resolve("settings.gradle.kts")

    fun resolve(path: String): Path = projectDir.resolve(path)

    fun gradleRunner(): GradleRunner = GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withProjectDir(projectDir.toFile())
}
