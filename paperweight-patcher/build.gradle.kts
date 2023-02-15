plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    implementation(libs.kotson)
}

gradlePlugin {
    plugins.all {
        implementationClass = "io.papermc.paperweight.patcher.PaperweightPatcher"
    }
}
