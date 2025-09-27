package io.papermc.paperweight.userdev.internal.action

import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension
import io.papermc.paperweight.userdev.PaperweightUserExtension
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class PaperweightUserTest {
    fun setupProject(dir: Path): Project {
        return ProjectBuilder.builder()
            .withProjectDir(dir.toFile())
            .build()
    }

    @Test
    fun testPluginApplication(@TempDir tmpDir: Path) {
        val project = setupProject(tmpDir)
        project.pluginManager.apply("io.papermc.paperweight.userdev")

        assertNotNull(project.extensions.getByType(PaperweightUserExtension::class))
        assertNotNull(project.dependencies.extensions.getByType(PaperweightUserDependenciesExtension::class))
    }
}