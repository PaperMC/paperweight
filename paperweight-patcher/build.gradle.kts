plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    shade(project(projects.paperweightLib.dependencyProject.path, "sharedRuntime"))
    implementation(libs.bundles.kotson)
}

gradlePlugin {
    plugins.all {
        description = "Gradle plugin for developing Paper derivatives"
        implementationClass = "io.papermc.paperweight.patcher.PaperweightPatcher"
    }
}
