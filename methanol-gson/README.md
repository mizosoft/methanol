# methanol-gson

`BodyAdapter` implementations for JSON using the [Gson][gson_github] library.

## Installation

Add this module as a dependency:

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol-gson:1.3.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol-gson</artifactId>
    <version>1.3.0</version>
  </dependency>
</dependencies>
```

### Registering providers

`Encoder` and `Decoder` implementations are not service-provided by default. You must add
provider declarations yourself if you intend to use them for dynamic request/response conversion.

#### Module path

Add this class to your module:

```java
public class GsonProviders {
  private GsonProviders() {}

  public static class EncoderProvider {
    private EncoderProvider() {}

    public static BodyAdapter.Encoder provider() {
      // Use a default Gson instance
      return GsonAdapterFactory.createEncoder();
    }
  }

  public static class DecoderProvider {
    private DecoderProvider() {}

    public static BodyAdapter.Decoder provider() {
      // May use a custom Gson instance also
      Gson gson = ...
      return GsonAdapterFactory.createDecoder(gson);
    }
  }
}
```

Then add provider declarations in your `module-info.java`:

```java
provides BodyAdapter.Encoder with GsonProviders.EncoderProvider;
provides BodyAdapter.Decoder with GsonProviders.DecoderProvider;
```

#### Class path

If you're running from the classpath, you must instead implement delegating `Encoder` and `Decoder`
that forward to the instances created by `GsonAdapterFactory`. Then declare them in
`META-INF/services` entries as described in `ServiceLoader`'s [Javadoc][ServiceLoader].

## Usage

```java
// For request
MyDto dto = ...
HttpRequest request = HttpRequest.newBuilder(...)
    .POST(MoreBodyPublishers.ofObject(dto, MediaType.APPLICATION_JSON))
     ...
    .build();

// For response
HttpResponse<MyDto> response = client.send(request, MoreBodyHandlers.ofObject(MyDto.class));
```

[ServiceLoader]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
[gson_github]: https://github.com/google/gson
