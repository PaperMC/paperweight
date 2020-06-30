package io.papermc.paperweight.tasks

import com.github.salomonbrys.kotson.fromJson
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.gson
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import util.BuildDataInfo

open class GatherBuildData : DefaultTask() {

    @InputFile
    val buildDataInfoFile = project.objects.fileProperty()

    val buildDataInfo = project.objects.property<BuildDataInfo>()

    @TaskAction
    fun run() {
        try {
            buildDataInfoFile.asFile.get().bufferedReader().use {
                buildDataInfo.set(gson.fromJson<BuildDataInfo>(it))
            }
        } catch (e: Exception) {
            throw PaperweightException("Failed to read build info file", e)
        }
    }
}