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
import io.papermc.paperweight.userdev.internal.setup.v5.DevBundleV5
import io.papermc.paperweight.userdev.internal.setup.v5.SetupHandlerImplV5
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

interface SetupHandler {
    fun populateCompileConfiguration(context: ConfigurationContext, dependencySet: DependencySet)

    fun populateRuntimeConfiguration(context: ConfigurationContext, dependencySet: DependencySet)

    fun combinedOrClassesJar(context: ExecutionContext): Path

    fun afterEvaluate(project: Project) {
        project.tasks.withType(UserdevSetupTask::class).configureEach {
            devBundleCoordinates.set(determineArtifactCoordinates(project.configurations.getByName(DEV_BUNDLE_CONFIG)).single())
        }
    }

    val reobfMappings: Path

    val minecraftVersion: String

    val pluginRemapArgs: List<String>

    val paramMappings: MavenDep?

    val decompiler: MavenDep?

    val remapper: MavenDep?

    val mache: MavenDep?

    val libraryRepositories: List<String>

    data class ConfigurationContext(
        val project: Project,
        val dependencyFactory: DependencyFactory,
        val devBundleCoordinates: String,
        val setupTask: TaskProvider<UserdevSetupTask>,
        val layout: ProjectLayout = project.layout,
    ) {
        constructor(
            project: Project,
            dependencyFactory: DependencyFactory,
            setupTask: TaskProvider<UserdevSetupTask>
        ) : this(
            project,
            dependencyFactory,
            determineArtifactCoordinates(project.configurations.getByName(DEV_BUNDLE_CONFIG)).single(),
            setupTask,
        )
    }

    data class ExecutionContext(
        val workerExecutor: WorkerExecutor,
        val javaLauncher: JavaLauncher,
        val layout: ProjectLayout,
        val logger: Logger,

        val decompilerConfig: FileCollection,
        val paramMappingsConfig: FileCollection,
        val macheDecompilerConfig: FileCollection,
        val macheConfig: FileCollection,
        val remapperConfig: FileCollection,
        val macheRemapperConfig: FileCollection,
        val macheParamMappingsConfig: FileCollection,
        val macheConstantsConfig: FileCollection,
        val macheCodebookConfig: FileCollection,
    )

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

            is DevBundleV5.Config -> SetupHandlerImplV5(
                parameters,
                extractedBundle as ExtractedBundle<DevBundleV5.Config>
            )

            is DevBundleV2.Config -> SetupHandlerImplV2(
                parameters,
                extractedBundle as ExtractedBundle<DevBundleV2.Config>
            )

            else -> throw PaperweightException("Unknown dev bundle config type: ${extractedBundle.config::class.java.typeName}")
        }
    }
}
