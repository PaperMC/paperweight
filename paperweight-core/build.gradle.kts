plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)

    implementation(libs.bundles.kotson)
    implementation(libs.coroutines)
    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }
    implementation(libs.bundles.cadix)
    shade(libs.jgit)

    testImplementation(project(":paperweight-lib", "testClassesJar"))
}

gradlePlugin {
    setupPlugin("core") {
        description = "Gradle plugin for developing Paper Server and derivatives"
        implementationClass = "io.papermc.paperweight.core.PaperweightCore"
    }
    setupPlugin("patcher") {
        description = "Gradle plugin for developing Paper derivatives"
        implementationClass = "io.papermc.paperweight.patcher.PaperweightPatcher"
    }
}
