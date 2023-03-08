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

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.attribute.Obfuscation
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.util.genSources
import io.papermc.paperweight.userdev.internal.setup.util.paperweightHash
import io.papermc.paperweight.userdev.internal.setup.util.sharedCaches
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.*
import org.gradle.util.internal.NameMatcher
import org.gradle.workers.WorkerExecutor

abstract class PaperweightUser : Plugin<Project> {
    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Inject
    abstract val javaToolchainService: JavaToolchainService

    override fun apply(target: Project) {
        val sharedCacheRootRoot = target.gradle.gradleUserHomeDir.toPath().resolve("caches/paperweight-userdev")
        val sharedCacheRoot = if (target.sharedCaches) sharedCacheRootRoot.resolve(paperweightHash) else null

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        val cleanAllTaskName = "cleanAllPaperweightUserdevCaches"
        if (target.sharedCaches) {
            target.tasks.register<Delete>(cleanAllTaskName) {
                group = "paperweight"
                description = "Delete the project & shared paperweight-userdev setup cache."
                delete(target.layout.cache)
                delete(sharedCacheRootRoot)
            }
        }
        val cleanCache by target.tasks.registering<Delete> {
            group = "paperweight"
            description = "Delete the project paperweight-userdev setup cache."
            delete(target.layout.cache)
        }

        target.configurations.register(DEV_BUNDLE_CONFIG)

        // must not be initialized until afterEvaluate, as it resolves the dev bundle
        val userdevSetup by lazy { createSetup(target, sharedCacheRoot) }

        val userdev = target.extensions.create(
            PAPERWEIGHT_EXTENSION,
            PaperweightUserExtension::class,
            target,
            workerExecutor,
            javaToolchainService,
            target.provider { userdevSetup },
            target.objects
        )

        target.dependencies.extensions.create(
            PAPERWEIGHT_EXTENSION,
            PaperweightUserDependenciesExtension::class,
            target.dependencies
        )

        createConfigurations(target, target.provider { userdevSetup })

        val reobfJar by target.tasks.registering<RemapJar> {
            group = "paperweight"
            description = "Remap the compiled plugin jar to Spigot's obfuscated runtime names."

            mappingsFile.pathProvider(target.provider { userdevSetup.reobfMappings })
            remapClasspath.from(target.provider { userdevSetup.serverJar })

            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(SPIGOT_NAMESPACE)

            remapper.from(project.configurations.named(REMAPPER_CONFIG))
            remapperArgs.set(target.provider { userdevSetup.pluginRemapArgs })
        }

        target.configurations.register(REOBF_CONFIG) {
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, target.objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, target.objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, target.objects.named(LibraryElements.JAR))
                attribute(Bundling.BUNDLING_ATTRIBUTE, target.objects.named(Bundling.EXTERNAL))
                attribute(Obfuscation.OBFUSCATION_ATTRIBUTE, target.objects.named(Obfuscation.OBFUSCATED))
            }
            outgoing.artifact(reobfJar)
        }

        target.afterEvaluate {
            // Manually check if cleanCache is a target, and skip setup.
            // Gradle moved NameMatcher to internal packages in 7.1, so this solution isn't ideal,
            // but it does work and allows using the cleanCache task without setting up the workspace first
            val cleaningCache = gradle.startParameter.taskRequests
                .any { req ->
                    req.args.any { arg ->
                        NameMatcher().find(arg, tasks.names) in setOf(cleanCache.name, cleanAllTaskName)
                    }
                }
            if (cleaningCache) {
                return@afterEvaluate
            }

            userdev.reobfArtifactConfiguration.get()
                .configure(target, reobfJar)

            if (userdev.injectPaperRepository.get()) {
                target.repositories.maven(PAPER_MAVEN_REPO_URL) {
                    content { onlyForConfigurations(DEV_BUNDLE_CONFIG) }
                }
            }

            // Print a friendly error message if the dev bundle is missing before we call anything else that will try and resolve it
            checkForDevBundle()

            configureRepositories(userdevSetup)
        }
    }

    private fun Project.configureRepositories(userdevSetup: UserdevSetup) = repositories {
        maven(userdevSetup.paramMappings.url) {
            name = PARAM_MAPPINGS_REPO_NAME
            content { onlyForConfigurations(PARAM_MAPPINGS_CONFIG) }
        }
        maven(userdevSetup.remapper.url) {
            name = REMAPPER_REPO_NAME
            content { onlyForConfigurations(REMAPPER_CONFIG) }
        }
        maven(userdevSetup.decompiler.url) {
            name = DECOMPILER_REPO_NAME
            content { onlyForConfigurations(DECOMPILER_CONFIG) }
        }
        for (repo in userdevSetup.libraryRepositories) {
            maven(repo)
        }

        userdevSetup.addIvyRepository(project)
    }

    private fun Project.checkForDevBundle() {
        val hasDevBundle = runCatching {
            !configurations.getByName(DEV_BUNDLE_CONFIG).isEmpty
        }
        if (hasDevBundle.isFailure || !hasDevBundle.getOrThrow()) {
            val message = "paperweight requires a development bundle to be added to the 'paperweightDevelopmentBundle' configuration, as" +
                " well as a repository to resolve it from in order to function. Use the paperweightDevBundle extension function to do this easily."
            throw PaperweightException(
                message,
                hasDevBundle.exceptionOrNull()?.let { PaperweightException("Failed to resolve dev bundle", it) }
            )
        }
    }

    private fun createConfigurations(
        target: Project,
        userdevSetup: Provider<UserdevSetup>
    ) {
        target.configurations.register(DECOMPILER_CONFIG) {
            defaultDependencies {
                for (dep in userdevSetup.get().decompiler.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        target.configurations.register(PARAM_MAPPINGS_CONFIG) {
            defaultDependencies {
                for (dep in userdevSetup.get().paramMappings.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        target.configurations.register(REMAPPER_CONFIG) {
            isTransitive = false // we use a fat jar for tiny-remapper, so we don't need it's transitive deps
            defaultDependencies {
                for (dep in userdevSetup.get().remapper.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        val mojangMappedServerConfig = target.configurations.register(MOJANG_MAPPED_SERVER_CONFIG) {
            exclude("junit", "junit") // json-simple exposes junit for some reason
            defaultDependencies {
                val ctx = createContext(target)
                userdevSetup.get().let { setup ->
                    setup.createOrUpdateIvyRepository(ctx)
                    setup.populateCompileConfiguration(ctx, this)
                }
            }
        }

        target.plugins.withType<JavaPlugin>().configureEach {
            listOf(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME
            ).map(target.configurations::named).forEach { config ->
                config {
                    extendsFrom(mojangMappedServerConfig.get())
                }
            }
        }

        target.configurations.register(MOJANG_MAPPED_SERVER_RUNTIME_CONFIG) {
            defaultDependencies {
                val ctx = createContext(target)
                userdevSetup.get().let { setup ->
                    setup.createOrUpdateIvyRepository(ctx)
                    setup.populateRuntimeConfiguration(ctx, this)
                }
            }
        }
    }

    private fun createContext(project: Project): SetupHandler.Context =
        SetupHandler.Context(project, workerExecutor, javaToolchainService)

    private fun createSetup(target: Project, sharedCacheRoot: Path?): UserdevSetup {
        val bundleConfig = target.configurations.named(DEV_BUNDLE_CONFIG)
        val devBundleZip = bundleConfig.map { it.singleFile }.convertToPath()
        val bundleHash = devBundleZip.sha256asHex()
        val cacheDir = if (sharedCacheRoot == null) {
            target.layout.cache
        } else {
            when (bundleConfig.get().dependencies.single()) {
                is ProjectDependency -> {
                    throw PaperweightException("Shared caches does not support the dev bundle being a ProjectDependency.")
                }

                is ModuleDependency -> {
                    val resolved =
                        bundleConfig.get().incoming.resolutionResult.rootComponent.get().dependencies.single() as ResolvedDependencyResult
                    val resolvedId = resolved.selected.id as ModuleComponentIdentifier
                    sharedCacheRoot.resolve("module/${resolvedId.group}/${resolvedId.module}/${resolvedId.version}")
                }

                else -> sharedCacheRoot.resolve("non-module/$bundleHash")
            }
        }

        val serviceName = "paperweight-userdev:setupService:$bundleHash"
        return target.gradle.sharedServices
            .registerIfAbsent(serviceName, UserdevSetup::class) {
                parameters {
                    cache.set(cacheDir)
                    bundleZip.set(devBundleZip)
                    downloadService.set(target.download)
                    genSources.set(target.genSources)
                }
            }
            .get()
    }
}
