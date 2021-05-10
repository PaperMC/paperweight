/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

package io.papermc.paperweight.transform

import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.kotlin.dsl.*

class TransformBuilder(private val project: Project, type: String, state: String) {

    init {
        project.dependencies {
            attributesSchema {
                attribute(transformState)
            }
            if (artifactTypes.names.contains(type)) {
                artifactTypes.named(type) {
                    attributes.attribute(transformState, TRANSFORM_STATE_BASE)
                }
            } else {
                artifactTypes.register(type) {
                    attributes.attribute(transformState, TRANSFORM_STATE_BASE)
                }
            }
        }
        project.configurations.named("test") {
            attributes.attribute(transformState, state)
        }
    }

    fun initialize() {
    }

    companion object {
        private val artifactType: Attribute<String> = attributeOf("artifactType")
        private val transformState: Attribute<String> = attributeOf("io.papermc.paperweight.transformState")

        private const val TRANSFORM_STATE_BASE: String = "base"

        private inline fun <reified T> attributeOf(name: String) = Attribute.of(name, T::class.java)
    }
}
