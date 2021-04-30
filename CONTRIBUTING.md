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

It is recommended to set the `JAVA_HOME` environment variable to a JDK 11 (or later) directory in
order to properly work with Gradle.

## Building

Before submitting a change, make sure to first run tests and code analysis. 

`./gradlew clean check -PenableErrorprone`

[Error-prone][errorprone] checks are included but disabled by default for build speed. It is desirable
to make errorprone happy (maybe via reasonable suppressions). [Checker Framework][checker-framework]
is optionally used but mainly for informative reasons (its acts weird and crashes, patches regarding
this are welcome). Similarly, it can be run with `enableCheckerframework` project property.

[`methanol-brotli`][build-brotli] uses JNI and contains a `c/c++` subproject for the brotli decoder.
The native project is not included in the build by default. You can do so with project property
`includeBrotliJni` or running the `installBrotli` task if you have a proper tool chain. Note that
brotli isn't yet supported for macOS, so make sure to exclude brotli tests when running from a Mac:

`./gradlew check -PenableErrorprone -x methanol-brotli:test`

## Dependencies

Methanol makes it easier to use third-party libraries with the HTTP client. However, it does so 
without making users pay for what they don't need. The core module currently has zero runtime 
dependencies, and it is important it remains so. Features that require dependencies should be in
separate modules, possibly with `ServideLoader` abstractions introduced in the core 
(e.g. `BodyAdapter`,`BodyDecoder`).

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
