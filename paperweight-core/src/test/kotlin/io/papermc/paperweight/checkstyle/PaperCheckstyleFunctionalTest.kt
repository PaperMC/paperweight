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

import io.papermc.paperweight.ProjectFiles
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class PaperCheckstyleFunctionalTest {
    @Test
    fun exclusionsAreTrackedForCaching(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("first").createDirectories()
        val project = setupProject(projectDir)
        val runner = project.gradleRunner()
            .withArguments("checkstyleMain", "--build-cache", "--configuration-cache", "--stacktrace")

        assertEquals(TaskOutcome.SUCCESS, runner.build().task(":checkstyleMain")?.outcome)
        val upToDate = runner.build()
        assertEquals(TaskOutcome.UP_TO_DATE, upToDate.task(":checkstyleMain")?.outcome)
        assertContains(upToDate.output, "Configuration cache entry reused.")

        projectDir.resolve("build/reports/checkstyle/main.xml").deleteExisting()
        assertEquals(TaskOutcome.FROM_CACHE, runner.build().task(":checkstyleMain")?.outcome)

        val changedSource = "class Bad {\n\t\tint field;\n}\n"
        projectDir.resolve("src/main/java/skip/Bad.java").writeText(changedSource)
        assertEquals(TaskOutcome.UP_TO_DATE, runner.build().task(":checkstyleMain")?.outcome)

        val relocated = setupProject(tempDir.resolve("second").createDirectories())
        relocated.resolve("src/main/java/skip/Bad.java").writeText(changedSource)
        val relocatedRunner = relocated.gradleRunner()
            .withArguments("checkstyleMain", "--build-cache", "--configuration-cache", "--stacktrace")
        assertEquals(TaskOutcome.FROM_CACHE, relocatedRunner.build().task(":checkstyleMain")?.outcome)

        project.buildGradle.appendText(
            "\ntasks.named('checkstyleMain') { rootPath.set(layout.projectDirectory.dir('src/main/java').asFile.absolutePath) }\n"
        )
        projectDir.resolve(".checkstyle/skipped.txt").writeText("skip/\n")
        assertEquals(TaskOutcome.SUCCESS, runner.build().task(":checkstyleMain")?.outcome)

        projectDir.resolve(".checkstyle/skipped.txt").writeText("")
        assertEquals(TaskOutcome.FAILED, runner.buildAndFail().task(":checkstyleMain")?.outcome)
        assertContains(projectDir.resolve("build/reports/checkstyle/main.xml").readText(), "FileTabCharacter")
    }

    private fun setupProject(projectDir: Path): ProjectFiles {
        val project = ProjectFiles(projectDir)
        project.settingsGradle.writeText(
            """
            rootProject.name = 'checkstyle-test'
            buildCache {
                local { directory = file('../shared-build-cache') }
            }
            """.trimIndent()
        )
        project.buildGradle.writeText(buildScript())
        projectDir.resolve(".checkstyle/checkstyle_base.xml").createParentDirectories().writeText(
            """<module name="Checker"><module name="FileTabCharacter"/></module>"""
        )
        projectDir.resolve(".checkstyle/checkstyle.xml").writeText("""<module name="Checker"/>""")
        projectDir.resolve(".checkstyle/skipped.txt").writeText("src/main/java/skip/\n")
        projectDir.resolve("src/main/java/skip/Bad.java").createParentDirectories().writeText(
            "class Bad {\n\tint field;\n}\n"
        )
        projectDir.resolve("src/main/java/Good.java").createParentDirectories().writeText("class Good {}\n")
        return project
    }

    private fun buildScript() = """
        plugins {
            id 'java'
            id 'io.papermc.paperweight.paper-checkstyle'
        }

        repositories {
            mavenCentral()
        }

        paperCheckstyle {
            directoriesToSkipFile = layout.settingsDirectory.file('.checkstyle/skipped.txt')
        }

        tasks.named('checkstyleMain') {
            typeUseAnnotations.set([])
            reports.html.required = false
        }
    """.trimIndent()
}
