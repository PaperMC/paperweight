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

package io.papermc.paperweight.util

import java.util.Properties
import kotlin.io.path.*
import kotlin.reflect.KProperty
import org.gradle.api.Project

class MergedProperties(private val properties: List<Properties>) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String {
        for (props in properties) {
            if (props.containsKey(property.name)) {
                return props.getProperty(property.name)
            }
        }
        error("No value for property '${property.name}'")
    }

    companion object {
        fun fromAllProjects(project: Project, relativePath: String): MergedProperties {
            // give our project priority
            val projects = listOf(project) + project.rootProject.allprojects
            val properties = projects.asSequence()
                .distinct()
                .map { it.layout.projectDirectory.file(relativePath).path }
                .filter { it.exists() }
                .map {
                    Properties().apply {
                        load(it.bufferedReader())
                    }
                }
                .toList()
            return MergedProperties(properties)
        }
    }
}
