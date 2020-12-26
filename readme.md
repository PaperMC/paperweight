## paperweight

How to use this for testing:

Install this plugin to Maven Local:

```bash
./gradlew publishToMavenLocal
```

Clone a new Paper repo and checkout the `feature/mojmap` branch.

* Add `mavenLocal()` to the list of repos in `settings.gradle.kts`.
* Change `paperweight` version to `1.0.0-LOCAL-SNAPSHOT` in the `plugins {}` block in `build.gradle.kts`.

Run the task (on the Paper repo) to set up the development environment:

```bash
./gradlew patchPaper
```

> All task outputs `paperweight` creates goes into `<project-root>/.gradle/caches`.

### Debugging

Create a remote JVM debug run configuration in IntelliJ which connects to port 5005, then run Gradle in debug mode:

```bash
./gradlew --no-daemon -Dorg.gradle.debug=true <task>
```

Gradle will not start until the debugger is connected so you don't need to worry about missing a breakpoint.
