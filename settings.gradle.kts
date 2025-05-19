plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "paperweight"

include("paperweight-core", "paperweight-lib", "paperweight-userdev")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
