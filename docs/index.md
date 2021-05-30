# Methanol

[![CI status](https://img.shields.io/github/workflow/status/mizosoft/methanol/CI?logo=github&style=flat-square)](https://github.com/mizosoft/methanol/actions)
[![Coverage Status](https://img.shields.io/coveralls/github/mizosoft/methanol?style=flat-square)](https://coveralls.io/github/mizosoft/methanol?branch=master)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.mizosoft.methanol/methanol?style=flat-square)](https://search.maven.org/search?q=g:%22com.github.mizosoft.methanol%22%20AND%20a:%22methanol%22)
[![Javadoc](https://img.shields.io/maven-central/v/com.github.mizosoft.methanol/methanol?color=blueviolet&label=Javadoc&style=flat-square)](https://mizosoft.github.io/methanol/api/latest/)

Java enjoys a neat, built-in [HTTP client](https://openjdk.java.net/groups/net/httpclient/intro.html).
However, it lacks key HTTP features like multipart uploads, caching and response decompression.
***Methanol*** comes in to fill these gaps. The library comprises a set of lightweight, yet powerful
extensions aimed at making it much easier & more productive to work with `java.net.http`. You can
say it's an `HttpClient` wrapper, but you'll see it almost seamlessly integrates with the standard
API you might already know.

Methanol isn't invasive. The core library has zero runtime dependencies. However, special attention
is given to object mapping, so integration with libraries like Jackson or Gson becomes a breeze.

## Installation

### Gradle

```gradle
implementation 'com.github.mizosoft.methanol:methanol:1.6.0'
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
  <artifactId>methanol</artifactId>
  <version>1.6.0</version>
</dependency>
```

## Contributing

See [CONTRIBUTING](CONTRIBUTING.md)

## License

[MIT](https://opensource.org/licenses/MIT)
