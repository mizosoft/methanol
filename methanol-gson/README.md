# methanol-gson

Adapters for JSON using [Gson][gson].

## Installation

First, add `methanol-gson` as a dependency.

### Gradle

```gradle
implementation 'com.github.mizosoft.methanol:methanol-gson:1.5.0'
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
  <artifactId>methanol-gson</artifactId>
  <version>1.5.0</version>
</dependency>
```

Next, register the adapters as [service providers][serviceloader_javadoc] so Methanol knows they're
there. The way this is done depends on your project setup.

### Module Path

Follow these steps if your project uses the Java module system.

1. Add this class to your module:

    ```java
    public class GsonProviders {
      private static final Gson gson = new Gson();
      
      public static class DecoderProvider {
        public static BodyAdapter.Decoder provider() {
          return GsonAdapterFactory.createDecoder(gson);
        }
      }
   
      public static class EncoderProvider {
        public static BodyAdapter.Encoder provider() {
          return GsonAdapterFactory.createEncoder(gson);
        }
      }
    }
    ```

2. Add the corresponding provider declarations in your `module-info.java` file.

    ```java
    provides BodyAdapter.Decoder with GsonProviders.DecoderProvider;
    provides BodyAdapter.Encoder with GsonProviders.EncoderProvider;
    ```

### Classpath

Registering adapters from the classpath requires declaring the adapter's implementation classes in
provider-configuration files that are bundled with your JAR. You'll first need to implement
delegating `Encoder` & `Decoder` that forward to the instances created by `GsonAdapterFactory`.
Extending from `ForwardingEncoder` & `ForwardingDecoder` makes this easier.

It is recommended to use Google's [AutoService][autoservice] to generate the provider-configuration
files automatically, so you won't bother writing them.

#### Using AutoService

After [installing AutoService][autoservice_getting_started], add this class to your project:

```java
public class GsonAdapters {
  private static final Gson gson = new Gson();
  
  @AutoService(BodyAdapter.Encoder.class)
  public class GsonEncoder extends ForwardingEncoder {
    public GsonEncoder() {
      super(GsonAdapterFactory.createEncoder(gson));
    }
  }

  @AutoService(BodyAdapter.Decoder.class)
  public class GsonDecoder extends ForwardingDecoder {
    public GsonDecoder() {
      super(GsonAdapterFactory.createDecoder(gson));
    }
  }
}
```

#### Manual Configuration

First, add this class to your project:

```java
public class GsonAdapters {
  private static final Gson gson = new Gson();
  
  public class GsonEncoder extends ForwardingEncoder {
    public GsonEncoder() {
      super(GsonAdapterFactory.createEncoder(gson));
    }
  }
  
  public class GsonDecoder extends ForwardingDecoder {
    public GsonDecoder() {
      super(GsonAdapterFactory.createDecoder(gson));
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
com.example.GsonAdapters$GsonEncoder
```

Similarly, the decoder's file is named:

```
META-INF/services/com.github.mizosoft.methanol.BodyAdapter$Decoder
```

and contains:

```
com.example.GsonAdapters$GsonDecoder
```

[gson]: https://github.com/google/gson
[autoservice]: https://github.com/google/auto/tree/master/service
[autoservice_getting_started]: https://github.com/google/auto/tree/master/service#getting-started
[serviceloader_javadoc]: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html
