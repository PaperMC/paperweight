## paperweight

How to use this for testing:

Install this plugin to Maven Local:

```bash
./gradlew install
```

Clone a new Paper repo and checkout the `feature/mcp` branch.

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
