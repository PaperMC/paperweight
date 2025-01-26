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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile

abstract class PatchRouletteCancel : AbstractPatchRouletteTask() {
    @get:OutputFile
    abstract val config: RegularFileProperty

    override fun run() {
        val config = if (config.path.isRegularFile()) {
            gson.fromJson<PatchRouletteApply.Config>(config.path)
        } else {
            throw PaperweightException("No config exists")
        }
        if (config.currentPatch == null) {
            throw PaperweightException("No current patch in config")
        }

        cancelPatch(config.currentPatch)
        this.config.path.writeText(gson.toJson(config.copy(currentPatch = null)))
    }
}
