# methanol-jackson-flux

Adapters for JSON & Reactive Streams using [Jackson][jackson] & [Reactor][reactor].

## Decoding

This adapter converts response bodies into publisher-based sources. Supported types are
`Mono`, `Flux`, `org.reactivestreams.Publisher` and `java.util.concurrent.Flow.Publisher`.
For all these types except `Mono`, the response body is expected to be a JSON array.
The array is tokenized into its individual elements, each mapped to the publisher's element type.

Note that an `HttpResponse` handled with this adapter is completed immediately after the response headers are received.
Body completion is handled by the returned publisher source. Additionally, the decoder always uses Jackson's non-blocking parser.

## Encoding

With the exception of `Mono`, any subtype of `org.reactivestreams.Publisher` or
`java.util.concurrent.Flow.Publisher` is encoded to a JSON array containing zero or more elements, each mapped from each published object.
`Mono` sources are encoded to a single JSON object (if completed with any).

## Installation

### Gradle

```kotlin
implementation("com.github.mizosoft.methanol:methanol-jackson-flux:1.8.4")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-jackson-flux</artifactId>
    <version>1.8.4</version>
</dependency>
```

## Usage

```java
var mapper = new JsonMapper();
var adapterCodec =
    AdapterCodec.newBuilder()
        .encoder(JacksonFluxAdapterFactory.createEncoder(mapper))
        .decoder(JacksonFluxAdapterFactory.createDecoder(mapper))
        .build();
var client = Methanol.newBuilder().adapterCodec(adapterCodec).build();

record Person(String name) {
}

var bruceLee = new Person("Bruce Lee");
var jackieChan = new Person("Jacki Chan");
var response =
    client.send(
        MutableRequest.POST(
            ".../echo",
            Flux.just(bruceLee, jackieChan),
            MediaType.APPLICATION_JSON), 
        new TypeRef<Flux<Person>>() {});
assertThat(response.body().toIterable()).containsExactly(bruceLee, jackieChan);
```

## Legacy Adapters

See [Legacy Adapters](https://mizosoft.github.io/methanol/legacy_adapters/)

[jackson]: https://github.com/FasterXML/jackson
[reactor]: https://github.com/reactor/reactor-core
