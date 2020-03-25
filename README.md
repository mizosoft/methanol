# Methanol

A lightweight library that complements `java.net.http` for a more thorough HTTP experience.

[![CI status](https://github.com/mizosoft/methanol/workflows/CI/badge.svg)](https://github.com/mizosoft/methanol/actions)
[![Coverage Status](https://coveralls.io/repos/github/mizosoft/methanol/badge.svg)](https://coveralls.io/github/mizosoft/methanol)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.mizosoft.methanol/methanol/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.mizosoft.methanol/methanol)

## Overview

Features provided by ***Methanol*** include, but are not limited to:

* Automatic response decompression.
* Special `BodyPublisher` implementations for form submission.
* An extensible object conversion mechanism.
* Modules for object conversion using formats like JSON and Google's Protocol Buffers.
* Additional `BodyPublisher`, `BodySubscriber` and `BodyHandler` implementations.

## Installation (Note: not yet released)

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
    <scope>compile</scope>
  </dependency>
</dependencies>
```

## Documentation

* [Javadocs](https://mizosoft.github.io/methanol/1.x/doc/): Latest API documentation
* [GitHub Wikis](https://github.com/mizosoft/methanol/wiki): User guide with examples

## License

[MIT](https://choosealicense.com/licenses/mit/)
