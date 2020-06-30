package io.papermc.paperweight.ext

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

fun Project.dirWithDefault(path: String): DirectoryProperty =
    objects.directoryProperty().convention(layout.dir(provider { file(path) }))

fun Project.fileWithDefault(path: String): RegularFileProperty  =
    objects.fileProperty().convention(layout.file(provider { file(path) }))
