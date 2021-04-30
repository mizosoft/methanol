# methanol-jackson

Adapters for JSON using [Jackson][jackson].

## Installation

First, add `methanol-jackson` as a dependency.

### Gradle

```gradle
implementation 'com.github.mizosoft.methanol:methanol-jackson:1.5.0'
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
  <artifactId>methanol-jackson</artifactId>
  <version>1.5.0</version>
</dependency>
```

Next, register the adapters as [service providers][serviceloader_javadoc] so Methanol knows they're
there. The way this is done depends on your project setup.

### Module Path

Follow these steps if your project uses the Java module system.

1. Add this class to your module:

    ```java
    public class JacksonProviders {
      private static final ObjectMapper mapper = new ObjectMapper();
   
      public static class EncoderProvider {
        public static BodyAdapter.Encoder provider() {
          return JacksonAdapterFactory.createEncoder(mapper);
        }
      }
   
      public static class DecoderProvider {
        public static BodyAdapter.Decoder provider() {
          return JacksonAdapterFactory.createDecoder(mapper);
        }
      }
    }
    ```

2. Add the corresponding provider declarations in your `module-info.java` file.

    ```java
    provides BodyAdapter.Encoder with JacksonProviders.EncoderProvider;
    provides BodyAdapter.Decoder with JacksonProviders.DecoderProvider;
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
public class JacksonAdapters {
  private static final ObjectMapper mapper = new ObjectMapper();
  
  @AutoService(BodyAdapter.Encoder.class)
  public class JacksonEncoder extends ForwardingEncoder {
    public JacksonEncoder() {
      super(JacksonAdapterFactory.createEncoder(mapper));
    }
  }

  @AutoService(BodyAdapter.Decoder.class)
  public class JacksonDecoder extends ForwardingDecoder {
    public JacksonDecoder() {
      super(JacksonAdapterFactory.createDecoder(mapper));
    }
  }
}
```

#### Manual Configuration

First, add this class to your project:

```java
public class JacksonAdapters {
  private static final ObjectMapper mapper = new ObjectMapper();
  
  public class JacksonEncoder extends ForwardingEncoder {
    public JacksonEncoder() {
      super(JacksonAdapterFactory.createEncoder(mapper));
    }
  }

  public class JacksonDecoder extends ForwardingDecoder {
    public JacksonDecoder() {
      super(JacksonAdapterFactory.createDecoder(mapper));
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
com.example.JacksonAdapters$JacksonEncoder
```

Similarly, the decoder's file is named:

```
META-INF/services/com.github.mizosoft.methanol.BodyAdapter$Decoder
```

and contains:

```
com.example.JacksonAdapters$JacksonDecoder
```

[jackson]: https://github.com/FasterXML/jackson
[autoservice]: https://github.com/google/auto/tree/master/service
[autoservice_getting_started]: https://github.com/google/auto/tree/master/service#getting-started
[serviceloader_javadoc]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
