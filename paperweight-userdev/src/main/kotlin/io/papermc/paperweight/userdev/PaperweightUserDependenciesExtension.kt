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

import io.papermc.paperweight.util.constants.*
import org.gradle.api.Action
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.*

abstract class PaperweightUserDependenciesExtension(
    private val dependencies: DependencyHandler
) {
    /**
     * Adds a dependency on Paper's dev bundle to the dev bundle [org.gradle.api.artifacts.Configuration].
     *
     * @param version dependency version
     * @param group dependency group
     * @param artifactId dependency artifactId
     * @param configuration dependency configuration
     * @param classifier dependency classifier
     * @param ext dependency extension
     * @param devBundleConfigurationName name of the dev bundle [org.gradle.api.artifacts.Configuration]
     * @param configurationAction action configuring the dependency
     * @return dependency
     */
    @JvmOverloads
    fun paperDevBundle(
        version: String? = null,
        group: String = "io.papermc.paper",
        artifactId: String = "dev-bundle",
        configuration: String? = null,
        classifier: String? = null,
        ext: String? = null,
        devBundleConfigurationName: String = DEV_BUNDLE_CONFIG,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ): ExternalModuleDependency {
        val dep = dependencies.create(group, artifactId, version, configuration, classifier, ext)
        configurationAction(dep)
        dependencies.add(devBundleConfigurationName, dep)
        return dep
    }

    /**
     * Adds a dependency on Folia's dev bundle to the dev bundle [org.gradle.api.artifacts.Configuration].
     *
     * @param version dependency version
     * @param group dependency group
     * @param artifactId dependency artifactId
     * @param configuration dependency configuration
     * @param classifier dependency classifier
     * @param ext dependency extension
     * @param devBundleConfigurationName name of the dev bundle [org.gradle.api.artifacts.Configuration]
     * @param configurationAction action configuring the dependency
     * @return dependency
     */
    @JvmOverloads
    fun foliaDevBundle(
        version: String? = null,
        group: String = "dev.folia",
        artifactId: String = "dev-bundle",
        configuration: String? = null,
        classifier: String? = null,
        ext: String? = null,
        devBundleConfigurationName: String = DEV_BUNDLE_CONFIG,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ): ExternalModuleDependency {
        val dep = dependencies.create(group, artifactId, version, configuration, classifier, ext)
        configurationAction(dep)
        dependencies.add(devBundleConfigurationName, dep)
        return dep
    }

    /**
     * Adds a dependency to the dev bundle [org.gradle.api.artifacts.Configuration].
     *
     * @param group dependency group
     * @param version dependency version
     * @param artifactId dependency artifactId
     * @param configuration dependency configuration
     * @param classifier dependency classifier
     * @param ext dependency extension
     * @param devBundleConfigurationName name of the dev bundle [org.gradle.api.artifacts.Configuration]
     * @param configurationAction action configuring the dependency
     * @return dependency
     */
    @JvmOverloads
    fun devBundle(
        group: String,
        version: String? = null,
        artifactId: String = "dev-bundle",
        configuration: String? = null,
        classifier: String? = null,
        ext: String? = null,
        devBundleConfigurationName: String = DEV_BUNDLE_CONFIG,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ): ExternalModuleDependency {
        val dep = dependencies.create(group, artifactId, version, configuration, classifier, ext)
        configurationAction(dep)
        dependencies.add(devBundleConfigurationName, dep)
        return dep
    }

    /**
     * Adds a dependency to the [DEV_BUNDLE_CONFIG] configuration.
     *
     * Intended for use with Gradle version catalogs.
     *
     * @param bundle dev bundle dependency provider
     * @param configurationAction action configuring the dependency
     */
    @JvmOverloads
    fun devBundle(
        bundle: Provider<MinimalExternalModuleDependency>,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ) {
        dependencies.addProvider(DEV_BUNDLE_CONFIG, bundle, configurationAction)
    }

    /**
     * Adds a dependency on the Paper dev bundle to the [DEV_BUNDLE_CONFIG] configuration.
     *
     * Intended for use with Gradle version catalogs.
     *
     * @param version version provider
     * @param configurationAction action configuring the dependency
     */
    @JvmOverloads
    fun paperDevBundle(
        version: Provider<String>,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ) {
        dependencies.addProvider(DEV_BUNDLE_CONFIG, version.map { "io.papermc.paper:dev-bundle:$it" }, configurationAction)
    }

    /**
     * Adds a dependency on the Folia dev bundle to the [DEV_BUNDLE_CONFIG] configuration.
     *
     * Intended for use with Gradle version catalogs.
     *
     * @param version version provider
     * @param configurationAction action configuring the dependency
     */
    @JvmOverloads
    fun foliaDevBundle(
        version: Provider<String>,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ) {
        dependencies.addProvider(DEV_BUNDLE_CONFIG, version.map { "dev.folia:dev-bundle:$it" }, configurationAction)
    }

    /**
     * Creates a Folia dev bundle dependency without adding it to any configurations.
     *
     * @param version dependency version
     * @param group dependency group
     * @param artifactId dependency artifactId
     * @param configuration dependency configuration
     * @param classifier dependency classifier
     * @param ext dependency extension
     * @param configurationAction action configuring the dependency
     * @return dependency
     */
    @JvmOverloads
    fun foliaDevBundleDependency(
        version: String? = null,
        group: String = "dev.folia",
        artifactId: String = "dev-bundle",
        configuration: String? = null,
        classifier: String? = null,
        ext: String? = null,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ): ExternalModuleDependency {
        val dep = dependencies.create(group, artifactId, version, configuration, classifier, ext)
        configurationAction(dep)
        return dep
    }

    /**
     * Creates a Paper dev bundle dependency without adding it to any configurations.
     *
     * @param version dependency version
     * @param group dependency group
     * @param artifactId dependency artifactId
     * @param configuration dependency configuration
     * @param classifier dependency classifier
     * @param ext dependency extension
     * @param configurationAction action configuring the dependency
     * @return dependency
     */
    @JvmOverloads
    fun paperDevBundleDependency(
        version: String? = null,
        group: String = "io.papermc.paper",
        artifactId: String = "dev-bundle",
        configuration: String? = null,
        classifier: String? = null,
        ext: String? = null,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ): ExternalModuleDependency {
        val dep = dependencies.create(group, artifactId, version, configuration, classifier, ext)
        configurationAction(dep)
        return dep
    }

    /**
     * Creates a dev bundle dependency without adding it to any configurations.
     *
     * @param group dependency group
     * @param version dependency version
     * @param artifactId dependency artifactId
     * @param configuration dependency configuration
     * @param classifier dependency classifier
     * @param ext dependency extension
     * @param configurationAction action configuring the dependency
     * @return dependency
     */
    @JvmOverloads
    fun devBundleDependency(
        group: String,
        version: String? = null,
        artifactId: String = "dev-bundle",
        configuration: String? = null,
        classifier: String? = null,
        ext: String? = null,
        configurationAction: Action<ExternalModuleDependency> = nullAction()
    ): ExternalModuleDependency {
        val dep = dependencies.create(group, artifactId, version, configuration, classifier, ext)
        configurationAction(dep)
        return dep
    }

    @Suppress("unchecked_cast")
    private fun <T : Any> nullAction(): Action<T> {
        return NullAction as Action<T>
    }

    private object NullAction : Action<Any> {
        override fun execute(t: Any) {}
    }
}
