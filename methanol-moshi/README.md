# methanol-moshi

Adapters for JSON using [moshi](https://github.com/square/moshi).

## Installation

### Gradle

```gradle
implementation("com.github.mizosoft.methanol:methanol-moshi:1.8.1")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-moshi</artifactId>
   <version>1.8.1</version>
</dependency>
```

## Usage

```kotlin
val moshi: Moshi = Moshi.Builder().build()
val client = Client {
  adapterCodec {
    +MoshiAdapter.Encoder(moshi, MediaType.APPLICATION_JSON)
    +MoshiAdapter.Decoder(moshi, MediaType.APPLICATION_JSON)
  }
}

data class Person(val name: String)

var bruceLee = Person("Bruce Lee")
val response: Response<Person> = client.post(".../echo") {
  body(bruceLee, MediaType.APPLICATION_JSON)
}
assertThat(response.body()).isEqualTo(bruceLee)
```

## Legacy Adapters

See [Legacy Adapters](https://mizosoft.github.io/methanol/legacy_adapters/)
