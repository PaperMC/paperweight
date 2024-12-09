plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    shade(project(projects.paperweightLib.path, "sharedRuntime"))
    implementation(libs.bundles.kotson)
    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }
}

gradlePlugin {
    plugins.all {
        description = "Gradle plugin for developing Paper plugins using server internals"
        implementationClass = "io.papermc.paperweight.userdev.PaperweightUser"
    }
}
