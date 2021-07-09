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

package io.papermc.paperweight.userdev.internal.setup

import com.github.salomonbrys.kotson.fromJson
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Returns whether the config changed, and the config
 */
fun extractDevBundle(
    destinationDirectory: Path,
    devBundle: Path
): Pair<Boolean, GenerateDevBundle.DevBundleConfig> {
    val hashFile = destinationDirectory.resolve("current.sha256")
    val newDevBundleHash = toHex(devBundle.hashFile(digestSha256()))

    if (destinationDirectory.exists()) {
        val currentDevBundleHash = if (hashFile.isRegularFile()) hashFile.readText(Charsets.UTF_8) else ""

        if (currentDevBundleHash.isNotBlank() && newDevBundleHash == currentDevBundleHash) {
            return false to readDevBundleConfig(destinationDirectory)
        }
        destinationDirectory.deleteRecursively()
    }
    destinationDirectory.createDirectories()

    hashFile.writeText(newDevBundleHash, Charsets.UTF_8)
    devBundle.openZip().use { fs ->
        fs.getPath("/").copyRecursivelyTo(destinationDirectory)
    }

    return true to readDevBundleConfig(destinationDirectory)
}

fun readDevBundleConfig(extractedDevBundlePath: Path): GenerateDevBundle.DevBundleConfig {
    val dataVersion = extractedDevBundlePath.resolve("data-version.txt").readText().trim().toInt()
    if (dataVersion != GenerateDevBundle.currentDataVersion) {
        throw PaperweightException(
            "The paperweight development bundle you are attempting to use is of data version '$dataVersion', but" +
                " the currently running version of paperweight only supports data version '${GenerateDevBundle.currentDataVersion}'."
        )
    }

    val configFile = extractedDevBundlePath.resolve("config.json")
    val config: GenerateDevBundle.DevBundleConfig = configFile.bufferedReader(Charsets.UTF_8).use { reader ->
        gson.fromJson(reader)
    }
    return config
}