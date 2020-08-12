/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

import io.papermc.paperweight.util.Command
import io.papermc.paperweight.util.UselessOutputStream
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Console

abstract class ControllableOutputTask : BaseTask() {

    @get:Console
    abstract val printOutput: Property<Boolean>

    fun Command.setupOut(showError: Boolean = true) = apply {
        if (printOutput.get()) {
            val err = if (showError) System.out else UselessOutputStream
            setup(System.out, err)
        } else {
            setup(UselessOutputStream, UselessOutputStream)
        }
    }

    fun Command.showErrors() = apply {
        if (printOutput.get()) {
            setup(System.out, System.out)
        } else {
            setup(UselessOutputStream, System.out)
        }
    }
}
