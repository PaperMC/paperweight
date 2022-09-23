/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Not worth caching")
abstract class ArchivePublication : ZippedTask() {
    @get:InputDirectory
    abstract val repo: DirectoryProperty

    override fun run(rootDir: Path) {
        repo.path.copyRecursivelyTo(rootDir)
    }
}

fun Project.archivePublication(publicationName: String): TaskProvider<ArchivePublication> {
    val repoName = "archiveTempRepo_$publicationName"
    val repoDir = layout.buildDirectory.dir(repoName)
    the<PublishingExtension>().repositories {
        maven {
            name = repoName
            setUrl(repoDir)
        }
    }
    val cleanTask = tasks.register<Delete>("clean${repoName.capitalize()}") {
        delete(repoDir)
        doLast {
            repoDir.get().path.createDirectories()
        }
    }
    val archiveTask = tasks.register<ArchivePublication>("archive${publicationName.capitalize()}Publication") {
        repo.set(repoDir)
    }
    tasks.all {
        if (name == "publish${publicationName.capitalize()}PublicationTo${repoName.capitalize()}Repository") {
            dependsOn(cleanTask)
            archiveTask.get().dependsOn(this)
        }
    }
    return archiveTask
}
