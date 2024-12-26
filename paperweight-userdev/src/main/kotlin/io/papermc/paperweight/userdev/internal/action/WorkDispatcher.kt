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

package io.papermc.paperweight.userdev.internal.action

import java.nio.file.Path

interface WorkDispatcher {
    fun outputFile(name: String): FileValue

    fun outputDir(name: String): DirectoryValue

    fun provided(value: Value<*>)

    fun provided(vararg values: Value<*>) = values.forEach { provided(it) }

    fun <T : Action> register(name: String, workUnit: T): T

    fun <T : Action> registered(name: String): T

    fun overrideTerminalInputHash(hash: String)

    fun dispatch(vararg targets: Value<*>, progressEventListener: (String) -> Unit = {})

    interface Action {
        fun execute()
    }

    companion object {
        fun create(work: Path): WorkDispatcher = WorkDispatcherImpl(work)
    }
}
