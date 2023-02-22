## paperweight

`paperweight` consists of three Gradle plugins:
- `paperweight-core`: Used to build Paper
- `paperweight-patcher`: Used to create forks of Paper or other `paperweight-patcher`-based forks
- `paperweight-userdev`: Used to develop internals plugins using Mojang mappings

### How to use this for testing:

- Install `paperweight` to Maven Local:
```bash
./gradlew publishToMavenLocal
```
- Add `mavenLocal()` for plugin resolution in your test project
  (see the [Gradle docs](https://docs.gradle.org/current/userguide/plugins.html#sec:custom_plugin_repositories) for more details)
- Adjust the `paperweight` version in your test project
  - Local versions of `paperweight` will use have the `-SNAPSHOT` suffix in the version from `gradle.properties` replaced by `-LOCAL-SNAPSHOT`

> Most output `paperweight` creates goes into `<project-root>/.gradle/caches/paperweight`

### Debugging

Create a remote JVM debug run configuration in IntelliJ which connects to port 5005, then run Gradle in debug mode:

```bash
./gradlew --no-daemon -Dorg.gradle.debug=true <task>
```

Gradle will not start until the debugger is connected so you don't need to worry about missing a breakpoint.

### Style Guide

This project follows the opinionated [`ktlint`](https://ktlint.github.io/) linter and formatter. It uses the
[`ktlint-gradle`](https://github.com/jlleitschuh/ktlint-gradle) plugin to automatically check and format the code in
this repo.

Run the `format` task to automatically reformat the project using `ktlint` - which should handle most cases - to
maintain a consistent code style. Adjust any errors `ktlint` can't fix itself before committing.

```
./gradlew format
```

### IDE Setup

It's recommended to run the `ktlintApplyToIdea` and `addKtlintFormatGitPreCommitHook` tasks to configure your IDE
with `ktlint` style settings and to automatically format this project's code before committing:

```
./gradlew ktlintApplyToIdea addKtlintFormatGitPreCommitHook
```

> This project uses lots of new Gradle features , to make sure we're ready for Gradle 7.0 and beyond, and we don't find
> ourselves stuck in a bad position where it's too hard for us to update. That being said, Gradle always marks new APIs
> as unstable for a bit until the next major version, so you should probably disable the "Unstable API Usages" inspection
> in IntelliJ as well. The easiest way to do this is just to find any place where an "unstable API" is used (tons in
> `Paperweight.kt`) and disable the inspection from there.
