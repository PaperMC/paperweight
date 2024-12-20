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
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

class FunctionalTest {

    val debug = true

    @Disabled
    @Test
    fun setupCleanTestRepo() {
        val projectDir = Path.of("F:\\Projects\\paperweight\\test").cleanFile().createDirectories()

        setupMache("fake_mache", projectDir.resolve("mache.zip"))
        setupMojang("fake_mojang", projectDir.resolve("fake_mojang"))
        projectDir.copyProject("functional_test")

        val settings = projectDir.resolve("settings.gradle")
        val text = settings.readText()
        settings.writeText(text.replace("// includeBuild '..'", "includeBuild '..'").replace("functional_test", "test"))

        projectDir.resolve("patches").deleteRecursively()
    }

    @Test
    fun `test simple test project`(@TempDir(cleanup = CleanupMode.ON_SUCCESS) tempDir: Path) {
        println("running in $tempDir")
        val testResource = Paths.get("src/test/resources/functional_test")

        setupMache("fake_mache", tempDir.resolve("mache.zip"))
        setupMojang("fake_mojang", tempDir.resolve("fake_mojang"))

        val gradleRunner = tempDir.copyProject("functional_test").gradleRunner()

        // appP -> works
        println("\nrunning applyPatches dependencies\n")
        val appP = gradleRunner
            .withArguments("applyPatches", "dependencies", ":test-server:dependencies", "--stacktrace", "-Dfake=true")
            .withDebug(debug)
            .build()

        assertEquals(appP.task(":test-server:applyPatches")?.outcome, TaskOutcome.SUCCESS)

        // clean rebuild rebP -> changes nothing
        println("\nrunning rebuildPatches\n")
        val rebP = gradleRunner
            .withArguments("rebuildPatches", "--stacktrace", "-Dfake=true")
            .withDebug(debug)
            .build()

        assertEquals(rebP.task(":test-server:rebuildPatches")?.outcome, TaskOutcome.SUCCESS)
        assertEquals(
            testResource.resolve("fake-patches/sources/Test.java.patch").readText(),
            tempDir.resolve("fake-patches/sources/Test.java.patch").readText()
        )

        // add AT to source -> patch and AT file is updated
        println("adding at to source")
        modifyFile(tempDir.resolve("test-server/src/vanilla/java/Test.java")) {
            it.replace(
                "\"2\";",
                "\"2\"; // Woo"
            ).replace("public final String getTest2() {", "public String getTest2() {// Paper-AT: public-f getTest2()Ljava/lang/String;")
        }

        Git(tempDir.resolve("test-server/src/vanilla/java")).let { git ->
            git("add", ".").executeSilently()
            git("commit", "--fixup", "file").executeSilently()
            git("rebase", "--autosquash", "upstream/main").executeSilently()
        }

        println("\nrunning rebuildPatches again\n")
        val rebP2 = gradleRunner
            .withArguments("rebuildPatches", "--stacktrace", "-Dfake=true")
            .withDebug(debug)
            .build()

        assertEquals(rebP2.task(":test-server:rebuildPatches")?.outcome, TaskOutcome.SUCCESS)
        assertEquals(
            testResource.resolve("fake-patches/expected/Test.java.patch").readText(),
            tempDir.resolve("fake-patches/sources/Test.java.patch").readText()
        )
        assertEquals(testResource.resolve("build-data/expected.at").readText(), tempDir.resolve("build-data/fake.at").readText())

        // feature patch
        println("\nmodifying feature patch\n")
        modifyFile(tempDir.resolve("test-server/src/vanilla/java/Test.java")) {
            it.replace("wonderful feature", "amazing feature")
        }

        Git(tempDir.resolve("test-server/src/vanilla/java")).let { git ->
            git("add", ".").executeSilently()
            git("commit", "--amend", "--no-edit").executeSilently()
        }

        println("\nrebuilding feature patch\n")
        val rebP3 = gradleRunner
            .withArguments("rebuildPatches", "--stacktrace", "-Dfake=true")
            .withDebug(debug)
            .build()
        assertEquals(rebP3.task(":test-server:rebuildPatches")?.outcome, TaskOutcome.SUCCESS)
        assertEquals(
            testResource.resolve("fake-patches/expected/0001-Feature.patch").readText(),
            tempDir.resolve("fake-patches/features/0001-Feature.patch").readText()
        )

        // lib patches
        println("\n lib patches\n")
        testResource.resolve("lib-patches/Main.java").copyTo(tempDir.resolve("test-server/src/main/java/Main.java").createParentDirectories())
        testResource.resolve("lib-patches/dev-imports.txt").copyTo(tempDir.resolve("build-data/dev-imports.txt"))
        testResource.resolve("lib-patches/0002-Remove-rotten-apples.patch")
            .copyTo(tempDir.resolve("fake-patches/features/0002-Remove-rotten-apples.patch"))
        testResource.resolve("lib-patches/org").copyToRecursively(tempDir.resolve("fake-patches/sources/org"), followLinks = false)
        modifyFile(tempDir.resolve("test-server/build.gradle")) {
            it.replace(
                "implementation project(\":test-api\")",
                """
                implementation project(":test-api")
                implementation "org.alcibiade:asciiart-core:1.1.0"
                """.trimIndent()
            )
        }

        val appP2 = gradleRunner
            .withArguments("applyPatches", "--stacktrace", "-Dfake=true")
            .withDebug(debug)
            .build()

        assertEquals(appP2.task(":test-server:applyPatches")?.outcome, TaskOutcome.SUCCESS)
        assertContains(tempDir.resolve("test-server/src/vanilla/java/oshi/PlatformEnum.java").readText(), "Windows CE")
        assertFalse(tempDir.resolve("test-server/src/vanilla/java/oshi/SystemInfo.java").readText().contains("MACOS"))
        assertContains(tempDir.resolve("test-server/src/vanilla/java/org/alcibiade/asciiart/widget/PictureWidget.java").readText(), "Trollface")
    }

    @Test
    fun `test full vanilla project`(@TempDir(cleanup = CleanupMode.ON_SUCCESS) tempDir: Path) {
        println("running in $tempDir")
        val gradleRunner = tempDir.copyProject("functional_test").gradleRunner()

        val result = gradleRunner
            .withArguments("applyPatches", ":test-server:dependencies", "--stacktrace", "-Dfake=false")
            .withDebug(debug)
            .build()

        assertEquals(result.task(":test-server:applyPatches")?.outcome, TaskOutcome.SUCCESS)
    }

    fun setupMache(macheName: String, target: Path) {
        val macheDir = Paths.get("src/test/resources/$macheName")
        zip(macheDir, target)
    }

    fun setupMojang(mojangName: String, target: Path) {
        val mojangDir = Paths.get("src/test/resources/$mojangName")
        mojangDir.copyRecursivelyTo(target)

        val serverFolder = target.resolve("server")
        ProcessBuilder()
            .directory(serverFolder)
            .command("javac", serverFolder.resolve("Test.java").toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()

        ProcessBuilder()
            .directory(serverFolder)
            .command("jar", "-cf", "server.jar", "Test.class", "test.json")
            .redirectErrorStream(true)
            .start()
            .waitFor()

        val versionFolder = target.resolve("bundle/META-INF/versions/fake/")
        versionFolder.createDirectories()
        serverFolder.resolve("server.jar").copyTo(versionFolder.resolve("server.jar"))

        val oshiFolder = target.resolve("bundle/META-INF/libraries/com/github/oshi/oshi-core/6.6.5/")
        oshiFolder.createDirectories()
        oshiFolder.resolve(
            "oshi-core-6.6.5.jar"
        ).writeBytes(URL("https://libraries.minecraft.net/com/github/oshi/oshi-core/6.6.5/oshi-core-6.6.5.jar").readBytes())
        zip(target.resolve("bundle"), target.resolve("bundle.jar"))
    }

    fun modifyFile(path: Path, action: (content: String) -> String) {
        path.writeText(action.invoke(path.readText()))
    }
}
