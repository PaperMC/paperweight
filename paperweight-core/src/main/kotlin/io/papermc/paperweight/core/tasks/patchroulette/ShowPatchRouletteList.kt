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

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.options.Option

abstract class ShowPatchRouletteList : AbstractPatchRouletteTask() {

    @get:Input
    @get:Optional
    @get:Option(option = "state", description = "Filter patches by status")
    abstract val statusFilter: Property<Status>

    @get:Input
    @get:Optional
    @get:Option(option = "user", description = "Filter patches by user")
    abstract val userFilter: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "path", description = "Filter patches by path")
    abstract val pathFilter: Property<String>

    override fun run() {
        var results = 0

        logger.lifecycle("| Status    | User                 | Path ")
        getAllPatches().forEach { patch ->
            if (statusFilter.isPresent && patch.status != statusFilter.get()) {
                return@forEach
            }

            if (userFilter.isPresent &&
                (patch.responsibleUser == null || !patch.responsibleUser.lowercase().contains(userFilter.get().lowercase()))
            ) {
                return@forEach
            }

            if (pathFilter.isPresent && !patch.path.lowercase().contains(pathFilter.get().lowercase())) {
                return@forEach
            }

            logger.lifecycle(String.format("| %-9s | %-20s | %s", patch.status, patch.responsibleUser, patch.path))
            results++
        }

        logger.lifecycle("$results patches found")
    }
}
