# methanol-jackson

Adapters for [Jackson][jackson].

## Installation

### Gradle

```gradle
implementation 'com.github.mizosoft.methanol:methanol-jackson:1.7.0'
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-jackson</artifactId>
   <version>1.7.0</version>
</dependency>
```

The adapters need to be registered as [service providers][serviceloader_javadoc] so Methanol knows they're there.
The way this is done depends on your project setup.

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

You can use Google's [AutoService][autoservice] to generate the provider-configuration files automatically,
so you won't bother writing them.

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

## Adapters for other formats

The Jackson adapter doesn't only support JSON. You can pair whatever `ObjectMapper` implementation 
with one or more `MediaTypes` to create adapters for any of the formats supported by Jackson. For
instance, here's a provider for a XML adapter. You'll need to pull in [`jackson-dataformat-xml`](https://github.com/FasterXML/jackson-dataformat-xml). You can install it as mentioned above. 

```java
public class JacksonXmlProviders {
  private static final ObjectMapper mapper = new XmlMapper();

  public static class EncoderProvider {
    public static BodyAdapter.Encoder provider() {
      return JacksonAdapterFactory.createEncoder(mapper, MediaType.TEXT_XML);
    }
  }

  public static class DecoderProvider {
    public static BodyAdapter.Decoder provider() {
      return JacksonAdapterFactory.createDecoder(mapper, MediaType.TEXT_XML);
    }
  }
}
```

For binary formats, you usually can't just plug in an `ObjectMapper` as a schema must be applied for each type.
For this reason you can use a custom `ObjectReaderFactory` and/or `ObjectWriterFactory`. For instance, here's a provider for a
[Protocol Buffers](https://github.com/FasterXML/jackson-dataformats-binary/tree/2.14/protobuf) adapter.
You'll need to know what types are expected beforehand.

```java
record Point(int x, int y) {}

public class JacksonProtobufProviders {
  private static final ObjectMapper mapper = new ProtobufMapper();

  /**
   * We'll store our schemas in a map. You can implement this in other ways, like loading the
   * protobuf files lazily when needed.
   */
  private static final Map<TypeRef<?>, ProtobufSchema> schemas;

  static {
    try {
      schemas = Map.of(
          TypeRef.from(Point.class),
          ProtobufSchemaLoader.std.parse(
              """
              message Point {
                required int32 x = 1;
                required int32 y = 2;
              }
              """));
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static class EncoderProvider {
    public static BodyAdapter.Encoder provider() {
      ObjectWriterFactory writerFactory = (mapper, type) -> mapper.writer(schemas.get(type));
      return JacksonAdapterFactory.createEncoder(
          mapper, writerFactory, MediaType.APPLICATION_X_PROTOBUF);
    }
  }

  public static class DecoderProvider {
    public static BodyAdapter.Decoder provider() {
      ObjectReaderFactory readerFactory =
          (mapper, type) -> mapper.readerFor(type.rawType()).with(schemas.get(type));
      return JacksonAdapterFactory.createDecoder(
          mapper, readerFactory, MediaType.APPLICATION_X_PROTOBUF);
    }
  }
}
```

[jackson]: https://github.com/FasterXML/jackson
[autoservice]: https://github.com/google/auto/tree/master/service
[autoservice_getting_started]: https://github.com/google/auto/tree/master/service#getting-started
[serviceloader_javadoc]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
