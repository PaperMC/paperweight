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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor

abstract class UserdevSetupTask : JavaLauncherTask() {
    @get:ServiceReference
    abstract val setupService: Property<UserdevSetup>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val devBundleCoordinates: Property<String>

    @get:CompileClasspath
    abstract val devBundle: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val decompilerConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val paramMappingsConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheDecompilerConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val remapperConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheRemapperConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheParamMappingsConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheConstantsConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheCodebookConfig: ConfigurableFileCollection

    @get:OutputFile
    abstract val mappedServerJar: RegularFileProperty

    @get:OutputFile
    @get:Optional
    abstract val legacyPaperclipResult: RegularFileProperty

    override fun init() {
        super.init()
        devBundle.from(project.configurations.named(DEV_BUNDLE_CONFIG))
        decompilerConfig.from(project.configurations.named(DECOMPILER_CONFIG))
        paramMappingsConfig.from(project.configurations.named(PARAM_MAPPINGS_CONFIG))
        remapperConfig.from(project.configurations.named(REMAPPER_CONFIG))
        macheConfig.from(project.configurations.named(MACHE_CONFIG))
    }

    @TaskAction
    fun run() {
        val context = SetupHandler.ExecutionContext(
            workerExecutor,
            launcher.get(),
            layout,
            logger,
            decompilerConfig,
            paramMappingsConfig,
            macheDecompilerConfig,
            macheConfig,
            remapperConfig,
            macheRemapperConfig,
            macheParamMappingsConfig,
            macheConstantsConfig,
            macheCodebookConfig,
        )

        setupService.get().combinedOrClassesJar(context)
    }
}
