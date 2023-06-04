package io.papermc.paperweight.tasks.mm

import com.github.salomonbrys.kotson.fromJson
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

abstract class ApplyInitialPaperPatch : BaseTask() {

    @get:InputDirectory
    abstract val craftBukkitDir: DirectoryProperty

    @get:InputFile
    abstract val initialPatch: RegularFileProperty

    @get:InputFile
    abstract val caseOnlyClassNameChanges: RegularFileProperty

    @get:Input
    abstract val additionalExcludes: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(craftBukkitDir)
        additionalExcludes.convention(objects.listProperty())
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val caseOnlyChanges = caseOnlyClassNameChanges.path.bufferedReader(Charsets.UTF_8).use { reader ->
            gson.fromJson<List<ClassNameChange>>(reader)
        }

        Git(craftBukkitDir).let { git ->
            val sourceDir = craftBukkitDir.path.resolve("src/main/java")
            val excludes = arrayListOf<String>()
            for (caseOnlyChange in caseOnlyChanges) {
                val file = sourceDir.resolve(caseOnlyChange.obfName + ".java")
                file.deleteForcefully()
                excludes += "--exclude=src/main/java/${caseOnlyChange.obfName}.java"
            }
            excludes += additionalExcludes.get().map { "--exclude=$it" }

            git("am", *excludes.toTypedArray(), initialPatch.path.absolutePathString()).execute()
            for (caseOnlyChange in caseOnlyChanges) {
                git("rm", "--cached", "src/main/java/${caseOnlyChange.obfName}.java").runSilently(silenceErr = true)
            }
            git("commit", "--amend", "--no-edit").execute()
        }
    }
}
