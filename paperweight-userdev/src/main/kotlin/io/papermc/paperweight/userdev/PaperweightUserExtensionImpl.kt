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

package io.papermc.paperweight.userdev

import io.papermc.paperweight.extension.AbstractJavaLauncherHolder
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.util.*
import org.gradle.api.Project
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

abstract class PaperweightUserExtensionImpl(
    project: Project,
    projectLayout: ProjectLayout,
    workerExecutor: WorkerExecutor,
    javaToolchainService: JavaToolchainService,
    setup: Provider<SetupHandler>
) : PaperweightUserExtension, AbstractJavaLauncherHolder(project, javaToolchainService) {
    @Suppress("OVERRIDE_DEPRECATION")
    override val mojangMappedServerJar: Provider<RegularFile> = projectLayout.file(
        setup.map { it.serverJar(SetupHandler.Context(project, workerExecutor)).toFile() }
    )

    override val minecraftVersion: Provider<String> = setup.map { it.minecraftVersion }

    init {
        init()
    }

    private fun init() {
        reobfArtifactConfiguration.convention(ReobfArtifactConfiguration.REOBF_PRODUCTION).finalizeValueOnRead()
        injectPaperRepository.convention(true).finalizeValueOnRead()
    }
}
