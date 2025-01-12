package io.papermc.paperweight.core.extension

import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.SetProperty

@Suppress("LeakingThis")
abstract class PaperCheckstyleExt {

    @get:Inject
    abstract val layout: ProjectLayout

    abstract val typeUseAnnotations: SetProperty<String>
    abstract val directoriesToSkip: SetProperty<String>
    abstract val projectLocalCheckstyleConfig: DirectoryProperty

    init {
        projectLocalCheckstyleConfig.convention(layout.projectDirectory.dir(".checkstyle"))
    }
}
