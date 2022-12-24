/*
 * Copyright (c) 2022 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Utils.startsWithIgnoreCase;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_REQ_TOO_LONG;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.CacheAwareResponse;
import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.CacheControl;
import com.github.mizosoft.methanol.HttpCache.Listener;
import com.github.mizosoft.methanol.HttpStatus;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.extensions.Handlers;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An {@link Interceptor} that serves incoming requests from cache, network, both (in case of
 * successful/failed revalidation), or none (in case of unsatisfiable requests). The interceptor
 * also updates, populates and invalidates cache entries as necessary.
 */
public final class CacheInterceptor implements Interceptor {
  private static final Logger logger = System.getLogger(CacheInterceptor.class.getName());

  private static final Set<String> RETAINED_HEADERS;
  private static final Set<String> RETAINED_HEADER_PREFIXES;

  static {
    // Replacing stored headers with these names (or prefixes) from a 304 response is prohibited.
    // These lists are based on Chromium's http_response_headers.cc (see lists kNonUpdatedHeaders &
    // kNonUpdatedHeaderPrefixes).
    RETAINED_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    RETAINED_HEADERS.addAll(
        Set.of(
            "Connection",
            "Proxy-Connection",
            "Keep-Alive",
            "WWW-Authenticate",
            "Proxy-Authenticate",
            "Proxy-Authorization",
            "TE",
            "Trailer",
            "Transfer-Encoding",
            "Upgrade",
            "Content-Location",
            "Content-MD5",
            "ETag",
            "Content-Encoding",
            "Content-Range",
            "Content-Type",
            "Content-Length",
            "X-Frame-Options",
            "X-XSS-Protection"));
    RETAINED_HEADER_PREFIXES = Set.of("X-Content-", "X-Webkit-");
  }

  private final LocalCache cache;
  private final Listener listener;
  private final Executor executor;
  private final Clock clock;

  public CacheInterceptor(LocalCache cache, Listener listener, Executor executor, Clock clock) {
    this.cache = requireNonNull(cache);
    this.listener = requireNonNull(listener);
    this.executor = requireNonNull(executor);
    this.clock = requireNonNull(clock);
  }

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    return Utils.get(exchange(request, chain, false)).handle(chain.bodyHandler());
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    return exchange(request, chain, true)
        .thenCompose(rawResponse -> rawResponse.handleAsync(chain.bodyHandler(), executor))
        .thenApply(Function.identity()); // TrackedResponse<T> -> HttpResponse<T>
  }

  private CompletableFuture<RawResponse> exchange(
      HttpRequest request, Chain<?> chain, boolean async) {
    listener.onRequest(request);

    var requestTime = clock.instant();
    var asyncAdapter = new AsyncAdapter(async);
    var publisherChain = Handlers.toPublisherChain(chain, executor);
    return getCacheResponse(request, chain, asyncAdapter)
        .thenApply(
            cacheResponse ->
                new Exchange(
                    request, publisherChain, cacheResponse.orElse(null), requestTime, asyncAdapter))
        .thenCompose(Exchange::evaluate)
        .thenApply(Exchange::serveResponse)
        .thenApply(
            response -> {
              listener.onResponse(request, (CacheAwareResponse<?>) response.get());
              return response;
            });
  }

  private CompletableFuture<Optional<CacheResponse>> getCacheResponse(
      HttpRequest request, Chain<?> chain, AsyncAdapter asyncAdapter) {
    // Bypass cache if:
    //   - Request method isn't GET; This implementation only caches GETs.
    //   - The request is conditional.
    //   - The request accepts push promises; We don't know what might be pushed by the server.
    if (!"GET".equalsIgnoreCase(request.method())
        || hasPreconditions(request.headers())
        || chain.pushPromiseHandler().isPresent()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    return asyncAdapter.get(cache, request);
  }

  private void handleAsyncRevalidation(
      @Nullable Exchange networkExchange, @Nullable Throwable exception) {
    var networkResponse = networkExchange != null ? networkExchange.networkResponse : null;
    assert networkResponse != null || exception != null;
    if (networkResponse != null) {
      // Make sure the network response is released properly. If we're  updating a cache entry,
      // discarding will drain the response body into cache.
      networkResponse.discard(executor);
    } else {
      logger.log(Level.WARNING, "Asynchronous revalidation failure", exception);
    }
  }

  private static boolean hasPreconditions(HttpHeaders headers) {
    return headers.map().keySet().stream().anyMatch(CacheInterceptor::isPreconditionField);
  }

  /**
   * Returns true if the given field name denotes a precondition (rfc7232 Section 3). 'If-Match' &
   * 'If-Unmodified-Since' are meant to be seen by the origin, so requests having them are
   * forwarded. rfc7234 allows caches to evaluate other preconditions, but the added complexity is
   * discouraging, so they're also forwarded.
   */
  private static boolean isPreconditionField(String name) {
    return "If-Match".equalsIgnoreCase(name)
        || "If-Unmodified-Since".equalsIgnoreCase(name)
        || "If-None-Match".equalsIgnoreCase(name)
        || "If-Modified-Since".equalsIgnoreCase(name)
        || "If-Range".equalsIgnoreCase(name);
  }

  /**
   * Returns true if the given field name can be added implicitly by HttpClient's own filters. This
   * can happen if an Authenticator or a CookieHandler is installed. If a response varies with
   * these, it's rendered uncacheable as we can't access the corresponding values from requests, so
   * we won't be able to match them with the correct response.
   */
  private static boolean isImplicitField(String name) {
    return "Cookie".equalsIgnoreCase(name)
        || "Cookie2".equalsIgnoreCase(name)
        || "Authorization".equalsIgnoreCase(name)
        || "Proxy-Authorization".equalsIgnoreCase(name);
  }

  private static boolean isNetworkOrServerError(
      @Nullable NetworkResponse networkResponse, @Nullable Throwable error) {
    assert networkResponse != null || error != null;
    if (networkResponse != null) {
      return HttpStatus.isServerError(networkResponse.get());
    }

    // Situational errors for network usage are considered for stale-if-error treatment, as
    // they're similar to 5xx response codes (but on the client side), which are perfect candidates
    // for stale-if-error.
    var cause = Utils.getDeepCompletionCause(error); // Might be a CompletionException.
    if (cause instanceof UncheckedIOException) {
      cause = cause.getCause();
    }
    return cause instanceof ConnectException || cause instanceof UnknownHostException;
  }

  /** Returns whether the given network response can be cached. Based on rfc7234 Section 3. */
  private static boolean isCacheable(HttpRequest request, TrackedResponse<?> response) {
    if (!"GET".equalsIgnoreCase(request.method())
        || !request.uri().equals(response.uri())
        || !request.method().equalsIgnoreCase(response.request().method())) {
      return false;
    }

    // Skip partial content.
    if (response.statusCode() == HTTP_PARTIAL) {
      return false;
    }

    CacheControl responseCacheControl;
    try {
      responseCacheControl = CacheControl.parse(response.headers());
    } catch (IllegalArgumentException e) {
      // Don't crash the client because of server's ill-formed Cache-Control.
      logger.log(Level.WARNING, "Invalid Cache-Control in response", e);
      return false;
    }

    if (responseCacheControl.noStore() || CacheControl.parse(request.headers()).noStore()) {
      return false;
    }

    // Skip if the response is unmatchable or varies with fields what we can't access.
    Set<String> varyFields;
    if ((varyFields = CacheResponseMetadata.varyFields(response.headers())).contains("*")
        || varyFields.stream().anyMatch(CacheInterceptor::isImplicitField)) {
      return false;
    }

    return responseCacheControl.maxAge().isPresent()
        || responseCacheControl.isPublic()
        || responseCacheControl.isPrivate()
        || isCacheableByDefault(response.statusCode())
        || response.headers().firstValue("Expires").filter(HttpDates::isHttpDate).isPresent();
  }

  /**
   * Returns whether a response with the given code is cacheable by default (can be cached in the
   * absence of explicit expiry, and hence may rely on heuristic freshness). Based on rfc7231
   * Section 6.1.
   */
  private static boolean isCacheableByDefault(int statusCode) {
    switch (statusCode) {
      case HTTP_OK:
      case HTTP_NOT_AUTHORITATIVE:
      case HTTP_NO_CONTENT:
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_NOT_FOUND:
      case HTTP_BAD_METHOD:
      case HTTP_GONE:
      case HTTP_REQ_TOO_LONG:
      case HTTP_NOT_IMPLEMENTED:
        return true;
      case HTTP_PARTIAL:
        // Partial responses are cacheable by default, but this implementation doesn't support it.
      default:
        return false;
    }
  }

  /**
   * Updates the cache response after successful revalidation as specified in rfc7234 Section 4.3.4.
   */
  private static CacheResponse updateCacheResponse(
      CacheResponse cacheResponse, NetworkResponse networkResponse) {
    return cacheResponse.with(
        builder ->
            builder
                .setHeaders(
                    mergeHeaders(cacheResponse.get().headers(), networkResponse.get().headers()))
                .timeRequestSent(networkResponse.get().timeRequestSent())
                .timeResponseReceived(networkResponse.get().timeResponseReceived()));
  }

  private static HttpHeaders mergeHeaders(HttpHeaders cacheHeaders, HttpHeaders networkHeaders) {
    var builder = new HeadersBuilder();
    builder.addAllLenient(cacheHeaders);
    networkHeaders
        .map()
        .forEach(
            (name, values) -> {
              if (canReplaceCacheHeader(name)) {
                builder.setLenient(name, values);
              }
            });

    // Remove 1xx Warning codes.
    builder.removeIf((name, value) -> "Warning".equalsIgnoreCase(name) && value.startsWith("1"));
    return builder.build();
  }

  private static boolean canReplaceCacheHeader(String name) {
    return !(RETAINED_HEADERS.contains(name)
        || RETAINED_HEADER_PREFIXES.stream()
            .anyMatch(prefix -> startsWithIgnoreCase(name, prefix)));
  }

  /** Returns the URIs invalidated by the given exchange as specified by rfc7234 Section 4.4. */
  private static List<URI> invalidatedUris(HttpRequest request, TrackedResponse<?> response) {
    if (isUnsafe(request.method())
        && (HttpStatus.isSuccessful(response) || HttpStatus.isRedirection(response))) {
      var invalidatedUris = new ArrayList<URI>();
      invalidatedUris.add(request.uri());
      invalidatedLocationUri(request.uri(), response.headers(), "Location")
          .ifPresent(invalidatedUris::add);
      invalidatedLocationUri(request.uri(), response.headers(), "Content-Location")
          .ifPresent(invalidatedUris::add);
      return Collections.unmodifiableList(invalidatedUris);
    } else {
      return List.of();
    }
  }

  private static Optional<URI> invalidatedLocationUri(
      URI requestUri, HttpHeaders responseHeaders, String locationField) {
    return responseHeaders
        .firstValue(locationField)
        .map(requestUri::resolve)
        .filter(resolvedUri -> Objects.equals(requestUri.getHost(), resolvedUri.getHost()));
  }

  /**
   * Returns whether the given request method is unsafe, and hence may invalidate cached entries.
   * Based on rfc7231 Section 4.2.1.
   */
  private static boolean isUnsafe(String method) {
    return !"GET".equalsIgnoreCase(method)
        && !"HEAD".equalsIgnoreCase(method)
        && !"OPTIONS".equalsIgnoreCase(method)
        && !"TRACE".equalsIgnoreCase(method);
  }

  private static void closeIfPresent(@Nullable CacheResponse c) {
    if (c != null) {
      c.close();
    }
  }

  /**
   * An object that masks synchronous operations as {@code CompletableFuture} calls that are
   * executed on the caller thread and hence are always completed when returned. This is important
   * in order to share major logic between {@code intercept} and {@code interceptAsync}, which
   * facilitates implementation & maintenance.
   */
  private static final class AsyncAdapter {
    private final boolean async;

    AsyncAdapter(boolean async) {
      this.async = async;
    }

    <T> CompletableFuture<HttpResponse<T>> forward(Chain<T> chain, HttpRequest request) {
      return async
          ? chain.forwardAsync(request)
          : Unchecked.supplyAsync(() -> chain.forward(request), FlowSupport.SYNC_EXECUTOR);
    }

    CompletableFuture<Optional<CacheResponse>> get(LocalCache cache, HttpRequest request) {
      return async
          ? cache.getAsync(request)
          : Unchecked.supplyAsync(() -> cache.get(request), FlowSupport.SYNC_EXECUTOR);
    }
  }

  private final class Exchange {
    private final HttpRequest request;
    private final Chain<Publisher<List<ByteBuffer>>> chain;
    private final @Nullable CacheResponse cacheResponse;
    private final @Nullable NetworkResponse networkResponse;
    private final Instant requestTime;
    private final CacheControl requestCacheControl;
    private final AsyncAdapter asyncAdapter;

    Exchange(
        HttpRequest request,
        Chain<Publisher<List<ByteBuffer>>> chain,
        @Nullable CacheResponse cacheResponse,
        Instant requestTime,
        AsyncAdapter asyncAdapter) {
      this(
          request,
          chain,
          cacheResponse,
          null,
          requestTime,
          CacheControl.parse(request.headers()),
          asyncAdapter);
    }

    private Exchange(
        HttpRequest request,
        Chain<Publisher<List<ByteBuffer>>> chain,
        @Nullable CacheResponse cacheResponse,
        @Nullable NetworkResponse networkResponse,
        Instant requestTime,
        CacheControl requestCacheControl,
        AsyncAdapter asyncAdapter) {
      this.request = request;
      this.chain = chain;
      this.cacheResponse = cacheResponse;
      this.networkResponse = networkResponse;
      this.requestTime = requestTime;
      this.requestCacheControl = requestCacheControl;
      this.asyncAdapter = asyncAdapter;
    }

    /** Evaluates this exchange and returns an exchange that's ready to serve the response. */
    CompletableFuture<Exchange> evaluate() {
      if (cacheResponse != null && cacheResponse.isServable()) {
        return CompletableFuture.completedFuture(this);
      }

      // Return immediately after firing async revalidation if stale-while-revalidate applies.
      if (cacheResponse != null && cacheResponse.isServableWhileRevalidating()) {
        // TODO implement a bounding policy on asynchronous revalidation & find a mechanism to
        //      notify caller for revalidation's completion.
        backgroundNetworkExchange()
            .thenCompose(Exchange::updateCache)
            .whenComplete(CacheInterceptor.this::handleAsyncRevalidation);
        return CompletableFuture.completedFuture(this);
      }

      // Don't use the network if the request prohibits it.
      if (requestCacheControl.onlyIfCached()) {
        return CompletableFuture.completedFuture(this);
      }

      listener.onNetworkUse(request, cacheResponse != null ? cacheResponse.get() : null);
      return networkExchange()
          .handle(this::handleNetworkOrServerError)
          .thenCompose(Exchange::updateCache);
    }

    CompletableFuture<Exchange> updateCache() {
      if (networkResponse == null) {
        return CompletableFuture.completedFuture(this);
      }

      // On successful revalidation, update the stored response as specified by rfc7234 Section
      // 4.3.3.
      if (cacheResponse != null && networkResponse.get().statusCode() == HTTP_NOT_MODIFIED) {
        // Release the network response properly.
        networkResponse.discard(executor);

        var updatedCacheResponse = updateCacheResponse(cacheResponse, networkResponse);
        cache
            .updateAsync(updatedCacheResponse)
            .whenComplete(
                (updated, ex) -> {
                  if (ex != null) {
                    listener.onWriteFailure(request, ex);
                  } else if (updated) {
                    listener.onWriteSuccess(request);
                  } else {
                    // TODO listener.onWriteDiscard();
                  }
                });
        return CompletableFuture.completedFuture(withCacheResponse(updatedCacheResponse));
      }

      if (isCacheable(request, networkResponse.get())) {
        return cache
            .putAsync(request, networkResponse, cacheResponse)
            .thenApply(
                cacheUpdatingNetworkResponse ->
                    cacheUpdatingNetworkResponse.map(this::withNetworkResponse).orElse(this));
      }

      // An uncacheable response might invalidate itself and other related responses.
      cache
          .removeAllAsync(invalidatedUris(request, networkResponse.get()))
          .whenComplete(
              (result, ex) -> {
                if (ex != null) {
                  logger.log(Level.WARNING, "Exception when removing entries", ex);
                }
              });
      return CompletableFuture.completedFuture(this);
    }

    RawResponse serveResponse() {
      if (networkResponse == null) {
        // No network was used. We might have a suitable cache response. It's also possible that
        // we're recovering from a network failure as per stale-if-error, or asynchronously
        // revalidating as per stale-while-revalidate.
        if (cacheResponse != null
            && (cacheResponse.isServable()
                || cacheResponse.isServableWhileRevalidating()
                || cacheResponse.isServableOnError())) {
          var cacheResponse = this.cacheResponse.withCacheHeaders();
          return cacheResponse.with(
              builder ->
                  builder
                      .request(request)
                      .cacheStatus(CacheStatus.HIT)
                      .cacheResponse(cacheResponse.get())
                      .timeRequestSent(requestTime)
                      .timeResponseReceived(clock.instant()));
        }

        // If neither cache nor network was applicable, this is an unsatisfiable request. So serve a
        // 504 Gateway Timeout response as per rfc7234 Section 5.2.1.7.
        closeIfPresent(cacheResponse);
        return NetworkResponse.from(
            new ResponseBuilder<>()
                .uri(request.uri())
                .request(request)
                .cacheStatus(CacheStatus.UNSATISFIABLE)
                .statusCode(HTTP_GATEWAY_TIMEOUT)
                .version(Version.HTTP_1_1)
                .timeRequestSent(requestTime)
                .timeResponseReceived(clock.instant())
                .body(FlowSupport.<List<ByteBuffer>>emptyPublisher())
                .buildTrackedResponse());
      }

      if (cacheResponse != null && networkResponse.get().statusCode() == HTTP_NOT_MODIFIED) {
        // This is a successful revalidation.
        return cacheResponse.with(
            builder ->
                builder
                    .request(request)
                    .cacheStatus(CacheStatus.CONDITIONAL_HIT)
                    .cacheResponse(cacheResponse.get())
                    .networkResponse(networkResponse.get()));
      }

      closeIfPresent(cacheResponse);
      return networkResponse.with(
          builder ->
              builder
                  .request(request)
                  .cacheStatus(CacheStatus.MISS)
                  .cacheResponse(cacheResponse != null ? cacheResponse.get() : null)
                  .networkResponse(networkResponse.get()));
    }

    private CompletableFuture<Exchange> networkExchange() {
      return networkExchange(asyncAdapter::forward);
    }

    private CompletableFuture<Exchange> backgroundNetworkExchange() {
      return networkExchange(Chain::forwardAsync);
    }

    private CompletableFuture<Exchange> networkExchange(
        BiFunction<
                Chain<Publisher<List<ByteBuffer>>>,
                HttpRequest,
                CompletableFuture<HttpResponse<Publisher<List<ByteBuffer>>>>>
            forwarder) {
      var networkRequest =
          cacheResponse != null ? cacheResponse.conditionalize(this.request) : this.request;
      return forwarder
          .apply(chain, networkRequest)
          .thenApply(
              response ->
                  NetworkResponse.from(
                      ResponseBuilder.newBuilder(response)
                          .timeRequestSent(requestTime)
                          .timeResponseReceived(clock.instant())
                          .buildTrackedResponse()))
          .thenApply(this::withNetworkResponse);
    }

    /**
     * Handles a network exchange, falling back to the cache response if one that satisfies
     * stale-if-error is available.
     */
    private Exchange handleNetworkOrServerError(
        @Nullable Exchange networkExchange, @Nullable Throwable exception) {
      var networkResponse = networkExchange != null ? networkExchange.networkResponse : null;
      assert networkResponse != null || exception != null;
      if (isNetworkOrServerError(networkResponse, exception)
          && cacheResponse != null
          && cacheResponse.isServableOnError()) {
        if (networkResponse != null) {
          networkResponse.discard(executor);
        }
        return this;
      }

      // stale-if-error isn't satisfied. Forward exception or networkExchange as is.
      if (exception != null) {
        closeIfPresent(cacheResponse);
        throw Utils.toCompletionException(exception);
      }
      return networkExchange;
    }

    private Exchange withCacheResponse(@Nullable CacheResponse cacheResponse) {
      return new Exchange(
          request,
          chain,
          cacheResponse,
          networkResponse,
          requestTime,
          requestCacheControl,
          asyncAdapter);
    }

    private Exchange withNetworkResponse(@Nullable NetworkResponse networkResponse) {
      return new Exchange(
          request,
          chain,
          cacheResponse,
          networkResponse,
          requestTime,
          requestCacheControl,
          asyncAdapter);
    }
  }
}
