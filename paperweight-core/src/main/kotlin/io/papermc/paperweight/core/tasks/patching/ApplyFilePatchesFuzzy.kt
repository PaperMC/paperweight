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

package io.papermc.paperweight.core.tasks.patching

import io.codechicken.diffpatch.util.PatchMode
import io.papermc.paperweight.core.util.defaultMinFuzz
import javax.inject.Inject
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

@UntrackedTask(because = "Always apply patches")
abstract class ApplyFilePatchesFuzzy : ApplyFilePatches() {

    @get:Inject
    abstract val providers: ProviderFactory

    @get:Input
    @get:Option(
        option = "min-fuzz",
        description = "Min fuzz. The minimum quality needed for a patch to be applied. Default is 0.5.",
    )
    abstract val minFuzz: Property<String>

    init {
        run {
            minFuzz.convention(providers.defaultMinFuzz())
        }
    }

    override fun mode(): PatchMode {
        return PatchMode.FUZZY
    }

    override fun minFuzz(): Float {
        return minFuzz.get().toFloat()
    }
}
