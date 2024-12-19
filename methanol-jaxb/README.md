# methanol-jaxb

Adapters for XML using Java EE's [JAXB][jaxb].

## Installation

### Gradle

```gradle
implementation("com.github.mizosoft.methanol:methanol-jaxb:1.7.0")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
   <artifactId>methanol-jaxb</artifactId>
   <version>1.7.0</version>
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

[jaxb]: https://javaee.github.io/jaxb-v2/
