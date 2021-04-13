!!! caution
    🚧 Under construction 🚧

# Methanol

<!-- ***Methanol*** is a library that comprises a set of lightweight, yet powerful extensions aimed at making it much
easier & much more productive to work with `java.net.http`. You can say it's an `HttpClient` wrapper, but you'll see
most of these extensions almost seamlessly integrate with the standard API you already know. Check out
the [snippets](#snippets) to whet your appetite! -->

Java enjoys a neat, built-in [HTTP client](https://openjdk.java.net/groups/net/httpclient/intro.html).
However, it lacks key HTTP features like multipart uploads, caching and response decompression.
***Methanol*** comes in to fill these gaps. The library comprises a set of lightweight, yet powerful
extensions aimed at making it much easier & more productive to work with `java.net.http`. You can say
it's an `HttClient` wrapper, but you'll see it almost seamlessly integrates with the standard API
you might already know.

<!-- However, sometimes it fails to satisfy our HTTP needs, like multipart uploads, caching or automatic
response decompression. -->

Methanol isn't invasive. The core library has zero runtime dependencies. However, special attention
is given to object mapping, so integration with libraries like Jackson or Gson becomes a breeze.

## Installation

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol:1.5.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol</artifactId>
    <version>1.5.0</version>
  </dependency>
</dependencies>
```

# License

[MIT](https://opensource.org/licenses/MIT)
