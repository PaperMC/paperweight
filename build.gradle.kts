tasks.register("printVersion") {
    doFirst {
        println(version)
    }
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}
