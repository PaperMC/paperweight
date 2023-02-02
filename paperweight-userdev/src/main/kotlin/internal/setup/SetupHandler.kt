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
import io.papermc.paperweight.userdev.internal.setup.v2.SetupHandlerImplV2
import io.papermc.paperweight.util.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkerExecutor

interface SetupHandler {
    fun createOrUpdateIvyRepository(context: Context)

    fun configureIvyRepo(repo: IvyArtifactRepository)

    fun populateCompileConfiguration(context: Context, dependencySet: DependencySet)

    fun populateRuntimeConfiguration(context: Context, dependencySet: DependencySet)

    fun serverJar(context: Context): Path

    val serverJar: Path

    val reobfMappings: Path

    val minecraftVersion: String

    val pluginRemapArgs: List<String>

    val paramMappings: MavenDep

    val decompiler: MavenDep

    val remapper: MavenDep

    val libraryRepositories: List<String>

    data class Context(
        val project: Project,
        val workerExecutor: WorkerExecutor,
        val javaToolchainService: JavaToolchainService
    ) {
        val defaultJavaLauncher: JavaLauncher
            get() = javaToolchainService.defaultJavaLauncher(project).get()
    }

    companion object {
        @Suppress("unchecked_cast")
        fun create(
            parameters: UserdevSetup.Parameters,
            extractedBundle: ExtractedBundle<Any>
        ): SetupHandler = when (extractedBundle.config) {
            is GenerateDevBundle.DevBundleConfig -> SetupHandlerImpl(
                parameters,
                extractedBundle as ExtractedBundle<GenerateDevBundle.DevBundleConfig>,
            )
            is DevBundleV2.Config -> SetupHandlerImplV2(
                parameters,
                extractedBundle as ExtractedBundle<DevBundleV2.Config>
            )
            else -> throw PaperweightException("Unknown dev bundle config type: ${extractedBundle.config::class.java.typeName}")
        }
    }
}
