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

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

open class CraftBukkitExtension(project: Project) {
    val bukkitDir: DirectoryProperty = project.dirWithDefault("work/Bukkit")
    var craftBukkitDir: DirectoryProperty = project.dirWithDefault("work/CraftBukkit")
    var patchDir: DirectoryProperty = project.dirWithDefault("work/CraftBukkit/nms-patches")
    var sourceDir: DirectoryProperty = project.dirWithDefault("work/CraftBukkit/src/main/java")
    var mappingsDir: DirectoryProperty = project.dirWithDefault("work/BuildData/mappings")
    var buildDataInfo: RegularFileProperty = project.fileWithDefault("work/BuildData/info.json")
    var fernFlowerJar: RegularFileProperty = project.fileWithDefault("work/BuildData/bin/fernflower.jar")
    var specialSourceJar: RegularFileProperty = project.fileWithDefault("work/BuildData/bin/SpecialSource.jar")
    var specialSource2Jar: RegularFileProperty = project.fileWithDefault("work/BuildData/bin/SpecialSource-2.jar")
}
