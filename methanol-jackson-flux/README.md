# methanol-jackson-flux

Adapters for JSON & Reactive Streams using [Jackson][jackson] & [Reactor][reactor].

## Decoding

This adapter converts response bodies into publisher-based sources. Supported types are
`Mono`, `Flux`, `org.reactivestreams.Publisher` and `java.util.concurrent.Flow.Publisher`. For all
these types except `Mono`, the response body is expected to be a JSON array. The array is tokenized
into its individual elements, each mapped to the publisher's element type.

Note that an `HttpResponse` handled with this adapter is completed immediately after the response headers
are received. Body completion is handled by the returned publisher source. Additionally, the decoder
always uses Jackson's non-blocking parser. This makes `MoreBodyHandlers::ofDeferredObject` redundant
with this decoder.

## Encoding

With the exception of `Mono`, any subtype of `org.reactivestreams.Publisher` or
`java.util.concurrent.Flow.Publisher` is encoded to a JSON array containing zero or more elements, each mapped from each published object.
`Mono` sources are encoded to a single JSON object (if completed with any).

## Installation

First, add `methanol-jackson-flux` as a dependency.

### Gradle

```gradle
implementation 'com.github.mizosoft.methanol:methanol-jackson-flux:1.5.0'
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
  <artifactId>methanol-jackson-flux</artifactId>
  <version>1.5.0</version>
</dependency>
```

Next, register the adapters as [service providers][serviceloader_javadoc] so Methanol knows they're
there. The way this is done depends on your project setup.

### Module Path

Follow these steps if your project uses the Java module system.

1. Add this class to your module:

    ```java
    public class JacksonFluxProviders {
      private static final ObjectMapper mapper = new ObjectMapper();
 
      public static class EncoderProvider {
        public static BodyAdapter.Encoder provider() {
          return JacksonFluxAdapterFactory.createEncoder(mapper);
        }
      }
   
      public static class DecoderProvider {
        public static BodyAdapter.Decoder provider() {
          return JacksonFluxAdapterFactory.createDecoder(mapper);
        }
      }
    }
    ```

2. Add the corresponding provider declarations in your `module-info.java` file.

    ```java
    provides BodyAdapter.Encoder with JacksonFluxProviders.EncoderProvider;
    provides BodyAdapter.Decoder with JacksonFluxProviders.DecoderProvider;
    ```

### Classpath

Registering adapters from the classpath requires declaring the adapter's implementation classes in
provider-configuration files that are bundled with your JAR. You'll first need to implement
delegating `Encoder` & `Decoder` that forward to the instances created by `JacksonAdapterFactory`.
Extending from `ForwardingEncoder` & `ForwardingDecoder` makes this easier.

It is recommended to use Google's [AutoService][autoservice] to generate the provider-configuration
files automatically, so you won't bother writing them.

#### Using AutoService

After [installing AutoService][autoservice_getting_started], add this class to your project:

```java
public class JacksonFluxAdapters {
  private static final ObjectMapper mapper = new ObjectMapper();

  @AutoService(BodyAdapter.Encoder.class)
  public class JacksonFluxEncoder extends ForwardingEncoder {
    public JacksonFluxEncoder() {
      super(JacksonFluxAdapterFactory.createEncoder(mapper));
    }
  }
  
  @AutoService(BodyAdapter.Decoder.class)
  public class JacksonFluxDecoder extends ForwardingDecoder {
    public JacksonFluxDecoder() {
      super(JacksonFluxAdapterFactory.createDecoder(mapper));
    }
  }
}
```

#### Manual Configuration

First, add this class to your project:

```java
public class JacksonAdapters {
  private static final ObjectMapper mapper = new ObjectMapper();

  public class JacksonFluxEncoder extends ForwardingEncoder {
    public JacksonFluxEncoder() {
      super(JacksonFluxAdapterFactory.createEncoder(mapper));
    }
  }
  
  public class JacksonFluxDecoder extends ForwardingDecoder {
    public JacksonFluxDecoder() {
      super(JacksonFluxAdapterFactory.createDecoder(mapper));
    }
  }
}
```

Next, create two provider-configuration files in the resource directory: `META-INF/services`,
one for the encoder and the other for the decoder. Each file must contain the fully qualified
name of the implementation class.

Let's say the above class is in a package named `com.example`. You'll want to have one file for the
encoder named:

```
META-INF/services/com.github.mizosoft.methanol.BodyAdapter$Encoder
```

and contains the following line:

```
com.example.JacksonFluxAdapters$JacksonFluxEncoder
```

Similarly, the decoder's file is named:

```
META-INF/services/com.github.mizosoft.methanol.BodyAdapter$Decoder
```

and contains:

```
com.example.JacksonFluxAdapters$JacksonFluxDecoder
```

[jackson]: https://github.com/FasterXML/jackson
[reactor]: https://github.com/reactor/reactor-core
[autoservice]: https://github.com/google/auto/tree/master/service
[autoservice_getting_started]: https://github.com/google/auto/tree/master/service#getting-started
[serviceloader_javadoc]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
