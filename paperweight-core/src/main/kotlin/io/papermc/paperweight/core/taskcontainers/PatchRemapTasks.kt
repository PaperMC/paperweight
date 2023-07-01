package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.ext
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.tasks.patchremap.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.patchremap.BuildDataInfo
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class PatchRemapTasks(
    project: Project,
    allTasks: AllTasks,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.ext,
) {

    val SPIGOT_MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/spigot-mojang+yarn.tiny"
    val OBF_SPIGOT_MAPPINGS = "$MAPPINGS_DIR/official-spigot.tiny"
    val SPIGOT_MEMBER_MAPPINGS = "$MAPPINGS_DIR/spigot-members.csrg"

    val cloneForPatchRemap by tasks.registering<CloneForPatchRemap> {
        craftBukkitDir.set(extension.patchRemap.craftBukkitDir)
        buildDataDir.set(extension.patchRemap.buildDataDir)
    }

    val buildDataInfo: Provider<BuildDataInfo> = project.contents(extension.patchRemap.buildDataInfo) {
        gson.fromJson(it)
    }

    val filterVanillaJar by tasks.registering<FilterJar> {
        inputJar.set(allTasks.extractFromBundler.flatMap { it.serverJar })
        includes.set(extension.patchRemap.vanillaJarIncludes)

        dependsOn(cloneForPatchRemap)
    }

    val generateSpigotMappings by tasks.registering<GenerateSpigotMappings> {
        classMappings.set(extension.patchRemap.mappingsDir.file(buildDataInfo.map { it.classMappings }))

        sourceMappings.set(allTasks.generateMappings.flatMap { it.outputMappings })

        outputMappings.set(cache.resolve(SPIGOT_MOJANG_YARN_MAPPINGS))
        notchToSpigotMappings.set(cache.resolve(OBF_SPIGOT_MAPPINGS))
        spigotMemberMappings.set(cache.resolve(SPIGOT_MEMBER_MAPPINGS))
    }

    val spigotRemapJar by tasks.registering<SpigotRemapJar> {
        inputJar.set(filterVanillaJar.flatMap { it.outputJar })
        classMappings.set(extension.patchRemap.mappingsDir.file(buildDataInfo.map { it.classMappings }))
        accessTransformers.set(extension.patchRemap.mappingsDir.file(buildDataInfo.map { it.accessTransforms }))

        memberMappings.set(generateSpigotMappings.flatMap { it.spigotMemberMappings })

        mcVersion.set(extension.minecraftVersion)

        workDir.set(extension.patchRemap.workDir)

        specialSourceJar.set(extension.patchRemap.specialSourceJar)
        specialSource2Jar.set(extension.patchRemap.specialSource2Jar)

        classMapCommand.set(buildDataInfo.map { it.classMapCommand })
        memberMapCommand.set(buildDataInfo.map { it.memberMapCommand })
        finalMapCommand.set(buildDataInfo.map { it.finalMapCommand })
    }

    val filterSpigotExcludes by tasks.registering<FilterSpigotExcludes> {
        inputZip.set(spigotRemapJar.flatMap { it.outputJar })
        excludesFile.set(extension.patchRemap.excludesFile)
    }

    val spigotDecompileJar by tasks.registering<SpigotDecompileJar> {
        inputJar.set(filterSpigotExcludes.flatMap { it.outputZip })
        fernFlowerJar.set(extension.patchRemap.fernFlowerJar)
        decompileCommand.set(buildDataInfo.map { it.decompileCommand })
    }

    val patchCraftBukkit by tasks.registering<ApplyCraftBukkitPatches> {
        // TODO temp to speed stuff up
        //sourceJar.set(spigotDecompileJar.flatMap { it.outputJar })
        sourceJar.set(cache.resolve("paperweight/taskCache/spigotDecompileJar.jar"))
        cleanDirPath.set("net/minecraft")
        patchDir.set(extension.patchRemap.patchDir)
        craftBukkitDir.set(extension.patchRemap.craftBukkitDir)
        outputDir.set(extension.patchRemap.patchedCraftBukkitDir)

        //dependsOn(cloneForPatchRemap)
    }
}
