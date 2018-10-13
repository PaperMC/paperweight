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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.Git
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.internal.os.OperatingSystem
import java.io.File
import java.util.Arrays

open class ApplyGitPatches : ZippedTask() {

    @get:Input lateinit var targetName: String
    @get:InputDirectory lateinit var patchDir: Any

    override fun action(rootDir: File) {
        val patchDirFile = project.file(patchDir)

        val git = Git(rootDir)

        println("   Applying patches to $targetName...")

        val statusFile = rootDir.resolve(".git/patch-apply-failed")
        statusFile.delete()
        git("am", "--abort").runSilently()

        val patches = patchDirFile.listFiles { _, name -> name.endsWith(".patch") } ?: run {
            println("No patches found")
            return
        }

        Arrays.sort(patches)
        if (git("am", "--3way", "--ignore-whitespace", *patches.map { it.absolutePath }.toTypedArray()).runOut() != 0) {
            Thread.sleep(100) // Wait for git
            statusFile.writeText("1")
            logger.error("***   Something did not apply cleanly to $targetName.")
            logger.error("***   Please review above details and finish the apply then")
            logger.error("***   save the changes with ./gradlew rebuildPatches")

            if (OperatingSystem.current().isWindows) {
                logger.error("")
                logger.error("***   Because you're on Windows you'll need to finish the AM,")
                logger.error("***   rebuild all patches, and then re-run the patch apply again.")
                logger.error("***   Consider using the scripts with Windows Subsystem for Linux.")
            }

            throw PaperweightException("Failed to apply patches")
        } else {
            statusFile.delete()
            println("   Patches applied cleanly to $targetName")
        }
    }
}
