plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    implementation(libs.bundles.kotson)
    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }
    implementation(libs.coroutines)
}

gradlePlugin {
    setupPlugin("userdev") {
        description = "Gradle plugin for developing Paper plugins using server internals"
        implementationClass = "io.papermc.paperweight.userdev.PaperweightUser"
    }
}
