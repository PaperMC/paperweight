package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input

@CacheableTask
abstract class CloneRepo : ZippedTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val ref: Property<String>

    @get:Input
    abstract val shallowClone: Property<Boolean>

    override fun init() {
        super.init()
        shallowClone.convention(true)
    }

    override fun run(dir: Path) {
        Git.checkForGit()

        val urlText = url.get().trim()

        if (dir.resolve(".git").notExists()) {
            dir.deleteRecursive()
            dir.createDirectories()

            Git(dir)("init", "--quiet").executeSilently()
        }

        val git = Git(dir)
        git("remote", "add", "origin", urlText).executeSilently(silenceErr = true)
        git.fetch()

        git("checkout", "-f", "FETCH_HEAD").executeSilently(silenceErr = true)
    }

    private fun Git.fetch() {
        if (shallowClone.get()) {
            this("fetch", "--depth", "1", "origin", ref.get()).executeSilently(silenceErr = true)
        } else {
            this("fetch", "origin", ref.get()).executeSilently(silenceErr = true)
        }
    }
}
