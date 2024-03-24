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

package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.util.patches.*
import io.papermc.paperweight.util.patches.Patcher
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

@UntrackedTask(because = "Always apply patches")
abstract class ApplyFilePatchesFuzzy : ApplyFilePatches() {

    @get:Input
    @get:Option(
        option = "max-fuzz",
        description = "Max fuzz. Cannot be set higher than context (3). Setting this " +
            "value higher increases the chances of a faulty patch.",
    )
    abstract val maxFuzz: Property<String>

    override fun createPatcher(): Patcher {
        return if (useNativeDiff.get()) {
            NativePatcherFuzzy(exec, patchExecutable.get(), maxFuzz.get().toInt())
        } else {
            JavaPatcherFuzzy(maxFuzz.get().toInt())
        }
    }
}
