package io.papermc.paperweight.checkstyle.tasks

import io.papermc.paperweight.tasks.TaskTest
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.gradle.kotlin.dsl.register
import org.junit.jupiter.api.io.TempDir

class MergeCheckstyleConfigsTest : TaskTest() {
    private lateinit var task: MergeCheckstyleConfigs

    @BeforeTest
    fun setup() {
        val project = setupProject()
        task = project.tasks.register("mergeConfigs", MergeCheckstyleConfigs::class).get()
    }

    @Test
    fun basicMerge(@TempDir tempDir: Path) {
        val testResource = Path.of("src/test/resources/checkstyle/basicMerge")
        val testInput = testResource.resolve("input")

        val baseConfig = setupFile(tempDir, testInput, "base_checkstyle.xml")
        val overrideConfig = setupFile(tempDir, testInput, "project_checkstyle.xml")
        val output = tempDir.resolve("merged_checkstyle.xml")

        task.baseConfigFile.set(baseConfig.toFile())
        task.overrideConfigFile.set(overrideConfig.toFile())
        task.mergedConfigFile.set(output.toFile())

        task.run()

        val testOutput = testResource.resolve("output")
        compareFile(tempDir, testOutput, "merged_checkstyle.xml")
    }
}
