package io.papermc.paperweight.tasks

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.JsonObject
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.gson
import org.gradle.api.DefaultTask
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import util.MinecraftManifest

open class GetRemoteJsons : DefaultTask() {

    val mcpJson: MapProperty<String, Map<String, IntArray>> = project.objects.mapProperty()
    val mcManifest: Property<MinecraftManifest> = project.objects.property()
    val versionJson: Property<JsonObject> = project.objects.property()

    @TaskAction
    fun run() {
        val cache = project.cache
        val extension = project.ext

        // McpMappings.json
        val jsonCache = cache.resolve(MCP_MAPPINGS_JSON)
        val etagFile = cache.resolve("$MCP_MAPPINGS_JSON.etag")
        val mcpJsonText = getWithEtag(URLS_MCP_JSON, jsonCache, etagFile)
        mcpJson.set(gson.fromJson<Map<String, Map<String, IntArray>>>(mcpJsonText))

        // McManifest.json
        val mcManifestJson = cache.resolve(MC_MANIFEST)
        val mcManifestEtag = cache.resolve("$MC_MANIFEST.etag")
        val mcManifestText = getWithEtag(URLS_MC_MANIFEST, mcManifestJson, mcManifestEtag)
        mcManifest.set(gson.fromJson<MinecraftManifest>(mcManifestText))

        val mcVersionJson = cache.resolve(Constants.paperVersionJson(extension))
        val mcVersionEtag = mcVersionJson.resolveSibling("${mcVersionJson.name}.etag")
        val mcVersion = getWithEtag(listOf(mcManifest.get().versions.first { it.id == extension.minecraftVersion.get() }.url), mcVersionJson, mcVersionEtag)
        versionJson.set(gson.fromJson<JsonObject>(mcVersion))

        mcpJson.finalizeValue()
        mcManifest.finalizeValue()
        versionJson.finalizeValue()
    }
}
