# methanol-jackson-flux

Provides `Publisher`-based `BodyAdapter` implementations for JSON using [Jackson][jackson_github]
and [Project reactor][reactor_github].

## Encoding and Decoding

Any subtype of `org.reactivestreams.Publisher` or `Flow.Publisher` is encoded to a JSON array
containing zero or more elements corresponding to each published object. `Mono` sources are instead
encoded into a single JSON object (if completed with any).

Decodable types are `Flux`, `Mono`, `org.reactivestreams.Publisher` and `Flow.Publisher`. For all
aforementioned types except `Mono`, a JSON array is first tokenized into its individual elements before
deserialization.

An `HttpResponse` handled with such a decoder is completed immediately after the response headers
are received. Additionally, the decoder always uses Jackson's non-blocking parser (using a streaming
sources is not possible). [Deferring][wiki_t_vs_supplier] the response body into a `Supplier` gives
no benefits with this decoder.

## Installation

Add this module as a dependency (*note: not yet released*):

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol-jackson:1.2.1-SNAPSHOT'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol-jackson</artifactId>
    <version>1.2.1-SNAPSHOT</version>
  </dependency>
</dependencies>
```

### Registering providers

`Encoder` and `Decoder` implementations are not service-provided by default. You must add
provider declarations yourself if you intend to use them for dynamic request/response conversion.

#### Module path

Add this class to your module:

```java
public class JacksonFluxProviders {
  private JacksonFluxProviders() {}

  public static class EncoderProvider {
    private EncoderProvider() {}

    public static BodyAdapter.Encoder provider() {
      // Use a default ObjectMapper
      return JacksonFluxAdapterFactory.createEncoder();
    }
  }

  public static class DecoderProvider {
    private DecoderProvider() {}

    public static BodyAdapter.Decoder provider() {
      // Or may use custom ObjectMapper
      ObjectMapper mapper = ...
      return JacksonFluAdapterFactory.createDecoder(mapper);
    }
  }
}
```

Then add provider declarations in your `module-info.java`:

```java
provides BodyAdapter.Encoder with JacksonFluxProviders.EncoderProvider;
provides BodyAdapter.Decoder with JacksonFluxProviders.DecoderProvider;
```

#### Class path

If you're running from the classpath, you must instead implement delegating `Encoder` and `Decoder`
that forward to the instances created by `JacksonFluxAdapterFactory`. Then declare them in
`META-INF/services` entries as described in `ServiceLoader`'s [Javadoc][ServiceLoader].

## Usage

### For request

```java
Mono<MyDto> mono = ...
BodyPublisher requestBody = MoreBodyPublishers.ofObject(mono, MediaType.of("application", "json"));
```

### For response

```java
HttpResponse<Flux<MyDto>> response =
    client.send(request, MoreBodyHandlers.ofObject(new TypeRef<Flux<MyDto>>() {}));
```

[jackson_github]: https://github.com/fasterXML/jackson
[reactor_github]: https://github.com/reactor/reactor-core
[wiki_t_vs_supplier]: https://github.com/mizosoft/methanol/wiki/ConversionWiki#t-vs-suppliert
[ServiceLoader]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
