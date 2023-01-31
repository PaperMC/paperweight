plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    implementation(libs.kotson)
    implementation("net.minecraftforge:DiffPatch:2.0.7:all") {
        isTransitive = false
    }
}
