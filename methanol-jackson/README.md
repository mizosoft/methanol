# methanol-jackson

Provides `BodyAdapter` implementations for JSON using the [Jackson][jackson_github] library.

## Installation

Add this module as a dependency:

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol-jackson:1.1.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol-jackson</artifactId>
    <version>1.1.0</version>
  </dependency>
</dependencies>
```

### Registering providers

`Encoder` and `Decoder` implementations are not service-provided by default. You must add
provider declarations yourself if you intend to use them for dynamic request/response conversion.

#### Module path

Add this class to your module:

```java
public class JacksonProviders {
  private JacksonProviders() {}

  public static class EncoderProvider {
    private EncoderProvider() {}

    public static Encoder provider() {
      // Use a default ObjectMapper
      return JacksonAdapterFactory.createEncoder();
    }
  }

  public static class DecoderProvider {
    private DecoderProvider() {}

    public static BodyAdapter.Decoder provider() {
      // Or may use custom ObjectMapper
      ObjectMapper mapper = ...
      return JacksonAdapterFactory.createDecoder(mapper);
    }
  }
}
```

Then add provider declarations in your `module-info.java`:

```java
provides BodyAdapter.Encoder with JacksonProviders.EncoderProvider;
provides BodyAdapter.Decoder with JacksonProviders.DecoderProvider;
```

#### Class path

If you're running from the classpath, you must instead implement delegating `Encoder` and `Decoder`
that forward to the instances created by `JacksonAdapterFactory`. Then declare them in
`META-INF/services` entries as described in `ServiceLoader`'s [Javadoc][ServiceLoader].

## Usage

### For request

```java
MyDto dto = ...
var requestBody = MoreBodyPublishers.ofObject(dto, MediaType.of("application", "json"));
```

### For response

```java
HttpResponse<MyDto> response = client.send(request, MoreBodyHandlers.ofObject(MyDto.class));
```

[ServiceLoader]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
[jackson_github]: https://github.com/fasterXML/jackson
