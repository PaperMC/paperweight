package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.bombe.type.signature.MethodSignature
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.lib.RebaseTodoLine
import org.eclipse.jgit.merge.MergeStrategy
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.*

@UntrackedTask(because = "Always process when requested")
abstract class ProcessNewSourceAT : JavaLauncherTask() {

    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:InputDirectory
    abstract val base: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val atFile: RegularFileProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @TaskAction
    fun run() {
        val inputDir = input.convertToPath()
        val baseDir = base.convertToPath()

        // find ATs, cleanup comment, apply to base
        val newATs = handleAts(baseDir, inputDir, atFile)
        println("ran handleAts, base should be patched")
        System.`in`.read()

        // save work and jump to AT commit
        val git = Git(inputDir)
        git("stash", "push").executeSilently(silenceErr = true)
        git("checkout", MACHE_TAG_ATS).executeSilently(silenceErr = true)

        // apply new ATs to source
        newATs.forEach { (path, ats) ->
            val source = inputDir.resolve(path)
            // TODO for some reason this does nothing
            applyNewATs(source, ats)
        }
        println("applied new ATs to source")
        System.`in`.read()

        // commit new ATs
        git("add", ".").executeSilently(silenceErr = true)
        git("commit", "--amend", "--no-edit").executeSilently(silenceErr = true)

        // TODO fix the tag

        // clean up tree: drop the old file commit and rebase the new file commit onto it
        git("switch", "-").executeSilently(silenceErr = true)
        println("switched back to main")
        System.`in`.read()
        org.eclipse.jgit.api.Git.open(inputDir.toFile()).rebase()
            .setUpstream(MACHE_TAG_ATS)
            .setStrategy(MergeStrategy.THEIRS)
            .runInteractively(object : RebaseCommand.InteractiveHandler {
                override fun prepareSteps(steps: MutableList<RebaseTodoLine>) {
                    // drop the first commit
                    steps.removeAt(0)
                }

                override fun modifyCommitMessage(message: String): String {
                    return message
                }
            })
            .call()

        println("finished rebase")
        System.`in`.read()

        git("stash", "pop").executeSilently(silenceErr = true)
    }

    private fun handleAts(
        baseDir: Path,
        inputDir: Path,
        atFile: RegularFileProperty
    ): MutableList<Pair<String, AccessTransformSet>> {
        val oldAts = AccessTransformFormats.FML.read(atFile.path)

        // handle AT
        val newATs = mutableListOf<Pair<String, AccessTransformSet>>()
        baseDir.walk()
            .map { it.relativeTo(baseDir).invariantSeparatorsPathString }
            .filter { it.endsWith(".java") }
            .forEach {
                val ats = AccessTransformSet.create()
                val source = inputDir.resolve(it)
                val decomp = baseDir.resolve(it)
                val className = it.replace(".java", "")
                if (findATInSource(source, ats, className)) {
                    applyNewATs(decomp, ats)
                    newATs.add(it to ats)
                }
                oldAts.merge(ats)
            }

        AccessTransformFormats.FML.writeLF(
            atFile.path,
            oldAts,
            "# This file is auto generated, any changes may be overridden!\n# See CONTRIBUTING.md on how to add access transformers.\n"
        )

        return newATs
    }

    private fun applyNewATs(decomp: Path, newAts: AccessTransformSet) {
        if (newAts.classes.isEmpty()) {
            println("new ats empty?!") //TODO remove
            return
        }
        println("apply new ats to $decomp, $newAts") //TODO remove

        val at = temporaryDir.toPath().resolve("ats.cfg").createParentDirectories()
        AccessTransformFormats.FML.writeLF(at, newAts)
        ats.run(
            launcher.get(),
            decomp,
            decomp,
            at,
            temporaryDir.toPath().resolve("jst_work").cleanDir(),
            singleFile = true,
        )
    }

    private fun findATInSource(source: Path, newAts: AccessTransformSet, className: String): Boolean {
        val sourceLines = source.readLines()
        var foundNew = false
        val fixedLines = ArrayList<String>(sourceLines.size)
        sourceLines.forEach { line ->
            if (!line.contains("// Paper-AT: ")) {
                fixedLines.add(line)
                return@forEach
            }

            foundNew = true

            val split = line.split("// Paper-AT: ")
            val at = split[1]
            try {
                val atClass = newAts.getOrCreateClass(className)
                val parts = at.split(" ")
                val accessTransform = atFromString(parts[0])
                val name = parts[1]
                val index = name.indexOf('(')
                if (index == -1) {
                    atClass.mergeField(name, accessTransform)
                } else {
                    atClass.mergeMethod(MethodSignature.of(name.substring(0, index), name.substring(index)), accessTransform)
                }
                logger.lifecycle("Found new AT in $className: $at -> $accessTransform")
            } catch (ex: Exception) {
                throw PaperweightException("Found invalid AT '$at' in class $className")
            }

            fixedLines.add(split[0].trimEnd())
        }

        if (foundNew) {
            source.writeText(fixedLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }

        return foundNew
    }
}
