# methanol-protobuf

Adapters for Google's [Protocol Buffers][protocol_buffers].

## Encoding & Decoding

Any subtype of `MessageLite` is supported by encoders & decoders. Decoders can optionally have an
`ExtensionRegistryLite` or an `ExtensionRegistry` to enable [message extensions][message_extensions].

## Installation

### Gradle

```gradle
implementation 'com.github.mizosoft.methanol:methanol-protobuf:1.7.0'
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-protobuf</artifactId>
   <version>1.7.0</version>
</dependency>
```

The adapters need to be registered as [service providers][serviceloader_javadoc] so Methanol knows they're there.
The way this is done depends on your project setup.

### Module Path

Follow these steps if your project uses the Java module system.

1. Add this class to your module:

    ```java
    public class ProtobufProviders {   
      public static class EncoderProvider {
        public static BodyAdapter.Encoder provider() {
          return ProtobufAdapterFactory.createEncoder();
        }
      }
   
      public static class DecoderProvider {
        public static BodyAdapter.Decoder provider() {
          return ProtobufAdapterFactory.createDecoder();
        }
      }
    }
    ```

2. Add the corresponding provider declarations in your `module-info.java` file.

    ```java
    requires methanol.adapter.protobuf;
   
    provides BodyAdapter.Encoder with ProtobufProviders.EncoderProvider;
    provides BodyAdapter.Decoder with ProtobufProviders.DecoderProvider;
    ```

### Classpath

Registering adapters from the classpath requires declaring the implementation classes in provider-configuration
files that are bundled with your JAR. You'll first need to implement delegating `Encoder` & `Decoder`
that forward to the instances created by `ProtobufAdapterFactory`. Extending from `ForwardingEncoder` &
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
public class ProtobufAdapters {  
  @AutoService(BodyAdapter.Encoder.class)
  public static class Encoder extends ForwardingEncoder {
    public Encoder() {
      super(ProtobufAdapterFactory.createEncoder());
    }
  }

  @AutoService(BodyAdapter.Decoder.class)
  public static class Decoder extends ForwardingDecoder {
    public Decoder() {
      super(ProtobufAdapterFactory.createDecoder());
    }
  }
}
```

#### Manual Configuration

You can also write the configuration files manually. First, add this class to your project:

```java
public class ProtobufAdapters {
  public static class Decoder extends ForwardingDecoder {
    public Decoder() {
      super(ProtobufAdapterFactory.createDecoder());
    }
  }

  public static class Encoder extends ForwardingEncoder {
    public Encoder() {
      super(ProtobufAdapterFactory.createEncoder());
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
com.example.ProtobufAdapters$Encoder
```

Similarly, the decoder's file is named:

```
META-INF/services/com.github.mizosoft.methanol.BodyAdapter$Decoder
```

and contains:

```
com.example.ProtobufAdapters$Decoder
```

[protocol_buffers]: https://developers.google.com/protocol-buffers
[message_extensions]: https://developers.google.com/protocol-buffers/docs/proto#extensions
[autoservice]: https://github.com/google/auto/tree/master/service
[autoservice_getting_started]: https://github.com/google/auto/tree/master/service#getting-started
[serviceloader_javadoc]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
