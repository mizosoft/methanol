# methanol-jackson

Adapters for [Jackson][jackson].

## Installation

### Gradle

```gradle
implementation("com.github.mizosoft.methanol:methanol-jackson:1.7.0")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-jackson</artifactId>
   <version>1.7.0</version>
</dependency>
```

## Usage

```java
var mapper = new JsonMapper();
var adapterCodec =
    AdapterCodec.newBuilder()
        .encoder(JacksonAdapterFactory.createJsonEncoder(mapper))
        .decoder(JacksonAdapterFactory.createJsonDecoder(mapper))
        .build();
var client =
    Methanol.newBuilder()
        .adapterCodec(adapterCodec)
        .build();

record Person(String name) {}

var bruceLee = new Person("Bruce Lee");
var response = client.send(
    MutableRequest.POST(".../echo", bruceLee, MediaType.APPLICATION_JSON),
    Person.class);
assertThat(response.body()).isEqualTo(bruceLee);
```

## Formats

`ObjectMapper` implementations can be paired with one or more `MediaTypes` to create adapters for any format supported by Jackson.
For instance, here's an adapter for XML that uses [`jackson-dataformat-xml`](https://github.com/FasterXML/jackson-dataformat-xml).

```java
 var mapper = new XmlMapper();
var adapterCodec =
    AdapterCodec.newBuilder()
        .encoder(
            JacksonAdapterFactory.createEncoder(
                mapper, MediaType.APPLICATION_XML, MediaType.TEXT_XML))
        .decoder(
            JacksonAdapterFactory.createDecoder(
                mapper, MediaType.APPLICATION_XML, MediaType.TEXT_XML))
        .build();
```

For binary formats, you usually can't just plug in an `ObjectMapper` as a schema must be applied for each type.
Thus, you would use a custom `ObjectReaderFactory` and/or `ObjectWriterFactory`.
Here's an adapter for [Protocol Buffers](https://github.com/FasterXML/jackson-dataformats-binary/tree/2.14/protobuf).
You'll need to know what types are expected beforehand.

```java
record Point(int x, int y) {}

var schemas =
    Map.of(
        TypeRef.of(Point.class),
        ProtobufSchemaLoader.std.parse(
            """
                message Point {
                  required int32 x = 1;
                  required int32 y = 2;
                }
                """));
var mapper = new ProtobufMapper();
var adapterCodec =
    AdapterCodec.newBuilder()
        .encoder(
            JacksonAdapterFactory.createEncoder(
                mapper,
                (localMapper, typeRef) -> localMapper.writer(schemas.get(typeRef)),
                MediaType.APPLICATION_X_PROTOBUF))
        .decoder(
            JacksonAdapterFactory.createDecoder(
                mapper,
                (localMapper, typeRef) ->
                    mapper.readerFor(typeRef.rawType()).with(schemas.get(typeRef)),
                MediaType.APPLICATION_X_PROTOBUF));
```

## Legacy Adapters

See [Legacy Adapters](https://mizosoft.github.io/methanol/legacy_adapters/)

[jackson]: https://github.com/FasterXML/jackson
