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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.tree.ClassNode

abstract class ScanJar : JavaLauncherTask() {
    companion object {
        private val logger: Logger = Logging.getLogger(ScanJar::class.java)
    }

    @get:Classpath
    abstract val jarToScan: RegularFileProperty

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val log: RegularFileProperty

    @get:Internal
    abstract val jvmArgs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()
        group = "verification"

        jvmArgs.convention(listOf("-Xmx768m"))
        log.set(layout.cache.resolve(paperTaskOutput("txt")))
    }

    @TaskAction
    fun run() {
        val launcher = launcher.get()
        val jvmArgs = jvmArgs.get()
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmArgs)
            forkOptions.executable(launcher.executablePath.path.absolutePathString())
        }
        this.queue(queue)
    }

    abstract fun queue(queue: WorkQueue)

    abstract class ScanJarAction<P : ScanJarAction.BaseParameters> : WorkAction<P>, AsmUtil {
        interface BaseParameters : WorkParameters {
            val jarToScan: RegularFileProperty
            val classpath: ConfigurableFileCollection
            val log: RegularFileProperty
        }

        protected val log = mutableListOf<String>()

        final override fun execute() {
            parameters.jarToScan.path.openZip().use { scan ->
                var fail: Exception? = null
                val classPathDirs = mutableListOf<Path>()
                val classPathJars = mutableListOf<FileSystem>()
                parameters.classpath.forEach {
                    if (it.isDirectory) {
                        classPathDirs.add(it.toPath())
                        return@forEach
                    }
                    if (!it.isFile || !it.name.endsWith(".jar")) {
                        return@forEach
                    }
                    try {
                        classPathJars += it.toPath().openZip()
                    } catch (ex: Exception) {
                        logger.error("Failed to open zip $it", ex)
                        if (fail == null) {
                            fail = ex
                        } else {
                            fail!!.addSuppressed(ex)
                        }
                    }
                }
                try {
                    if (fail != null) {
                        throw PaperweightException("Failed to read classpath jars", fail)
                    }
                    val classNodeCache = ClassNodeCache.create(scan, classPathJars, classPathDirs)
                    scan(scan, classNodeCache)
                } finally {
                    var err: Exception? = null
                    classPathJars.forEach {
                        try {
                            it.close()
                        } catch (ex: Exception) {
                            logger.error("Failed to close zip $it", ex)
                            if (err == null) {
                                err = ex
                            } else {
                                err!!.addSuppressed(ex)
                            }
                        }
                    }
                    if (err != null) {
                        throw PaperweightException("Failed to close classpath jars", err)
                    }
                }
            }
            if (!Files.exists(parameters.log.path.parent)) {
                Files.createDirectories(parameters.log.path.parent)
            }
            parameters.log.path.writeLines(log)

            if (log.isNotEmpty()) {
                throw PaperweightException("Bad code was found, see log file at ${parameters.log.path.toAbsolutePath()}")
            }
        }

        private fun scan(scan: FileSystem, classNodeCache: ClassNodeCache) {
            scan.walk().use { stream ->
                stream.forEach { file ->
                    if (!Files.isRegularFile(file) || !file.fileName.toString().endsWith(".class")) {
                        return@forEach
                    }
                    val classNode = classNodeCache.findClass(file.toString()) ?: return@forEach
                    this.handleClass(classNode, classNodeCache)
                }
            }
        }

        protected abstract fun handleClass(classNode: ClassNode, classNodeCache: ClassNodeCache)
    }
}
