tasks.register("printVersion") {
    val ver = project.version
    doFirst {
        println(ver)
    }
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}
