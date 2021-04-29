/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_PARTIAL;

import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.CacheControl;
import com.github.mizosoft.methanol.HttpStatus;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.extensions.Handlers;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An {@link Interceptor} that serves incoming requests from cache, network, both, or none (in case
 * of unsatisfiable requests). The interceptor also updates, populates and invalidates cache entries
 * as appropriate.
 */
public final class CacheInterceptor implements Interceptor {
  private static final Logger LOGGER = Logger.getLogger(CacheInterceptor.class.getName());

  /**
   * Fields that can be added implicitly by HttpClient's own filters. This can happen if an
   * Authenticator or a CookieHandler is installed. If a response varies with these (unlikely but
   * possible), it's rendered uncacheable as we can't access the corresponding values from requests.
   */
  private static final Set<String> IMPLICITLY_ADDED_FIELDS;

  static {
    var fields = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    fields.addAll(Set.of("Cookie", "Cookie2", "Authorization", "Proxy-Authorization"));
    IMPLICITLY_ADDED_FIELDS = fields;
  }

  private final InternalCache cache;
  private final Executor cacheExecutor;
  private final Executor handlerExecutor;
  private final Clock clock;

  public CacheInterceptor(
      InternalCache cache, Executor cacheExecutor, Executor handlerExecutor, Clock clock) {
    this.cache = cache;
    this.cacheExecutor = cacheExecutor;
    this.handlerExecutor = handlerExecutor;
    this.clock = clock;
  }

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    return Utils.block(exchange(request, chain, false)).handle(chain.bodyHandler());
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    return exchange(request, chain, true)
        .thenCompose(rawResponse -> rawResponse.handleAsync(chain.bodyHandler(), handlerExecutor))
        .thenApply(Function.identity()); // TrackedResponse<T> -> HttpResponse<T>
  }

  public CompletableFuture<RawResponse> exchange(
      HttpRequest request, Chain<?> chain, boolean async) {
    cache.onRequest(request.uri());

    var requestTime = clock.instant();
    var asyncAdapter = new AsyncAdapter(async);
    var publisherChain = Handlers.toPublisherChain(chain, handlerExecutor);

    return getCacheResponse(request, chain, asyncAdapter)
        .thenApply(
            cacheResponse ->
                new Exchange(request, publisherChain, cacheResponse, requestTime, asyncAdapter))
        .thenCompose(Exchange::evaluate)
        .thenApply(Exchange::serveResponse);
  }

  private CompletableFuture<@Nullable CacheResponse> getCacheResponse(
      HttpRequest request, Chain<?> chain, AsyncAdapter asyncAdapter) {
    // Bypass cache if:
    //   - Request method isn't GET; this implementation only caches GETs.
    //   - The request has preconditions.
    //   - There's a push promise handler; we don't know what might be pushed by the server.
    if (!"GET".equalsIgnoreCase(request.method())
        || hasPreconditions(request.headers())
        || chain.pushPromiseHandler().isPresent()) {
      return CompletableFuture.completedFuture(null);
    }

    return asyncAdapter.get(cache, request);
  }

  private void handleAsyncRevalidation(
      @Nullable Exchange networkExchange, @Nullable Throwable error) {
    var networkResponse = networkExchange != null ? networkExchange.networkResponse : null;

    assert networkResponse != null || error != null;

    if (networkResponse != null) {
      // Release the network response properly. If this is a cache miss and we're updating a
      // cache entry, discarding will drain the response body so it's fully written and the
      // entry is committed.
      networkResponse.discard(handlerExecutor);
    } else {
      LOGGER.log(Level.WARNING, "asynchronous revalidation failure", error);
    }
  }

  private static boolean hasPreconditions(HttpHeaders headers) {
    return headers.map().keySet().stream().anyMatch(CacheInterceptor::isPreconditionField);
  }

  private static boolean isPreconditionField(String name) {
    switch (name) {
      case "If-Match":
      case "If-Unmodified-Since":
        // These are meant for the origin and must be forwarded to it
        return true;

      case "If-None-Match":
      case "If-Modified-Since":
      case "If-Range":
        // rfc7234 allows us to evaluate these, but the added complexity
        // is discouraging, particularly considering that preconditions
        // are usually intended to be seen by the origin.
        return true;

      default:
        return false;
    }
  }

  private static boolean isNetworkOrServerError(
      @Nullable NetworkResponse networkResponse, @Nullable Throwable error) {
    assert networkResponse != null || error != null;

    if (networkResponse != null) {
      return HttpStatus.isServerError(networkResponse.get());
    }

    // It seems silly to regard an IllegalArgumentException or something as a
    // candidate for stale-if-error treatment. ConnectException and subclasses are
    // regarded as so since they're usually situational errors for network usage,
    // similar to 5xx response codes, which are themselves perfect candidates for
    // stale-if-error.
    var cause = Utils.getDeepCompletionCause(error); // Might be a CompletionException
    if (cause instanceof UncheckedIOException) {
      cause = cause.getCause();
    }
    return cause instanceof ConnectException;
  }

  /** Returns whether the given network response can be cached. Based on rfc7234 section 3. */
  private static boolean isCacheable(HttpRequest request, TrackedResponse<?> response) {
    // Refuse anything but GETs
    if (!"GET".equalsIgnoreCase(request.method())) {
      return false;
    }

    // Refuse partial content
    if (response.statusCode() == HTTP_PARTIAL) {
      return false;
    }

    // Refuse if the response has a different URI or method (e.g. redirection)
    if (!request.uri().equals(response.uri())
        || !request.method().equalsIgnoreCase(response.request().method())) {
      return false;
    }

    // Refuse if caching is prohibited
    var responseCacheControl = CacheControl.parse(response.headers());
    if (responseCacheControl.noStore() || CacheControl.parse(request.headers()).noStore()) {
      return false;
    }

    // Refuse if the response is unmatchable or varies with fields that we can't see
    var varyFields = CacheResponseMetadata.varyFields(response.headers());
    if (varyFields.contains("*") || !Collections.disjoint(varyFields, IMPLICITLY_ADDED_FIELDS)) {
      return false;
    }

    return response.headers().firstValue("Expires").filter(DateUtils::isHttpDate).isPresent()
        || responseCacheControl.maxAge().isPresent()
        || responseCacheControl.isPublic()
        || responseCacheControl.isPrivate()
        || isCacheableByDefault(response.statusCode());
  }

  /**
   * Returns whether a response with the given code is cacheable by default (can be cached even if
   * there's no explicit caching headers like Cache-Control & Expires). Based on rfc7231 6.1.
   */
  private static boolean isCacheableByDefault(int statusCode) {
    switch (statusCode) {
      case 200:
      case 203:
      case 204:
      case 300:
      case 301:
      case 404:
      case 405:
      case 410:
      case 414:
      case 501:
        return true;

      case HTTP_PARTIAL:
        // Partial responses are cacheable by default, but this implementation doesn't support it

      default:
        return false;
    }
  }

  /** Returns the URIs invalidated by the given exchange as specified by rfc7234 section 4.4. */
  private static List<URI> invalidatedUris(HttpRequest request, TrackedResponse<?> response) {
    if (isUnsafe(request.method())
        && (HttpStatus.isSuccessful(response) || HttpStatus.isRedirection(response))) {
      var invalidatedUris = new ArrayList<URI>();
      invalidatedUris.add(request.uri());

      // Add the URIs referenced by Location & Content-Location
      invalidatedLocationUri(request.uri(), response.headers(), "Location")
          .ifPresent(invalidatedUris::add);
      invalidatedLocationUri(request.uri(), response.headers(), "Content-Location")
          .ifPresent(invalidatedUris::add);
      return Collections.unmodifiableList(invalidatedUris);
    } else {
      return List.of(); // Nothing is invalidated
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
   * Based on rfc7231 4.2.1.
   */
  private static boolean isUnsafe(String method) {
    switch (method.toUpperCase(Locale.ROOT)) {
      case "GET":
      case "HEAD":
      case "OPTIONS":
      case "TRACE":
        return false;

      default:
        return true;
    }
  }

  public static Set<String> implicitlyAddedFieldsForTesting() {
    return IMPLICITLY_ADDED_FIELDS;
  }

  /**
   * A dirty hack that masks synchronous operations as {@code CompletableFuture} calls that are
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

    CompletableFuture<@Nullable CacheResponse> get(InternalCache cache, HttpRequest request) {
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

      // Return immediately after firing async revalidation if stale-while-revalidate applies
      if (cacheResponse != null && cacheResponse.isServableWhileRevalidating()) {
        // TODO implement a bounding policy on asynchronous revalidation
        // TODO find a mechanism to notify caller for revalidation's completion
        networkExchange()
            .thenApply(Exchange::updateCache)
            .whenComplete(CacheInterceptor.this::handleAsyncRevalidation);

        return CompletableFuture.completedFuture(this);
      }

      // Don't use network if the request prohibits it
      if (requestCacheControl.onlyIfCached()) {
        return CompletableFuture.completedFuture(this);
      }

      cache.onNetworkUse(request.uri());
      return networkExchange()
          .handle(this::handleNetworkOrServerError)
          .thenApply(Exchange::updateCache);
    }

    Exchange updateCache() {
      if (networkResponse == null) {
        return this; // There's nothing to update from
      }

      // On successful revalidation, update the stored response as specified by rfc7234 4.3.3
      if (cacheResponse != null && networkResponse.get().statusCode() == HTTP_NOT_MODIFIED) {
        // Release the network response properly
        networkResponse.discard(handlerExecutor);

        var updatedCacheResponse = updateCacheResponse(cacheResponse, networkResponse);
        cacheExecutor.execute(() -> cache.update(updatedCacheResponse));
        return withCacheResponse(updatedCacheResponse);
      }

      if (isCacheable(request, networkResponse.get())) {
        var cacheUpdatingNetworkResponse = cache.put(cacheResponse, networkResponse);
        if (cacheUpdatingNetworkResponse != null) {
          // The entry is populated as cacheUpdatingNetworkResponse is consumed
          return withNetworkResponse(cacheUpdatingNetworkResponse);
        }
      } else {
        invalidatedUris(request, networkResponse.get()).forEach(cache::remove);
      }
      return this;
    }

    RawResponse serveResponse() {
      if (networkResponse == null) {
        // No network was used, we might have a suitable cache response. It's also possible
        // that we're recovering from a network failure as per stale-if-error or asynchronously
        // revalidating as per stale-while-revalidate.
        if (cacheResponse != null
            && (cacheResponse.isServable()
                || cacheResponse.isServableWhileRevalidating()
                || cacheResponse.isServableOnError())) {
          cache.onStatus(request.uri(), CacheStatus.HIT);
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

        // Release the unserviceable cache response
        if (cacheResponse != null) {
          cacheResponse.close();
        }

        // If neither cache nor network was applicable, this is an unsatisfiable request.
        // So serve a 504 Gateway Timeout response as per rfc7234 5.2.1.7.
        cache.onStatus(request.uri(), CacheStatus.UNSATISFIABLE_REQUEST);
        return NetworkResponse.from(
            new ResponseBuilder<>()
                .uri(request.uri())
                .request(request)
                .cacheStatus(CacheStatus.UNSATISFIABLE_REQUEST)
                .statusCode(HTTP_GATEWAY_TIMEOUT)
                .version(Version.HTTP_1_1)
                .timeRequestSent(requestTime)
                .timeResponseReceived(clock.instant())
                .body(FlowSupport.<List<ByteBuffer>>emptyPublisher())
                .buildTracked());
      }

      // Serve the cache response on successful revalidated
      if (cacheResponse != null && networkResponse.get().statusCode() == HTTP_NOT_MODIFIED) {
        return cacheResponse.with(
            builder ->
                builder
                    .request(request)
                    .cacheStatus(CacheStatus.CONDITIONAL_HIT)
                    .cacheResponse(cacheResponse.get())
                    .networkResponse(networkResponse.get()));
      }

      // Release the unserviceable cache response
      if (cacheResponse != null) {
        cacheResponse.close();
      }

      cache.onStatus(request.uri(), CacheStatus.MISS);
      return networkResponse.with(
          builder ->
              builder
                  .request(request)
                  .cacheStatus(CacheStatus.MISS)
                  .cacheResponse(cacheResponse != null ? cacheResponse.get() : null)
                  .networkResponse(networkResponse.get()));
    }

    private CompletableFuture<Exchange> networkExchange() {
      var networkRequest =
          cacheResponse != null ? cacheResponse.toValidationRequest(request) : request;
      return asyncAdapter
          .forward(chain, networkRequest)
          .thenApply(
              response ->
                  NetworkResponse.from(
                      ResponseBuilder.newBuilder(response)
                          .timeRequestSent(requestTime)
                          .timeResponseReceived(clock.instant())
                          .buildTracked()))
          .thenApply(this::withNetworkResponse);
    }

    /**
     * Handles a network exchange, falling back to the cached response if one is available and
     * satisfies stale-if-error.
     */
    private Exchange handleNetworkOrServerError(
        @Nullable Exchange networkExchange, @Nullable Throwable error) {
      var networkResponse = networkExchange != null ? networkExchange.networkResponse : null;

      assert networkResponse != null || error != null;

      if (isNetworkOrServerError(networkResponse, error)
          && cacheResponse != null
          && cacheResponse.isServableOnError()) {
        if (networkResponse != null) {
          networkResponse.discard(handlerExecutor);
        }
        return this;
      }

      // stale-if-error isn't satisfied. Forward error or networkExchange as is.
      if (error != null) {
        if (cacheResponse != null) {
          cacheResponse.close();
        }
        throw new CompletionException(error);
      }
      return networkExchange;
    }

    /** Updates the cache response after successful revalidation as specified in rfc7234 4.3.4. */
    private CacheResponse updateCacheResponse(
        CacheResponse cacheResponse, NetworkResponse networkResponse) {
      var mergedHeaders =
          mergeHeaders(cacheResponse.get().headers(), networkResponse.get().headers());
      return cacheResponse.with(
          builder ->
              builder
                  .setHeaders(mergedHeaders)
                  .timeRequestSent(networkResponse.get().timeRequestSent())
                  .timeResponseReceived(networkResponse.get().timeResponseReceived())
                  .apply(__ -> networkResponse.get().sslSession().ifPresent(builder::sslSession)));
    }

    private HttpHeaders mergeHeaders(HttpHeaders storedHeaders, HttpHeaders networkHeaders) {
      var builder = new HeadersBuilder();
      builder.addAll(storedHeaders);

      // Remove Warning headers with a 1xx warn code in the stored response
      builder.removeIf((name, value) -> "Warning".equalsIgnoreCase(name) && value.startsWith("1"));

      // Use the 304 response fields to replace those with the same names in the
      // stored response. The Content-Length of the stored response however is
      // restored to avoid replacing it with the Content-Length: 0 some servers
      // incorrectly send with their 304 responses.
      builder.setAll(networkHeaders);
      storedHeaders
          .firstValue("Content-Length")
          .ifPresent(value -> builder.set("Content-Length", value));

      return builder.build();
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
