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

package io.papermc.paperweight.userdev

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.RepositoryContentDescriptor
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.AbstractArchiveTask
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
        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        val cleanCache by target.tasks.registering<Delete> {
            group = "paperweight"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(DEV_BUNDLE_CONFIG)

        // these must not be initialized until afterEvaluate, as they resolve the dev bundle
        val userdevSetup by lazy { UserdevSetup(target, workerExecutor, javaToolchainService) }
        val devBundleConfig by lazy { userdevSetup.devBundleConfig }

        val userdev = target.extensions.create(
            PAPERWEIGHT_EXTENSION,
            PaperweightUserExtension::class,
            target,
            target.provider { userdevSetup },
            target.objects
        )

        createConfigurations(target, target.provider { userdevSetup })

        val reobfJar by target.tasks.registering<RemapJar> {
            group = "paperweight"
            description = "Remap the compiled plugin jar to Spigot's obfuscated runtime names."

            outputJar.convention(project.layout.buildDirectory.file("libs/${project.name}-${project.version}.jar"))

            mappingsFile.pathProvider(target.provider { userdevSetup.extractedBundle.resolve(devBundleConfig.buildData.reobfMappingsFile) })
            remapClasspath.from(target.provider { userdevSetup.mojangMappedPaperJar })

            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(SPIGOT_NAMESPACE)

            remapper.from(project.configurations.named(REMAPPER_CONFIG))
        }

        target.afterEvaluate {
            // Manually check if cleanCache is a target, and skip setup.
            // Gradle moved NameMatcher to internal packages in 7.1, so this solution isn't ideal,
            // but it does work and allows using the cleanCache task without setting up the workspace first
            val cleaningCache = gradle.startParameter.taskRequests
                .any { req ->
                    req.args.any { arg ->
                        NameMatcher().find(arg, tasks.names) == cleanCache.name
                    }
                }
            if (cleaningCache) return@afterEvaluate

            checkForDevBundle() // Print a friendly error message if the dev bundle is missing before we call anything that will try and resolve it

            val jar = tasks.named<AbstractArchiveTask>("jar") {
                archiveClassifier.set("dev")
            }
            val devJarTask = if (tasks.findByName("shadowJar") != null) {
                tasks.named<AbstractArchiveTask>("shadowJar") {
                    archiveClassifier.set("dev-all")
                }
            } else {
                jar
            }
            reobfJar {
                inputJar.set(devJarTask.flatMap { it.archiveFile })
            }

            configureRepositories(userdev, devBundleConfig)
        }
    }

    private fun Project.configureRepositories(
        userdev: PaperweightUserExtension,
        devBundleConfig: GenerateDevBundle.DevBundleConfig
    ) = repositories {
        if (userdev.injectPaperRepository.get()) {
            maven(PAPER_MAVEN_REPO_URL) {
                content { onlyForConfigurations(DEV_BUNDLE_CONFIG) }
            }
        }

        maven(devBundleConfig.buildData.paramMappings.url) {
            content { onlyForConfigurations(PARAM_MAPPINGS_CONFIG) }
        }
        maven(devBundleConfig.remap.dep.url) {
            content { onlyForConfigurations(REMAPPER_CONFIG) }
        }
        maven(devBundleConfig.decompile.dep.url) {
            content { onlyForConfigurations(DECOMPILER_CONFIG) }
        }
        for (repo in devBundleConfig.buildData.libraryRepositories) {
            maven(repo) {
                content { onlyForConfigurations(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME) }
            }
        }

        ivy(layout.cache.resolve(IVY_REPOSITORY)) {
            content {
                includeFromDependencyNotation(devBundleConfig.mappedServerCoordinates)
            }
            patternLayout {
                artifact(IvyArtifactRepository.MAVEN_ARTIFACT_PATTERN)
                ivy(IvyArtifactRepository.MAVEN_IVY_PATTERN)
                setM2compatible(true)
            }
            metadataSources(IvyArtifactRepository.MetadataSources::ivyDescriptor)
            isAllowInsecureProtocol = true
            resolve.isDynamicMode = false
        }
    }

    private fun Project.checkForDevBundle() {
        if (configurations.getByName(DEV_BUNDLE_CONFIG).isEmpty) {
            val message = "paperweight requires a development bundle to be added to the 'paperweightDevelopmentBundle' configuration in" +
                " order to function. Use the paperweightDevBundle extension function to do this easily."
            throw PaperweightException(message)
        }
    }

    private fun createConfigurations(
        target: Project,
        userdevSetup: Provider<UserdevSetup>
    ) {
        val devBundleConfig by lazy { userdevSetup.get().devBundleConfig }

        target.configurations.create(DECOMPILER_CONFIG) {
            defaultDependencies {
                for (dep in devBundleConfig.decompile.dep.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        target.configurations.create(PARAM_MAPPINGS_CONFIG) {
            defaultDependencies {
                for (dep in devBundleConfig.buildData.paramMappings.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        target.configurations.create(REMAPPER_CONFIG) {
            isTransitive = false // we use a fat jar for tiny-remapper, so we don't need it's transitive deps (which aren't on the quilt repo)
            defaultDependencies {
                for (dep in devBundleConfig.remap.dep.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        target.configurations.create(MINECRAFT_LIBRARIES_CONFIG) {
            exclude("junit", "junit") // json-simple exposes junit for some reason
            defaultDependencies {
                for (dep in devBundleConfig.buildData.libraryDependencies) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        target.configurations.create(PAPER_API_CONFIG) {
            defaultDependencies {
                add(target.dependencies.create(devBundleConfig.apiCoordinates))
                add(target.dependencies.create(devBundleConfig.mojangApiCoordinates))
            }
        }

        target.configurations.create(MOJANG_MAPPED_SERVER_CONFIG) {
            defaultDependencies {
                userdevSetup.get().installServerArtifactToIvyRepository(target.layout.cache.resolve(IVY_REPOSITORY))
                add(target.dependencies.create(devBundleConfig.mappedServerCoordinates))
            }
        }

        target.plugins.withType<JavaPlugin> {
            target.configurations.named(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME) {
                extendsFrom(
                    target.configurations.getByName(MOJANG_MAPPED_SERVER_CONFIG),
                    target.configurations.getByName(MINECRAFT_LIBRARIES_CONFIG),
                    target.configurations.getByName(PAPER_API_CONFIG)
                )
            }
        }
    }

    private fun RepositoryContentDescriptor.includeFromDependencyNotation(dependencyNotation: String) {
        val split = dependencyNotation.split(':')
        when {
            split.size == 1 -> includeGroup(split[0])
            split.size == 2 -> includeModule(split[0], split[1])
            split.size >= 3 -> {
                includeModule(split[0], split[1])
                includeVersion(split[0], split[1], split[2])
            }
        }
    }
}
