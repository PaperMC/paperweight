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
import io.papermc.paperweight.userdev.internal.setup.v2.DevBundleV2
import io.papermc.paperweight.userdev.internal.setup.v3.DevBundleV3
import io.papermc.paperweight.userdev.internal.setup.v4.DevBundleV4
import kotlin.reflect.KClass

object DevBundleVersions {
    data class SupportedVersion<C>(
        val version: Int,
        val configType: KClass<out Any>,
        val factory: SetupHandler.Factory<C>
    )

    val versions: Map<Int, SupportedVersion<*>> = listOf(
        DevBundleV2.version,
        DevBundleV3.version,
        DevBundleV4.version,
    ).associateBy { it.version }

    val versionsByConfigType: Map<KClass<out Any>, SupportedVersion<*>> = versions.values.associateBy { it.configType }

    fun checkSupported(dataVersion: Int) {
        if (dataVersion !in versions) {
            throw PaperweightException(
                "The paperweight development bundle you are attempting to use is of data version '$dataVersion', but" +
                    " the currently running version of paperweight only supports data versions '${versions.keys}'."
            )
        }
    }
}
