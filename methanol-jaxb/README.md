# methanol-jaxb

`BodyAdapter` implementations for XML binding using [JAXB][jaxb].

## Installation

Add this module as a dependency:

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol-jaxb:1.2.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol-jaxb</artifactId>
    <version>1.2.0</version>
  </dependency>
</dependencies>
```

### Registering providers

`Encoder` and `Decoder` implementations are not service-provided by default. You must add
provider declarations yourself if you intend to use them for dynamic request/response conversion.

#### Module path

Add this class to your module:

```java
public class JaxbProviders {
  private JaxbProviders() {}

  public static class EncoderProvider {
    private EncoderProvider() {}

    public static BodyAdapter.Encoder provider() {
      // By default, JAXBContexts are created and cached for each type
      return JaxbAdapterFactory.createEncoder();
    }
  }

  public static class DecoderProvider {
    private DecoderProvider() {}

    public static BodyAdapter.Decoder provider() {
      // May use a custom JAXBContext (provided indirectly via JaxbBindingFactory)
      JaxbBindingFactory factory = ...
      return JaxbAdapterFactory.createDecoder(factory);
    }
  }
}
```

Then add provider declarations in your `module-info.java`:

```java
provides BodyAdapter.Encoder with JaxbProviders.EncoderProvider;
provides BodyAdapter.Decoder with JaxbProviders.DecoderProvider;
```

#### Class path

If you're running from the classpath, you must instead implement delegating `Encoder` and `Decoder`
that forward to the instances created by `JaxbAdapterFactory`. Then declare them in
`META-INF/services` entries as described in `ServiceLoader`'s [Javadoc][ServiceLoader].

## Usage

```java
// For request
MyDto dto = ...
HttpRequest request = HttpRequest.newBuilder(...)
    .POST(MoreBodyPublishers.ofObject(dto, MediaType.APPLICATION_XML))
     ...
    .build();

// For response
HttpResponse<MyDto> response = client.send(request, MoreBodyHandlers.ofObject(MyDto.class));
```

[ServiceLoader]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
[jaxb]: https://javaee.github.io/jaxb-v2/
