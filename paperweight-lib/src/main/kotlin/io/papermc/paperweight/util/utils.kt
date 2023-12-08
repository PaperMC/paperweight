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

package io.papermc.paperweight.util

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.*
import dev.denwav.hypo.model.ClassProviderRoot
import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.constants.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.lang.reflect.Type
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.*
import org.cadixdev.lorenz.merge.MergeResult
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.LogLevel
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.*

val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().registerTypeHierarchyAdapter(Path::class.java, PathJsonConverter()).create()

class PathJsonConverter : JsonDeserializer<Path?>, JsonSerializer<Path?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Path? {
        return if (json != null) Paths.get(json.asString) else null
    }

    override fun serialize(src: Path?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src.toString())
    }
}

inline fun <reified T> Gson.fromJson(any: Any): T = when (any) {
    is String -> fromJson(any)
    else -> any.convertToPath().bufferedReader(Charsets.UTF_8).use { fromJson(it) }
}

val ProjectLayout.cache: Path
    get() = projectDirectory.dir(".gradle/$CACHE_PATH").path

fun ProjectLayout.cacheDir(path: String) = projectDirectory.dir(".gradle/$CACHE_PATH").dir(path)
fun ProjectLayout.initSubmodules() {
    Git.checkForGit()
    Git(projectDirectory.path)("submodule", "update", "--init").executeOut()
}

fun <T : FileSystemLocation> Provider<out T>.fileExists(project: Project): Provider<out T?> {
    return flatMap { project.provider { it.takeIf { f -> f.path.exists() } } }
}

inline fun <reified T : Task> TaskContainer.providerFor(name: String): TaskProvider<T> {
    return if (names.contains(name)) {
        named<T>(name)
    } else {
        register<T>(name)
    }
}

inline fun <reified T : Task> TaskContainer.configureTask(name: String, noinline configure: T.() -> Unit): TaskProvider<T> {
    return if (names.contains(name)) {
        named(name, configure)
    } else {
        register(name, configure)
    }
}

@Suppress("UNCHECKED_CAST")
val Project.download: Provider<DownloadService>
    get() = gradle.sharedServices.registrations.getByName(DOWNLOAD_SERVICE_NAME).service as Provider<DownloadService>

fun commentRegex(): Regex {
    return Regex("\\s*#.*")
}

val ProviderFactory.isBaseExecution: Provider<Boolean>
    get() = gradleProperty(PAPERWEIGHT_DOWNSTREAM_FILE_PROPERTY)
        .orElse(provider { "false" })
        .map { it == "false" }

val Project.isBaseExecution: Boolean
    get() = providers.isBaseExecution.get()

fun ProviderFactory.verboseApplyPatches(): Provider<Boolean> =
    gradleProperty(PAPERWEIGHT_VERBOSE_APPLY_PATCHES)
        .map { it.toBoolean() }
        .orElse(false)

val redirectThreadCount: AtomicLong = AtomicLong(0)

fun redirect(input: InputStream, out: OutputStream): CompletableFuture<Unit> {
    val future = CompletableFuture<Unit>()
    val thread = Thread {
        try {
            input.copyTo(out)
            future.complete(Unit)
        } catch (e: Throwable) {
            future.completeExceptionally(PaperweightException("Failed to copy $input to $out", e))
        }
    }
    thread.name = "paperweight stream redirect thread #${redirectThreadCount.getAndIncrement()}"
    thread.isDaemon = true
    thread.start()
    return future
}

object UselessOutputStream : OutputStream() {
    override fun write(b: Int) {
    }
}

class DelegatingOutputStream(vararg delegates: OutputStream) : OutputStream() {

    private val delegates: MutableSet<OutputStream> = Collections.newSetFromMap(IdentityHashMap())

    init {
        this.delegates.addAll(delegates)
    }

    override fun write(b: Int) {
        for (delegate in delegates) {
            delegate.write(b)
        }
    }
}

fun Any.convertToPath(): Path {
    return when (this) {
        is Path -> this
        is File -> this.toPath()
        is FileSystemLocation -> this.path
        is Provider<*> -> this.get().convertToPath()
        else -> throw PaperweightException("Unknown type representing a file: ${this.javaClass.name}")
    }
}

fun Any.convertToFileProvider(layout: ProjectLayout, providers: ProviderFactory): Provider<RegularFile> {
    return when (this) {
        is Path -> layout.file(providers.provider { toFile() })
        is File -> layout.file(providers.provider { this })
        is FileSystemLocation -> layout.file(providers.provider { asFile })
        is Provider<*> -> flatMap { it.convertToFileProvider(layout, providers) }
        else -> throw PaperweightException("Unknown type representing a file: ${this.javaClass.name}")
    }
}

fun Any?.convertToPathOrNull(): Path? {
    if (this == null) {
        return null
    }
    return this.convertToPath()
}

fun Any.convertToUrl(): URL {
    return when (this) {
        is URL -> this
        is URI -> this.toURL()
        is String -> URI.create(this).toURL()
        is Provider<*> -> this.get().convertToUrl()
        else -> throw PaperweightException("Unknown URL type: ${this.javaClass.name}")
    }
}

fun String.capitalized(): String {
    return replaceFirstChar(Char::uppercase)
}

fun ensureParentExists(vararg files: Any) {
    for (file in files) {
        val parent = file.convertToPath().parent
        try {
            parent.createDirectories()
        } catch (e: Exception) {
            throw PaperweightException("Failed to create directory $parent", e)
        }
    }
}

fun ensureDeleted(vararg files: Any) {
    for (file in files) {
        val f = file.convertToPath()
        try {
            f.deleteRecursive()
        } catch (e: Exception) {
            throw PaperweightException("Failed to delete file or directory $f", e)
        }
    }
}

fun BaseTask.defaultOutput(name: String, ext: String): RegularFileProperty {
    return objects.fileProperty().convention {
        layout.cache.resolve(paperTaskOutput(name, ext)).toFile()
    }
}

fun BaseTask.defaultOutput(ext: String): RegularFileProperty {
    return defaultOutput(name, ext)
}

fun BaseTask.defaultOutput(): RegularFileProperty {
    return defaultOutput("jar")
}

val <T> Optional<T>.orNull: T?
    get() = orElse(null)

inline fun <reified T : Any> Project.contents(contentFile: RegularFileProperty, crossinline convert: (String) -> T): Provider<T> {
    return providers.fileContents(contentFile)
        .asText
        .map { convert(it) }
}

fun findOutputDir(baseFile: Path): Path {
    var dir: Path
    do {
        dir = baseFile.resolveSibling("${baseFile.name}-" + ThreadLocalRandom.current().nextInt())
    } while (dir.exists())
    return dir
}

private val emptyMergeResult = MergeResult(null)
fun <T> emptyMergeResult(): MergeResult<T?> {
    @Suppress("UNCHECKED_CAST")
    return emptyMergeResult as MergeResult<T?>
}

inline fun <reified T : Task> TaskContainer.registering(noinline configuration: T.() -> Unit) = registering(T::class, configuration)
inline fun <reified T : Task> TaskContainer.registering() = registering(T::class)

enum class HashingAlgorithm(val algorithmName: String) {
    SHA256("SHA-256"),
    SHA1("SHA-1");

    private val threadLocalMessageDigest = ThreadLocal.withInitial { createDigest() }

    fun createDigest(): MessageDigest = MessageDigest.getInstance(algorithmName)

    val threadLocalDigest: MessageDigest
        get() = threadLocalMessageDigest.get()
}

class Hash(
    @get:Input
    val value: String,
    @get:Input
    val algorithm: HashingAlgorithm
) {
    @get:Internal
    val valueLower: String by lazy { value.lowercase(Locale.ENGLISH) }
}

fun String.hash(algorithm: HashingAlgorithm): ByteArray = algorithm.threadLocalDigest.let {
    it.update(toByteArray())
    it.digest()
}

fun InputStream.hash(algorithm: HashingAlgorithm, bufferSize: Int = 8192): ByteArray {
    val digest = algorithm.threadLocalDigest
    val buffer = ByteArray(bufferSize)
    while (true) {
        val count = read(buffer)
        if (count == -1) {
            break
        }
        digest.update(buffer, 0, count)
    }
    return digest.digest()
}

private val hexChars = "0123456789abcdef".toCharArray()

fun ByteArray.asHexString(): String {
    val chars = CharArray(2 * size)
    forEachIndexed { i, byte ->
        val unsigned = byte.toInt() and 0xFF
        chars[2 * i] = hexChars[unsigned / 16]
        chars[2 * i + 1] = hexChars[unsigned % 16]
    }
    return String(chars)
}

fun JavaToolchainService.defaultJavaLauncher(project: Project): Provider<JavaLauncher> {
    return launcherFor(project.extensions.getByType<JavaPluginExtension>().toolchain).orElse(
        launcherFor {
            // If the java plugin isn't applied, or no toolchain value was set
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
}

fun <P : Property<*>> P.withDisallowChanges(): P = apply { disallowChanges() }
fun <P : Property<*>> P.withDisallowUnsafeRead(): P = apply { disallowUnsafeRead() }

fun FileCollection.toJarClassProviderRoots(): List<ClassProviderRoot> = files.asSequence()
    .map { f -> f.toPath() }
    .filter { p -> p.isLibraryJar }
    .map { p -> ClassProviderRoot.fromJar(p) }
    .toList()

private fun javaVersion(): Int {
    val version = System.getProperty("java.specification.version")
    val parts = version.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
    val errorMsg = "Could not determine version of the current JVM"
    check(parts.isNotEmpty()) { errorMsg }
    return if (parts[0] == "1") {
        check(parts.size >= 2) { errorMsg }
        parts[1].toInt()
    } else {
        parts[0].toInt()
    }
}

fun checkJavaVersion() {
    val minimumJava = 11
    val ver = javaVersion()
    if (ver < minimumJava) {
        var msg = "paperweight requires Gradle to be run with a Java $minimumJava runtime or newer."
        val runtimeMX = ManagementFactory.getRuntimeMXBean()
        if (runtimeMX != null) {
            msg += " Current runtime: Java ${runtimeMX.specVersion} (${runtimeMX.vmName} ${runtimeMX.vmVersion})"
        }
        throw PaperweightException(msg)
    }
}

inline fun <reified P> printId(pluginId: String, gradle: Gradle) {
    if (gradle.startParameter.logLevel == LogLevel.QUIET) {
        return
    }
    println("$pluginId v${P::class.java.`package`.implementationVersion} (running on '${System.getProperty("os.name")}')")
}
