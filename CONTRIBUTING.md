# Contributing

Contributions are welcome! It is often a good idea to first discuss changes before submitting them.
If you're considering small changes (e.g. in documentation), you can open a PR directly.

You are more than welcome to:

* Report a bug
* Ask a question
* Propose a feature
* Submit a fix
* Improve documentation

## Prerequisites

It is recommended to set the `JAVA_HOME` environment variable to a JDK 17 (or later) directory in
order to properly work with Gradle.

## Building

Before submitting a change, make sure to first run tests and code analysis. 

`./gradlew clean check minVersionTest -PenableErrorprone`

[Error-prone][errorprone] checks are included but disabled by default for build speed. It is desirable
to make errorprone happy (maybe via reasonable suppressions). [Checker Framework][checker-framework]
is optionally used but mainly for informative reasons (it acts weird and crashes, patches regarding
this are welcome). Similarly, it can be run with `enableCheckerframework` project property.

### Brotli

[`methanol-brotli`][build-brotli] uses JNI and contains a `native` CMake project for native brotli code. The workflow for
native code changes is as follows:

 - Update core C or JNI code by copying from the [main brotli repo](https://github.com/google/brotli).
 - Run the [Build Brotli](https://github.com/mizosoft/methanol/actions/workflows/build-brotli.yml) action on CI.
 - Merge the automatically created pull request (e.g. [here](https://github.com/mizosoft/methanol/pull/163)).

## Dependencies

Methanol makes it easier to use third-party libraries with the HTTP client. However, it does so 
without making users pay for what they don't need. The core module currently has zero runtime 
dependencies, and it is important it remains so. Features that require dependencies should be in
separate modules, possibly with `ServideLoader` abstractions introduced in the core 
(e.g. `BodyAdapter`,`BodyDecoder`).

### Version Ranges

Integration modules (e.g., `methanol-jackson`) declare version ranges for their dependencies to give users flexibility:

```toml
# gradle/libs.versions.toml
jackson = "[2.13.0,3)"
gson = "[2.13.1,3)"
```

By default, Gradle resolves to the **latest** version in the range. However, we need to ensure code actually compiles and works with the **minimum** versions declared.

The `min-version-test` convention plugin creates a separate `minVersionTest` task that:

1. Creates a new source set (`minVersionTest`) with its own configurations.
2. Forces all version ranges to resolve to their minimum versions.
3. Runs the same tests as the regular `test` task.

```bash
# Normal test: uses latest Jackson
./gradlew :methanol-jackson:test

# Minimum version test: uses minimum Jackson
./gradlew :methanol-jackson:minVersionTest
```

## Style

The project mostly adheres to the [Google Style Guide][google-style-guide]. Changes are expected to
be consistent regarding key style aspects (2 space indentation, 4 for continuation, etc). It is
preferable to use [google-java-format][google-java-format] for new code.

[build-brotli]: <https://github.com/mizosoft/methanol/tree/master/methanol-brotli>
[errorprone]: <https://errorprone.info/>
[checker-framework]: <https://checkerframework.org/>
[google-brotli]: <https://github.com/google/brotli>
[google-style-guide]: <https://google.github.io/styleguide/javaguide.html>
[google-java-format]: <https://github.com/google/google-java-format>
