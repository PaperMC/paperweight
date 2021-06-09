import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

plugins {
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.gradle.licenser)
    implementation(libs.gradle.ktlint)
    implementation(libs.gradle.shadow)
    implementation(libs.gradle.kotlin.dsl)
    implementation(libs.gradle.kotlin.plugin.withVersion(embeddedKotlinVersion))
}

fun Provider<MinimalExternalModuleDependency>.withVersion(version: String): Provider<String> {
    return map { "${it.module.group}:${it.module.name}:$version" }
}
