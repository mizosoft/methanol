# Interceptors

Interceptors allow you to inspect, mutate, retry and short-circuit HTTP calls. Together, interceptors
build an invocation chain that's capable of applying powerful transformations to requests moving forward
and to responses in their way back.

## Writing Interceptors

Interceptors sit between a `Methanol` client and its underlying `HttpClient`, referred to as its backend.
When registered, an interceptor is invoked each `send` or `sendAsync` call. Here's an interceptor that logs requests and their responses.

```java
public final class LoggingInterceptor implements Methanol.Interceptor {
  private static final Logger logger = Logger.getLogger(LoggingInterceptor.class.getName());

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    logRequest(request);
    return toLoggingChain(request, chain).forward(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    logRequest(request);
    return toLoggingChain(request, chain).forwardAsync(request);
  }

  private static void logRequest(HttpRequest request) {
    logger.info(() -> String.format("Sending %s%n%s", request, headersToString(request.headers())));
  }

  private static <T> Chain<T> toLoggingChain(HttpRequest request, Chain<T> chain) {
    var sentAt = Instant.now();
    return chain.withBodyHandler(responseInfo -> {
        logger.info(() -> String.format(
            "Completed %s %s with %d in %sms%n%s",
            request.method(),
            request.uri(),
            responseInfo.statusCode(),
            Duration.between(sentAt, Instant.now()).toMillis(),
            headersToString(responseInfo.headers())));

        // Apply the original BodyHandler
        return chain.bodyHandler().apply(responseInfo);
    });
  }

  private static String headersToString(HttpHeaders headers) {
    return headers.map().entrySet().stream()
        .map(entry -> entry.getKey() + ": " + String.join(", ", entry.getValue()))
        .collect(Collectors.joining(System.lineSeparator()));
  }
}
```

`HttpClient` has blocking and asynchronous APIs, so interceptors must implement two methods matching each.
An interceptor is given a `Chain<T>` so it can forward requests to its next sibling, or to the backend in case there are no more interceptors in the chain.
The backend is where requests finally get sent. Typically, an interceptor calls its chain's `forward` or `forwardAsync` after it has done its job.

If your interceptor only modifies requests, prefer passing a lambda to `Interceptor::create`.

```java
// Enable 'Expect: Continue' for all POSTs to a particular host
var expectContinueInterceptor = Interceptor.create(request ->
    request.method().equalsIgnoreCase("POST") && request.uri().getHost().equals("api.imgur.com")
        ? MutableRequest.copyOf(request).expectContinue(true)
        : request);
```

## Intercepting Bodies

A powerful property of interceptors is their control over how responses are received by their caller.
An interceptor can transform its chain's `BodyHandler` before it forwards requests.
A transformed `BodyHandler` typically applies the handler the chain previously had, then wraps the resulted `BodySubscriber`, so it intercepts the response body as it's being received by the caller.
This is how `Methanol` does transparent decompression & cache writes.

Note that this applies to requests too. You can transform a request body by wrapping its `BodyPublisher`.
`BodyPublisher` & `BodySubscriber` APIs can be nicely layered to apply different transformations.

## Invocation Order

An interceptor can be either a *client* or a *backend* interceptor. Client interceptors sit between
the application and `Methanol`'s internal interceptors. They are called as soon as the client receives a request.
Backend interceptors sit between `Methanol` and its backend `HttpClient`. They get invoked right before the request gets sent.
This has a number of implications.

### Client Interceptors

 * See the request just as received from the application.
 * Their transformed `BodyHandler` receives the response body after the client applies its
   decompressing & cache writing handlers.

### Backend Interceptors

* Observe the request after the client applies things like the base URI and default headers.
  Additionally, they see intermediate headers added by the client or the cache like `Accept-Encoding` & `If-None-Math`.
* Receive the response body just as transmitted by the backend. For instance, a transformed
  `BodyHandler` receives a compressed body if the response comes with a `Content-Encoding` header.
* May not always be invoked. This is the case when a cache decides it doesn't need network and hence
  doesn't proceed the call to the backend.

!!! attention
    If a cache is installed, `Methanol` does automatic redirection by itself, which would otherwise
    be done by the backend. This allows redirects to be cached, increasing cache efficiency. As a
    consequence, backend interceptors may get invoked more than once in the presence of a cache.

## Registration

You can register client and backend interceptors with `interceptor(...)` and `backendInterceptor(...)` respectively.
Interceptors in each group get invoked in registration order.

=== "Client Interceptors"

    ```java
    var client = Methanol.newBuilder()
        .interceptor(new LoggingInterceptor())
        .build();
    ```

=== "Backend Interceptors"

    ```java
    var client = Methanol.newBuilder()
        .backendInterceptor(new LoggingInterceptor())
        .build();
    ```

## Short-circuiting

Both client & backend interceptors can refrain from forwarding a request. They're allowed to
short-circuit a request's path by returning a fabricated response. This makes them good candidates for testing.
You can mock responses with client interceptors to investigate requests just as sent by your application.
Moreover, responses can be mocked with backend interceptors to explore requests as they get sent.
This makes backend interceptors suitable for testing how your application interacts with the cache.

## Limitations

Remember that `Methanol` is built atop a standard `HttpClient`, which can perform its own redirects,
retries and other intermediate requests like authentications. These aren't interceptable. That's
because `HttpClient` exports no API to do so.
