package io.papermc.paperweight.checkstyle.tasks

import io.papermc.paperweight.checkstyle.JavadocTag
import io.papermc.paperweight.util.path
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.resources.TextResourceFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

abstract class PaperCheckstyleTask : Checkstyle() {

    @get:Input
    abstract val rootPath: Property<String>

    @get:InputFile
    @get:Optional
    abstract val directoriesToSkipFile: RegularFileProperty

    @get:InputFile
    abstract val typeUseAnnotationsFile: RegularFileProperty

    @get:Nested
    @get:Optional
    abstract val customJavadocTags: SetProperty<JavadocTag>

    @get:InputFile
    abstract val mergedConfigFile: RegularFileProperty

    @get:Internal
    val textResourceFactory: TextResourceFactory = project.resources.text

    init {
        reports.xml.required.set(true)
        reports.html.required.set(true)
        maxHeapSize.set("2g")
        configDirectory.set(project.rootProject.layout.projectDirectory.dir(".checkstyle"))
    }

    @TaskAction
    override fun run() {
        config = textResourceFactory.fromFile(mergedConfigFile.path.toFile())
        val existingProperties = configProperties?.toMutableMap() ?: mutableMapOf()
        existingProperties["type_use_annotations"] = typeUseAnnotationsFile.path.readText().trim().split("\n").joinToString("|")
        existingProperties["custom_javadoc_tags"] = customJavadocTags.getOrElse(emptySet()).joinToString("|") { it.toOptionString() }
        configProperties = existingProperties
        exclude {
            if (it.isDirectory) return@exclude false
            val absPath = it.file.toPath().toAbsolutePath().relativeTo(Paths.get(rootPath.get()))
            val parentPath = (absPath.parent?.invariantSeparatorsPathString + "/")
            if (directoriesToSkipFile.isPresent) {
                return@exclude directoriesToSkipFile.path.readText().trim().split("\n").any { pkg -> parentPath == pkg }
            }
            return@exclude false
        }
        if (!source.isEmpty) {
            super.run()
        }
    }
}
