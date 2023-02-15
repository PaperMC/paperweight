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
import dev.denwav.hypo.asm.hydrate.BridgeMethodHydrator
import dev.denwav.hypo.asm.hydrate.SuperConstructorHydrator
import dev.denwav.hypo.core.HypoContext
import dev.denwav.hypo.hydrate.HydrationManager
import dev.denwav.hypo.mappings.ChangeChain
import dev.denwav.hypo.mappings.MappingsCompletionManager
import dev.denwav.hypo.mappings.contributors.CopyMappingsDown
import dev.denwav.hypo.mappings.contributors.PropagateMappingsUp
import dev.denwav.hypo.mappings.contributors.RemoveUnusedMappings
import dev.denwav.hypo.model.ClassProviderRoot
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class CleanupMappings : JavaLauncherTask() {

    @get:Classpath
    abstract val sourceJar: RegularFileProperty

    @get:CompileClasspath
    abstract val libraries: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx1G"))
    }

    @TaskAction
    fun run() {
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        queue.submit(CleanupMappingsAction::class) {
            inputMappings.set(this@CleanupMappings.inputMappings.path)
            libraries.from(this@CleanupMappings.libraries.files)
            sourceJar.set(this@CleanupMappings.sourceJar.path)

            outputMappings.set(this@CleanupMappings.outputMappings.path)
        }
    }

    abstract class CleanupMappingsAction : WorkAction<CleanupMappingsAction.Parameters> {

        interface Parameters : WorkParameters {
            val inputMappings: RegularFileProperty
            val libraries: ConfigurableFileCollection
            val sourceJar: RegularFileProperty

            val outputMappings: RegularFileProperty
        }

        override fun execute() {
            val mappings = MappingFormats.TINY.read(
                parameters.inputMappings.path,
                SPIGOT_NAMESPACE,
                DEOBF_NAMESPACE
            )

            val cleanedMappings = HypoContext.builder()
                .withProvider(AsmClassDataProvider.of(ClassProviderRoot.fromJar(parameters.sourceJar.path)))
                .withContextProvider(AsmClassDataProvider.of(parameters.libraries.toJarClassProviderRoots()))
                .withContextProvider(AsmClassDataProvider.of(ClassProviderRoot.ofJdk()))
                .build().use { hypoContext ->
                    HydrationManager.createDefault()
                        .register(BridgeMethodHydrator.create())
                        .register(SuperConstructorHydrator.create())
                        .hydrate(hypoContext)

                    ChangeChain.create()
                        .addLink(RemoveUnusedMappings.create())
                        .addLink(PropagateMappingsUp.create())
                        .addLink(CopyMappingsDown.create())
                        .applyChain(mappings, MappingsCompletionManager.create(hypoContext))
                }

            MappingFormats.TINY.write(
                cleanedMappings,
                parameters.outputMappings.path,
                SPIGOT_NAMESPACE,
                DEOBF_NAMESPACE
            )
        }
    }
}
