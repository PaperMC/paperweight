plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    download(libs.restamp)

    implementation(libs.bundles.kotson)
    implementation(libs.coroutines)
}

gradlePlugin {
    plugins.all {
        description = "Gradle plugin for developing Paper"
        implementationClass = "io.papermc.paperweight.core.PaperweightCore"
    }
}
