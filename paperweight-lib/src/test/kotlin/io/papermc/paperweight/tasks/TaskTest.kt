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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

open class TaskTest {

    fun setupProject() = ProjectBuilder.builder()
        .withGradleUserHomeDir(File("build"))
        .withProjectDir(File(""))
        .build()

    fun setupDir(tempDir: Path, testResource: Path, name: String): Path {
        val temp = tempDir.resolve(name).toFile()
        temp.mkdir()
        testResource.resolve(name).copyRecursivelyTo(temp.convertToPath())
        return temp.convertToPath()
    }

    fun setupFile(tempDir: Path, testResource: Path, name: String): Path {
        val temp = tempDir.resolve(name)
        testResource.resolve(name).copyTo(temp)
        return temp
    }

    fun compareDir(tempDir: Path, testResource: Path, name: String) {
        val actualOutput = tempDir.resolve(name)
        val expectedOutput = testResource.resolve(name)

        val expectedFiles = expectedOutput.walk().filter { Files.isRegularFile(it) }.filter { !it.toString().contains(".git") }.toList()
        val actualFiles = actualOutput.walk().filter { Files.isRegularFile(it) }.filter { !it.toString().contains(".git") }.toList()

        assertEquals(expectedFiles.size, actualFiles.size, "Expected $expectedFiles files, got $actualFiles")

        expectedFiles.forEach { expectedFile ->
            val actualFile = actualOutput.resolve(expectedOutput.relativize(expectedFile))

            compareFile(actualFile, expectedFile)
        }
    }

    fun compareZip(tempDir: Path, testResource: Path, name: String) {
        val actualOutput = tempDir.resolve(name)
        val expectedOutput = testResource.resolve(name)

        compareZip(actualOutput, expectedOutput)
    }

    fun compareZip(actualOutput: Path, expectedOutput: Path) {
        actualOutput.openZip().use { actualZip ->
            val actualFiles = actualZip.walkSequence().toList()
            expectedOutput.openZip().use { expectedZip ->
                val expectedFiles = expectedZip.walkSequence().toList()

                assertEquals(expectedFiles.size, actualFiles.size, "Expected $expectedFiles files, got $actualFiles")

                expectedFiles.forEach { expectedFile ->
                    val actualFile = actualZip.getPath(expectedFile.toString())

                    compareFile(actualFile, expectedFile)
                }
            }
        }
    }

    fun compareFile(tempDir: Path, testResource: Path, name: String) {
        val actualOutput = tempDir.resolve(name)
        val expectedOutput = testResource.resolve(name)

        compareFile(actualOutput, expectedOutput)
    }

    private fun compareFile(actual: Path, expected: Path) {
        assertTrue(actual.exists(), "Expected file $actual doesn't exist")
        assertEquals(expected.readText(), actual.readText(), "File $actual doesn't match expected")
    }

    fun setupGitRepo(directory: File, mainBranch: String, tag: String? = null) {
        val git = Git.init().setDirectory(directory).setInitialBranch(mainBranch).call()

        git.add().addFilepattern(".").call()
        git.commit().setSign(false).setMessage("Test").call()
        if (tag != null) {
            git.tag().setName(tag).call()
        }

        git.close()
    }

    fun setupGitHook(repo: File) {
        val hook = repo.toPath().resolve(".git/hooks/post-rewrite")
        hook.parent.createDirectories()
        hook.writeText(javaClass.getResource("/post-rewrite.sh")!!.readText())
        hook.toFile().setExecutable(true)
    }

    fun createZip(tempDir: Path, testResource: Path, zipName: String, vararg fileNames: String,): Path {
        val targetZip = tempDir.resolve(zipName)

        targetZip.writeZip().use { zip ->
            fileNames.forEach { fileName ->
                val sourceFile = testResource.resolve(fileName)
                zip.getPath(fileName).writeText(sourceFile.readText())
            }
        }

        return targetZip
    }

    fun createJar(tempDir: Path, testResource: Path, name: String): Path {
        val sourceFile = tempDir.resolve("$name.java")
        testResource.resolve("$name.java").copyTo(sourceFile)

        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Tests must run on a JDK" }
        assertEquals(0, compiler.run(null, null, null, "-d", tempDir.toString(), sourceFile.toString()))

        val jar = tempDir.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("$name.class"))
            Files.copy(tempDir.resolve("$name.class"), output)
            output.closeEntry()
        }
        return jar
    }
}
