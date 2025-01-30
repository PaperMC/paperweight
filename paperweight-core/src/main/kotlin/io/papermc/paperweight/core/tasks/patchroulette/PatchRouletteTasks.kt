/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.core.tasks.patchroulette

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.*

class PatchRouletteTasks(
    target: Project,
    namePrefix: String,
    minecraftVer: Provider<String>,
    patchDirectory: Provider<Directory>,
    targetDirectory: Directory,
) {
    init {
        target.tasks.register<ShowPatchRouletteList>("${namePrefix}PatchRouletteList") {
            minecraftVersion = minecraftVer
        }
        target.tasks.register<PushPatchRouletteList>("${namePrefix}PatchRoulettePush") {
            minecraftVersion = minecraftVer
            patchDir = patchDirectory
        }
        if (paperweightDebug()) {
            // Require debug to ensure no one does this by accident
            target.tasks.register<ClearPatchRouletteList>("${namePrefix}PatchRouletteClear") {
                minecraftVersion = minecraftVer
            }
        }
        target.tasks.register<PatchRouletteApply>("${namePrefix}PatchRouletteApply") {
            minecraftVersion = minecraftVer
            patchDir = patchDirectory
            targetDir = targetDirectory
            config.pathProvider(
                minecraftVer.map {
                    layout.cache.resolve(PATCH_ROULETTE_CONFIG_DIR).resolve("$namePrefix-$it.json")
                }
            )
        }
        target.tasks.register<PatchRouletteCancel>("${namePrefix}PatchRouletteCancel") {
            minecraftVersion = minecraftVer
            config.pathProvider(
                minecraftVer.map {
                    layout.cache.resolve(PATCH_ROULETTE_CONFIG_DIR).resolve("$namePrefix-$it.json")
                }
            )
        }
        target.tasks.register<PatchRouletteFinish>("${namePrefix}PatchRouletteFinish") {
            minecraftVersion = minecraftVer
            patchDir = patchDirectory
            config.pathProvider(
                minecraftVer.map {
                    layout.cache.resolve(PATCH_ROULETTE_CONFIG_DIR).resolve("$namePrefix-$it.json")
                }
            )
        }
    }
}
