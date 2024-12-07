package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class UnpackSpigotBuildData : BaseTask() {
    @get:InputFile
    abstract val buildDataZip: RegularFileProperty

    @get:OutputFile
    abstract val buildDataInfoFile: RegularFileProperty

    @get:OutputFile
    abstract val excludesFile: RegularFileProperty

    @get:OutputFile
    abstract val atFile: RegularFileProperty

    @get:OutputFile
    abstract val classMappings: RegularFileProperty

    @get:OutputFile
    abstract val specialSourceJar: RegularFileProperty

    @get:OutputFile
    abstract val specialSource2Jar: RegularFileProperty

    override fun init() {
        buildDataInfoFile.convention(defaultOutput("spigot-build-data-info", "json"))
        excludesFile.convention(defaultOutput("spigot-excludes", "exclude"))
        atFile.convention(defaultOutput("spigot-ats", "at"))
        classMappings.convention(defaultOutput("spigot-class-mapping", "csrg"))
        specialSourceJar.convention(defaultOutput("special-source", "jar"))
        specialSource2Jar.convention(defaultOutput("special-source-2", "jar"))
    }

    @TaskAction
    fun run() {
        buildDataZip.path.openZip().use {
            val root = it.getPath("/")
            root.resolve("info.json")
                .copyTo(buildDataInfoFile.path.createParentDirectories(), overwrite = true)
            val mappings = root.resolve("mappings")
            bukkitFileFrom(mappings, "exclude")
                .copyTo(excludesFile.path.createParentDirectories(), overwrite = true)
            bukkitFileFrom(mappings, "at")
                .copyTo(atFile.path.createParentDirectories(), overwrite = true)
            bukkitFileFrom(mappings, "csrg")
                .copyTo(classMappings.path.createParentDirectories(), overwrite = true)
            root.resolve("bin/SpecialSource.jar")
                .copyTo(specialSourceJar.path.createParentDirectories(), overwrite = true)
            root.resolve("bin/SpecialSource-2.jar")
                .copyTo(specialSource2Jar.path.createParentDirectories(), overwrite = true)
        }
    }

    private fun bukkitFileFrom(dir: Path, extension: String): Path =
        dir.useDirectoryEntries { it.filter { f -> f.name.endsWith(extension) }.single() }
}
