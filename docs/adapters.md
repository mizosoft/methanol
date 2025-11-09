# Adapters

HTTP bodies are often mappable to high-level types that your code understands. Java's HttpClient was designed
with that in mind. However, available `BodyPublisher` & `BodySubscriber` implementations are too basic, and
implementing your own can be tricky. Methanol builds upon these APIs with an extensible object mapping mechanism
that treats your objects as first-citizen HTTP bodies.

## Usage

A serialization library can be integrated with Methanol through a corresponding adapter.
Adapters for the most popular serialization libraries are provided by separate modules.

* [`methanol-gson`](adapters/gson.md): JSON with Gson
* [`methanol-jackson`](adapters/jackson.md): JSON with Jackson (but also XML, Protocol Buffers, and other formats support by Jackson)
* [`methanol-jackson-flux`](adapters/jackson_flux.md): Streaming JSON with Jackson and Reactor
* [`methanol-jaxb`](adapters/jaxb.md): XML with JAXB
* [`methanol-jaxb-jakarta`](adapters/jaxb.md): XML with JAXB (Jakarta version)
* [`methanol-protobuf`](adapters/protobuf.md): Protocol Buffers
* [`methanol-moshi`](adapters/moshi.md): JSON with Moshi, intended for Kotlin

We'll pick `methanol-jackson` for some of the examples presented here, which interact with GitHub's REST API.

```java
var mapper = new JsonMapper();
var adapterCodec =
    AdapterCodec.newBuilder()
        .basic()
        .encoder(JacksonAdapterFactory.createJsonEncoder(mapper))
        .decoder(JacksonAdapterFactory.createJsonDecoder(mapper))
        .build();
var client =
    Methanol.newBuilder()
        .adapterCodec(adapterCodec)
        .baseUri("https://api.github.com/")
        .defaultHeader("Accept", "application/vnd.github.v3+json")
        .build();
```

An [`AdapterCodec`][adapter_codec] groups together one or more adapters, possibly targeting different mapping schemes. It helps `Methanol`
to select the correct adapter based on the request's or response's [`MediaType`](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/MediaType.html).

[`basic()`][adaptercodec_basic_javadoc] adds the basic adapter, which encodes & decodes basic types like `String` & `InputStream`, and enables 
[conditional response handling](./#ResponsePayload-conditional-response-handling). Trace through the Javadoc for all supported basic types.

!!! info
    Adapters are selected based on two criteria: the response/request `Content-Type` and the high level body type. For instance, `methanol-jackson` matches
    with `application/json` & any object type supported by the `ObjectMapper`. Adapters are prioritized based on `AdapterCodec::Builder` addition order.
    The first adapter matching the two attributes is selected. The basic adapter matches with any `Content-Type`, and its supported set of object types.
    It's always a good idea to add the basic adapter first before any other adapter. That way, it will be prioritized for basic types, which you'll unlikely
    want to get handled with complex adapters like `methanol-jackson`.

### Receiving Objects

To get an `HttpResponse<T>`, give `Methanol::send` a `T.class`.

```java
@JsonIgnoreProperties(ignoreUnknown = true) // We'll ignore most fields for brevity.
public record GitHubUser(String login, long id, String url) {}

GitHubUser getUser(String username) throws IOException, InterruptedException {
  return client.send(MutableRequest.GET("users/" + username), GitHubUser.class).body();
}
```

If you want to get fancier with generics, pass a `TypeRef<T>`.

```java
@JsonIgnoreProperties(ignoreUnknown = true) // We'll ignore most fields for brevity.
public record GitHubIssue(String title, GitHubUser user, String body) {}

List<GitHubIssue> getIssues(String owner, String repo) throws IOException, InterruptedException {
  return client.send(
      MutableRequest.GET("repos/" + owner + "/" + repo + "/issues"),
      new TypeRef<List<GitHubIssue>>() {}).body();
}
```

### Sending Objects

Each `MutableRequest` can have a payload as its body. A payload is an arbitrary object that is not yet resolved into a `BodyPublisher`.
When the request is sent, the payload will be resolved with the client's `AdapterCodec`.

```java
public record Markdown(String text, String context, String mode) {}

String markdownToHtml(String text, String contextRepo) throws IOException, InterruptedException {
  return client.send(
      MutableRequest.POST("markdown", new Markdown(text, contextRepo, "gfm"), MediaType.APPLICATION_JSON),
      String.class).body();
}
```

A payload must be given along with a `MediaType` specifying the format with which it will be resolved.

### `ResponsePayload` - Conditional Response Handling

Sometimes, the response body structure depends on conditional factors like the response status code. In such cases, you can't determine the target type (`T.class`) beforehand.
This is where `ResponsePayload` comes in handy. It is enabled through the basic adapter, which can be added as shown in the client setup above.

```java
@JsonIgnoreProperties(ignoreUnknown = true) // We'll ignore most fields for brevity.
public record GitHubUser(String login, long id, String url) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorResponse(String message, String documentation_url) {}

public static class GithubApiException extends RuntimeException {
  public GithubApiException(String message) {
    super(message);
  }
}

GitHubUser getUser(String username) throws IOException, InterruptedException {
  var response = client.send(MutableRequest.GET("users/" + username), ResponsePayload.class);
  try (var payload = response.body()) {
    if (HttpStatus.isSuccessful(response)) {
      return payload.to(GitHubUser.class);
    } else {
      var error = payload.to(ErrorResponse.class);
      throw new GithubApiException(String.format("Error: %s%nSee%s%n", error.message(), error.documentation_url()));
    }
  }
}
```

You can either pass: 
 - a type that is supported by the installed `AdapterCodec`.
 - a `BodyHandler<T>` of your choice with `ResponsePayload::handleWith`.

Always ensure that the `ResponsePayload` is closed, even if you consume it. Using a try-with-resources block (as shown above) guarantees that all underlying resources are properly released.

## Adapter API

An adapter provides an [`Encoder`][encoder_javadoc] and/or a [`Decoder`][decoder_javadoc] implementation.
An `Encoder` creates `BodyPublisher` instances that stream a given object's serialized form.
Similarly, a `Decoder` creates `BodySubscriber<T>` instances that convert the response body into `T`.
Encoders & decoders are given [`Hints`][hints_javadoc] to customize their behavior.
One notable hint is the `MediaType`, which can be used to further describe the desired mapping format (e.g. specify a charset).

### Example - An HTML Adapter

Here's an adapter that uses [Jsoup][jsoup] to convert HTML bodies to `Document` objects and vise versa.
When you're writing adapters, it's a good idea to extend from [`AbstractBodyAdapter`][abstractbodyadapter_javadoc].

```java
public abstract class JsoupAdapter extends AbstractBodyAdapter {
  JsoupAdapter() {
    super(MediaType.TEXT_HTML);
  }

  @Override
  public boolean supportsType(TypeRef<?> type) {
    return type.rawType() == Document.class;
  }

  public static final class Decoder extends JsoupAdapter implements BaseDecoder {
    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      var charset = hints.mediaTypeOrAny().charsetOrUtf8();
      var subscriber = BodySubscribers.mapping(BodySubscribers.ofString(charset), Jsoup::parse);
      return BodySubscribers.mapping(subscriber, typeRef.exactRawType()::cast); // Safely cast Document to T.
    }
  }

  public static final class Encoder extends JsoupAdapter implements BaseEncoder {
    @Override
    public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      var charset = hints.mediaTypeOrAny().charsetOrUtf8();
      var publisher = BodyPublishers.ofString(((Document) value).outerHtml(), charset);
      return attachMediaType(publisher, hints.mediaTypeOrAny());
    }
  }
}
```

!!! tip
    Make sure your encoders call `AbstractBodyAdapter::attachMediaType`, so the created `BodyPublisher` can be converted to a `MimeBodyPublisher`.
    That way, requests get the correct `Content-Type` header added by `Methanol`.

## Buffering vs Streaming

Decoders typically load the whole response body into memory before deserialization. If your responses tend to have large bodies,
or you'd prefer the memory efficiency afforded by streaming sources, you can ask to get a `Supplier<T>` instead.

```java
@JsonIgnoreProperties(ignoreUnknown = true) // We'll ignore most fields for brevity.
public record GitHubUser(String login, long id, String url) {}

GitHubUser getUser(String username) throws IOException, InterruptedException {
  return client.send(
          MutableRequest.GET("user/" + username), 
          new TypeRef<Supplier<GitHubUser>>() {}).body().get();
}
```

In such case, the response is completed as soon as all headers are read. If he decoder supports
streaming, the supplier will deserialize from a streaming source, typically an `InputStream` or a `Reader`.

The way a `Decoder` implements streaming is by overriding `toDeferredObject` to return a `BodySubscriber<Supplier<T>>`.
Here's how it'd be properly implemented for our HTML adapter's decoder.

```java
@Override
public <T> BodySubscriber<Supplier<T>> toDeferredObject(TypeRef<T> typeRef, Hints hints) {
  requireSupport(typeRef, hints);
  return BodySubscribers.mapping(
      MoreBodySubscribers.ofReader(hints.mediaTypeOrAny().charsetOrUtf8()),
      reader ->
          () ->
              typeRef
                  .exactRawType() // Get Class<Document> as Class<T>
                  .cast(
                      Parser.htmlParser()
                          .parseInput(
                              new BufferedReader(reader), ""))); // Note the deferred parsing
}
```

## Legacy Adapters

See [Legacy Adapter](legacy_adapters.md).

[methanol_jackson]: https://github.com/mizosoft/methanol/tree/master/methanol-jackson

[jsoup]: https://jsoup.org/

[encoder_javadoc]: ../api/latest/methanol/com/github/mizosoft/methanol/BodyAdapter.Encoder.html

[decoder_javadoc]: ../api/latest/methanol/com/github/mizosoft/methanol/BodyAdapter.Decoder.html

[bodyadapter_javadoc]: ../api/latest/methanol/com/github/mizosoft/methanol/BodyAdapter.html

[hints_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/BodyAdapter.Hints.html

[mediatype_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/MediaType.html

[abstractbodyadapter_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/adapter/AbstractBodyAdapter.html

[adaptercodec_basic_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/AdapterCodec.Builder.html#basic()

[adapter_codec]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/AdapterCodec.html
