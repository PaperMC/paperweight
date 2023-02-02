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

package io.papermc.paperweight.util.data

import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier

data class ModuleId(val group: String, val name: String, val version: String, val classifier: String? = null) : Comparable<ModuleId> {
    fun toPath(): String {
        val fileName = listOfNotNull(name, version, classifier).joinToString("-") + ".jar"
        return "${group.replace('.', '/')}/$name/$version/$fileName"
    }

    override fun compareTo(other: ModuleId): Int {
        return comparator.compare(this, other)
    }

    override fun toString(): String = listOfNotNull(group, name, version, classifier).joinToString(":")

    companion object {
        private val comparator = compareBy<ModuleId>({ it.group }, { it.name }, { it.version }, { it.classifier })

        fun parse(text: String): ModuleId {
            val split = text.split(":")
            val (group, name, version) = split
            return ModuleId(group, name, version, split.getOrNull(3))
        }

        fun fromIdentifier(id: ComponentArtifactIdentifier): ModuleId {
            if (id is DefaultModuleComponentArtifactIdentifier) {
                val idx = id.componentIdentifier
                return ModuleId(idx.group, idx.module, idx.version, id.name.classifier)
            }
            val compId = id.componentIdentifier
            if (compId is ModuleComponentIdentifier) {
                return ModuleId(compId.group, compId.module, compId.version)
            }
            error("Could not create ModuleId from ComponentArtifactIdentifier $id")
        }
    }
}
