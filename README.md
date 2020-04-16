# Methanol

A lightweight library that complements `java.net.http` for a more thorough HTTP experience.

[![CI status](https://github.com/mizosoft/methanol/workflows/CI/badge.svg)](https://github.com/mizosoft/methanol/actions)
[![Coverage Status](https://coveralls.io/repos/github/mizosoft/methanol/badge.svg)](https://coveralls.io/github/mizosoft/methanol)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.mizosoft.methanol/methanol.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.github.mizosoft.methanol%22%20AND%20a:%22methanol%22)

## Overview

Features provided by ***Methanol*** include:

* Automatic response decompression.
* Special `BodyPublisher` implementations for form submission.
* An extensible object conversion mechanism, with optional support for JSON and Protocol Buffers.
* A custom `HttpClient` with request decoration and async `Publisher<HttpResponse<T>>` dispatches.
* Additional `BodyPublisher`, `BodySubscriber` and `BodyHandler` implementations.

## Installation

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol:1.0.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol</artifactId>
    <version>1.0.0</version>
  </dependency>
</dependencies>
```

## Documentation

* [Javadocs](https://mizosoft.github.io/methanol/1.x/doc/): API documentation
* [GitHub Wikis](https://github.com/mizosoft/methanol/wiki): User guide with examples

## License

[MIT](https://choosealicense.com/licenses/mit/)
