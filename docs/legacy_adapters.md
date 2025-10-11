# Legacy Adapters

Before version 1.8.0, [adapters](adapters.md) were required to be registered as service providers and used through
static methods, instead of being bundled in a per-client [`AdapterCodec`][adaptercodec_javadoc]. The latter is the
recommended way to use adapters. The legacy way is not deprecated but might be in the future.

## Compatibility

If you have adapters installed as service providers, they can still be used with the newer, non-static APIs.

```java
record Person(String name) {}

// The client will fall back to an AdapterCodec of installed adapters, if any.
var client = Methanol.create();
var response = client.send(
    MutableRequest.POST(".../echo", new Person("Bruce Lee"), MediaType.APPLICATION_JSON),
    Person.class);
assertThat(response.body()).isEqualTo(new Person("Bruce Lee"));
```

## Installation

The legacy way to register adapters is through [service providers][serviceloader_javadoc].
How this is done depends on your project setup. We'll use [`methanol-jackson`][methanol_jackson] as an example.

### Module Path

Follow these steps if your project uses the Java module system.

1. Add this class to your module:

    ```java
    public class JacksonJsonProviders {
      private static final ObjectMapper mapper = new ObjectMapper();
   
      public static class EncoderProvider {
        public static BodyAdapter.Encoder provider() {
          return JacksonAdapterFactory.createJsonEncoder(mapper);
        }
      }
   
      public static class DecoderProvider {
        public static BodyAdapter.Decoder provider() {
          return JacksonAdapterFactory.createJsonDecoder(mapper);
        }
      }
    }
    ```

2. Add the corresponding provider declarations in your `module-info.java` file.

    ```java
    requires methanol.adapter.jackson;
   
    provides BodyAdapter.Encoder with JacksonJsonProviders.EncoderProvider;
    provides BodyAdapter.Decoder with JacksonJsonProviders.DecoderProvider;
    ```

### Classpath

Registering adapters from the classpath requires declaring the implementation classes in provider-configuration
files that are bundled with your JAR. You'll first need to implement delegating `Encoder` & `Decoder`
that forward to the instances created by `JacksonAdapterFactory`. Extending from `ForwardingEncoder` &
`ForwardingDecoder` makes this easier.

You can use Google's [AutoService][autoservice] to generate the provider-configuration files automatically.

#### Using AutoService

First, [install AutoService][autoservice_getting_started].

##### Gradle

```gradle
implementation "com.google.auto.service:auto-service-annotations:$autoServiceVersion"
annotationProcessor "com.google.auto.service:auto-service:$autoServiceVersion"
```

##### Maven

```xml
<dependency>
  <groupId>com.google.auto.service</groupId>
  <artifactId>auto-service-annotations</artifactId>
  <version>${autoServiceVersion}</version>
</dependency>
```

Configure the annotation processor with the compiler plugin.

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>com.google.auto.service</groupId>
        <artifactId>auto-service</artifactId>
        <version>${autoServiceVersion}</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

Next, add this class to your project:

```java
public class JacksonJsonAdapters {
  private static final ObjectMapper mapper = new ObjectMapper();
  
  @AutoService(BodyAdapter.Encoder.class)
  public static class Encoder extends ForwardingEncoder {
    public Encoder() {
      super(JacksonAdapterFactory.createJsonEncoder(mapper));
    }
  }
  
  @AutoService(BodyAdapter.Decoder.class)
  public static class Decoder extends ForwardingDecoder {
    public Decoder() {
      super(JacksonAdapterFactory.createJsonDecoder(mapper));
    }
  }
}
```

#### Manual Configuration

You can also write the configuration files manually. First, add this class to your project:

```java
public class JacksonJsonAdapters {
  private static final ObjectMapper mapper = new ObjectMapper();
  
  public static class Encoder extends ForwardingEncoder {
    public Encoder() {
      super(JacksonAdapterFactory.createJsonEncoder(mapper));
    }
  }
  
  public static class Decoder extends ForwardingDecoder {
    public Decoder() {
      super(JacksonAdapterFactory.createJsonDecoder(mapper));
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
com.example.JacksonJsonAdapters$Encoder
```

Similarly, the decoder's file is named:

```
META-INF/services/com.github.mizosoft.methanol.BodyAdapter$Decoder
```

and contains:

```
com.example.JacksonJsonAdapters$Decoder
```

[jackson]: https://github.com/FasterXML/jackson

[autoservice]: https://github.com/google/auto/tree/master/service

[autoservice_getting_started]: https://github.com/google/auto/tree/master/service#getting-started

[serviceloader_javadoc]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html

[adaptercodec_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/AdapterCodec.html

[methanol_jackson]: https://mizosoft.github.io/methanol/adapters/jackson/
