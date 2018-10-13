/*
 * This file contains a collection of methods and classes meant to interact fairly directly with ForgeGradle and MCP,
 * and the logic was lifted (and converted from Java) from ForgeGradle. As such, this file is licensed under LGPL
 * rather than Apache like the rest of this project.
 *
 * Copyright (C) 2013-2018 Minecraft Forge
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

package io.papermc.paperweight.util

import com.github.salomonbrys.kotson.fromJson
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.ZippedTask
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.FileVisitor
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.jetbrains.java.decompiler.code.CodeConstants
import org.jetbrains.java.decompiler.main.DecompilerContext
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger
import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import org.jetbrains.java.decompiler.main.extern.IVariableNameProvider
import org.jetbrains.java.decompiler.main.extern.IVariableNamingFactory
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair
import org.jetbrains.java.decompiler.struct.StructMethod
import org.jetbrains.java.decompiler.util.InterpreterUtil
import org.jetbrains.java.decompiler.util.JADNameProvider
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Arrays
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


private const val USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11"
private const val MCP_MAPPINGS_JSON = "McpMappings.json"
private const val MC_MANIFEST = "McManifest.json"

internal const val CONFIG_MAPPINGS = "forgeGradleMcpMappings"
internal const val CONFIG_MCP_DATA = "forgeGradleMcpData"

private const val URL_FORGE_MAVEN = "https://files.minecraftforge.net/maven"
private const val URL_LIBRARY = "https://libraries.minecraft.net/"

internal const val TASK_EXTRACT_MCP = "extractMcpData"
internal const val TASK_EXTRACT_MAPPINGS = "extractMcpMappings"

private val URLS_MCP_JSON = listOf(
    "https://files.minecraftforge.net/maven/de/oceanlabs/mcp/versions.json",
    "http://export.mcpbot.bspk.rs/versions.json"
)

private val URLS_MC_MANIFEST = listOf("https://launchermeta.mojang.com/mc/game/version_manifest.json")

internal fun createBasics(project: Project) {
    project.configurations.register(CONFIG_MAPPINGS)
    project.configurations.register(CONFIG_MCP_DATA)
    project.repositories.apply {
        maven {
            name = "forge"
            url = project.uri(URL_FORGE_MAVEN)
        }
        mavenCentral()
        maven {
            name = "minecraft"
            url = project.uri(URL_LIBRARY)
        }
    }
}

internal fun validateConfig(project: Project) {
    val extension = project.ext
    if (extension.minecraftVersion == Constants.DEFAULT_STRING) {
        throw PaperweightException("Missing value for minecraftVersion")
    }
    if (extension.mcpVersion == Constants.DEFAULT_STRING) {
        throw PaperweightException("Missing value for mcpVersion")
    }

    parseAndCheckMcpVersion(extension, project)
}

private fun parseAndCheckMcpVersion(extension: PaperweightExtension, project: Project) {
    val mappings = extension.mcpVersion

    val index = mappings.indexOf('_')
    if (index == -1) {
        throw PaperweightException("Mappings version format is invalid")
    }

    extension.mcpChannel = mappings.substring(0, index)
    if (extension.mcpChannel == "custom") {
        throw PaperweightException("'custom' mappings version is not supported")
    }

    try {
        extension.mappingsVersion = mappings.substring(index + 1).toInt()
    } catch (e: NumberFormatException) {
        throw PaperweightException("Mappings version must be an integer")
    }

    extension.mcpMinecraftVersion = extension.minecraftVersion

    checkMappings(extension, project)
}

private fun checkMappings(extension: PaperweightExtension, project: Project) {
    val versionMap = extension.mcpJson[extension.minecraftVersion]
    val channel = rawMappingsChannel(extension.mcpChannel)
    if (versionMap != null) {
        val channelList = versionMap[channel] ?:
        throw PaperweightException("There is no MCP mapping channel named $channel")

        if (searchArray(channelList, extension.mappingsVersion)) {
            return
        }
    }

    for (mcEntry in extension.mcpJson) {
        for (channelEntry in mcEntry.value) {
            if (searchArray(channelEntry.value, extension.mappingsVersion)) {
                val correctMc = mcEntry.key == extension.minecraftVersion
                val correctChannel = channelEntry.key == channel

                if (correctChannel && !correctMc) {
                    project.logger.warn("This mapping '${extension.mappingsVersion}' was designed for MC " +
                            "${mcEntry.key}! Use at your own peril.")

                    extension.mcpMinecraftVersion = mcEntry.key
                    return
                } else if (correctMc && !correctChannel) {
                    throw PaperweightException("This mapping '${extension.mappingsVersion}' does not exist! " +
                            "Perhaps you meant '${channelEntry.key}_${extension.mappingsVersion}'")
                }
            }
        }
    }

    throw PaperweightException("The specified mapping '${extension.mappingsVersion}' does not exist!")
}

private fun rawMappingsChannel(channel: String): String {
    val index = channel.indexOf('_')
    return if (index == -1) {
        channel
    } else {
        channel.substring(0, index)
    }
}

internal fun setupConfigurations(project: Project) {
    val extension = project.ext

    project.dependencies.add(CONFIG_MAPPINGS, mapOf(
        "group" to "de.oceanlabs.mcp",
        "name" to "mcp_${extension.mcpChannel}",
        "version" to "${extension.mappingsVersion}-${extension.mcpMinecraftVersion}",
        "ext" to "zip"
    ))

    project.dependencies.add(CONFIG_MCP_DATA, mapOf(
        "group" to "de.oceanlabs.mcp",
        "name" to "mcp_config",
        "version" to extension.mcpMinecraftVersion,
        "ext" to "zip"
    ))
}

internal fun getRemoteJsons(project: Project) {
    val cache = project.cache
    val extension = project.ext

    // McpMappings.json
    val jsonCache = cache.resolve(MCP_MAPPINGS_JSON)
    val etagFile = cache.resolve("$MCP_MAPPINGS_JSON.etag")
    val mcpJson = getWithEtag(URLS_MCP_JSON, jsonCache, etagFile)
    extension.mcpJson = gson.fromJson(mcpJson)

    // McManifest.json
    val mcManifestJson = cache.resolve(MC_MANIFEST)
    val mcManifestEtag = cache.resolve("$MC_MANIFEST.etag")
    val mcManifest = getWithEtag(URLS_MC_MANIFEST, mcManifestJson, mcManifestEtag)
    extension.mcManifest = gson.fromJson(mcManifest)

    val mcVersionJson = cache.resolve(Constants.paperVersionJson(extension))
    val mcVersionEtag = mcVersionJson.resolveSibling("${mcVersionJson.name}.etag")
    val mcVersion = getWithEtag(listOf(extension.mcManifest.versions.first { it.id == extension.minecraftVersion }.url), mcVersionJson, mcVersionEtag)
    extension.versionJson = gson.fromJson(mcVersion)
}

private fun searchArray(array: IntArray, key: Int): Boolean {
    Arrays.sort(array)
    val index = Arrays.binarySearch(array, key)
    return index >= 0 && array[index] == key
}

private fun getWithEtag(urls: List<String>, cache: File, etagFile: File): String {
    if (cache.exists() && cache.lastModified() + TimeUnit.MINUTES.toMillis(1) >= System.currentTimeMillis()) {
        return cache.readText()
    }

    val etag = if (etagFile.exists()) {
        etagFile.readText()
    } else {
        etagFile.parentFile.mkdirs()
        ""
    }

    var thrown: Throwable? = null

    for (stringUrl in urls) {
        try {
            val url = URL(stringUrl)

            val con = url.openConnection() as HttpURLConnection
            con.instanceFollowRedirects = true
            con.setRequestProperty("User-Agent", USER_AGENT)
            con.ifModifiedSince = cache.lastModified()

            if (etag.isNotEmpty()) {
                con.setRequestProperty("If-None-Match", etag)
            }

            try {
                con.connect()

                when (con.responseCode) {
                    304 -> {
                        cache.setLastModified(System.currentTimeMillis())
                        return cache.readText()
                    }
                    200 -> {
                        val data = con.inputStream.use { stream ->
                            stream.readBytes()
                        }

                        cache.writeBytes(data)

                        val newEtag = con.getHeaderField("ETag")
                        if (newEtag.isNullOrEmpty()) {
                            if (!etagFile.createNewFile()) {
                                etagFile.setLastModified(System.currentTimeMillis())
                            }
                        } else {
                            etagFile.writeText(newEtag)
                        }

                        return String(data)
                    }
                    else -> {
                        throw RuntimeException("Etag download for $stringUrl failed with code ${con.responseCode}")
                    }
                }
            } finally {
                con.disconnect()
            }
        } catch (e: Exception) {
            if (thrown == null) {
                thrown = e
            } else {
                thrown.addSuppressed(e)
            }
        }
    }

    val errorString = "Unable to download from $urls with etag"
    val ex = if (thrown != null) {
        PaperweightException(errorString, thrown)
    } else {
        PaperweightException(errorString)
    }
    throw ex
}

internal open class ExtractConfigTask : DefaultTask() {

    @get:Input internal lateinit var config: String
    @get:Input internal var clean = false
    @get:OutputDirectory internal lateinit var destinationDir: Any

    @TaskAction
    fun doStuff() {
        val dest = project.file(destinationDir)

        if (clean) {
            if (!dest.deleteRecursively()) {
                throw IOException("Failed to clear directory ${dest.absolutePath}")
            }
        }

        if (!dest.exists() && !dest.mkdirs()) {
            throw IOException("Failed to create directory ${dest.absolutePath}")
        }

        for (file in project.configurations[config]) {
            logger.debug("Extracting: $file")
            project.zipTree(file).visit(ExtractionFileVisitor(dest))
        }
    }
}

internal class ExtractionFileVisitor(private val dest: File) : FileVisitor {
    override fun visitFile(fileDetails: FileVisitDetails) {
        val outFile = dest.resolve(fileDetails.path)
        if (!outFile.parentFile.exists() && !outFile.parentFile.mkdirs()) {
            throw IOException("Failed to create directory ${outFile.parentFile.absolutePath}")
        }

        fileDetails.copyTo(outFile)
    }

    override fun visitDir(dirDetails: FileVisitDetails) {
        val dir = dest.resolve(dirDetails.path)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create directory ${dir.absolutePath}")
        }
    }
}

internal open class RemapSrgSources : ZippedTask() {

    @get:InputFile lateinit var methodsCsv: Any
    @get:InputFile lateinit var fieldsCsv: Any
    @get:InputFile lateinit var paramsCsv: Any

    private val methods = hashMapOf<String, String>()
    private val methodDocs = hashMapOf<String, String>()
    private val fields = hashMapOf<String, String>()
    private val fieldDocs = hashMapOf<String, String>()
    private val params = hashMapOf<String, String>()

    override fun action(rootDir: File) {
        readCsv()

        rootDir.walkBottomUp()
            .filter { it.isFile && it.name.endsWith(".java") }
            .forEach(::processFile)
    }

    private fun processFile(file: File) {
        val newFile = file.resolveSibling(file.name + ".bak")
        file.bufferedReader().use { reader ->
            newFile.bufferedWriter().use { writer ->
                writeFile(reader, writer)
            }
        }

        if (!file.delete()) {
            throw PaperweightException("Failed to delete file: $file")
        }

        newFile.renameTo(file)
    }

    private fun writeFile(reader: BufferedReader, writer: BufferedWriter) {
        for (line in reader.lineSequence()) {
            replaceInLine(line, writer)
        }
    }

    private fun replaceInLine(line: String, writer: BufferedWriter) {
        val buffer = StringBuffer()
        val matcher = SRG_FINDER.matcher(line)

        while (matcher.find()) {
            val find = matcher.group()

            val result = when {
                find.startsWith("p_") -> params[find]
                find.startsWith("func_") -> methods[find]
                find.startsWith("field_") -> fields[find]
                else -> null
            } ?: matcher.group()

            matcher.appendReplacement(buffer, result)
        }

        matcher.appendTail(buffer)

        writer.appendln(buffer.toString())
    }

    private fun readCsv() {
        readCsvFile(project.file(methodsCsv), methods, methodDocs)
        readCsvFile(project.file(fieldsCsv), fields, fieldDocs)

        getReader(project.file(paramsCsv)).use { reader ->
            for (line in reader.readAll()) {
                params[line[0]] = line[1]
            }
        }
    }

    private fun readCsvFile(file: File, names: MutableMap<String, String>, docs: MutableMap<String, String>) {
        getReader(file).use { reader ->
            for (line in reader.readAll()) {
                names[line[0]] = line[1]
                if (line[3].isNotEmpty()) {
                    docs[line[0]] = line[3]
                }
            }
        }
    }

    companion object {
        private val SRG_FINDER = Pattern.compile("func_[0-9]+_[a-zA-Z_]+|field_[0-9]+_[a-zA-Z_]+|p_[\\w]+_\\d+_\\b")
    }
}

internal open class RunForgeFlower : DefaultTask() {

    @get:InputFile lateinit var inputJar: Any

    @get:OutputFile
    lateinit var outputJar: Any

    private val ignored = Regex("\\s*(Loading Class|Adding (File|Archive)).*")

    @TaskAction
    fun doStuff() {
        val map = mapOf(
            IFernflowerPreferences.DECOMPILE_INNER to "1",
            IFernflowerPreferences.ASCII_STRING_CHARACTERS to "1",
            IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH to "1",
            IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES to "1",
            IFernflowerPreferences.REMOVE_SYNTHETIC to "1",
            IFernflowerPreferences.REMOVE_BRIDGE to "1",
            IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH to "1",
            IFernflowerPreferences.USE_JAD_VARNAMING to "1",
            IFernflowerPreferences.MAX_PROCESSING_METHOD to "0",
            IFernflowerPreferences.INDENT_STRING to "    ",
            DecompilerContext.RENAMER_FACTORY to AdvancedJadRenamerFactory::class.java.name
        )

        val inputJarFile = project.file(inputJar)
        val outputJarFile = project.file(outputJar)
        val decomp = outputJarFile.resolveSibling("decomp" + ThreadLocalRandom.current().nextInt())

        try {
            val logger = object : PrintStreamLogger(System.out) {
                override fun writeMessage(message: String, severity: Severity) {
                    if (message.matches(ignored)) {
                        return
                    }
                    super.writeMessage(message, severity)
                }
            }
            val decompiler = BaseDecompiler(BytecodeProvider(), ResultSaver(decomp), map, logger)

            decompiler.addSpace(inputJarFile, true)
            decompiler.decompileContext()

            decomp.resolve(inputJarFile.name).copyTo(outputJarFile, overwrite = true)
        } finally {
            decomp.deleteRecursively()
        }
    }
}

internal class BytecodeProvider : IBytecodeProvider {
    override fun getBytecode(externalPath: String, internalPath: String?): ByteArray {
        val file = File(externalPath)

        return if (internalPath == null) {
            InterpreterUtil.getBytes(file)
        } else {
            ZipFile(file).use { archive ->
                val entry = archive.getEntry(internalPath) ?: throw IOException("Entry not found: $internalPath")
                InterpreterUtil.getBytes(archive, entry)
            }
        }
    }
}

internal class ResultSaver(private val root: File) : IResultSaver {
    private val mapArchiveStreams = hashMapOf<String, ZipOutputStream>()
    private val mapArchiveEntries = hashMapOf<String, HashSet<String>>()

    override fun saveFolder(path: String) {
        val dir = File(getAbsolutePath(path))
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw PaperweightException("Cannot create directory $dir")
        }
    }

    override fun copyFile(source: String, path: String, entryName: String) {
        try {
            InterpreterUtil.copyFile(File(source), File(getAbsolutePath(path), entryName))
        } catch (e: IOException) {
            DecompilerContext.getLogger().writeMessage("Cannot copy $source to $entryName", e)
        }
    }

    override fun saveClassFile(path: String, qualifiedName: String?, entryName: String, content: String, mapping: IntArray?) {
        val file = File(getAbsolutePath(path), entryName)
        try {
            file.bufferedWriter().use { writer ->
                writer.write(content)
            }
        } catch (e: IOException) {
            DecompilerContext.getLogger().writeMessage("Cannot write class file $file", e)
        }
    }

    override fun createArchive(path: String, archiveName: String, manifest: Manifest?) {
        val file = File(getAbsolutePath(path), archiveName)
        try {
            if (!file.isFile && !file.createNewFile()) {
                throw IOException("Cannot create file $file")
            }

            val out = file.outputStream()
            val zipStream = if (manifest != null) {
                JarOutputStream(out, manifest)
            } else {
                ZipOutputStream(out)
            }
            mapArchiveStreams[file.path] = zipStream
        } catch (e: IOException) {
            DecompilerContext.getLogger().writeMessage("Cannot create archive $file", e)
        }
    }

    override fun saveDirEntry(path: String, archiveName: String, entryName: String) {
        saveClassEntry(path, archiveName, null, entryName, null)
    }

    override fun copyEntry(source: String, path: String, archiveName: String, entryName: String) {
        val file = File(getAbsolutePath(path), archiveName).path

        if (!checkEntry(entryName, file)) {
            return
        }

        try {
            ZipFile(File(source)).use { srcArchive ->
                val entry = srcArchive.getEntry(entryName)
                if (entry != null) {
                    srcArchive.getInputStream(entry).use { input ->
                        val out = mapArchiveStreams[file]
                        InterpreterUtil.copyStream(input, out)
                    }
                }
            }
        } catch (e: IOException) {
            DecompilerContext.getLogger().writeMessage("Cannot copy entry $entryName from $source to $file", e)
        }
    }

    override fun saveClassEntry(path: String, archiveName: String, qualifiedName: String?, entryName: String, content: String?) {
        val file = File(getAbsolutePath(path), archiveName).path

        if (!checkEntry(entryName, file)) {
            return
        }

        try {
            mapArchiveStreams[file]?.let { out ->
                out.putNextEntry(ZipEntry(entryName))
                content?.let { con ->
                    out.write(con.toByteArray())
                }
            }
        } catch (e: IOException) {
            DecompilerContext.getLogger().writeMessage("Cannot write entry $entryName to $file", e)
        }
    }

    private fun checkEntry(entryName: String, file: String): Boolean {
        var set = mapArchiveEntries[file]
        if (set == null) {
            set = hashSetOf()
            mapArchiveEntries[file] = set
        }

        val added = set.add(entryName)
        if (!added) {
            val message = "Zip entry $entryName already exists in $file"
            DecompilerContext.getLogger().writeMessage(message, IFernflowerLogger.Severity.WARN)
        }
        return added
    }

    override fun closeArchive(path: String, archiveName: String) {
        val file = File(getAbsolutePath(path), archiveName).path

        try {
            mapArchiveEntries.remove(file)
            mapArchiveStreams.remove(file)?.close()
        } catch (e: IOException) {
            DecompilerContext.getLogger().writeMessage("Cannot close $file", IFernflowerLogger.Severity.WARN)
        }
    }

    private fun getAbsolutePath(path: String): String {
        return root.resolve(path).absolutePath
    }
}

class AdvancedJadRenamerFactory : IVariableNamingFactory {
    override fun createFactory(structMethod: StructMethod): IVariableNameProvider {
        return AdvancedJadRenamer(structMethod)
    }
}

class AdvancedJadRenamer(private val wrapper: StructMethod) : JADNameProvider(wrapper) {

    override fun renameAbstractParameter(abstractParam: String, index: Int): String {
        if ((wrapper.accessFlags and CodeConstants.ACC_ABSTRACT) == 0) {
            return abstractParam
        }

        val methodName = wrapper.name
        val matcher = MATCHER.matcher(methodName)
        if (matcher.find()) {
            return "p_${matcher.group(1)}_${index}_"
        }
        return abstractParam
    }

    companion object {
        private val MATCHER = Pattern.compile("func_(\\d+)_.*")
    }
}

