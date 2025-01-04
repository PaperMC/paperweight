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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.Value
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.action.ZippedFileValue
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.workers.WorkerExecutor

class AccessTransformMinecraftAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    private val workerExecutor: WorkerExecutor,
    @Input val at: ZippedFileValue,
    @Input private val inputJar: FileValue,
    @Output val outputJar: FileValue,
) : WorkDispatcher.Action {
    override fun execute() {
        val atTmp = outputJar.get().resolveSibling("tmp.at").cleanFile()
        at.extractTo(atTmp)
        try {
            applyAccessTransform(
                inputJarPath = inputJar.get(),
                outputJarPath = outputJar.get(),
                atFilePath = atTmp,
                workerExecutor = workerExecutor,
                launcher = javaLauncher.get(),
            ).await()
        } finally {
            atTmp.deleteIfExists()
        }
    }
}
