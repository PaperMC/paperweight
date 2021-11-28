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

package io.papermc.paperweight.util.data

import org.gradle.api.artifacts.component.ModuleComponentIdentifier

data class ModuleId(val group: String, val name: String, val version: String) : Comparable<ModuleId> {
    fun toPath(): String {
        val fileName = "$name-$version.jar"
        return "${group.replace('.', '/')}/$name/$version/$fileName"
    }

    override fun compareTo(other: ModuleId): Int {
        return comparator.compare(this, other)
    }

    override fun toString(): String {
        return "$group:$name:$version"
    }

    companion object {
        private val comparator = compareBy<ModuleId>({ it.group }, { it.name }, { it.version })

        fun parse(text: String): ModuleId {
            val (group, name, version) = text.split(":")
            return ModuleId(group, name, version)
        }

        fun fromIdentifier(id: ModuleComponentIdentifier): ModuleId {
            return ModuleId(id.group, id.module, id.version)
        }
    }
}
