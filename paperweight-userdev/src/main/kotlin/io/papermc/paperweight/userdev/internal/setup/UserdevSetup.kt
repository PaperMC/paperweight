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

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.userdev.internal.setup.util.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener

abstract class UserdevSetup : BuildService<UserdevSetup.Parameters>, SetupHandler, AutoCloseable, OperationCompletionListener {

    companion object {
        val LOGGER: Logger = Logging.getLogger(UserdevSetup::class.java)
    }

    interface Parameters : BuildServiceParameters {
        val bundleZip: RegularFileProperty
        val bundleZipHash: Property<String>
        val cache: RegularFileProperty
        val downloadService: Property<DownloadService>
        val genSources: Property<Boolean>
    }

    private val extractDevBundle: ExtractedBundle<Any> = lockSetup(parameters.cache.path) {
        val extract = extractDevBundle(
            parameters.cache.path.resolve(paperSetupOutput("extractDevBundle", "dir")),
            parameters.bundleZip.path,
            parameters.bundleZipHash.get()
        )
        lastUsedFile(parameters.cache.path).writeText(System.currentTimeMillis().toString())
        extract
    }

    private val setup = createSetup()

    private fun createSetup(): SetupHandler =
        SetupHandler.create(parameters, extractDevBundle)

    override fun onFinish(event: FinishEvent?) {
        // no-op, a workaround to keep the service alive for the entire build
        // see https://github.com/diffplug/spotless/pull/720#issuecomment-713399731
    }

    override fun close() {
        // see comments in onFinish
    }

    // begin delegate to setup
    override fun populateCompileConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        setup.populateCompileConfiguration(context, dependencySet)
    }

    override fun populateRuntimeConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        setup.populateRuntimeConfiguration(context, dependencySet)
    }

    override fun combinedOrClassesJar(context: SetupHandler.ExecutionContext): Path {
        return setup.combinedOrClassesJar(context)
    }

    override fun afterEvaluate(project: Project) {
        setup.afterEvaluate(project)
    }

    override val reobfMappings: Path
        get() = setup.reobfMappings

    override val minecraftVersion: String
        get() = setup.minecraftVersion

    override val pluginRemapArgs: List<String>
        get() = setup.pluginRemapArgs

    override val paramMappings: MavenDep?
        get() = setup.paramMappings

    override val decompiler: MavenDep?
        get() = setup.decompiler

    override val remapper: MavenDep
        get() = setup.remapper

    override val mache: MavenDep?
        get() = setup.mache

    override val libraryRepositories: List<String>
        get() = setup.libraryRepositories
    // end delegate to setup
}
