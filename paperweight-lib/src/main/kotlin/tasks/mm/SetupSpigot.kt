package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import kotlin.io.path.*
import java.nio.file.Files
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class SetupSpigot : BaseTask() {

    @get:InputDirectory
    abstract val spigotServerDir: DirectoryProperty

    @get:OutputFile
    abstract val initialVanillaSpigotPatch: RegularFileProperty

    @get:OutputFile
    abstract val initialCraftBukkitSpigotPatch: RegularFileProperty

    @get:OutputDirectory
    abstract val spigotNmsPatches: DirectoryProperty

    override fun init() {
        group = "mm"
        initialVanillaSpigotPatch.convention(objects.fileProperty().convention(layout.cacheFile("$INITIAL_SPIGOT_PATCHES/01-vanilla.patch")))
        initialCraftBukkitSpigotPatch.convention(objects.fileProperty().convention(layout.cacheFile("$INITIAL_SPIGOT_PATCHES/02-cb.patch")))
        spigotNmsPatches.convention(objects.directoryProperty().convention(layout.cacheDir(SPIGOT_NMS_PATCHES)))
    }

    @TaskAction
    fun run() {
        Git.checkForGit()
        initialCraftBukkitSpigotPatch.path.deleteForcefully()
        initialVanillaSpigotPatch.path.deleteForcefully()
        spigotNmsPatches.path.deleteRecursively()

        Git(spigotServerDir).let {
            val vanilla = it("format-patch", "--no-stat", "-N", "--zero-commit", "--full-index", "--no-signature", "-1", "base~1")
            vanilla.execute()
            Files.move(spigotServerDir.path.resolve(vanilla.getText().trim()), initialVanillaSpigotPatch.path)

            val cb = it("format-patch", "--no-stat", "-N", "--zero-commit", "--full-index", "--no-signature", "-1", "base")
            cb.execute()
            Files.move(spigotServerDir.path.resolve(cb.getText().trim()), initialCraftBukkitSpigotPatch.path)
        }

        Git(spigotServerDir)("format-patch", "-o", spigotNmsPatches.path.absolutePathString(), "--no-stat", "-N", "base",
            "--", "src/main/java/net/minecraft/").execute()
    }
}
