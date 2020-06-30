package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.rawMappingsChannel
import io.papermc.paperweight.util.searchArray
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property

open class ValidateConfig : DefaultTask() {

    @Input
    val minecraftVersion = project.objects.property<String>()
    @Input
    val mcpVersion = project.objects.property<String>()
    @Input
    val mcpJson = project.objects.mapProperty<String, Map<String, IntArray>>()

    val mcpMinecraftVersion: Property<String> = project.objects.property()
    val mcpChannel: Property<String> = project.objects.property()
    val mappingsVersion: Property<Int> = project.objects.property()

    @TaskAction
    fun run() {
        val mappings = mcpVersion.get()

        val index = mappings.indexOf('_')
        if (index == -1) {
            throw PaperweightException("Mappings version format is invalid")
        }

        val channel = mappings.substring(0, index)
        mcpChannel.set(channel)
        if (channel == "custom") {
            throw PaperweightException("'custom' mappings version is not supported")
        }

        try {
            mappingsVersion.set(mappings.substring(index + 1).toInt())
        } catch (e: NumberFormatException) {
            throw PaperweightException("Mappings version must be an integer")
        }

        mcpMinecraftVersion.set(minecraftVersion)

        checkMappings()

        mcpMinecraftVersion.finalizeValue()
        mcpChannel.finalizeValue()
        mappingsVersion.finalizeValue()
    }

    private fun checkMappings() {
        val versionMap = mcpJson.get()[minecraftVersion.get()]
        val channel = rawMappingsChannel(mcpChannel.get())
        if (versionMap != null) {
            val channelList = versionMap[channel] ?:
            throw PaperweightException("There is no MCP mapping channel named $channel")

            if (searchArray(channelList, mappingsVersion.get())) {
                return
            }
        }

        for (mcEntry in mcpJson.get()) {
            for (channelEntry in mcEntry.value) {
                if (searchArray(channelEntry.value, mappingsVersion.get())) {
                    val correctMc = mcEntry.key == minecraftVersion.get()
                    val correctChannel = channelEntry.key == channel

                    if (correctChannel && !correctMc) {
                        project.logger.warn("This mapping '${mappingsVersion.get()}' was designed for MC " +
                                "${mcEntry.key}! Use at your own peril.")

                        mcpMinecraftVersion.set(mcEntry.key)
                        return
                    } else if (correctMc && !correctChannel) {
                        throw PaperweightException("This mapping '${mappingsVersion.get()}' does not exist! " +
                                "Perhaps you meant '${channelEntry.key}_${mappingsVersion.get()}'")
                    }
                }
            }
        }

        throw PaperweightException("The specified mapping '${mappingsVersion.get()}' does not exist!")
    }

}
