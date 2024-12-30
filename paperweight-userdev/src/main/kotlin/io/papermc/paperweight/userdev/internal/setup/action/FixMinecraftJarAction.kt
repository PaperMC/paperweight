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

package io.papermc.paperweight.userdev.internal.setup.action

import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.Value
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.util.fixJar
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.workers.WorkerExecutor

class FixMinecraftJarAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    private val workerExecutor: WorkerExecutor,
    @Input private val inputJar: FileValue,
    @Output val outputJar: FileValue,
    @Input private val vanillaServerJar: FileValue,
    private val useLegacyParameterAnnotationFixer: Boolean = false,
) : WorkDispatcher.Action {
    override fun execute() {
        fixJar(
            workerExecutor = workerExecutor,
            launcher = javaLauncher.get(),
            vanillaJarPath = vanillaServerJar.get(),
            inputJarPath = inputJar.get(),
            outputJarPath = outputJar.get(),
            useLegacyParameterAnnotationFixer = useLegacyParameterAnnotationFixer
        ).await()
    }
}
