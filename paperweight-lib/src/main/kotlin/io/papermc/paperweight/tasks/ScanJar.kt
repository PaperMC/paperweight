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

        jvmArgs.convention(listOf("-Xmx512m"))
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
                        ScanJarForBadCalls.logger.error("Failed to open zip $it", ex)
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
                            ScanJarForBadCalls.logger.error("Failed to close zip $it", ex)
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
