# methanol-jaxb-jakarta

Adapters for XML using Jakarta EE's [JAXB][jaxb].

## Installation

### Gradle

```kotlin
implementation("com.github.mizosoft.methanol:methanol-jaxb-jakarta:1.8.1")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-jaxb-jakarta</artifactId>
   <version>1.8.1</version>
</dependency>
```

## Usage

```java
var adapterCodec =
    AdapterCodec.newBuilder()
        .encoder(JaxbAdapterFactory.createEncoder())
        .decoder(JaxbAdapterFactory.createDecoder())
        .build();
var client =
    Methanol.newBuilder()
        .adapterCodec(adapterCodec)
        .build();

record Person(String name) {}

var bruceLee = new Person("Bruce Lee");
var response = client.send(
    MutableRequest.POST(".../echo", bruceLee, MediaType.APPLICATION_XML),
    Person.class);
assertThat(response.body()).isEqualTo(bruceLee);
```

## Legacy Adapters

See [Legacy Adapters](https://mizosoft.github.io/methanol/legacy_adapters/)

[jaxb]: https://eclipse-ee4j.github.io/jaxb-ri/
