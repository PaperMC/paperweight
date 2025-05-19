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

package io.papermc.paperweight.userdev.internal.setup

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.setup.v2.DevBundleV2
import io.papermc.paperweight.userdev.internal.setup.v5.DevBundleV5
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*

private val supported = mapOf(
    2 to DevBundleV2.Config::class, // 1.17.1
    3 to DevBundleV5.Config::class, // up to 1.20.4
    4 to DevBundleV5.Config::class, // 1.20.5, early 1.20.6
    5 to DevBundleV5.Config::class, // 1.20.6+ (nullable mojangApiCoordinates)
    6 to GenerateDevBundle.DevBundleConfig::class, // Post-repo-restructure 1.21.4
    7 to GenerateDevBundle.DevBundleConfig::class, // 1.21.4+
)

fun readBundleInfo(bundleZip: Path): BundleInfo<Any> {
    bundleZip.openZipSafe().use { fs ->
        readDevBundle(fs.getPath("/")).let { (config, _) ->
            return BundleInfo(config, bundleZip)
        }
    }
}

data class BundleInfo<C>(
    val config: C,
    val zip: Path,
)

private fun readDevBundle(
    devBundleRoot: Path
): Pair<Any, Int> {
    val dataVersion = devBundleRoot.resolve("data-version.txt").readText().trim().toInt()
    if (dataVersion !in supported) {
        throw PaperweightException(
            "The paperweight development bundle you are attempting to use is of data version '$dataVersion', but" +
                " the currently running version of paperweight only supports data versions '${supported.keys}'."
        )
    }

    val configClass = supported[dataVersion] ?: throw PaperweightException("Could not find config class for version $dataVersion?")
    val configFile = devBundleRoot.resolve("config.json")
    val config: Any = configFile.bufferedReader(Charsets.UTF_8).use { reader ->
        gson.fromJson(reader, configClass.java)
    }
    return config to dataVersion
}
