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
import kotlin.test.Test
import kotlin.test.assertEquals
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

class FunctionalTest {

    val debug = false

    @Disabled
    @Test
    fun setupCleanTestRepo() {
        val projectDir = Path.of("F:\\Projects\\paperweight\\test").ensureClean().createDirectories()

        setupMache("fake_mache", projectDir.resolve("mache.zip"))
        setupMojang("fake_mojang", projectDir.resolve("fake_mojang"))
        projectDir.copyProject("functional_test")

        val settings = projectDir.resolve("settings.gradle")
        val text = settings.readText()
        settings.writeText(text.replace("// includeBuild '..'", "includeBuild '..'").replace("functional_test", "test"))

        projectDir.resolve("patches").deleteRecursively()
    }

    @Test
    fun `test simple test project`(@TempDir(cleanup = CleanupMode.NEVER) tempDir: Path) {
        val testResource = Paths.get("src/test/resources/functional_test")

        setupMache("fake_mache", tempDir.resolve("mache.zip"))
        setupMojang("fake_mojang", tempDir.resolve("fake_mojang"))

        val gradleRunner = tempDir.copyProject("functional_test").gradleRunner()

        // appP -> works
        val appP = gradleRunner
            .withArguments("applyPatches", "dependencies", ":test-server:dependencies", "--stacktrace", "-Dfake=true")
            .withDebug(debug)
            .build()

        assertEquals(appP.task(":applyPatches")?.outcome, TaskOutcome.SUCCESS)

        // clean rebuild rebP -> changes nothing
        val rebP = gradleRunner
            .withArguments("rebuildPatches", "--stacktrace", "-Dfake=true")
            .withDebug(debug)
            .build()

        assertEquals(rebP.task(":rebuildPatches")?.outcome, TaskOutcome.SUCCESS)
        assertEquals(
            testResource.resolve("fake-patches/sources/Test.java.patch").readText(),
            tempDir.resolve("fake-patches/sources/Test.java.patch").readText()
        )

        // add AT to source -> patch and AT file is updated
        val sourceFile = tempDir.resolve("test-server/src/vanilla/java/Test.java")
        val replacedContent = sourceFile.readText().replace(
            "\"2\";",
            "\"2\"; // Woo"
        ).replace("public String getTest2() {", "private final String getTest2() {// Paper-AT: private+f getTest2()Ljava/lang/String;")
        sourceFile.writeText(replacedContent)

        val rebP2 = gradleRunner
            .withArguments("rebuildPatches", "--stacktrace", "-Dfake=true")
            .withDebug(debug)
            .build()

        assertEquals(rebP2.task(":rebuildPatches")?.outcome, TaskOutcome.SUCCESS)
        assertEquals(
            testResource.resolve("fake-patches/expected/Test.java.patch").readText(),
            tempDir.resolve("fake-patches/sources/Test.java.patch").readText()
        )
        assertEquals(testResource.resolve("build-data/expected.at").readText(), tempDir.resolve("build-data/fake.at").readText())
    }

    @Test
    fun `test full vanilla project`(@TempDir(cleanup = CleanupMode.ON_SUCCESS) tempDir: Path) {
        val result = tempDir.copyProject("functional_test")
            .gradleRunner()
            .withArguments("applyPatches", "-Dfake=false")
            .withDebug(debug)
            .build()

        assertEquals(result.task(":applyPatches")?.outcome, TaskOutcome.SUCCESS)
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

        val oshiFolder = target.resolve("bundle/META-INF/libraries/com/github/oshi/oshi-core/6.4.5/")
        oshiFolder.createDirectories()
        oshiFolder.resolve(
            "oshi-core-6.4.5.jar"
        ).writeBytes(URL("https://libraries.minecraft.net/com/github/oshi/oshi-core/6.4.5/oshi-core-6.4.5.jar").readBytes())
        zip(target.resolve("bundle"), target.resolve("bundle.jar"))
    }
}
