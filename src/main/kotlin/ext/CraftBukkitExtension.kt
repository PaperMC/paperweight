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

open class CraftBukkitExtension {
    var bukkitDir: Any = "work/Bukkit"
    var craftBukkitDir: Any = "work/CraftBukkit"
    var patchDir: Any = "work/CraftBukkit/nms-patches"
    var sourceDir: Any = "work/CraftBukkit/src/main/java"
    var mappingsDir: Any = "work/BuildData/mappings"
    var buildDataInfo: Any = "work/BuildData/info.json"
    var fernFlowerJar: Any = "work/BuildData/bin/fernflower.jar"
    var specialSourceJar: Any = "work/BuildData/bin/SpecialSource.jar"
    var specialSource2Jar: Any = "work/BuildData/bin/SpecialSource-2.jar"
}
