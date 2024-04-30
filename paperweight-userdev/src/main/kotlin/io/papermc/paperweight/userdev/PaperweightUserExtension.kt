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

import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.util.*
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

/**
 * Extension exposing configuration and other APIs for paperweight userdev.
 */
abstract class PaperweightUserExtension(
    project: Project,
    workerExecutor: WorkerExecutor,
    javaToolchainService: JavaToolchainService,
    setup: Provider<SetupHandler>,
    objects: ObjectFactory
) {
    /**
     * Whether to inject the Paper maven repository for use by the dev bundle configuration.
     *
     * True by default to allow easily resolving Paper development bundles.
     */
    val injectPaperRepository: Property<Boolean> = objects.property<Boolean>().convention(true)

    /**
     * Whether to patch dependencies to exclude `junit:junit` from the transitive dependencies of `com.googlecode.json-simple:json-simple`.
     *
     * True by default to avoid `junit:junit` appearing on the `compileClasspath` with older versions of Paper.
     */
    val applyJunitExclusionRule: Property<Boolean> = objects.property<Boolean>().convention(true)

    /**
     * The [ReobfArtifactConfiguration] is responsible for setting the input and output jars for `reobfJar`,
     * as well as changing the classifiers of other jars (i.e. `jar` or `shadowJar`).
     */
    val reobfArtifactConfiguration: Property<ReobfArtifactConfiguration> = objects.property<ReobfArtifactConfiguration>()
        .convention(ReobfArtifactConfiguration.REOBF_PRODUCTION)

    /**
     * Provides a runnable Mojang mapped server jar, extracted from the current dev bundle.
     */
    @Deprecated(
        message = "As of 1.18, the dev bundle no longer contains a runnable server jar. Use the mojangMappedServerRuntime configuration instead.",
        replaceWith = ReplaceWith("project.configurations.mojangMappedServerRuntime"),
        level = DeprecationLevel.WARNING
    )
    val mojangMappedServerJar: Provider<RegularFile> = objects.fileProperty().pathProvider(
        setup.map { it.serverJar(SetupHandler.Context(project, workerExecutor, javaToolchainService)) }
    ).withDisallowChanges().withDisallowUnsafeRead()

    /**
     * Provides the Minecraft version of the current dev bundle.
     */
    val minecraftVersion: Provider<String> = objects.property<String>().value(
        setup.map { it.minecraftVersion }
    ).withDisallowChanges()
}
