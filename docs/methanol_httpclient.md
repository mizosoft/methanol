# Enhanced HttpClient

Methanol has a special `HttpClient` that extends the standard one with interesting features.
Unsurprisingly, the client is named `Methanol`.

## Usage

In addition to [interceptors] and [caching], `Methanol` can apply default properties to your requests.
Think resolving with a base URI, adding default request headers, default timeouts, etc.

```java
var builder = Methanol.newBuilder()
    .userAgent("Will Smith")                     // Custom User-Agent
    .baseUri("https://api.github.com")           // Base URI to resolve requests' URI against
    .defaultHeader("Accept","application/json")  // Default request headers
    .requestTimeout(Duration.ofSeconds(20))      // Default request timeout
    .headersTimeout(Duration.ofSeconds(5))       // Timeout for receiving response headers
    .readTimeout(Duration.ofSeconds(5))          // Timeout for single reads
    .autoAcceptEncoding(true);                   // Transparent response compression, this is true by default

// Continue using as a standard HttpClient.Builder.
var client = builder.connectTimeout(Duration.ofSeconds(30)).build();
```

You can also build from an existing `HttpClient` instance. However, you can't install an `HttpCache` in such case.

```java
var prebuiltClient = HttpClient.newHttpClient();
var client = Methanol.newBuilder(prebuiltClient).build();
```

!!! note
    Default properties don't override those the request already has. For instance, a client with a
    default `Accept: text/html` will not override a request's `Accept: application/json`.

### Transparent Compression

If `autoAcceptEncoding` is enabled, the client complements requests with an `Accept-Encoding` header
which accepts all supported encodings (i.e. available [`BodyDecoder`](decompression.md) providers).
Additionally, the response is transparently decompressed.

Since `deflate` & `gzip` are supported out of the box, they're always included in `Accept-Encoding`.
For instance, if [brotli][methanol-brotli] is installed, requests will typically have:
`Accept-Encoding: gzip, deflate, br`.
If you want specific encodings to be applied, add `Accept-Encoding` as a default header or
explicitly set one in your request.

=== "Default Header"

    ```java
    // Advertise brotli decompression
    var client = Methanol.newBuilder()
        .defaultHeader("Accept-Encoding", "br")
        .build();
    ```

=== "Request Header"

    ```java
    // Advertise brotli decompression
    var request = MutableRequest.GET(uri)
        .header("Accept-Encoding", "br");
    ```

### MimeBodyPublisher

`Methanol` automatically sets a request's `Content-Type` if it has a [`MimeBodyPublisher`](media_types.md#mimebodypublisher).
If the request already has a `Content-Type`, it's overwritten. This makes sense as a body knows its
media type better than the containing request.

### Reactive Dispatching

If you like reactive streams, use `Methanol::exchange`, which is like `sendAsync` but returns
`Publisher<HttpResponse<T>>` sources instead.

=== "Without HTTP/2 Push"

    ```java
    var client = Methanol.create();

    var request = MutableRequest.GET("https://http2-push.appspot.com/?nopush");
    var publisher = client.exchange(request, BodyHandlers.ofFile(Path.of("page.html")));

    JdkFlowAdapter.flowPublisherToFlux(publisher)
        .doOnNext(response -> System.out.printf("%s: %s", response, response.body()))
        .blockLast();
    ```

=== "With HTTP/2 Push"

    ```java
    var client = Methanol.create();

    var request = MutableRequest.GET("https://http2-push.appspot.com");
    var publisher = client.exchange(
        request, 
        BodyHandlers.ofFile(Path.of("page.html")), 
        pushPromise -> BodyHandlers.ofFile(Path.of(pushPromise.uri().getPath()).getFileName()));

    JdkFlowAdapter.flowPublisherToFlux(publisher)
        .doOnNext(response -> System.out.printf("%s: %s%n", response, response.body()))
        .blockLast();
    ```

## MutableRequest

`MutableRequest` is an `HttpRequest` with additional properties. It implements `HttpRequest.Builder` for settings request's fields.

```java
var response = client.send(
    MutableReqeust.GET(uri).header("Accept", "application/json"),
    BodyHandlers.ofString());
```

`MutableRequest` accepts relative URIs (standard `HttpRequest.Builder` doesn't).
This complements `Methanol`'s base URIs, against which relative ones are resolved.

!!! tip
    Use `MutableRequest::toImmutableRequest` to get an immutable `HttpRequest` snapshot.

[interceptors]: interceptors.md

[caching]: caching.md

[methanol-brotli]: https://github.com/mizosoft/methanol/tree/master/methanol-brotli
