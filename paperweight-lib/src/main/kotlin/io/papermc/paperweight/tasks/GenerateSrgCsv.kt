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
                val namedMethod = namedClass.get().getMethodMapping(srgMethod.obfuscatedName, srgMethod.obfuscatedDescriptor)

                if (/*srgMethod.deobfuscatedName == "m_193328_" || srgClass.deobfuscatedName.contains("C_183045_") || */srgClass.deobfuscatedName.contains("C_278349_")) {
                    println("wooo1")
                    println(srgMethod.parameterMappings)
                    println(srgMethod)
                    if (namedMethod.isPresent) {
                        println(namedMethod.get().parameterMappings)
                        println(namedMethod.get())
                    } else {
                        println("no named")
                    }
                }

                if (namedMethod.isPresent) {
                    srgToOurs[srgMethod.deobfuscatedName] = namedMethod.get().deobfuscatedName


                    val params = namedMethod.get().parameterMappings.toTypedArray()
                    srgMethod.parameterMappings.forEachIndexed { index, srgParam ->
                        val namedParam = if (params.size > index) {
                            Optional.of(params[index])
                        } else {
                            println("empty")
                            Optional.empty()
                        }
                        //val namedParam = namedMethod.get().getParameterMapping(srgParam.obfuscatedName.toInt())

                        //if (srgMethod.deobfuscatedName == "m_193328_") {
                        //    println("wooo $srgParam")
                        //    println(namedMethod.get().parameterMappings)
                        //    println(namedMethod.get())
                        //    println(srgMethod.parameterMappings)
                        //    println(srgMethod)
                        //}

                        if (namedParam.isPresent) {
                            srgToOurs[srgParam.deobfuscatedName] = namedParam.get().deobfuscatedName
                        } else {
                            //println("skipping param $srgParam")
                            //println(namedMethod.get().parameterMappings)
                            //println(namedMethod.get())
                            //println(srgMethod)
                            srgToOurs[srgParam.deobfuscatedName] = srgParam.obfuscatedName
                        }
                    }
                } else {
                    //println("skipping method ${srgMethod.obfuscatedName} ${srgMethod.deobfuscatedName}")
                    //println(namedClass.get())
                    //println(srgMethod)
                    srgToOurs[srgMethod.deobfuscatedName] = srgMethod.obfuscatedName
                }
            }
        }

        Files.write(outputCsv.path, srgToOurs.map { it.key + "," + it.value })
    }
}
