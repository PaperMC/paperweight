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
