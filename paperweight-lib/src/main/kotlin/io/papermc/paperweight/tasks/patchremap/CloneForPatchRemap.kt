package io.papermc.paperweight.tasks.patchremap

import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.path
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

abstract class CloneForPatchRemap : BaseTask() {

    @get:Internal
    abstract val craftBukkitDir: DirectoryProperty

    @get:Internal
    abstract val buildDataDir: DirectoryProperty

    @TaskAction
    open fun run() {
        Git.checkForGit()

        Git.clone("https://hub.spigotmc.org/stash/scm/spigot/craftbukkit.git", craftBukkitDir.get().path)
        Git.clone("https://hub.spigotmc.org/stash/scm/spigot/builddata.git", buildDataDir.get().path)
    }
}
