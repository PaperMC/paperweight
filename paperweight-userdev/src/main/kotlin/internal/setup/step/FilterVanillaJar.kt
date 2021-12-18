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
import io.papermc.paperweight.userdev.internal.setup.util.*
import io.papermc.paperweight.util.*
import java.nio.file.Path

fun filterVanillaServerJar(
    vanillaJar: Path,
    outputJar: Path,
    includes: List<String>,
) {
    val hashFunction = buildHashFunction(vanillaJar, outputJar, includes)
    val hashFile = outputJar.siblingHashesFile()
    if (hashFunction.upToDate(hashFile)) {
        return
    }

    UserdevSetup.LOGGER.lifecycle(":filtering vanilla server jar")
    filterJar(
        vanillaJar,
        outputJar,
        includes
    )
    hashFunction.writeHash(hashFile)
}
