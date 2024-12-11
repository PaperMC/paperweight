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

package io.papermc.paperweight.util.data.mache

import io.papermc.paperweight.util.constants.*
import org.gradle.api.Project
import org.gradle.kotlin.dsl.*

data class MacheMeta(
    val minecraftVersion: String,
    val macheVersion: String,
    val dependencies: MacheDependencies,
    val repositories: List<MacheRepository>,
    val decompilerArgs: List<String>,
    val remapperArgs: List<String>,
    val additionalCompileDependencies: MacheAdditionalDependencies? = null,
) {
    fun addRepositories(project: Project) {
        project.repositories {
            for (repository in repositories) {
                maven(repository.url) {
                    name = repository.name
                    mavenContent {
                        for (group in repository.groups ?: listOf()) {
                            includeGroupByRegex("$group.*")
                        }
                    }
                }
            }
        }
    }

    fun addDependencies(project: Project) {
        val macheDeps = this@MacheMeta.dependencies
        project.configurations {
            named(MACHE_CODEBOOK_CONFIG) {
                defaultDependencies {
                    macheDeps.codebook.forEach {
                        add(project.dependencies.create(it.toMavenString()))
                    }
                }
            }
            named(MACHE_PARAM_MAPPINGS_CONFIG) {
                defaultDependencies {
                    macheDeps.paramMappings.forEach {
                        add(project.dependencies.create(it.toMavenString()))
                    }
                }
            }
            named(MACHE_CONSTANTS_CONFIG) {
                defaultDependencies {
                    macheDeps.constants.forEach {
                        add(project.dependencies.create(it.toMavenString()))
                    }
                }
            }
            named(MACHE_REMAPPER_CONFIG) {
                defaultDependencies {
                    macheDeps.remapper.forEach {
                        add(project.dependencies.create(it.toMavenString()))
                    }
                }
            }
            named(MACHE_DECOMPILER_CONFIG) {
                defaultDependencies {
                    macheDeps.decompiler.forEach {
                        add(project.dependencies.create(it.toMavenString()))
                    }
                }
            }
        }
    }
}
