# methanol-gson

Adapters for JSON using [Gson][gson].

## Installation

### Gradle

```kotlin
implementation("com.github.mizosoft.methanol:methanol-gson:1.7.0")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-gson</artifactId>
   <version>1.7.0</version>
</dependency>
```

## Usage

```java
var gson = new Gson();
var adapterCodec =
    AdapterCodec.newBuilder()
        .encoder(GsonAdapterFactory.createEncoder(gson))
        .decoder(GsonAdapterFactory.createDecoder(gson))
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

## Legacy Adapters

See [Legacy Adapters](https://mizosoft.github.io/methanol/legacy_adapters/)

[gson]: https://github.com/google/gson
