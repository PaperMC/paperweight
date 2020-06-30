/*
 * Copyright 2018 Kyle Wood
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.papermc.paperweight.ext

import com.google.gson.JsonObject
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import util.BuildDataInfo
import util.MinecraftManifest

open class PaperweightExtension(project: Project) {

    val minecraftVersion: Property<String> = project.objects.property()
    val mcpVersion: Property<String> = project.objects.property()

    val craftBukkit: CraftBukkitExtension = CraftBukkitExtension(project)
    val spigot: SpigotExtension = SpigotExtension(project)
    val paper: PaperExtension = PaperExtension(project)

    fun craftBukkit(action: Action<in CraftBukkitExtension>) {
        action.execute(craftBukkit)
    }

    fun spigot(action: Action<in SpigotExtension>) {
        action.execute(spigot)
    }

    fun paper(action: Action<in PaperExtension>) {
        action.execute(paper)
    }

    val mcpVersionProvider: Provider<String>
        get() = mcpVersion.map { it.toLowerCase() }
}
