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

package io.papermc.paperweight.core.tasks.patching

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.kotlin.dsl.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

class ProcessNewSourceATsTest : TaskTest() {
    private lateinit var project: Project
    private lateinit var task: ProcessNewSourceATs

    @BeforeEach
    fun setup() {
        project = setupProject()
        project.plugins.apply("java")
        project.repositories {
            maven(PAPER_MAVEN_REPO_URL)
        }
        project.configurations.register(JST_CONFIG) {
            defaultDependencies {
                add(project.dependencies.create("io.papermc.jst:jst-cli-bundle:${LibraryVersions.JST}"))
            }
        }
        task = project.tasks.register("processNewSourceATs", ProcessNewSourceATs::class).get()
    }

    @Test
    fun `should process source access transformers`(@TempDir(cleanup = CleanupMode.ON_SUCCESS) tempDir: Path) {
        val testResource = Path.of("src/test/resources/process_new_source_ats")
        val testInput = testResource.resolve("input")

        val source = setupDir(tempDir, testInput, "source").toFile()
        setupGitRepo(source, "main", MACHE_TAG_ATS)
        setupGitRepo(tempDir.toFile(), "main", "dum")
        setupGitHook(source)
        val base = setupDir(tempDir, testInput, "base").toFile()
        val atFile = setupFile(tempDir, testInput, "ats.at")

        task.base.set(base)
        task.input.set(source)
        task.atFile.set(atFile)

        task.ats.jst.setFrom(project.configurations[JST_CONFIG])

        task.run()

        val testOutput = testResource.resolve("output")
        compareDir(tempDir, testOutput, "base")
        compareDir(tempDir, testOutput, "source")
        compareFile(tempDir, testOutput, "ats.at")
    }
}
