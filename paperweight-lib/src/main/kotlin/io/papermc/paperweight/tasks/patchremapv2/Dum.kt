package io.papermc.paperweight.tasks.patchremapv2

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Dum should always run when requested")
abstract class Dum : BaseTask() {

    // TODO this is the right jar but we need to source remap to our mappings!
    // spigot patched decompiler -> specialsource remap (official-mojang+yarn) -> vanilla
    @get:InputFile
    abstract val spigotVanilla: RegularFileProperty
    // forge flower -> spigot AT -> tinyremapper (official-mojang+yarn) -> vanilla
    @get:InputFile
    abstract val spigotVanillaRemapped: RegularFileProperty
    // spigot fernflower -> mache remap -> vanilla
    @get:InputFile
    abstract val macheSpigot: RegularFileProperty
    // VF -> mache remap -> vanilla
    @get:InputFile
    abstract val macheVanilla: RegularFileProperty
    // remap spigot sources (officaial-mojang+yarn patched) -> spigot patches -> cb patches -> spigot patched decompliler -> specialsource remap (official-mojang+yarn) -> vanilla
    @get:InputFile
    abstract val remappedSpigot: RegularFileProperty

    @get:OutputDirectory
    abstract val outputPatchDir: DirectoryProperty

    @TaskAction
    fun run() {
        val patchDir = outputPatchDir.convertToPath().ensureClean()
        val base = spigotVanilla.convertToPath().openZip()
        val target = remappedSpigot.convertToPath().openZip()

        (target.getPath("net").walk() + target.getPath("com").walk()).forEach { path ->
            diffFile(target.getPath("/"), base.getPath("/"), path.toString(), patchDir)
        }
    }

    private fun diffFile(sourceRoot: Path, decompRoot: Path, relativePath: String, patchDir: Path): Int {
        val source = sourceRoot.resolve(relativePath)
        val decomp = decompRoot.resolve(relativePath)

        if (!source.exists() || !decomp.exists()) {
            if (!source.exists()) {
                logger.warn("No source file found for $source")
            }
            if (!decomp.exists()) {
                logger.warn("No decomp file found for $decomp (${spigotVanillaRemapped.convertToPath()}")
            }
            return 0
        }

        val sourceLines = source.readLines(Charsets.UTF_8)
        val decompLines = decomp.readLines(Charsets.UTF_8)

        val patch = DiffUtils.diff(decompLines, sourceLines)
        if (patch.deltas.isEmpty()) {
            return 0
        }

        val unifiedPatch = UnifiedDiffUtils.generateUnifiedDiff(
            "a/$relativePath",
            "b/$relativePath",
            decompLines,
            patch,
            3,
        )

        val patchFile = patchDir.resolve("$relativePath.patch")
        patchFile.parent.createDirectories()
        patchFile.writeText(unifiedPatch.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        return 1
    }
}
