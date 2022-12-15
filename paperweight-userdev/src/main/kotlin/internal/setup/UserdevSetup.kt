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

package io.papermc.paperweight.userdev.internal.setup

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.userdev.internal.setup.util.lockSetup
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.kotlin.dsl.*

abstract class UserdevSetup : BuildService<UserdevSetup.Parameters>, SetupHandler {

    companion object {
        val LOGGER: Logger = Logging.getLogger(UserdevSetup::class.java)
    }

    interface Parameters : BuildServiceParameters {
        val bundleZip: RegularFileProperty
        val cache: RegularFileProperty
        val downloadService: Property<DownloadService>
        val genSources: Property<Boolean>
    }

    private val extractDevBundle: ExtractedBundle<Any> = lockSetup(parameters.cache.path) {
        extractDevBundle(
            parameters.cache.path.resolve(paperSetupOutput("extractDevBundle", "dir")),
            parameters.bundleZip.path
        )
    }

    private val setup = createSetup()

    private fun createSetup(): SetupHandler =
        SetupHandler.create(parameters, extractDevBundle)

    fun addIvyRepository(project: Project) {
        project.repositories {
            setupIvyRepository(parameters.cache.path.resolve(IVY_REPOSITORY)) {
                configureIvyRepo(this)
            }
        }
    }

    // begin delegate to setup
    override fun createOrUpdateIvyRepository(context: SetupHandler.Context) {
        setup.createOrUpdateIvyRepository(context)
    }

    override fun configureIvyRepo(repo: IvyArtifactRepository) {
        setup.configureIvyRepo(repo)
    }

    override fun populateCompileConfiguration(context: SetupHandler.Context, dependencySet: DependencySet) {
        setup.populateCompileConfiguration(context, dependencySet)
    }

    override fun populateRuntimeConfiguration(context: SetupHandler.Context, dependencySet: DependencySet) {
        setup.populateRuntimeConfiguration(context, dependencySet)
    }

    override fun serverJar(context: SetupHandler.Context): Path {
        return setup.serverJar(context)
    }

    override val serverJar: Path
        get() = setup.serverJar

    override val reobfMappings: Path
        get() = setup.reobfMappings

    override val minecraftVersion: String
        get() = setup.minecraftVersion

    override val pluginRemapArgs: List<String>
        get() = setup.pluginRemapArgs

    override val paramMappings: MavenDep
        get() = setup.paramMappings

    override val decompiler: MavenDep
        get() = setup.decompiler

    override val remapper: MavenDep
        get() = setup.remapper

    override val libraryRepositories: List<String>
        get() = setup.libraryRepositories
    // end delegate to setup
}
