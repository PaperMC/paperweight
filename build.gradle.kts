tasks.register("printVersion") {
    doFirst {
        println(version)
    }
}

subprojects {
    repositories {
        mavenLocal()
    }
}
