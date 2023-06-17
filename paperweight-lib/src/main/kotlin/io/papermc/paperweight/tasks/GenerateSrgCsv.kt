package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import java.util.Optional
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.InnerClassMapping
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateSrgCsv : ControllableOutputTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ourMappings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val srgMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputCsv: RegularFileProperty

    @TaskAction
    fun run() {
        val obfToOurs = MappingFormats.TINY.read(ourMappings.path, OBF_NAMESPACE, DEOBF_NAMESPACE)
        val obfToSrg = TSrg2Reader(Files.newBufferedReader(srgMappings.path)).read()

        val srgToOurs = mutableMapOf<String, String>()
        val classes = mutableListOf<ClassMapping<*, *>>()
        //classes.addAll(obfToOurs.topLevelClassMappings)
        //while (classes.isNotEmpty()) {
        //    val namedClass = classes[0]
        //    classes.removeAt(0)
        //
        //    val srgClass = if (namedClass is InnerClassMapping) {
        //        val namedParentClass = namedClass.parent
        //        val namedSrgClass = obfToSrg.getClassMapping(namedParentClass.obfuscatedName).orElseThrow()
        //        namedSrgClass.getInnerClassMapping(namedClass.obfuscatedName).orElseThrow()
        //    } else {
        //        classes.addAll(namedClass.innerClassMappings)
        //        obfToSrg.getClassMapping(namedClass.obfuscatedName).orElseThrow()
        //    }
        //
        //    if (namedClass.deobfuscatedName.contains("LootDataType")) {
        //        println(namedClass)
        //        println(srgClass)
        //    }
        //
        //    namedClass.fieldMappings.forEach { namedField ->
        //        val srgField = srgClass.getFieldMapping(namedField.obfuscatedName).orElseThrow()
        //        srgToOurs[srgField.deobfuscatedName] = namedField.deobfuscatedName
        //    }
        //
        //    namedClass.methodMappings.forEach { namedMethod ->
        //        val srgMethod = srgClass.getMethodMapping(namedMethod.obfuscatedName, namedMethod.obfuscatedDescriptor).orElseThrow()
        //        srgToOurs[srgMethod.deobfuscatedName] = namedMethod.deobfuscatedName
        //
        //        namedMethod.parameterMappings.forEachIndexed { index, namedParam ->
        //            val srgParam = srgMethod.getParameterMapping(index).orElseThrow()
        //            srgToOurs[srgParam.deobfuscatedName] = namedParam.deobfuscatedName
        //        }
        //    }
        //}

        classes.addAll(obfToSrg.topLevelClassMappings)
        while (classes.isNotEmpty()) {
            val srgClass = classes[0]
            classes.removeAt(0)

            val namedClass = if (srgClass is InnerClassMapping) {
                val srgParentClass = srgClass.parent
                val obfSrgClass = obfToOurs.getClassMapping(srgParentClass.obfuscatedName)
                if (obfSrgClass.isEmpty) {
                    Optional.empty()
                } else {
                    obfSrgClass.get().getInnerClassMapping(srgClass.obfuscatedName)
                }
            } else {
                classes.addAll(srgClass.innerClassMappings)
                obfToOurs.getClassMapping(srgClass.obfuscatedName)
            }

            if (!namedClass.isPresent) {
                println("skipping class ${srgClass.deobfuscatedName}")
                continue
            }

            srgClass.fieldMappings.forEach { srgField ->
                val namedField = namedClass.get().getFieldMapping(srgField.obfuscatedName)
                if (namedField.isPresent) {
                    srgToOurs[srgField.deobfuscatedName] = namedField.get().deobfuscatedName
                } else {
                    srgToOurs[srgField.deobfuscatedName] = srgField.obfuscatedName
                }
            }

            srgClass.methodMappings.forEach { srgMethod ->
                val namedMethod =
                    namedClass.get().getMethodMapping(srgMethod.obfuscatedName, srgMethod.obfuscatedDescriptor)

                if (namedMethod.isPresent) {
                    srgToOurs[srgMethod.deobfuscatedName] = namedMethod.get().deobfuscatedName

                    val params = namedMethod.get().parameterMappings.toTypedArray().reversedArray()
                    srgMethod.parameterMappings.reversed().forEachIndexed { index, srgParam ->
                        if (srgParam.deobfuscatedName.startsWith("f_")) {
                            // record pattern! no touchy!
                            return@forEachIndexed
                        }
                        val namedParam = if (srgMethod.obfuscatedName == "<init>") {
                            // ctors aren't synthetic, no need for fuckery
                            namedMethod.get().getParameterMapping(srgParam.index)
                        } else if (params.size > index) {
                            Optional.of(params[index])
                        } else {
                            Optional.empty()
                        }

                        if (namedParam.isPresent) {
                            srgToOurs[srgParam.deobfuscatedName] = namedParam.get().deobfuscatedName
                        } else {
                            println("skipping param $srgParam of method ${srgMethod.deobfuscatedName}")
                            //println(namedMethod.get().parameterMappings)
                            //println(namedMethod.get())
                            //println(srgMethod)
                            srgToOurs[srgParam.deobfuscatedName] = srgParam.deobfuscatedName
                        }
                    }
                } else {
                    if (srgMethod.obfuscatedName != srgMethod.deobfuscatedName) {
                        println("skipping method ${srgMethod.obfuscatedName} ${srgMethod.deobfuscatedName}")
                        //println(namedClass.get())
                        //println(srgMethod)
                    }
                    srgToOurs[srgMethod.deobfuscatedName] = srgMethod.obfuscatedName
                }
            }
        }

        Files.write(outputCsv.path, srgToOurs.map
        { it.key + "," + it.value })
    }
}
