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

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.gradle.api.Project
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.getPlugin
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class PaperCheckstyleTest {
    fun setupProject(dir: Path): Project {
        return ProjectBuilder.builder()
            .withProjectDir(dir.toFile())
            .build()
    }

    @Test
    fun testPluginApplication(@TempDir tmpDir: Path) {
        val project = setupProject(tmpDir)
        project.pluginManager.apply("io.papermc.paperweight.paper-checkstyle")

        assertNotNull(project.plugins.getPlugin(PaperCheckstylePlugin::class))
        assertNotNull(project.extensions.getByType(CheckstyleExtension::class))
        assertNotNull(project.extensions.getByType(PaperCheckstyleExt::class))
        assertNotNull(project.tasks.getByName("mergeCheckstyleConfigs"))
    }
}
