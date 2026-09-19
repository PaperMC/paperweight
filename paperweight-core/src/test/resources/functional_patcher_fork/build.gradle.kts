import io.papermc.paperweight.core.tasks.CheckoutRepo
import org.gradle.api.plugins.JavaPluginExtension

plugins {
    id("io.papermc.paperweight.patcher")
}

subprojects {
    plugins.apply("java")
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
    }
}

paperweight {
    upstreams.register("forkOfPaper") {
        repo = file("forkOfPaper-upstream").absolutePath
        ref = "HEAD"
        applyUpstreamNested = false

        patchFile {
            path = "forkOfPaper-server/README.md"
            outputFile = file("forkOfPaper-server/README.md")
            patchFile = file("forkOfPaper-server/README.md.patch")
        }
    }
}

tasks.register("writeForkOfPaperNestedOutput") {
    val checkoutForkOfPaperRepo = tasks.named<CheckoutRepo>("checkoutForkOfPaperRepo")
    val outputDir = checkoutForkOfPaperRepo.flatMap { it.outputDir.dir("forkOfPaper-server/generated") }

    outputs.dir(outputDir)
    dependsOn(checkoutForkOfPaperRepo)

    doLast {
        val outputFile = outputDir.get().file("generated.txt").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText("generated")
    }
}
