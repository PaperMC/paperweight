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

package io.papermc.paperweight.util

import io.papermc.paperweight.PaperweightException
import org.apache.tools.ant.ExitException
import org.apache.tools.ant.util.optional.NoExitSecurityManager
import org.gradle.api.Task
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.Manifest

internal fun Task.runJar(jar: Any, vararg args: String) {
    val jarFile = project.file(jar)
    val url = jarFile.toURI().toURL()

    val mainClassName = project.zipTree(jarFile).matching {
        include("META-INF/MANIFEST.MF")
    }.singleFile.inputStream().use { input ->
        val man = Manifest(input)
        return@use man.mainAttributes?.getValue("Main-Class")
    } ?: throw PaperweightException("Failed to find Main-Class attribute in $jarFile")

    val classLoader = URLClassLoader(arrayOf(url))
    val clazz = Class.forName(mainClassName, true, classLoader)

    val mainMethod = clazz.getMethod("main", Array<String>::class.java)
        ?: throw PaperweightException("Main class not found in class $mainClassName in jar $jarFile")

    val exitCode = AtomicInteger(0)

    val thread = Thread {
        Thread.currentThread().contextClassLoader = classLoader

        val sm = System.getSecurityManager()
        try {
            System.setSecurityManager(NoExitSecurityManager())
            mainMethod(null, args)
        } catch (e: ExitException) {
            exitCode.set(e.status)
        } finally {
            System.setSecurityManager(sm)
        }
    }

    thread.start()
    try {
        thread.join()
    } catch (ignored: InterruptedException) {}

    val e = exitCode.get()
    if (e != 0) {
        throw PaperweightException("Execution of $jarFile failed with exit code $e")
    }
}
