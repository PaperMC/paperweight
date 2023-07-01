package io.papermc.paperweight.core.extension

import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.listProperty

open class PatchRemapExtension (objects: ObjectFactory, layout: ProjectLayout) {

    @Suppress("MemberVisibilityCanBePrivate")
    val workDir: DirectoryProperty = objects.directoryProperty().convention(layout.cacheDir("patchRemap"))
    val craftBukkitDir: DirectoryProperty =  objects.dirFrom(workDir, "CraftBukkit")
    val patchedCraftBukkitDir: DirectoryProperty =  objects.dirFrom(workDir, "PatchedCraftBukkit")
    val patchDir: DirectoryProperty = objects.dirFrom(craftBukkitDir, "nms-patches")

    @Suppress("MemberVisibilityCanBePrivate")
    val buildDataDir: DirectoryProperty = objects.dirFrom(workDir, "BuildData")
    val buildDataInfo: RegularFileProperty = objects.fileFrom(buildDataDir, "info.json")
    val mappingsDir: DirectoryProperty = objects.dirFrom(buildDataDir, "mappings")
    val excludesFile: RegularFileProperty = objects.bukkitFileFrom(mappingsDir, "exclude")

    @Suppress("MemberVisibilityCanBePrivate")
    val buildDataBinDir: DirectoryProperty = objects.dirFrom(buildDataDir, "bin")
    val fernFlowerJar: RegularFileProperty = objects.fileFrom(buildDataBinDir, "fernflower.jar")
    val specialSourceJar: RegularFileProperty = objects.fileFrom(buildDataBinDir, "SpecialSource.jar")
    val specialSource2Jar: RegularFileProperty = objects.fileFrom(buildDataBinDir, "SpecialSource-2.jar")

    val vanillaJarIncludes: ListProperty<String> = objects.listProperty<String>().convention(
        listOf("/*.class", "/net/minecraft/**", "/com/mojang/math/**")
    )

    private fun ObjectFactory.bukkitFileFrom(base: DirectoryProperty, extension: String): RegularFileProperty =
        fileProperty().convention(
            base.flatMap { dir ->
                val file = dir.path.useDirectoryEntries { it.filter { f -> f.name.endsWith(extension) }.singleOrNull() }
                if (file != null) {
                    mappingsDir.file(file.name)
                } else {
                    fileProperty()
                }
            }
        )

}
