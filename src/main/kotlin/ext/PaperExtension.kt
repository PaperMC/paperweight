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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

open class PaperExtension(project: Project) {
    val spigotApiPatchDir: Property<String> = project.objects.property<String>().convention("Spigot-API-Patches")
    val spigotServerPatchDir: Property<String> = project.objects.property<String>().convention("Spigot-Server-Patches")
    val paperApiDir: Property<String> = project.objects.property<String>().convention("Paper-API")
    val paperServerDir: Property<String> = project.objects.property<String>().convention("Paper-Server")

    val mcpRewritesFile: RegularFileProperty = project.fileWithDefault("mcp/mcp-rewrites.txt")
    val preMapSrgFile: RegularFileProperty = project.fileWithDefault("mcp/paper.srg")
    val removeListFile: RegularFileProperty = project.fileWithDefault("mcp/remove-list.txt")
    val memberMoveListFile: RegularFileProperty = project.fileWithDefault("mcp/member-moves.txt")
}
