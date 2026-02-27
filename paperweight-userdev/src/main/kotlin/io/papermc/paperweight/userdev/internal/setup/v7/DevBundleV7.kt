package io.papermc.paperweight.userdev.internal.setup.v7

import io.papermc.paperweight.util.MavenDep

object DevBundleV7 {
    data class Config(
        val minecraftVersion: String,
        val mache: MavenDep,
        val patchDir: String,
        val reobfMappingsFile: String?,
        val mojangMappedPaperclipFile: String,
        val libraryRepositories: List<String>,
        val pluginRemapArgs: List<String>,
    )
}
