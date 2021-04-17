# Object Mapping

HTTP bodies are often mappable to high-level entities that your code understands. Java's HttpClient
was designed with that in mind. However, available `BodyPublisher` & `BodySubscriber` implementations
are too basic, and implementing your own can be tricky. Methanol builds upon these APIs with an extensible
and easy-to-use object mapping mechanism that treats your objects as first-citizen HTTP bodies.

## Setup

Before sending and receiving objects over HTTP, Methanol needs to adapt to your desired mapping schemes.
Adapters for the most popular serialization libraries are provided in separate modules.

  * [`methanol-gson`](methanol_gson): JSON with Gson
  * [`methanol-jackson`](methanol_jackson): JSON with Jackson
  * [`methanol-jackson-flux`](methanol_jackson_flux): Reactive JSON with Jackson and Reactor
  * [`methanol-protobuf`](methanol_protobuf): Google's Protocol Buffers
  * [`methanol-jaxb`](methanol_jaxb): XML with JAXB

Adapters are dynamically located using Java's `ServiceLoader`. You can find clear installation steps
in each module's README. We'll later see how to implement custom adapters as well.

If you want to run examples presented here, get started by installing your favorite JSON adapter!

<!-- 
First, add `methanol-gson` as a dependency.

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol-gson:1.5.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol-gson</artifactId>
    <version>1.5.0</version>
  </dependency>
</dependencies>
```

Next, we'll let Methanol know about the adapter by registering it as a service provider. The way 
this is done depends on how you're running your application.

### Module path

Add this class to your module:

```java
public class GsonProviders {
  static final Gson gson = new Gson();

  private GsonProviders() {}

  public static class EncoderProvider {
    private EncoderProvider() {}

    public static BodyAdapter.Encoder provider() {
      return GsonAdapterFactory.createEncoder(gson);
    }
  }

  public static class DecoderProvider {
    private DecoderProvider() {}

    public static BodyAdapter.Decoder provider() {
      return GsonAdapterFactory.createDecoder(gson);
    }
  }
}
```

Then add provider declarations in your `module-info.java` file.

```java
provides BodyAdapter.Encoder with GsonProviders.EncoderProvider;
provides BodyAdapter.Decoder with GsonProviders.DecoderProvider;
```

### Classpath

First, implement delegating `Encoder` & `Decoder` that forward to the instances created by the adapter
factory. Use `ForwardingEncoder` & `ForwardingDecoder` to make this easier.

```java
class GsonHolder {
  static final Gson gson = new Gson();
}

public class GsonEncoder extends ForwardingEncoder {
  public GsonEncoder() {
    super(GsonHolder.gson);
  }
}

public class GsonDecoder extends ForwardingDecoder {
  public GsonDecoder() {
    super(GsonHolder.gson);
  }
}
```

Next, add two provider-configuration files in the resource directory `META-INF/services`, one for
`GsonEncoder` and the other for `GsonDecoder`, each containing their fully qualified names.

For instance, if the classes are in a package named `com.example`, the provider-configuration file
should be named:

```
META-INF/services/com.github.mizosoft.methanol.BodyAdapter$Encoder
```

and should contain the following line:

```
com.example.GsonEncoder
```

Similarly, the decoder file is named:

```
META-INF/services/com.github.mizosoft.methanol.BodyAdapter$Decoder
```

and contains:

```
com.example.GsonDecoder
``` 
-->

## Receiving Objects

To get an `HttpResponse<T>`, give `MoreBodyHandlers` a `T.class` and it'll give you a `BodyHandler<T>`
in return.

```java hl_lines="8"
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.github.com")
    .defaultHeader("Accept", "application/vnd.github.v3+json")
    .build();

GitHubUser getUser(String username) throws IOException, InterruptedException {
  var request = MutableRequest.GET("/user/" + username);
  var response = client.send(request, MoreBodyHandlers.ofObject(GitHubUser.class));

  return response.body();
}

public static final class GitHubUser {
  public String login;
  public long id;
  public String url;
  
  // Other fields omitted
}
```

If you want to get fancier with generics, use a `TypeRef<T>`.

```java hl_lines="4"
List<GitHubIssue> getIssuesForRepo(String owner, String repo) throws IOException, InterruptedException {
  var request = MutableRequest.GET("repos/" + owner + "/" + repo "/issues");
  var response = client.send(
    request, MoreBodyHandlers.ofObject(new TypeRef<List<GitHubIssue>>() {}));

  return response.body();
}

public static final class GitHubIssue {
  public String title;
  public GitHubUser user;
  public String body;

  // Other fields omitted
}
```

The right adapter is selected based on response's `Content-Type`. For instance, a response with
`Content-Type: application/json` causes Methanol to look for a JSON adapter. If such lookup
fails, an `UnsupportedOperationException` is thrown. 

## Sending Objects

Get a `BodyPubilsher` for whatever object you've got by passing it in along with a `MediaType` describing
which adapter you prefer selected.

```java hl_lines="6"
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.github.com")
    .build();

String renderMarkdown(RenderRequest renderRequest) throws IOException, InterruptedException {
  var requestBody = MoreBodyPublishers.ofObject(renderRequest, MediaType.APPLICATION_JSON);
  var request = MutableRequest.POST("/user/" + username, requestBody)
      .header("Accept", "application/html");
  var response = client.send(request, BodyHandlers.ofString());

  return response.body();
}

public static final class RenderRequest {
  public String text, mode, context;
}
```

##  Adapters

An adapter provides `Encoder` and/or `Decoder` implementations. Both interfaces implement `BodyAdapter`,
which defines the methods necessary for Methanol to know which  object types the adapter believes it
can handle, and in what scheme. An `Encoder` creates a `BodyPublisher` that streams a given object's
serialized form. Similarly, a `Decoder` supplies `BodySubscriber<T>` instances for a given `TypeRef<T>`
that convert the response body into `T`. An optional `MediaType` is passed to encoders & decoders to
further describe the desired mapping scheme (e.g. specify a character set).

### Example - An HTML Adapter

Here's an adapter that uses [Jsoup][jsoup] to convert HTML bodies to parsed `Document` objects and
vise versa. When you're writing adapters, extend from `AbstractBodyAdapter` to get free media type
matching & other helpful functions.

```java
public abstract class JsoupAdapter extends AbstractBodyAdapter implements BodyAdapter {
  JsoupAdapter() {
    super(MediaType.TEXT_HTML);
  }

  @Override
  public boolean supportsType(TypeRef<?> type) {
    return type.rawType() == Document.class;
  }

  public static final class Decoder extends JsoupAdapter implements BodyAdapter.Decoder {
    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> type, @Nullable MediaType mediaType) {
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      var charset = charsetOrUtf8(mediaType);
      var subscriber = BodySubscribers.mapping(BodySubscribers.ofString(charset), Jsoup::parse);
      return BodySubscribers.mapping(subscriber, type.exactRawType()::cast); // Safely cast Document to T
    }
  }

  public static final class Encoder extends JsoupAdapter implements BodyAdapter.Encoder {
    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      var charset = charsetOrUtf8(mediaType);
      var publisher = BodyPublishers.ofString(((Document) object).outerHtml(), charset);
      return attachMediaType(pubisher, mediaType);
    }
  }
}
```

!!! tip
    Make sure your encoders call `AbstractBodyAdapter::attachMediaType` so the created `BodyPublisher`
    is converted to a `MimeBodyPublisher` if the given media type isn't null. That way, requests get
    the correct `Content-Type` header added by `Methanol`.

### Registration

Declare your encoder & decoder implementations as service-providers in the manner specified by Java's
`ServiceLoader`. Here's the appropriate provider declarations for our Jsoup adapter to put in
`module-info.java`.

```java
module my.module {
  ...

  provides BodyAdapter.Decoder with JsoupAdapter.Decoder;
  provides BodyAdapter.Encoder with JsoupAdapter.Encoder;
}
```

### Usage

Now Methanol can send and receive HTML `Documents`!

```java
final Methanol client = Methanol.create();

HttpResponse<Document> downloadHtml(String url) IOException, InterruptedException {
  var request = MutableRequest.GET(url)
      .header("Accept", "application/html");
      
  return client.send(request, MoreBodyHandlers.ofObject(Document.class));
}

<T> HttpResponse<T> uploadHtml(String url, Document htmlDoc, BodyHandler<T> responseHandler) 
    throws IOException, InterruptedException {
  var requestBody = MoreBodyPublishers.ofObject(htmlDoc, MediaType.TEXT_HTML);
  var request = MutableRequest.POST(url, requestBody);
  
  return client.send(request, responseHandler);
}
```

## Buffering vs Streaming

`MoreBodyHandlers::ofObject` creates handlers that use `MoreBodySubscribers::ofObject` to obtain the
appropriate `BodySubscriber<T>` from a chosen adapter. Such subscriber typically loads the whole response
into memory then decodes from there. If your responses tend to have large bodies, or you'd prefer the
memory efficiency afforded by streaming sources, `MoreBodyHandlers::ofDeferredObject` is the way to go.

```java hl_lines="3"
GitHubUser getUser(String username) throws IOException, InterruptedException {
  var request = MutableRequest.GET("/user/" + username);
  var response = client.send(request, MoreBodyHandlers.ofDeferredObject(GitHubUser.class));

  return response.body().get();
}
```

The handler results in an `HttpResponse<Supplier<T>>`. The response is completed as soon as all headers
are read. If the chosen decoder's `toDeferredObject` is implemented correctly, processing is deferred
till you invoke the supplier and the body is decoded from a streaming source, typically an `InputStream`
or a `Reader`.

The `Decoder` interface has a naive default implementation for `toDeferredObject` that doesn't read
from a streaming source. Here's how it'd be properly implemented for our HTML adapter's decoder.

```java hl_lines="9"
@Override
public <T> BodySubscriber<Supplier<T>> toDeferredObject(
    TypeRef<T> type, @Nullable MediaType mediaType) {
  requireSupport(type);
  requireCompatibleOrNull(mediaType);
  var charset = charsetOrUtf8(mediaType);
  BodySubscriber<Supplier<Document>> subscriber = BodySubscribers.mapping(
      MoreBodySubscribers.ofReader(charset),
      reader -> () -> Parser.htmlParser().parseInput(reader, "")); // Note the deferred parsing  
  return BodySubscribers.mapping(
      subscriber,
      supplier -> () -> type.exactRawType().cast(supplier.get())); // Safely cast Document to T  
}
```

[methanol_jackson]: https://github.com/mizosoft/methanol/tree/master/methanol-jackson
[jsoup]: https://jsoup.org/
