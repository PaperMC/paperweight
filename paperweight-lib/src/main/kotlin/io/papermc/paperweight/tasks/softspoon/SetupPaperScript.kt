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

package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

@UntrackedTask(because = "Always setup paper script")
abstract class SetupPaperScript : BaseTask() {

    data class Command(val name: String, val aliases: List<String>, val description: String, val commands: List<String>)

    @get:Input
    @get:Option(
        option = "script-name",
        description = "Allows setting the script name. Default is paper.",
    )
    abstract val scriptName: Property<String>

    @get:InputDirectory
    abstract val root: DirectoryProperty

    init {
        run {
            scriptName.convention("paper")
        }
    }

    @TaskAction
    open fun run() {
        val scriptName = scriptName.get()
        val rootPath = root.get().convertToPath().absolutePathString()
        val commands = listOf(
            Command("root", listOf(), "Jumps to the root directory (%root%)", listOf("cd %root%")),
            Command("api", listOf("a"), "Jumps to the api directory", listOf("cd %root%/paper-api")),
            Command("server", listOf("s"), "Jumps to the server directory", listOf("cd %root%/paper-server")),
            Command(
                "minecraft",
                listOf("m"),
                "Jumps to the Minecraft sources directory",
                listOf("cd %root%/paper-server/src/minecraft/java")
            ),
            Command(
                "resources",
                listOf("r"),
                "Jumps to the Minecraft resources directory",
                listOf("cd %root%/paper-server/src/minecraft/resources")
            ),
            Command(
                "fixupSourcePatches",
                listOf("fs"),
                "Puts the current source changes into the file patches commit",
                listOf(
                    "cd %root%/paper-server/src/minecraft/java",
                    "git add .",
                    "git commit --fixup file",
                    "git -c sequence.editor=: rebase -i --autosquash mache/main"
                )
            ),
            Command(
                "fixupResourcePatches",
                listOf("fr"),
                "Puts the current resource changes into the file patches commit",
                listOf(
                    "cd %root%/paper-server/src/minecraft/resources",
                    "git add .",
                    "git commit --fixup file",
                    "git -c sequence.editor=: rebase -i --autosquash mache/main"
                )
            )
        )

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val script = generateScript(scriptName, rootPath, commands, isWindows)

        if (isWindows) {
            val appData = System.getenv("APPDATA")
            val paperFolder = Path(appData).resolve(".paper")
            val path = paperFolder.resolve("$scriptName.bat")
            path.createParentDirectories()
            path.writeText(script)

            val alreadyOnPath = System.getenv("PATH").contains(paperFolder.absolutePathString())
            if (!alreadyOnPath) {
                val command = """
        [Environment]::SetEnvironmentVariable(\"Path\", [Environment]::GetEnvironmentVariable(\"Path\", \"User\") + \";${paperFolder.absolutePathString()}\", \"User\")
                """.trimIndent()
                val process = Runtime.getRuntime().exec(arrayOf("powershell.exe", "-Command", command))
                process.waitFor()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                if (process.exitValue() != 0) {
                    reader.lines().forEach { println(it) }
                    errorReader.lines().forEach { println(it) }
                    println(
                        "Error while installing $scriptName script, " +
                            "please add \"${paperFolder.absolutePathString()}\" to PATH manually (exit code ${process.exitValue()})"
                    )
                } else {
                    println("$scriptName script installed")
                }
            } else {
                println("$scriptName script created")
            }
        } else {
            val paperFolder = Path("~").resolve(".paper")
            val path = paperFolder.resolve("$scriptName.sh")
            path.createParentDirectories()
            path.writeText(script)

            val alreadyOnPath = System.getenv("PATH").contains(paperFolder.absolutePathString())
            if (!alreadyOnPath) {
                // TODO what do you want to do here?
                println("setting up path for linux is not supported yet, add ${paperFolder.absolutePathString()} to your path manually pls")
            } else {
                println("$scriptName script created")
            }
        }
    }

    fun generateScript(scriptName: String, root: String, commands: List<Command>, isWindows: Boolean): String {
        val script = StringBuilder()
        val lineSeparator = if (isWindows) "\r\n" else "\n"
        // header
        val scriptHeader = if (isWindows) {
            "@echo off$lineSeparator" + "goto:main$lineSeparator"
        } else {
            "#!/bin/bash$lineSeparator"
        }
        script.append(scriptHeader)
        script.append(lineSeparator)

        // help
        if (isWindows) {
            script.append(":help$lineSeparator")
            script.append("setlocal EnableDelayedExpansion$lineSeparator")
            script.append("set \"\"=\"$lineSeparator")
            script.append("echo Available commands:$lineSeparator")
        } else {
            script.append("help() {$lineSeparator")
            script.append("    echo \"Available commands:\"$lineSeparator")
        }

        commands.forEach { command ->
            val aliases = command.aliases.joinToString(",", " (alias: ", ")")
            val line = "$scriptName ${command.name}$aliases: ${command.description.replace("%root%", root)}"
            if (isWindows) {
                script.append("echo !\"!$line$lineSeparator")
            } else {
                script.append("    echo \"$line\"$lineSeparator")
            }
        }

        if (isWindows) {
            script.append("goto:eof$lineSeparator")
        } else {
            script.append("}$lineSeparator")
        }
        script.append(lineSeparator)

        // commands
        commands.forEach { command ->
            if (isWindows) {
                script.append(":${command.name}$lineSeparator")
                command.commands.forEach { cmd ->
                    script.append("${cmd.replace("%root%", root)}$lineSeparator")
                }
                script.append("goto:eof$lineSeparator")
                command.aliases.forEach { alias ->
                    script.append(":$alias$lineSeparator")
                    script.append("call:${command.name}$lineSeparator")
                    script.append("goto:eof$lineSeparator")
                }
            } else {
                script.append("${command.name}() {$lineSeparator")
                command.commands.forEach { cmd ->
                    script.append("    ${cmd.replace("%root%", root)}$lineSeparator")
                }
                script.append("}$lineSeparator")
                command.aliases.forEach { alias ->
                    script.append("$alias() {$lineSeparator")
                    script.append("    ${command.name}$lineSeparator")
                    script.append("}$lineSeparator")
                }
            }
            script.append(lineSeparator)
        }

        // main
        if (isWindows) {
            script.append(":main$lineSeparator")
            script.append("if \"%1\"==\"\" ($lineSeparator")
            script.append("    echo No command provided, try $scriptName help$lineSeparator")
            script.append("    exit /b 1$lineSeparator")
            script.append(")$lineSeparator")
            script.append(lineSeparator)
            script.append("call:%1$lineSeparator")
        } else {
            script.append("if [ \$# -eq 0 ]; then$lineSeparator")
            script.append("    echo \"No command provided, try $scriptName help\"$lineSeparator")
            script.append("    exit 1$lineSeparator")
            script.append("fi$lineSeparator")
            script.append(lineSeparator)
            script.append("\"$@\"$lineSeparator")
        }

        return script.toString()
    }
}
