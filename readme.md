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

### Style Guide

This projects follows the opinionated [`ktlint`](https://ktlint.github.io/) linter and formatter. It uses the
[`ktlint-gradle`](https://github.com/jlleitschuh/ktlint-gradle) plugin to automatically check and format the code in
this repo.

Run the `format` task to automatically reformat the project using `ktlint` - which should handle most cases - to
maintain a consistent code style. Adjust any errors `ktlint` can't fix itself before committing.

```
./gradlew format
```

### IDE Setup

It's recommended to run the `ktlintApplyToIdea` and `addKtlintFormatGitPreCommitHook` tasks to configure your
IDE with `ktlint` style settings and to automatically format this project's code before committing:

```
./gradlew ktlintApplyToIdea addKtlintFormatGitPreCommitHook
```

> This project uses many new Gradle features to make sure we're ready for Gradle 7.0 and beyond, and we don't find
> ourselves stuck in a bad position where it's too hard for us to update. That being said, Gradle always marks new APIs
> as unstable for a bit until the next major version, so you should probably disable the "Unstable API Usages" inspection
> in IntelliJ as well. The easiest way to do this is just find any place where an "unstable API" is used (tons in
> `Paperweight.kt`) and disable the inspection from there.
