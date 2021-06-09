plugins {
    `config-kotlin`
}

dependencies {
    implementation(libs.httpclient)
    implementation(libs.kotson)

    // ASM for inspection
    implementation(libs.bundles.asm)

    implementation(libs.bundles.hypo)
    implementation(libs.bundles.cadix)

    implementation(libs.lorenzTiny)

    implementation(libs.jbsdiff)
}
