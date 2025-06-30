# Change Log

## Version 1.8.3

* Fixed [#121](https://github.com/mizosoft/methanol/issues/121), where the response body was retained throughout the
  entire read timeout, resulting in a "timed" memory leak. This happened as the previously used JVM-wide scheduler
  retained
  references to timeout tasks (which retained references to the response body through a reference chain starting from
  `TimeoutBodySubscriber`).
  Methanol now uses a library-wide scheduler that loses references to timeout tasks when invalidated.

* Fixed [#125](https://github.com/mizosoft/methanol/issues/125), where exception causes where swallowed in sync calls.

## Version 1.8.2 

Fix regression caused by [#118](https://github.com/mizosoft/methanol/pull/118).

## Version 1.8.1 

Fixed [#117](https://github.com/mizosoft/methanol/issues/117), where decompressing the response could potentially hang.

## Version 1.8.0

Ok, here we go. That took a while.

There's been a number of unreleased features brewing in the last two and a half years (!). Guess I could say I've been cooking some Meth—anol, and now it's ready to serve. What's—my—name? Please don't say [Heisenbug](https://en.wikipedia.org/wiki/Heisenbug#:~:text=In%20computer%20programming%20jargon%2C%20a,one%20attempts%20to%20study%20it.).

Anyhow, here's what's new:

* Added a [Redis storage backend](https://mizosoft.github.io/methanol/redis/) for the HTTP cache, which supports Standalone & Cluster setups.
* Added the ability to chain caches with different storage backends, expectedly in the order of decreasing locality.
  This will work well with the Redis cache. Consider the case where you have multiple instances of your service all sharing
  a Redis setup, you can have a chain of (JVM memory -> Redis) or even (JVM memory -> disk -> Redis) caches, so each node can have a local cache to consult first, and the shared Redis cache after.
* The object mapping mechanism has been reworked to stay away from `ServiceLoader` & static state. 
  We now have an `AdapterCodec` that is registered per-client.
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
  
  HttpResponse<Person> response = client.send(
        MutableRequest.GET(".../echo", new Person("Jack Reacher"), MediaType.APPLICATION_JSON),
        Person.class);
  ```
* Added hip Kotlin extensions. These were enjoyable to work on. [Check them out!](https://mizosoft.github.io/methanol/kotlin/).
* Added adapters for [Moshi](https://github.com/square/moshi). This is mainly intended for Kotlin.
* Added hints API for adapters. This allows carrying arbitrary parameters to customize encoders & decoders. Currently, supported
  adapters expose no customization. If you think there's a useful, generalizable customization that can be passed to any of the supported adapters, feel free to create an issue.
* Added `MoreBodyPublishers::ofOutputStream` & `MoreBodyPublishers::ofByteChannel` to be used in favor of `WritableBodyPublisher`.
* Added adapters for [basic](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/AdapterCodec.Builder.html#basic()) types in the core module.
* Added the ability to conditionally handle responses with [`ResponsePayload`](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/ResponsePayload.html) using the basic adapter.
* Disk cache writes became considerably faster by avoiding `fsync` on entry writes/updates, which was used to provide durability in a manner that later turned out
  to be unnecessary for caches. Now CRC checks are used. Reads however became slightly slower.
* Added adapters for JAXB Jakarta. They're practically the same as JAXB JavaEE, but use the newer namespaces.
* New `HttpClient` APIs for closure & for setting a local socket address have been implemented.
* As of Java 16, `sendAsync(...).cancel(true)`, or an interruption for the thread calling `send` can cancel the underlying
  exchange. This is made sure to continue being the case even after the Methanol seasoning.
* Made `ResponseBuilder` part of public API.

There are other incremental improvements here and there that I may have missed. I promise though your code won't break after the update. If that happens, please file an issue.

Later!

## Version 1.7.0

*9-5-2022*

A full year has passed since the last Methanol release! Time truly flies. It's been difficult to find
the time to cut this release due to my senior college year & other life circumstances, but here we are!

* The Jackson adapter has been reworked to support the multitude of formats supported by Jackson, not 
  only JSON ([#45](https://github.com/mizosoft/methanol/issues/45)). That means you can now pass arbitrary
  `ObjectMapper` instances along with one or more `MediaTypes` describing their formats. For instance,
  here's a provider for a Jackson-based XML decoder.

  ```java
  public class JacksonXmlDecoderProvider {
    private JacksonXmlDecoderProvider() {}
  
    public static BodyAdapter.Decoder provider() {
      return JacksonAdapterFactory.createDecoder(new XmlMapper(), MediaType.TEXT_XML);
    }
  }
  ```

  Binary formats (e.g. protocol buffers) usually require applying a schema for each type. `ObjectReaderFacotry` 
  & `ObjectWriterFactory` have been added for this purpose. For instance, here's a provider for a protocol-buffers
  decoder. You'll need to know which types to expect beforehand.

  ```java
  public class JacksonProtobufDecoderProvider {
    private JacksonProtobufDecoderProvider() {}
  
    public record Point(int x, int y) {}
  
    public static BodyAdapter.Decoder provider() throws IOException {
      var schemas = Map.of(
          TypeRef.from(Point.class),
          ProtobufSchemaLoader.std.parse(
              """
              message Point {
                required int32 x = 1;
                required int32 y = 2;
              }
              """), ...);
      
      // Apply the corresponding schema for each created ObjectReader
      ObjectReaderFactory readerFactory = 
          (mapper, type) -> mapper.readerFor(type.rawType()).with(schemas.get(type));
      return JacksonAdapterFactory.createDecoder(
          new ProtobufMapper(), readerFactory, MediaType.APPLICATION_X_PROTOBUF);
    }
  }
  ```

* To avoid ambiguity, `JacksonAdapterFactory::createDecoder` & `JacksonAdapterFactory::createEncoder`
  that don't take an explicit `MediaType` have been deprecated and replaced with `JacksonAdapterFactory::createJsonDecoder`
  & `JacksonAdapterFactory::createJsonEncoder` respectively.

* Added timeouts for receiving all response headers ([#49](https://github.com/mizosoft/methanol/issues/49)).
  You can use these along with read timeouts to set more granular timing constraints for your requests
  when request timeouts are too strict.
 
  ```java
  var client = Methanol.newBuilder()
      .headersTimeout(Duration.ofSeconds(30))
      .readTimeout(Duration.ofSeconds(30))
      ...
      .build()
  ```

* Fix ([#40](https://github.com/mizosoft/methanol/issues/40)): Methanol had a long-lived issue that made it
  difficult for service providers to work with custom JAR formats, particularly the one used by Spring Boot's
  [executable JARs](https://docs.spring.io/spring-boot/docs/current/reference/html/executable-jar.html).
  Instead of the system classloader, Methanol now relies on the classloader that loaded the library itself
  for locating providers. This is not necessarily the system classloader as in the case with Spring Boot.
* Fix ([46](https://github.com/mizosoft/methanol/issues/46)): `ProgressTracker` now returns `MimeBodyPublisher`
  if the body being tracked is itself a `MimeBodyPublisher`. This prevents "swallowing" the `MediaType` of such bodies.
* Upgraded Jackson to `2.13.2`.
* Upgraded Gson to `2.9.0`.
* Upgraded Reactor to `3.4.17`.

## Version 1.6.0

*30-5-2021*

* Added [`HttpCache.Listener`](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/HttpCache.Listener.html).
* Added `TaggableRequest`. This facilitates carrying application-specific data throughout interceptors & listeners.

  ```java
  var interceptor = Interceptor.create(request -> {
      var taggableRequest = TaggableRequest.from(request);
      var context = taggableRequest.tag(MyContext.class).orElseGet(MyContext::empty);
      ...
  });
  var client = Methanol.newBuilder()
      .interceptor(interceptor)
      .build();
  
  var context = ...
  var request = MutableRequest.GET("https://example.com")
      .tag(MyContext.class, context);
  var response = client.send(request, BodyHandlers.ofString());
  ```
  
* Fixed disk cache possibly manipulating the disk index concurrently. This could happen if an index
  update is delayed, as the scheduler mistakenly ran the index write immediately after the delay evaluates instead
  of queuing it with the sequential index executor.
* Fixed `TimeoutSubscriber` (used in `MoreBodySubscribers::withReadTimeout`) possibly calling
  downstream's `onNext` & `onError` concurrently. This could happen if timeout evaluates while downstream's
  `onNext` is still executing.
* Made `AsyncBodyDecoder` ignore upstream signals after decoding in `onNext` fails and the error is
  reported to `onError`. This prevents receiving further `onXXXX` by upstream if it doesn't immediately
  detect cancellation.
* Made the disk cache catch and log `StoreCorruptionException` thrown when opening an entry. This is
  done instead of rethrowing.
* `Methanol` now always validates request's `URI` after being resolved with the optional base `URI`.
  Previously, the `URI` was only validated if there was a base `URI`.
* Upgraded [gson to 2.8.7](https://github.com/google/gson/blob/master/CHANGELOG.md#version-287).

## Version 1.5.0

*14-5-2021*

* Methanol now has an [RFC-compliant][httpcaching_rfc] HTTP cache! It can store entries on disk or
  in memory. Give it a try!
  ```java
  void cache() throws InterruptedException, IOException {
    var cache = HttpCache.newBuilder()
        .cacheOnDisk(Path.of("cache-dir"), 100 * 1024 * 1024)
        .build();
    var client = Methanol.newBuilder()
        .cache(cache)
        .build();

    var request = MutableRequest.GET("https://i.imgur.com/NYvl8Sy.mp4");
    var response = (CacheAwareResponse<Path>) client.send(
        request, BodyHandlers.ofFile(Path.of("banana_cat.mp4")));

    System.out.printf(
        "%s (CacheStatus: %s, elapsed: %s)%n",
        response,
        response.cacheStatus(),
        Duration.between(response.timeRequestSent(), response.timeResponseReceived()));

    cache.close();
  }
  ```

* Added `CacheControl` to model the `Cache-Control` header and its directives. This is complementary
  to the new cache as all configuration is communicated through `Cache-Control`.
* Interceptors have been reworked. The old naming convention is deprecated. An interceptor is now either 
  a *client* or a *backend* interceptor instead of a pre/post decoration interceptor, where 'backend' refers
  to `Methanol`'s backing `HttpClient`. The cache intercepts requests after client but before backend
  interceptors. It was tempting to name the latter 'network interceptors', but that seemed rather confusing
  as not all 'network' requests can be intercepted (`HttpClient` can make its own intermediate requests
  like redirects & retries).
* Added `HttpStatus`, which contains functions for checking response codes.
* Added `ForwardingEncoder` & `ForwardingDecoder`. These are meant for easier installation of adapters
  from the classpath.
* `System.Logger` API is now used instead of `java.util.logging`.
* Fix: Don't attempt to decompress responses to HEADs. This fixed failures like `unexpected end of gzip stream`.
* Fix: Decompressed responses now have their stale `Content-Encoding` & `Content-Length` headers removed.  
* Changed reactor dependency to API scope in the `methanol-jackson-flux` adapter.
* Upgraded [Jackson to 2.12.3](https://github.com/FasterXML/jackson-databind/blob/f67f2a194651609deeffc4dba868bb767c067c57/release-notes/VERSION-2.x#L52).
* Upgraded [Reactor to 3.4.6](https://github.com/reactor/reactor-core/releases/tag/v3.4.6).
* New [project website](https://mizosoft.github.io/methanol)!

## Version 1.4.1

*26-9-2020*

* Updated dependencies.
* Fix: Autodetect if a deflated stream is zlib-wrapped or not to not crash when some servers 
  incorrectly send raw deflated bytes for the `deflate` encoding.

## Version 1.4.0

*27-7-2020*

* Multipart progress tracking.

## Version 1.3.0

*22-6-2020*

* Default read timeout in `Methanol` client.
* API for tracking upload/download progress.
* High-level client interceptors.

## Version 1.2.0

*1-5-2020*

* Reactive JSON adapters with Jackson and Reactor.
* Common `MediaType` constants.
* XML adapters with JAXB.

## Version 1.1.0

*17-4-2020* 

* First "main-stream" release.

## Version 1.0.0

*25-3-2020*

* Dummy release.

[httpcaching_rfc]: https://datatracker.ietf.org/doc/html/rfc7234
