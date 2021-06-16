/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.DosFileAttributeView
import java.util.EnumSet
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.FileSystemLocationProperty
import org.gradle.api.provider.Provider

// utils for dealing with java.nio.file.Path and java.io.File

val FileSystemLocation.path: Path
    get() = asFile.toPath()
val Provider<out FileSystemLocation>.path: Path
    get() = get().path
val Provider<out FileSystemLocation>.pathOrNull: Path?
    get() = orNull?.path
fun FileSystemLocationProperty<*>.set(path: Path?) = set(path?.toFile())
fun FileSystemLocationProperty<*>.convention(path: Path?) = convention(path?.toFile())

val Path.isLibraryJar: Boolean
    get() = name.endsWith(".jar") && !name.endsWith("-sources.jar")

fun Path.deleteForcefully() {
    fixWindowsPermissionsForDeletion()
    deleteIfExists()
}

fun Path.deleteRecursively() {
    if (!exists()) {
        return
    }
    if (!isDirectory()) {
        fixWindowsPermissionsForDeletion()
        deleteIfExists()
        return
    }

    val fileList = Files.walk(this).use { stream ->
        stream.asSequence().toList()
    }

    fileList.forEach { f -> f.fixWindowsPermissionsForDeletion() }
    fileList.asReversed().forEach { f -> f.deleteIfExists() }
}

private val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
private val currentUser = System.getProperty("user.name")

private fun Path.fixWindowsPermissionsForDeletion() {
    if (!isWindows || notExists()) {
        return
    }

    runCatching {
        val aclAttr = fileAttributesView<AclFileAttributeView>()

        val user = fileSystem.userPrincipalLookupService.lookupPrincipalByName(currentUser)

        val builder = AclEntry.newBuilder()
        val perms = EnumSet.allOf(AclEntryPermission::class.java)
        builder.setPermissions(perms)
        builder.setPrincipal(user)
        builder.setType(AclEntryType.ALLOW)

        aclAttr.acl = listOf(builder.build())
    }

    runCatching {
        val dosAttr = fileAttributesView<DosFileAttributeView>()
        dosAttr.setHidden(false)
        dosAttr.setReadOnly(false)
    }
}

fun Path.copyRecursively(target: Path) {
    target.createDirectories()
    if (!exists()) {
        return
    }
    Files.walk(this).use { stream ->
        stream.forEach { f ->
            val targetPath = target.resolve(f.relativeTo(this))
            if (f.isDirectory()) {
                targetPath.createDirectories()
            } else {
                f.copyTo(targetPath)
            }
        }
    }
}

private fun Path.jarUri(): URI {
    return URI.create("jar:${toUri()}")
}

fun Path.openZip(): FileSystem {
    return FileSystems.newFileSystem(jarUri(), emptyMap<String, Any>())
}

fun Path.writeZip(): FileSystem {
    return FileSystems.newFileSystem(jarUri(), mapOf("create" to "true"))
}

fun FileSystem.walk(): Stream<Path> {
    return StreamSupport.stream(rootDirectories.spliterator(), false)
        .flatMap { Files.walk(it) }
}

fun ProcessBuilder.directory(path: Path): ProcessBuilder = directory(path.toFile())
