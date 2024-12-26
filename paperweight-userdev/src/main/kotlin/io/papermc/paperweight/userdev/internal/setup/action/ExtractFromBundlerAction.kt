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

package io.papermc.paperweight.userdev.internal.setup.action

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.action.DirectoryValue
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher

class ExtractFromBundlerAction(
    @Input
    val mojangJar: FileValue,
    @Output
    val vanillaServerJar: FileValue,
    @Output
    val minecraftLibraryJars: DirectoryValue,
) : WorkDispatcher.Action {
    override fun execute() {
        ServerBundler.extractFromBundler(
            mojangJar.get(),
            vanillaServerJar.get(),
            minecraftLibraryJars.get(),
            null,
            null,
            null,
            null,
        )
    }
}
