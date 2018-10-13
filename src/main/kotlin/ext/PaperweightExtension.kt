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
import io.papermc.paperweight.util.Constants.DEFAULT_STRING
import org.gradle.api.Action
import util.BuildDataInfo
import util.MinecraftManifest

open class PaperweightExtension {

    var minecraftVersion: String = DEFAULT_STRING
    var mcpVersion: String = DEFAULT_STRING
        set(value) {
            field = value.toLowerCase()
        }

    internal lateinit var mcpMinecraftVersion: String
    internal lateinit var mcpChannel: String
    internal var mappingsVersion: Int = 0
    internal lateinit var mcpJson: Map<String, Map<String, IntArray>>
    internal lateinit var mcManifest: MinecraftManifest
    internal lateinit var buildDataInfo: BuildDataInfo
    internal lateinit var versionJson: JsonObject

    var craftBukkit: CraftBukkitExtension = CraftBukkitExtension()
    var spigot: SpigotExtension = SpigotExtension()
    var paper: PaperExtension = PaperExtension()

    fun craftBukkit(action: Action<in CraftBukkitExtension>) {
        action.execute(craftBukkit)
    }

    fun spigot(action: Action<in SpigotExtension>) {
        action.execute(spigot)
    }

    fun paper(action: Action<in PaperExtension>) {
        action.execute(paper)
    }
}
