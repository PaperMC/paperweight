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
import io.papermc.paperweight.userdev.internal.action.ListValue
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.util.*

class FilterVanillaJarAction(
    @Input private val vanillaJar: FileValue,
    @Input val includes: ListValue<String>,
    @Output val outputJar: FileValue,
) : WorkDispatcher.Action {
    override fun execute() {
        filterJar(vanillaJar.get(), outputJar.get(), includes.get())
    }
}
