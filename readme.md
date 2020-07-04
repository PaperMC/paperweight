How to use this for testing:

Install this plugin to Maven Local:

```bash
./gradlew install
```

Clone new Paper repo  and checkout the `ver/1.16` branch.

Copy the `mcp/` directory from the (now very old) `feature/mcp` branch into your working directory. I have no idea
if any of the values in this directory are still correct or still apply to 1.16, and they almost certainly don't,
but it's a good starting off point to test with until the work can be done to re-create these files for 1.16.

Create a file called `build.gradle.kts` in the root project directy and copy in this text:
```kotlin
plugins {
    id("com.github.johnrengelman.shadow") version "6.0.0" apply false
    id("io.papermc.paperweight") version "1.0.0-SNAPSHOT"
}

group = "com.destroystokyo.paper"
version = "1.16.1-R0.1-SNAPSHOT"

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.github.johnrengelman.shadow")

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://repo.md-5.net/content/repositories/releases/")
        maven("https://ci.emc.gs/nexus/content/groups/aikar/")
        maven("https://papermc.io/repo/repository/maven-public/")
    }
}

paperweight {
    minecraftVersion.set("1.16.1")
    mcpVersion.set("20200625.160719")
    mcpMappingsChannel.set("snapshot")
    mcpMappingsVersion.set("20200702-1.15.1")
}
```

Now create a file called `settings.gradle.kts` in teh root project directory and copy in this text:
```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        jcenter()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://files.minecraftforge.net/maven/")
        gradlePluginPortal()
    }
}
```

Now create the Gradle wrapper:
```bash
gradle wrapper --gradle-version=6.5
```

From this point you can now test the plugin by installing it to Maven Local (`./gradlew install`) and then running
the task you want to test in your new MCP Paper repo.

Other helpful things that you will probably want to do:

Add the following files to the `.gitignore` file in the Paper repo:
```
.gradle/
build/
```

Either delete or rename the `pom.xml` file to reduce the chance of IntelliJ getting confused
(if you decide to open the project in IntelliJ). 
