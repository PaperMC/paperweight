package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class SetupSpigot : BaseTask() {

    @get:InputDirectory
    abstract val spigotServerDir: DirectoryProperty

    @get:OutputFile
    abstract val initialCraftBukkitSpigotPatch: RegularFileProperty

    override fun init() {
        group = "mm"
        initialCraftBukkitSpigotPatch.convention(objects.fileProperty().convention(layout.cacheFile("$INITIAL_SPIGOT_PATCHES/02-cb.patch")))
    }

    @TaskAction
    fun run() {
        Git.checkForGit()
        initialCraftBukkitSpigotPatch.path.deleteForcefully()

        Git(spigotServerDir).let { git ->
            val cb = git("format-patch", "--no-stat", "-N", "--zero-commit", "--full-index", "--no-signature", "-1", "base")
            cb.execute()
            Files.move(spigotServerDir.path.resolve(cb.getText().trim()), initialCraftBukkitSpigotPatch.path)
        }
    }
}
