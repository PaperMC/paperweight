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

package io.papermc.paperweight.tasks

import dev.denwav.hypo.asm.AsmClassDataProvider
import dev.denwav.hypo.core.HypoConfig
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.ChangeRegistry
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.contributors.ChangeContributor
import dev.denwav.hypo.model.ClassProviderRoot
import dev.denwav.hypo.model.data.ClassData
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.lorenz.model.ClassMapping
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class GenerateRelocatedReobfMappings : JavaLauncherTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputMappings: RegularFileProperty

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:Internal
    abstract val jvmArgs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val craftBukkitPackageVersion: Property<String>

    override fun init() {
        super.init()

        jvmArgs.convention(listOf("-Xmx2G"))
    }

    @TaskAction
    fun run() {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmArgs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        queue.submit(Action::class) {
            inputMappings.set(this@GenerateRelocatedReobfMappings.inputMappings)
            inputJar.set(this@GenerateRelocatedReobfMappings.inputJar)
            craftBukkitPackageVersion.set(this@GenerateRelocatedReobfMappings.craftBukkitPackageVersion)

            outputMappings.set(this@GenerateRelocatedReobfMappings.outputMappings)
        }
    }

    abstract class Action : WorkAction<Action.Parameters> {
        interface Parameters : WorkParameters {
            val inputMappings: RegularFileProperty
            val inputJar: RegularFileProperty
            val craftBukkitPackageVersion: Property<String>

            val outputMappings: RegularFileProperty
        }

        override fun execute() {
            val mappingsIn = MappingFormats.TINY.read(
                parameters.inputMappings.path,
                DEOBF_NAMESPACE,
                SPIGOT_NAMESPACE
            )
            val mappingsOut = HypoContext.builder()
                .withConfig(HypoConfig.builder().setRequireFullClasspath(false).withParallelism(1).build())
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(parameters.inputJar.path)))
                .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
                .build().use { hypoContext ->
                    HydrationManager.createDefault().hydrate(hypoContext)
                    ChangeChain.create()
                        .addLink(CraftBukkitRelocation(parameters.craftBukkitPackageVersion.get()))
                        .applyChain(mappingsIn, MappingsCompletionManager.create(hypoContext))
                }
            MappingFormats.TINY.write(
                mappingsOut,
                parameters.outputMappings.path,
                DEOBF_NAMESPACE,
                SPIGOT_NAMESPACE
            )
        }
    }

    class CraftBukkitRelocation(packageVersion: String) : ChangeContributor {
        companion object {
            const val PREFIX = "org/bukkit/craftbukkit/"
            const val MAIN = "${PREFIX}Main"
        }

        private val relocateTo: String = "$PREFIX$packageVersion"

        override fun name(): String = "CraftBukkitRelocation"

        override fun contribute(
            currentClass: ClassData?,
            classMapping: ClassMapping<*, *>?,
            context: HypoContext,
            registry: ChangeRegistry
        ) {
            if (currentClass == null || classMapping != null) {
                return
            }
            if (currentClass.name().startsWith(PREFIX) && !currentClass.name().startsWith(MAIN)) {
                registry.submitChange(
                    GenerateReobfMappings.AddClassMappingChange(
                        currentClass.name(),
                        "$relocateTo/${currentClass.name().substring(PREFIX.length)}"
                    )
                )
            }
        }
    }
}
