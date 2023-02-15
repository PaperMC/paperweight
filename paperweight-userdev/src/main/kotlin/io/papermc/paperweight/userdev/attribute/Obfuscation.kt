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

package io.papermc.paperweight.userdev.attribute

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

/**
 * Attribute representing the state of obfuscation.
 */
interface Obfuscation : Named {
    companion object {
        val OBFUSCATION_ATTRIBUTE = Attribute.of(
            "io.papermc.paperweight.obfuscation",
            Obfuscation::class.java
        )

        // Note that we don't reference this in the project, but you can use it as a value to pick the
        // runtimeElements configuration instead of reobf.
        /**
         * No obfuscation, i.e. using human-readable names.
         */
        const val NONE = "none"

        /**
         * Obfuscated, i.e. using runtime names.
         */
        const val OBFUSCATED = "obfuscated"
    }
}
