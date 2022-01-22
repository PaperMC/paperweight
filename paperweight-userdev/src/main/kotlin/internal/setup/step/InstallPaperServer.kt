/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

package io.papermc.paperweight.userdev.internal.setup.step

import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.util.constants.IVY_REPOSITORY
import io.papermc.paperweight.util.installToIvyRepo
import java.nio.file.Path

fun installPaperServer(
    cache: Path,
    mappedServerCoordinates: String,
    dependencies: List<String>,
    serverJar: Path,
    serverSourcesJar: Path?,
    mcVersion: String,
) {
    val didInstall = installToIvyRepo(
        cache.resolve(IVY_REPOSITORY),
        mappedServerCoordinates,
        dependencies,
        serverJar,
        serverSourcesJar,
    )
    if (didInstall) {
        UserdevSetup.LOGGER.lifecycle(":installed server artifacts to cache")
        UserdevSetup.LOGGER.lifecycle(":done setting up paperweight userdev workspace for minecraft {}", mcVersion)
    }
}
