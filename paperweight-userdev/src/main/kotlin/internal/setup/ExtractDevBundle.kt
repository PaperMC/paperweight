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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*

data class ExtractedBundle<C>(
    val changed: Boolean,
    val config: C,
    val dataVersion: Int,
    val dir: Path,
) {
    constructor(bundleChanged: Boolean, pair: Pair<C, Int>, dir: Path) :
        this(bundleChanged, pair.first, pair.second, dir)
}

fun extractDevBundle(
    destinationDirectory: Path,
    devBundle: Path
): ExtractedBundle<Any> {
    val hashFile = destinationDirectory.resolve("current.sha256")
    val newDevBundleHash = devBundle.sha256asHex()

    if (destinationDirectory.exists()) {
        val currentDevBundleHash = if (hashFile.isRegularFile()) hashFile.readText(Charsets.UTF_8) else ""

        if (currentDevBundleHash.isNotBlank() && newDevBundleHash == currentDevBundleHash) {
            return ExtractedBundle(false, readDevBundle(destinationDirectory), destinationDirectory)
        }
        destinationDirectory.deleteRecursively()
    }
    destinationDirectory.createDirectories()

    hashFile.writeText(newDevBundleHash, Charsets.UTF_8)
    devBundle.openZip().use { fs ->
        fs.getPath("/").copyRecursivelyTo(destinationDirectory)
    }

    return ExtractedBundle(true, readDevBundle(destinationDirectory), destinationDirectory)
}

private fun readDevBundle(
    extractedDevBundlePath: Path
): Pair<Any, Int> {
    val dataVersion = extractedDevBundlePath.resolve("data-version.txt").readText().trim().toInt()
    DevBundleVersions.checkSupported(dataVersion)

    val ver = DevBundleVersions.versions[dataVersion] ?: throw PaperweightException("Could not find data version $dataVersion?")
    val configFile = extractedDevBundlePath.resolve("config.json")
    val config: Any = configFile.bufferedReader(Charsets.UTF_8).use { reader ->
        gson.fromJson(reader, ver.configType.java)
    }
    return config to dataVersion
}
