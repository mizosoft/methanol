# Retrying Requests

Methanol provides an [Interceptor](./interceptors.md) implementation that retries requests based on declaratively
specified conditions.

## Usage

You can create [`RetryingIntercepotors`](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/RetryingInterceptor.html) by specifying conditions that trigger retries, based on the resulting response or
exception, with varying degrees of specificity. Here's a `RetryingInterceptor` that retries `5xx` responses or `ConnectExceptions` at most 3 times (making at most 4 total attempts),
and backs off each retry with [exponential full-jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/) delays to not overwhelm the server.

```java
var interceptor =
    RetryingInterceptor.newBuilder()
        .maxRetries(3)
        .onException(ConnectException.class)
        .onStatus(HttpStatus::isServerError)
        .backoff(
            RetryingInterceptor.BackoffStrategy.exponential(
                    Duration.ofMillis(100), Duration.ofSeconds(5))
                .withJitter())
        .build();
```

It can be applied to `Methanol` as any other interceptor.

```java
var client = Methanol.newBuilder().interceptor(interceptor).build();
var response = client.send(MutableRequest.GET("https://example.com"), BodyHandlers.ofString()); // Retry magic happens here.
```

## Retry Behavior

`RetryingInterceptor.Builder` has a number of `onX` methods to specify retry conditions. You can also set a timeout on the entire
retry process.

```java
var interceptor = 
    RetryingInterceptor.newBuilder()
        .maxRetries(5)
        .onException(ConnectException.class, SSLException.class) // Retry on exception classes.
        .onException(e -> e instanceof IOException) // Retry on generic exception predicates.
        .onStatus(500, 501) // Retry on specific status codes.
        .onStatus(HttpStatus::isServerError) // Retry on generic status predicates.
        .onResponse(response -> response.statusCode() == 500) // Retry on generic response predicates.
        .on(
            context ->
                context.exception().map(e -> e instanceof IOException).orElse(false)
                    || context
                        .response()
                        .map(r -> r.statusCode() == 500)
                        .orElse(false)) // Retry on retry context predicates.
        .timeout(Duration.ofSeconds(10));
```

If any of the conditions evaluates to true, the request is retried. Retrying goes on until any of the following happens:
  1. None of the specified conditions evaluate to true. The resulting response or thrown exception is forwarded as-is, which is what you want.
  2. At least one retry condition evaluates to true, but the maximum number of retries is exhausted. By default, the resulting response or thrown exception is forwarded as-is. If you never want to get a retryable response or exception, call builder's `RetryingInterceptor.Builder::throwOnExhaustion` so an `HttpRetriesExhaustedException` is thrown in this case.
  3. The total retry timeout is exceeded. An `HttpRetryTimeoutException` is thrown in this case.

!!! note
    Retry conditions are evaluated in declaration order. The first matching condition determines retry behavior - subsequent conditions are not evaluated.

## Request Selectors

It is a good practice to share HTTP client instances when talking to different endpoints. If that's the case, you might want to apply
different retry strategies for each endpoint, or only retry on certain endpoints. You can use request selectors for filtering what requests
to apply a certain `RetryingInterceptor` to.

```java
var client =
    Methanol.newBuilder()
        .interceptor(
            RetryingInterceptor.newBuilder()
                .maxRetries(10)
                .onException(ConnectException.class)
                .onStatus(HttpStatus::isServerError)
                .backoff( // We know our internal service uses the Retry-After header, so use that for backing off.
                    BackoffStrategy.retryAfterOr(
                        BackoffStrategy.exponential(
                            Duration.ofMillis(100), Duration.ofSeconds(10))))
                .build(request -> request.uri().getHost().startsWith("internal.")))
        .build();
```

Or you may want to only retry if explicitly specified. Request tags are perfect candidates for this.

```java
class RetryRequestTag {}

var client =
    Methanol.newBuilder()
        .interceptor(
            RetryingInterceptor.newBuilder()
                .maxRetries(5)
                .onException(ConnectException.class)
                .onStatus(HttpStatus::isServerError)
                .backoff(
                    BackoffStrategy.exponential(Duration.ofMillis(100), Duration.ofSeconds(10)))
                .build(
                    request ->
                        TaggableRequest.tagOf(request, RetryRequestTag.class).isPresent()))
        .build();

// Only retry if a RetryRequestTag is set.
client.send(
    MutableRequest.GET("https://example.com").tag(new RetryRequestTag()),
    BodyHandlers.discarding());
```

## Request Modification

The interceptor lets you modify requests before being sent for the first time and/or before each retry based on each retry condition.
This can be useful if you want to attach debug headers to each request.

```java
var client =
    Methanol.newBuilder()
        .interceptor(
            RetryingInterceptor.newBuilder()
                .beginWith(request -> MutableRequest.copyOf(request).header("X-Attempt", "1")) // Modifies request before FIRST attempt (not retries)
                .onException(
                    Set.of(ConnectException.class),
                    context ->
                        MutableRequest.copyOf(context.request())
                            .header("X-Attempt", Integer.toString(context.retryCount() + 1))
                            .header("X-Retry-Reason", "ConnectException"))
                .onStatus(
                    HttpStatus::isServerError,
                    context ->
                        MutableRequest.copyOf(context.request())
                            .header("X-Attempt", Integer.toString(context.retryCount() + 1))
                            .header("X-Retry-Reason", "Server error"))
                .build())
        .build();
```

Using this pattern, you can camouflage a `RetryingInterceptor` into a reactively authenticating interceptor.

```java
// Basic Authentication with automatic credential refresh on 401.
var clientWithAuth = Methanol.newBuilder()
    .interceptor(
        RetryingInterceptor.newBuilder()
            .maxRetries(2)
            .beginWith(request ->
                MutableRequest.copyOf(request)
                    .header("Authorization", createBasicAuthHeader(getCurrentCredentials()))
                    .build())
            .onStatus(Set.of(401), context ->
                MutableRequest.copyOf(context.request())
                    .removeHeader("Authorization")
                    .header("Authorization", createBasicAuthHeader(refreshCredentials()))
                    .build())
            .build())
    .build();

// Helper method.
private String createBasicAuthHeader(Credentials creds) {
    var auth = creds.username() + ":" + creds.password();
    return "Basic " + Base64.getEncoder()
        .encodeToString(auth.getBytes(StandardCharsets.UTF_8));
}

private Credentials getCurrentCredentials() {
  // ...
}

private Credentials refreshCredentials() {
  // ...
}
```

## Monitoring Retries

For debugging/monitoring purposes, you can apply a [RetryingInterceptor.Listener](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/RetryingInterceptor.Listener.html) to receive retry events.

```java
var interceptor = RetryingInterceptor.newBuilder()
    .maxRetries(5)
    .onException(ConnectException.class)
    .onStatus(HttpStatus::isServerError)
    .backoff(BackoffStrategy.exponential(Duration.ofMillis(100), Duration.ofSeconds(10)))
    .listener(new RetryingInterceptor.Listener() {
      @Override
      public void onRetry(RetryingInterceptor.Context<?> context, HttpRequest nextRequest, Duration delay) {
        System.out.println("Retrying request " + nextRequest + " in " + delay);
      }
    })
    .build();
```
