plugins {
    id("java")
    id("io.papermc.paperweight.core")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    mache(files("../mache.zip"))
}

paperweight {
    minecraftVersion = "fake"
    minecraftManifestUrl = "file://project/../fake_mojang/version_manifest.json"

    val forkOfPaper = forks.register("forkOfPaper") {
        upstream.patchDir("paperServer") {
            upstreamPath = "paper-server"
            excludes = listOf("src/minecraft")
            patchesDir = rootDirectory.dir("forkOfPaper-server/paper-patches")
            outputDir = rootDirectory.dir("paper-server")
        }
    }

    val forkOfFork = forks.register("forkOfFork") {
        forks = forkOfPaper

        upstream.patchDir("forkOfPaperServer") {
            upstreamPath = "forkOfPaper-server"
            excludes = listOf("src/minecraft")
            patchesDir = rootDirectory.dir("forkOfFork-server/forkOfPaper-patches")
            outputDir = rootDirectory.dir("forkOfPaper-server")
        }
    }

    activeFork = forkOfFork
}
