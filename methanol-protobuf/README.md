# methanol-protobuf

Adapters for Google's [Protocol Buffers][protocol_buffers].

## Encoding & Decoding

Any subtype of `MessageLite` is supported by encoders & decoders. Decoders can optionally have an
`ExtensionRegistryLite` or an `ExtensionRegistry` to enable [message extensions][message_extensions].

## Installation

### Gradle

```kotlin
implementation("com.github.mizosoft.methanol:methanol-protobuf:1.8.2")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-protobuf</artifactId>
   <version>1.8.2</version>
</dependency>
```

## Usage

```java
var adapterCodec =
    AdapterCodec.newBuilder()
        .encoder(ProtobufAdapterFactory.createEncoder())
        .decoder(ProtobufAdapterFactory.createDecoder())
        .build();
var client =
    Methanol.newBuilder()
        .adapterCodec(adapterCodec)
        .build();

var bruceLee = Person.newBuilder().setName("Bruce Lee").build();
var response = client.send(
    MutableRequest.POST(".../echo", bruceLee, MediaType.APPLICATION_XML),
    MyMessage.class);
assertThat(response.body()).isEqualTo(bruceLee);
```

[protocol_buffers]: https://developers.google.com/protocol-buffers
[message_extensions]: https://developers.google.com/protocol-buffers/docs/proto#extensions
