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

import io.papermc.paperweight.util.Git
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import java.io.File

open class CloneRepo : ZippedTask() {

    @get:InputDirectory lateinit var repo: Any
    @get:Input lateinit var branch: String
    @get:Input lateinit var sourceName: String
    @get:Input lateinit var targetName: String

    override fun action(rootDir: File) {
        val repoFile = project.file(repo)

        val git = Git(repoFile)

        if (repoFile.resolve(".git").exists()) {
            git("fetch").runOut()
        } else {
            git("init").executeSilently()
            git("add", "src").executeSilently()
            git("commit", "-m", "Initial", "--author=Auto <auto@mated.null>").executeSilently()
        }
        git("branch", "-f", "upstream", branch).executeSilently()

        rootDir.deleteRecursively()

        git.repo = rootDir.parentFile
        git("clone", repoFile.canonicalPath, rootDir.name).executeSilently()

        git.repo = rootDir

        if (!rootDir.resolve(".git").exists()) {
            git("init").executeSilently()
        }

        println("Resetting $targetName to $sourceName")
        git("remote", "rm", "upstream").runSilently()
        git("remote", "add", "upstream", repoFile.canonicalPath).executeSilently()

        if (git("checkout", "master").runSilently() != 0) {
            git("checkout", "-b", "master").executeSilently()
        }

        git("fetch", "upstream").executeSilently()
        git("reset", "--hard", "upstream/upstream").executeSilently()
    }
}
