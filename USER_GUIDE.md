# Methanol's User Guide

* [Overview](#overview)
  * [Goals](#goals)
  * [Non-goals](#non-goals)
  * [Native reactiveness](#native-reactiveness)
* [Usage](#usage)
  * [Response decompression](#response-decompression)
    * [BodyDecoder](#bodydecoder)
    * [Supported encodings](#supported-encodings)
    * [Extending decompression support](#extending-decompression-support)
  * [Request decoration](#request-decoration)
    * [Transparent compression](#transparent-compression)
    * [No overwrites](#no-overwrites)
  * [MutableRequest](#mutablerequest)
  * [MediaType](#mediatype)
    * [Media ranges](#media-ranges)
  * [MimeBodyPublisher](#mimebodypublisher)
  * [Object conversion](#object-conversion)
    * [BodyAdapter](#bodyadapter)
    * [Installation](#installation)
    * [Deferred conversion](#deferred-conversion)
  * [Interceptors](#interceptors)
    * [Invocation order](#invocation-order)
  * [Reactive request dispatches](#reactive-request-dispatches)
    * [Push promises](#push-promises)
  * [Sending forms](#sending-forms)
  * [Multipart bodies](#multipart-bodies)
  * [WritableBodyPublisher](#writablebodypublisher)
  * [Interruptible reading](#interruptible-reading)
  * [Tracking progress](#tracking-progress)

## Overview

***Methanol*** is a lightweight library that complements `java.net.http` for a better HTTP
experience. Applications using Java's non-blocking HTTP client shall find it more robust and easier
to use with Methanol.

### Goals

* Provide useful lightweight HTTP extensions built on top of `java.net.http`.
* Enhance the client's API to make it more powerful and easier to use.
* Allow easier integration with other libraries commonly used in HTTP messaging.

### Non-goals

* Providing fancy low-level extensions like network interceptors or connection pooling control; we
  can only go as far as allowed by the client's API.

### Native reactiveness

Most features provided by Methanol are [reactive-streams](http://www.reactive-streams.org/)
extensions. If you're wondering which of the wild reactive-streams implementations Methanol is
using, the answer is *none*. Methanol has its native `Flow.*` implementations. This was resolved due
 to a couple of reasons:

* Most prominent reactive-streams libraries are non-trivial dependencies; Methanol is meant to be
  lightweight. Additionally, it doesn't *require* any of the fancy rx operators.
* Choosing an implementation over the other might be unsuitable for some applications.
* Integration can be rather provided via separate modules (e.g.
[methanol-jackson-flux](methanol-jackson-flux)).

Java's `SubmissionPublisher` could have worked in some cases, but it has awkward buffering policies
and requires asynchronous scheduling of subscriber signals, so it wasn't an option.

That being said, it's worth noting that Methanol's native `Flow.*` implementations are tested
thoroughly and validated against the [TCK][tck]. Additionally, they utilize optimizations common to
the reactive-streams world, making them fast and efficient.

## Usage

Methanol adheres to the standard `java.net.http` API by introducing very few new concepts. Using the
provided extensions shouldn't feel that different from normal `HttpClient` usage. If you're not
familiar with the client, make sure to skim through the [recipes][httpclient_recipies] to get an idea
of basic usage patterns.

### Response decompression

One caveat concerning the HTTP client is that it has [no native decompression support][so_question].
A solution to this is to use an available `InputStream` decompressor (e.g. `GZIPInputStream`)
corresponding to the value of the `Content-Encoding` header. However, this forces us to always use
an `InputStream` body, which effectively throws away most of the flexibility the client's API
provides. We're back at the blocking realm again! We can definitely do better than this.

With Methanol, all you need is to simply wrap your `BodyHandler` with
[`MoreBodyHandlers::decoding`][MoreBodyHandlers_decoding]:

```java
HttpResponse<String> response = client.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
```

And that's it! The new `BodyHandler` will intercept the response, checking if a `Content-Encoding`
header is present. If so, the body is decompressed accordingly. Otherwise, it acts as a no-op and
delegates to your handler directly.

Notes:

* It doesn't matter which `BodyHandler` you are using; you can have whatever response body type you
  want!
* If the response is compressed, your handler won't see any `Content-Encoding` or `Content-Length`
  headers. This is simply because they'll be outdated in that case.

#### BodyDecoder

What makes this possible is the [`BodyDecoder`][BodyDecoder] interface. A `BodyDecoder` is a normal
`BodySubscriber` but with the added semantics of a `Flow.Processor`. It intercepts the flow of bytes
from the HTTP client and decodes each `List<ByteBuffer>` individually. It then forwards the decoded
bytes into your downstream `BodySubscriber`, which itself converts those into the desired response
body.

##### Scheduling signals

A `BodyDecoder` has two modes for scheduling downstream signals:

* **Synchronous**: The decoder processes and submits downstream items in the same thread supplying
  the compressed bytes. This is normally a thread in the client's `Executor`.
* **Asynchronous**: The decoder dispatches downstream signals to a custom `Executor`. This can lead
  to overlapped processing between the two subscribers (both can work asynchronously at different
  rates). Note, however, that the overhead of managing the executor itself might overwhelm any
  improvement gained from both subscribers running asynchronously, possibly resulting in decreased
  performance.

#### Supported encodings

The core module has default support for deflate and gzip. There is also an optional
[module][methanol_brotli] providing support for [brotli][google_brotli].

#### Extending decompression support

Adding support for more encodings is a matter of providing matching `BodyDecoder.Factory`
implementations. However, implementing the `Flow.Publisher` semantics of `BodyDecoder` can be a
challenge if you're not using a reactive-streams library. [`AsyncBodyDecoder`][AsyncBodyDecoder] is
provided for that purpose. This class, along with its sibling interface [`AsyncDecoder`][AsyncDecoder],
allows you to only focus on your decompression logic. For the sake of an example, let's add support
for the `identity` encoding (note that this is not really needed as `identity` is not a valid value
for `Content-Encoding`).

Decoding is simply done as zero or more `decode(source, sink)` rounds finalized by one final round,
each with the currently available input. After the final round, `AsyncDecoder` must've completely
exhausted the source.

```java
public class IdentityBodyDecoderFactory implements BodyDecoder.Factory {
  private static final String IDENTITY = "identity";

  public IdentityBodyDecoderFactory() {}

  @Override
  public String encoding() {
    return IDENTITY;
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(new IdentityDecoder(), downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(new IdentityDecoder(), downstream, executor);
  }

  private static final class IdentityDecoder implements AsyncDecoder {
    IdentityDecoder() {}

    @Override
    public String encoding() {
      return IDENTITY;
    }

    @Override
    public void decode(ByteSource source, ByteSink sink) {
      while (source.hasRemaining()) {
        sink.pushBytes(source.currentSource());
      }
    }

    @Override
    public void close() {}
  }
}
```

Now make your implementation discoverable by Methanol's `ServiceLoader`. In case you're using the
module path, you can add a `provides...with` clause to your `module-info.java` as follows:

```java
provides BodyDecoder.Factory with IdentityBodyDecoderFactory;
```

### Request decoration

You can use the [`Methanol`][Methanol] `HttpClient` wrapper to decorate outgoing requests with
default properties. Think resolving with a base `URI`, adding default HTTP headers, default
timeouts, etc.

```java
Methanol client = Methanol.newBuilder()
    .userAgent("Will Smith") // Set a custom User-Agent
    .baseUri("https://api.github.com") // To resolve each request's URI against
    .defaultHeader("Accept", "application/vnd.github.v3+json") // Added to each request if not present
    .requestTimeout(Duration.ofSeconds(5)) // Default request timeout to use if not set
    .autoAcceptEncoding(true) // Transparent compression, this is true by default
    .version(Version.HTTP_2) // Then configure the client as with HttpClient.Builder
     ...
    .build();
```

You can also build from an existing `HttpClient`:

```java
HttpClient baseClient = ...
Methanol client = Methanol.newBuilder(baseClient)
    .defaultHeaders(...)
     ...
    .build();
```

#### Transparent compression

If transparent compression is enabled, the client will request a compressed response with all
supported schemes (available `BodyDecoder` providers). For example, if gzip, deflate and brotli are
supported, each request will have an `Accept-Encoding: br, deflate, gzip` header added. Of course,
the response will be automatically decompressed as well.

#### No overwrites

Default request properties are not set in a request that already has them. For example, for a client
with default header `Accept: text/html`, a request with an `Accept: application/json` header will
remain so.

You may benefit from this in forcing the use of a specific compression scheme instead of all
supported ones (if transparent compression is enabled):

```java
Methanol client = Methanol.create();
MutableRequest request = MutableRequest.GET(uri)
    .header("Accept-Encoding", "gzip"); // Will force gzip only
HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
```

An exception to this is that a request with a [`MimeBodyPublisher`](#mimebodypublisher) will have
it's `Content-Type` header set, or overwritten if already there. This makes sense because a body
knows about its media type better than a containing request mistakenly setting a different one.

### MutableRequest

[`MutableRequest`][MutableRequest] is an `HttpRequest` that implements `HttpRequest.Builder` for
setting request's fields. This drops immutability in favor of more convenience when the request is
used immediately (which is typically the case):

```java
HttpResponse<String> response = client.send(MutableRequest.GET(uri), BodyHandlers.ofString());
```

Additionally, it also allows setting a relative or empty `URI` (standard `HttpRequest.Builder`
doesn't). This is useful when the request is sent over a `Methanol` client with a base `URI` against
which the relative one is resolved.

If you still want immutability, you can use `MutableRequest::build` for getting an immutable
`HttpRequest` snapshot.

### MediaType

Media types are the web's notion of file extensions. They play a very important role in identifying
how a response body should be approached. Methanol provides a [`MediaType`][MediaType] class for
representing and manipulating media types.

```java
MediaType applicationJsonUtf8 = MediaType.of("application", "json", Map.of("charset", "UTF-8"));
MediaType parsedApplicationJsonUtf8 = MediaType.parse("application/json; charset=UTF-8");
assertEquals(applicationJsonUtf8, parsedApplicationJsonUtf8);

assertEquals("application", applicationJsonUtf8.type());
assertEquals("json", applicationJsonUtf8.subtype());
assertEquals("utf-8", applicationJsonUtf8.parameters().get("charset"));
assertEquals(Optional.of(StandardCharsets.UTF_8), applicationJsonUtf8.charset());
```

#### Media ranges

A `MediaType` also defines a [media range][media_ranges] to which a set of media types belong,
including itself. A media range is created like any other media type. You can check for inclusion in
a range with `MediaType::includes` or `MediaType::isCompatibleWith`.

```java
MediaType anyTextType = MediaType.parse("text/*");
MediaType textHtml = MediaType.parse("text/html");
MediaType applicationJson = MediaType.parse("application/json");

assertTrue(anyTextType.hasWildcard());
assertTrue(anyTextType.includes(textHtml));
assertFalse(anyTextType.includes(applicationJson));
assertTrue(anyTextType.isCompatibleWith(textHtml));
assertTrue(textHtml.isCompatibleWith(anyTextType));
```

The class also has `static final` definitions for commonly used media types and ranges.

### MimeBodyPublisher

`MimeBodyPublisher` is a mixin-style interface for body publishers that know their body's concrete
media type. The interface is recognized by [multipart bodies](#multipart-bodies) and the
[`Methanol`][Methanol] client in that any `MimeBodyPublisher` will have it's `Content-Type` header
implicitly added. You can adapt an existing `BodyPublisher` into a `MimeBodyPublisher` using
[`MoreBodyPublishers::ofMediaType`][MoreBodyPublishers_ofMediaType]:

```java
static MimeBodyPublisher ofMimeBody(String body, MediaType mediaType) {
  Charset charset = mediaType.charsetOrDefault(StandardCharsets.UTF_8);
  BodyPublisher bodyPublisher = BodyPublishers.ofString(body, charset);
  return MoreBodyPublishers.ofMediaType(bodyPublisher, mediaType);
}
```

### Object conversion

It is often the case that an HTTP body is mappable to or from a higher-level entity that your code
understands. `BodyPublisher` and `BodySubscriber` APIs are designed with this in mind. However,
available implementations, especially available `BodyHandlers`, are not really that high-level.
Implementing your own can be a tiring and repetitive process, not to mention, for example, choosing
the correct handler for each response (e.g. `JsonHandler` for `application/json` or `XmlHandler` for
 `application/xml`). Methanol does that for you!

In case of response handling, all you need is to pass a `Class` for your desired type (assuming you
have the correct dependencies [installed](#installation)):

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.github.com")
    .defaultHeader("Accept", "application/vnd.github.v3+json")
    .build();

GitHubUser getUser(String name) throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET("/users/" + name);
  HttpResponse<GitHubUser> response =
      client.send(request, MoreBodyHandlers.ofObject(GitHubUser.class));

  return response.body();
}

static class GitHubUser {
  public String login;
  public long id;
  public String bio;
  // other fields omitted
}
```

Or pass a [`TypeRef`][TypeRef] if you wanna get fancier with generics:

```java
List<GitHubUser> getUserFollowers(String userName) throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET("/users/" + userName + "/followers");
  HttpResponse<List<GitHubUser>> response =
      client.send(request, MoreBodyHandlers.ofObject(new TypeRef<List<GitHubUser>>() {}));

  return response.body();
}
```

For requests, just pass whatever `Object` you want to convert, along with a `MediaType` describing
which format to use for serialization.

```java
String renderMarkdown(RenderRequest renderRequest) throws IOException, InterruptedException {
  BodyPublisher requestBody = MoreBodyPublishers.ofObject(renderRequest, MediaType.APPLICATION_JSON);
  // No need to set Content-Type header!
  MutableRequest request = MutableRequest.POST("/markdown", requestBody)
      .header("Accept", "text/html");
  HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

  return response.body();
}

static class RenderRequest {
  public String text, mode, context;
}
```

#### BodyAdapter

[`BodyAdapter`][BodyAdapter] is the driver for this automatic object conversion mechanism. It's two
specialized subtypes, [`Encoder`][Encoder] and [`Decoder`][Decoder], act as factories for
`BodyPublisher` and `BodySubscriber` respectively. Given a `TypeRef<T>` and an optional `MediaType`,
a `Decoder` returns a `BodySubscriber<T>` responsible for converting the response body into a `T`.
Similarly, given an `Object` and optionally a `MediaType`, an `Encoder` returns a `BodyPublisher`
that streams that object's serialized form.

#### Installation

You add support for schemes and object types by installing matching `Decoder` and `Encoder` adapters.
Methanol has the following adapter modules:

* [methanol-gson](methanol-gson): JSON conversion with Gson
* [methanol-jackson](methanol-jackson): JSON conversion with Jackson
* [methanol-jackson-flux](methanol-jackson-flux): Reactive JSON conversion with Jackson and Reactor
* [methanol-protobuf](methanol-protobuf): Support for Protocol Buffers
* [methanol-jaxb](methanol-jaxb): XML conversion with JAXB

Note that implementations are not service-provided by default. See each module's `README` for how to
install.

#### Deferred conversion

Most messaging libraries support either reading from a streaming source (e.g. `InputStream` or a
`Reader`), or from a memory buffer (e.g. `byte[]` or `String`). Streaming sources are more efficient
because they do not require having the whole thing in memory prior to processing.

##### The problem

Streaming sources are also blocking. They assume you can either read something if it's there or keep
blocking till it's available. This simply doesn't fit in the reactive world, which is fundamentally
non-blocking (you request a message and get notified when it arrives). You can try to get around
this by using `BodySubscribers.mapping(BodySubscribers.ofInputStream(), ...)` with a
`Function<InputStream, T>`. However, this exposes your code to multiple problems, including starving
the client out of threads and even lurking deadlocks due to a pre-JDK13
[bug][BodySubscribers_mapping_bug].

##### The solution

Following [Javadoc's][BodyHandlers_mapping_jdk13] advice, `MoreBodyHandlers` declares `T` and
`Supplier<T>` variants for dynamically handling a response. Use
[`MoreBodyHandlers::ofObject`][MoreBodyHandlers_ofObject] to get an `HttpResponse<T>`, which will
typically buffer the body into memory then decode from there. This is fine for bodies with relatively
small sizes. Use [`MoreBodyHandlers::ofDeferredObject`][MoreBodyHandlers_ofDeferredObject] to get an
`HttpResponse<Supplier<T>>`, which will be completed immediately after headers are received,
*deferring* body processing till `Supplier::get` is called. This has better memory efficiency as it
reads from a streaming source, suiting cases where loading the whole body might cause problems. Be
aware however that processing in that case is done by the thread invoking the supplier.

### Interceptors

Interceptors allow you to monitor, mutate, retry or even short-circuit ongoing exchanges. You can 
register one or more interceptors with `Methanol` to be invoked in order for each `send` or
`sendAsync` call. Here's an interceptor that logs each ongoing blocking or asynchronous exchange.

```java
class LoggingInterceptor implements Interceptor {
  private static final Logger LOG = Logger.getLogger(LoggingInterceptor.class.getName());

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    logRequest(request);

    return chain.withBodyHandler(loggingBodyHandler(request, chain.bodyHandler()))
        .forward(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    logRequest(request);

    return chain.withBodyHandler(loggingBodyHandler(request, chain.bodyHandler()))
        .forwardAsync(request);
  }

  private static void logRequest(HttpRequest request) {
    LOG.info(() -> String.format("Sending %s%n%s", request, headersToString(request.headers())));
  }

  private static <T> BodyHandler<T> loggingBodyHandler(
      HttpRequest request, BodyHandler<T> bodyHandler) {
    var beforeSend = Instant.now();
    return info -> {
      LOG.info(() -> String.format(
          "Completed %s %s with %d in %s%n%s",
          request.method(),
          request.uri(),
          info.statusCode(),
          Duration.between(beforeSend, Instant.now()),
          headersToString(info.headers())));

      return bodyHandler.apply(info);
    };
  }

  private static String headersToString(HttpHeaders headers) {
    return headers.map().entrySet().stream()
        .map(entry -> entry.getKey() + ": " + String.join(", ", entry.getValue()))
        .collect(Collectors.joining(System.lineSeparator()));
  }
}
```

You then register the interceptor with `Methanol.Builder` as follows:

```java
var client = Methanol.newBuilder()
     ...
    .interceptor(new LoggingInterceptor())
    .build();
```

Because the HTTP client has both blocking and asynchronous APIs, we must implement two
`Interceptor` methods matching each. An interceptor is given a `Chain<T>` so that it can forward
requests to its sibling, or to the underlying HTTP client in case it's the last interceptor. The
chain can also be used to perform `BodyHandler` and `PushPromiseHandler` transformations before
forwarding. 

You can use `Interceptor::create` to easily rewrite or decorate requests. For example, you may want
to enable the expect-continue feature for each ongoing POST request for a specific host.

```java
var myHost = ...;
var interceptor = Interceptor.create(
    req -> req.uri().getHost().equals(myHost) && req.method().equalsIgnoreCase("POST")
        ? MutableRequest.copyOf(req).expectContinue(true)
        : req);
var client = Methanol.newBuilder()
     ...
    .interceptor(interceptor)
    .build();
```

Interceptors can forward to the chain as many times as they want. Here's an interceptor that retries
each request up to 3 times in case of timeout.

```java
static class RetryingInterceptor implements Interceptor {
  private static final int MAX_RETRIES = 3;

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    for (int retries = 0; ; retries++) {
      try {
        return chain.forward(request);
      } catch(HttpTimeoutException e) {
        if (retries >= MAX_RETRIES) throw e;
      }
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    return withRetries(() -> chain.forwardAsync(request), new AtomicInteger());
  }

  private <T> CompletableFuture<HttpResponse<T>> withRetries(
      Supplier<CompletableFuture<HttpResponse<T>>> callOnRetry,
      AtomicInteger retryCount) {
    return callOnRetry.get()
        .handle((r, x) -> handleRetry(r, x, callOnRetry, retryCount))
        .thenCompose(Function.identity());
  }

  private <T> CompletableFuture<HttpResponse<T>> handleRetry(
      HttpResponse<T> response, Throwable error,
      Supplier<CompletableFuture<HttpResponse<T>>> callOnRetry, AtomicInteger retryCount) {
    if (response != null) return CompletableFuture.completedFuture(response);

    if (error instanceof CompletionException) error = error.getCause();

    return error instanceof HttpTimeoutException && retryCount.incrementAndGet() <= MAX_RETRIES
        ? withRetries(callOnRetry, retryCount)
        : CompletableFuture.failedFuture(error);
  }
}
```

The async version looks a bit awkward as we have to perform some async recursive lambda magic for
retries.

> If you're on JDK 12+, `handle(...).thenCompse(Function.identity())` can be replaced with 
> `exceptionallyCompose(...)` API.

#### Invocation order

Due to the fact that the client itself does request decoration and response body
transformation (i.e. decompression), interceptors are separated into two groups: *pre decoration*
and *post decoration* interceptors. The only difference is that the former gets invoked before any
default request properties or handler transformations are applied, while the latter gets invoked
right before relaying to the underlying HTTP client. Order of invocation for each group matches
addition order. You should be aware of this if you intend to do checksums or request/response body
transformation (i.e. encryption/decryption).

You can add post decoration interceptors as follows:

```java
var client = Methanol.newBuilder()
     ...
    .postDecorationInterceptor(new LoggingInterceptor())
    .build();
```

### Reactive request dispatches

For a truly reactive experience, one might want to dispatch async requests as
`Publisher<HttpResponse<T>>` sources. You can use `Methanol::exchnge` for this.

```java
Methanol client = Methanol.create();
Publisher<HttpResponse<String>> publisher =
    client.exchange(MutableRequest.GET("https://example.com"), BodyHandlers.ofString());

publisher.subscribe(new Subscriber<>() {
  @Override public void onSubscribe(Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override public void onNext(HttpResponse<String> response) {
    System.out.println("Response arrived: " + response.statusCode());
    System.out.println(response.body());
  }

  @Override public void onError(Throwable throwable) {
    throwable.printStackTrace();
  }

  @Override public void onComplete() {}
});
```

Here, the publisher acts as a `Mono` source which publishes the response when requested, then
completes the subscriber either normally if successful, or exceptionally if the request fails.

#### Push promises

You can also retrieve a `Publisher<HttpResponse<T>>` that publishes resources pushed by the server
in addition to the main response (only works with HTTP/2). Use with a reactive-streams library for
better control of the stream.

```java
Methanol client = Methanol.create(); // default Version is HTTP_2
MutableRequest request = MutableRequest.GET("https://http2.golang.org/serverpush");
Publisher<HttpResponse<Path>> publisher =
    client.exchange(
        request,
        BodyHandlers.ofFile(Path.of("index.html")),
        promise -> BodyHandlers.ofFile(Path.of(promise.uri().getPath()).getFileName()));
JdkFlowAdapter.flowPublisherToFlux(publisher)
    .filter(res -> res.statusCode() == 200)
    .map(HttpResponse::body)
    .subscribe(System.out::println);
```

The function passed to `exchange(...)` must return a `BodyHandler<T>` for handling the pushed
response. It may return `null` to reject the promise entirely.

### Sending forms

[`FormBodyPubliher`][FormBodyPublisher] implements the `application/x-www-form-urlencoded` format
often used with `<form>` HTML tags. Data in this body is added as a sequence of basic `String`
key-value pairs and serialized into a URL-encoded string.

```java
FormBodyPublisher body = FormBodyPublisher.newBuilder()
    .query("foo", "bar")
    .query("baz", "qux")
    .build();
System.out.println("Encoded body: " + body.encodedString());

HttpRequest request = HttpRequest.newBuilder()
    .POST(body)
    .header("Content-Type", body.mediaType().toString())
     ...
    .build();
```

Note that `FormBodyPublisher` implements [`MimeBodyPublisher`](MimeWiki.md#mimebodypublisher) so it
knows it's request's `Content-Type`, which can be transparently added if using the
[`Methanol`][Methanol] client.

### Multipart bodies

[`MultipartBodyPublisher`][MultipartBodyPublisher] implements the more flexible `multipart` format.
This format is often used with file uploads and sending composite bodies with mixed schemes. A
multipart body has one or more parts separated by a boundary, where each part is itself another
`BodyPublisher` with `HttpHeaders` that describe it. The default multipart subtype is `form-data`,
but you can use any other subtype as well. `MultipartBodyPublisher.Builder`'s API is flexible enough
for adding any combination of body parts you want. The builder also has convenient methods for
directly adding form parts (those with a `Content-Disposition: form-data` header).

```java
MultipartBodyPublisher body =
MultipartBodyPublisher.newBuilder()
    .textPart("text_field", "Hello world!")
    .filePart("file_field", Path.of("path/to/file"))
    .formPart(
        "json_field",
        MoreBodyPublishers.ofMediaType(
            BodyPublishers.ofString(...), MediaType.APPLICATION_JSON)) // explicitly specify part's Content-Type
    .part(Part.create(HttpHeaders.of(...), BodyPublishers.ofInputStream(...))) // can use custom headers/bodies
    .build();
```

Note that if a file part is added without specifying a `MediaType`, the builder will ask the system
for one using the provided `Path`. If it fails to do so, `application/octet-stream` will be used.
You can explicitly specify a form-part's media type by adding it as a `MimeBodyPublisher`.

### WritableBodyPublisher

Not all APIs play well with non-blocking components like `BodyPublisher`. Many only support writing
into a blocking sink like an `OutputStream` or a `Reader`. Using such APIs can be easier with
[`WritableBodyPublisher`][WritableBodyPublisher], which allows you to stream the request body
through an `OutputStream` or a `WritableByteChannel`, possibly asynchronously.

Let's say your sever supports compressed requests, and you want your file upload to be faster, so
you compress the request body with gzip.

```java
final Methanol client = Methanol.create();

CompletableFuture<HttpResponse<Void>> postAsync(Path file) {
  WritableBodyPublisher requestBody = WritableBodyPublisher.create();
  MutableRequest request = MutableRequest.POST("https://example.com", requestBody)
      .header("Content-Encoding", "gzip");

  CompletableFuture.runAsync(() -> {
    try (OutputStream gzipOut = new GZIPOutputStream(requestBody.outputStream())) {
      Files.copy(file, gzipOut);
    } catch (IOException ioe) {
      requestBody.closeExceptionally(ioe);
    }
  });

  return client.sendAsync(request, BodyHandlers.discarding());
}
```

Note that `WritableBodyPublisher` acts as a pipe which connects `OutputStream` and
`Publisher<ByteBuffer>` backends. It may buffer content temporarily in case the consumer can't keep
up with the producer, or till an inner buffer becomes full. You can use `WritableBodyPublisher::flush`
to make any buffered content consumable by the downstream. After writing content, call `close()` or
`closeExceptionally(Throwable)` to complete the request either normally or exceptionally.

### Interruptible reading

Another feature you might find useful if you like reading from blocking sources is support for
[interruptible channels][InterruptibleChannel]. These allow you to asynchronously close or interrupt
a reading operation in case it is not relevant anymore (e.g. when closing the application or
changing contexts). [`MoreBodySubscibers`][MoreBodySubscribers] provides interruptible
`ReadableByteChannel` and `Reader` implementations.

This example uses a hypothetical component that reads the response from a `ReadableByteChannel`.
When the task is to be discarded, reader threads are interrupted by shutting down the containing
`ExecutorService`. This closes the channel and instructs it to immediately stop blocking.

```java
class BodyProcessor {
  final ExecutorService service = Executors.newCachedThreadPool();
  final HttpClient client = HttpClient.newHttpClient();

  CompletableFuture<Void> processAsync(HttpRequest request, Consumer<ByteBuffer> processAction) {
    return client.sendAsync(request, MoreBodyHandlers.ofByteChannel())
        .thenApplyAsync(res -> {
          ByteBuffer data = ByteBuffer.allocate(4 * 1024);
          try (ReadableByteChannel channel = res.body()) {
            while (channel.read(data.clear()) >= 0) {
              processAction.accept(data.flip());
            }
          } catch (ClosedByInterruptException | ClosedChannelException ignored) {
            // The thread was interrupted due to pool shutdown
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return null;
        }, service);
  }

  void terminate() { service.shutdownNow(); }
}
```

### Tracking progress

Methanol introduces a simple API for tracking the progress of single upload or download operations.
You do that by attaching a [`Listener`][Listener] to a `BodyPublisher` or `BodyHandler` using a
configured [`ProgressTracker`][ProgressTracker]. To avoid getting too many events, you can configure
a tracker to signal progress only when a byte count or time threshold is exceeded. You can also set
a custom `Executor` for invoking listener callbacks. If the listener updates GUI for example, this
can be used to make sure it is executed in the GUI thread.

```java
ProgressTracker tracker = ProgressTracker.newBuilder()
    .bytesTransferredThreshold(5 * 1024) // at least 5KB is downloaded before any events
    .timePassedThreshold(Duration.ofSeconds(1)) // at least 1 second passes before any events
    .executor(SwingUtilities::invokeLater) // invoke in event dispatcher thread if updating UI
    .build();

// Track upload
BodyPublisher requestBody =
    tracker.tracking(BodyPublishers.ofString(...), p -> logUploadProgress(p));
MutableRequest request = MutableRequest.POST(url, requestBody);

// Track download
HttpResponse<String> response =
    client.send(request, tracker.tracking(BodyHandlers.ofString(), p -> logDownloadProgress(p)));
```

In case of [compressed responses](#response-decompression), the `Content-Length` header becomes
invalidated, which prevents calculation of progress percentage. On such case you can first send a
`HEAD` request with `Accept-Encoding: identity` to get the correct length. Then use that to track
download from a downstream `BodySubscriber`. See 
[this JavaFX sample](methanol-samples/src/main/java/com/github/mizosoft/methanol/samples/DownloadProgress.java)
for an example.

[tck]: <https://github.com/reactive-streams/reactive-streams-jvm/tree/master/tck-flow>
[httpclient_recipies]: <https://openjdk.java.net/groups/net/httpclient/recipes.html>
[so_question]: <https://stackoverflow.com/questions/53502626/does-java-http-client-handle-compression>
[MoreBodyHandlers_decoding]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/MoreBodyHandlers.html#decoding(java.net.http.HttpResponse.BodyHandler>
[BodyDecoder]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/BodyDecoder.html>
[methanol_brotli]: <https://github.com/mizosoft/methanol/tree/master/methanol-brotli>
[google_brotli]: <https://github.com/google/brotli>
[AsyncBodyDecoder]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/dec/AsyncBodyDecoder.html>
[AsyncDecoder]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/dec/AsyncDecoder.html>
[Methanol]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/Methanol.html>
[MutableRequest]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/MutableRequest.html>
[MediaType]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/MediaType.html>
[media_ranges]: <https://tools.ietf.org/html/rfc7231#section-5.3.2>
[MoreBodyPublishers_ofMediaType]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/MoreBodyPublishers.html#ofMediaType(java.net.http.HttpRequest.BodyPublisher,com.github.mizosoft.methanol.MediaType)>
[TypeRef]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/TypeRef.html>
[BodyAdapter]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/BodyAdapter.html>
[Encoder]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/Encoder.html>
[Decoder]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/Decoder.html>
[BodySubscribers_mapping_bug]: <https://bugs.openjdk.java.net/browse/JDK-8219213>
[BodyHandlers_mapping_jdk13]: <https://docs.oracle.com/en/java/javase/13/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscribers.html#mapping(java.net.http.HttpResponse.BodySubscriber,java.util.function.Function)>
[MoreBodyHandlers_ofObject]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/MoreBodyHandlers.html#ofObject(com.github.mizosoft.methanol.TypeReference)>
[MoreBodyHandlers_ofDeferredObject]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/MoreBodyHandlers.html#ofDeferredObject(com.github.mizosoft.methanol.TypeReference)>
[FormBodyPublisher]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/FormBodyPublisher.html>
[MultipartBodyPublisher]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/MultipartBodyPublisher.html>
[WritableBodyPublisher]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/WritableBodyPublisher.html>
[InterruptibleChannel]: <https://docs.oracle.com/javase/7/docs/api/java/nio/channels/InterruptibleChannel.html>
[MoreBodySubscribers]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/MoreBodySubscribers.html>
[ProgressTracker]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/ProgressTracker.html>
[Listener]: <https://mizosoft.github.io/methanol/1.x/doc/methanol/com/github/mizosoft/methanol/Listener.html>
