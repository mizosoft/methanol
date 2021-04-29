/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.Validate.TODO;
import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static com.github.mizosoft.methanol.internal.cache.DateUtils.formatHttpDate;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.Methanol.Interceptor.Chain;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.CacheResponse;
import com.github.mizosoft.methanol.internal.cache.CacheResponseMetadata;
import com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber;
import com.github.mizosoft.methanol.internal.cache.DateUtils;
import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.FreshnessComputation;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.RawResponse;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An HTTP cache that resides between a {@link Methanol} client and the underlying network {@link
 * HttpClient}. No API is exported to add or update HTTP request/response entries. Instead, the
 * cache inserts itself as an {@link Interceptor} when configured with a {@code Methanol.Builder}.
 * The cache however allows explicitly invalidating an entry matching a specified request or
 * deleting all available entries.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7234">RFC 7234: HTTP caching</a>
 */
public final class HttpCache implements AutoCloseable {
  private final Store store;
  private final Executor executor;
  private final boolean ownedExecutor;
  private final boolean userVisibleExecutor;
  private final Clock clock;

  public HttpCache(Builder builder) {
    this.store = castNonNull(builder.store);
    var userExecutor = builder.executor;
    if (userExecutor != null) {
      executor = userExecutor;
      ownedExecutor = false;
      userVisibleExecutor = true;
    } else if (store instanceof MemoryStore) { // Can use common ForkJoinPool as there's no IO
      executor = ForkJoinPool.commonPool();
      ownedExecutor = false;
      userVisibleExecutor = false;
    } else {
      executor = Executors.newCachedThreadPool();
      ownedExecutor = true;
      userVisibleExecutor = false;
    }
    clock = builder.clock;
  }

  /**
   * Returns the directory in which HTTP cache entries are persisted.
   *
   * @throws UnsupportedOperationException if the cache doesn't persist entries on disk
   */
  public Path directory() {
    return TODO();
  }

  /** Returns this cache's max size in bytes. */
  public long maxSize() {
    return store.maxSize();
  }

  /** Returns an {@code Optional} containing this cache's executor if one is explicitly set. */
  public Optional<Executor> executor() {
    return userVisibleExecutor ? Optional.of(executor) : Optional.empty();
  }

  /** Resets this cache's max size. Might evict any excessive entries as necessary. */
  public void resetMaxSize(long maxSize) {
    store.resetMaxSize(maxSize);
  }

  /** Returns the size this cache occupies in bytes. */
  public long size() {
    return store.size();
  }

  /** Removes all entries from this cache. */
  public void clear() {
    store.clear();
  }

  /** Removes the entry associated with the given uri if one is present. */
  public boolean remove(URI uri) {
    return store.remove(key(uri));
  }

  /** Removes the entry associated with the given request if one is present. */
  public boolean remove(HttpRequest request) {
    return store.remove(key(request));
  }

  /** Atomically clears and closes this cache. */
  public void destroy() {
    store.destroy();
  }

  /** Closes this cache. */
  // TODO specify what is closed exactly
  @Override
  public void close() {
    store.close();
    if (ownedExecutor && executor instanceof ExecutorService) {
      ((ExecutorService) executor).shutdown();
    }
  }

  Interceptor interceptor(@Nullable Executor clientExecutor) {
    return new CacheInterceptor(this, clientExecutor);
  }

  private CompletableFuture<@Nullable CacheResponse> getAsync(
      HttpRequest request,
      BiFunction<Store, String, CompletableFuture<@Nullable Viewer>> viewAdapter) {
    return "GET".equalsIgnoreCase(request.method()) // The implementation only caches GETs
        ? viewAdapter
            .apply(store, key(request))
            .thenApply(viewer -> viewer != null ? createCacheResponse(request, viewer) : null)
        : CompletableFuture.completedFuture(null);
  }

  private @Nullable CacheResponse createCacheResponse(HttpRequest request, Viewer viewer) {
    CacheResponseMetadata metadata;
    try {
      metadata = CacheResponseMetadata.decode(viewer.metadata());
    } catch (IOException e) {
      viewer.close(); // TODO close quietly
      // TODO might want to ignore
      throw new CompletionException(e); // Unrolled by CompletableFuture
    }
    if (!metadata.matches(request)) {
      viewer.close(); // TODO close quietly
      return null;
    }
    return new CacheResponse(metadata, viewer, executor);
  }

  private static String key(HttpRequest request) {
    // Since the cache is restricted to GETs, only the URI is needed as a primary key
    return key(request.uri());
  }

  private static String key(URI uri) {
    return uri.toString();
  }

  /**
   * A dirty hack that masks synchronous operations as {@code CompletableFuture} calls that are
   * always completed when returned. This is important in order to share major logic between {@code
   * intercept} and {@code interceptAsync}, which facilitates implementation & maintainance.
   */
  private static final class AsyncAdapter {
    private final boolean async;

    AsyncAdapter(boolean async) {
      this.async = async;
    }

    <T> CompletableFuture<HttpResponse<T>> send(Chain<T> chain, HttpRequest request) {
      return async ? chain.forwardAsync(request) : adapt(() -> chain.forward(request));
    }

    CompletableFuture<@Nullable Viewer> view(Store store, String key) {
      return async ? store.viewAsync(key) : adapt(() -> store.view(key));
    }

    private static <T> CompletableFuture<T> adapt(IOSupplier<T> supplier) {
      return CompletableFuture.supplyAsync(
          supplier.toUncheckedForAsyncCompletion(), FlowSupport.SYNC_EXECUTOR);
    }

    @FunctionalInterface
    interface IOSupplier<T> {
      T get() throws IOException, InterruptedException;

      default Supplier<T> toUncheckedForAsyncCompletion() {
        return () -> {
          try {
            return get();
          } catch (IOException | InterruptedException e) {
            throw new CompletionException(e); // Unrolled by CompletableFuture
          }
        };
      }
    }
  }

  static Set<String> implicitlyAddedFieldsForTesting() {
    return CacheInterceptor.IMPLICITLY_ADDED_FIELDS;
  }

  /**
   * This interceptor does most HTTP caching workload: restore a response from the underlying store,
   * determine freshness, forward to network or revalidate if required and finally update or
   * invalidate cache if necessary then serve the response.
   */
  private static class CacheInterceptor implements Methanol.Interceptor {
    /**
     * Fields that can be added implicitly by HttpClient's own filters. This can happen if an
     * Authenticator or a CookieHandler is installed. If the response varies with these (unlikely
     * but possible) they're not accepted as we can't access their values.
     */
    static final Set<String> IMPLICITLY_ADDED_FIELDS =
        Set.of("Cookie", "Cookie2", "Authorization", "Proxy-Authorization");

    private static final Duration ONE_DAY = Duration.ofDays(1);

    private final HttpCache cache;
    private final Executor cacheExecutor;

    /**
     * The {@code Executor} used for handling responses. Will be either the client executor or the
     * cache executor if the former is not present.
     */
    private final Executor handlerExecutor;

    private final Clock clock;

    CacheInterceptor(HttpCache cache, @Nullable Executor clientExecutor) {
      this.cache = cache;
      cacheExecutor = cache.executor;
      handlerExecutor = requireNonNullElse(clientExecutor, cacheExecutor);
      clock = cache.clock;
    }

    // TODO figure out what to do with push promises

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      if (chain.pushPromiseHandler().isPresent()) {
        return chain.forward(request);
      }

      try {
        return doIntercept(request, chain.with(BodyHandlers.ofPublisher(), null), false)
            .get()
            .handle(chain.bodyHandler());
      } catch (ExecutionException e) {
        throw Utils.rethrowAsyncIOFailure(e.getCause());
      }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      if (chain.pushPromiseHandler().isPresent()) {
        return chain.forwardAsync(request);
      }

      return doIntercept(request, chain.with(BodyHandlers.ofPublisher(), null), true)
          .thenCompose(rawResponse -> rawResponse.handleAsync(chain.bodyHandler(), handlerExecutor))
          .thenApply(Function.identity()); // Downcast from TrackedResponse<T> to HttpResponse<T>
    }

    private CompletableFuture<RawResponse> doIntercept(
        HttpRequest request, Chain<Publisher<List<ByteBuffer>>> chain, boolean async) {
      var asyncAdapter = new AsyncAdapter(async);
      var context = new ExchangeContext(clock, request, chain, asyncAdapter);

      // Forward client's request to origin if it has preconditions
      if (hasPreconditions(request.headers())) {
        return context.forwardToNetwork().thenApply(this::updateCacheAndServe);
      }

      return cache
          .getAsync(request, asyncAdapter::view)
          .thenApply(cacheResponse -> context.withResponse(cacheResponse, null))
          .thenCompose(this::exchangeAsync)
          .thenApply(this::updateCacheAndServe);
    }

    private CompletableFuture<ExchangeContext> exchangeAsync(ExchangeContext context) {
      var requestCacheControl = cacheControl(context.request.headers());
      var cacheResponse = context.cacheResponse;
      if (cacheResponse == null) {
        // Don't forward the request if the client prohibits network use
        return requestCacheControl.onlyIfCached()
            ? CompletableFuture.completedFuture(context)
            : context.forwardToNetwork();
      }

      var responseCacheControl = cacheControl(cacheResponse.get().headers());
      var computation =
          new FreshnessComputation(
              requestCacheControl.maxAge().or(responseCacheControl::maxAge),
              cacheResponse.get().timeRequestSent(),
              cacheResponse.get().timeResponseReceived(),
              cacheResponse.get().headers());
      var now = context.clock.instant();
      var age = computation.computeAge(now);
      var lifetime =
          computation.computeFreshnessLifetime().orElseGet(computation::computeHeuristicLifetime);
      var freshness = lifetime.minus(age);
      if (canServeWithoutRevalidation(requestCacheControl, responseCacheControl, freshness)) {
        // Network entirely avoided! Hooray!
        var servableCacheResponse =
            cacheResponse.with(
                builder -> {
                  builder.setHeader("Age", Long.toString(age.toSeconds()));
                  if (!computation.hasExplicitExpiration() && age.compareTo(ONE_DAY) > 0) {
                    builder.header("Warning", "113 - \"Heuristic Expiration\"");
                  }
                  if (freshness.isNegative()) {
                    builder.header("Warning", "110 - \"Response is Stale\"");
                  }
                });
        return CompletableFuture.completedFuture(context.withResponse(servableCacheResponse, null));
      }

      // Must revalidate the response with the server

      // Don't revalidate if the client prohibits network use
      if (requestCacheControl.onlyIfCached()) {
        cacheResponse.close();
        return CompletableFuture.completedFuture(context.withResponse(null, null));
      }

      // rfc7232 2.4 encourages sending both If-None-Match & If-Modified-Since validators
      var conditionalRequest = MutableRequest.copyOf(context.request);
      cacheResponse
          .get()
          .headers()
          .firstValue("ETag")
          .ifPresent(etag -> conditionalRequest.header("If-None-Match", etag));
      conditionalRequest.header(
          "If-Modified-Since", formatHttpDate(computation.computeEffectiveLastModified()));
      var networkContextFuture =
          context.withRequest(conditionalRequest.toImmutableRequest()).forwardToNetwork();
      // Let's not forget to release the cacheResponse if network fails
      networkContextFuture.whenComplete(
          (__, error) -> {
            if (error != null) {
              cacheResponse.close();
            }
          });
      return networkContextFuture;
    }

    private boolean canServeWithoutRevalidation(
        CacheControl requestCacheControl, CacheControl responseCacheControl, Duration freshness) {
      if (requestCacheControl.noCache() || responseCacheControl.noCache()) {
        return false;
      }

      if (!freshness.isNegative()) {
        // The response is fresh, but might not be fresh enough for the client
        return requestCacheControl.minFresh().isEmpty()
            || freshness.compareTo(requestCacheControl.minFresh().get()) >= 0;
      }

      // The server might impose network use for stale responses
      if (responseCacheControl.mustRevalidate()) {
        return false;
      }

      // The response is stale, but the client might be willing to accept it
      var staleness = freshness.negated();
      return (requestCacheControl.anyMaxStale()
          || (requestCacheControl.maxStale().isPresent()
              && staleness.compareTo(requestCacheControl.maxStale().get()) <= 0));
    }

    // TODO are resources held by CacheResponse handled correctly?
    // TODO figure out what to do with HEADs
    // TODO properly handle logging
    // TODO merging headers doesn't quite work correctly,
    //      for example Content-Length: 0 replaces original
    // TODO consider implementing our own redirecting interceptor
    //      to be above the caching layer so they get cached

    private RawResponse updateCacheAndServe(ExchangeContext context) {
      var request = context.request;
      var cacheResponse = context.cacheResponse;
      var networkResponse = context.networkResponse;

      // If neither the cache nor the network was applicable,
      // serve a 504 Gateway Timeout response as per rfc7234 5.2.1.7.
      if (cacheResponse == null && networkResponse == null) {
        return RawResponse.from(
            new ResponseBuilder<>()
                .uri(request.uri())
                .request(request)
                .cacheStatus(CacheStatus.LOCALLY_GENERATED)
                .statusCode(HTTP_GATEWAY_TIMEOUT)
                .version(Version.HTTP_1_1)
                .timeRequestSent(context.requestTime)
                .timeResponseReceived(context.clock.instant())
                .body(FlowSupport.<List<ByteBuffer>>emptyPublisher())
                .build());
      }

      // Serve the cacheResponse directly if no network was used
      if (networkResponse == null) {
        return cacheResponse.with(
            builder ->
                builder
                    .request(request)
                    .cacheStatus(CacheStatus.HIT)
                    .cacheResponse(cacheResponse.get())
                    .timeRequestSent(context.requestTime)
                    .timeResponseReceived(context.clock.instant()));
      }

      // If the cacheResponse was successfully revalidated then
      // serve it after freshening as specified in rfc7234 4.3.4.
      if (cacheResponse != null && networkResponse.get().statusCode() == HTTP_NOT_MODIFIED) {
        // Make sure networkResponse is consumed properly
        networkResponse.handleAsync(BodyHandlers.discarding(), handlerExecutor);

        var mergedHeaders = new HeadersBuilder();
        mergedHeaders.addAll(cacheResponse.get().headers());
        // Remove Warning headers with a 1xx warn code
        mergedHeaders.removeIf(
            (name, value) -> "Warning".equalsIgnoreCase(name) && value.startsWith("1"));
        mergedHeaders.setAll(networkResponse.get().headers());
        var servedCacheResponse =
            cacheResponse.with(
                builder ->
                    builder
                        .request(request)
                        .cacheStatus(CacheStatus.CONDITIONAL_HIT)
                        .cacheResponse(cacheResponse.get())
                        .networkResponse(networkResponse.get())
                        .timeRequestSent(networkResponse.get().timeRequestSent())
                        .timeResponseReceived(networkResponse.get().timeResponseReceived())
                        .setHeaders(mergedHeaders.build())); // Replace original headers with merged
        // Update merged metadata in background
        cacheExecutor.execute(() -> cache.updateMetadata(servedCacheResponse));
        return servedCacheResponse;
      }

      var servedNetworkResponse =
          networkResponse.with(
              builder ->
                  builder
                      .cacheStatus(CacheStatus.MISS)
                      .cacheResponse(cacheResponse != null ? cacheResponse.get() : null)
                      .networkResponse(networkResponse.get()));
      if (isCacheable(request, servedNetworkResponse.get())) {
        var cacheUpdatingResponse = cache.updating(servedNetworkResponse, cacheResponse);
        if (cacheUpdatingResponse != null) {
          servedNetworkResponse = cacheUpdatingResponse;
        }
      } else if (invalidatesCache(request, servedNetworkResponse.get())) {
        cache.remove(request.uri());
      }

      if (cacheResponse != null) {
        cacheResponse.close();
      }
      return servedNetworkResponse;
    }

    private static CacheControl cacheControl(HttpHeaders headers) {
      return headers
          .firstValue("Cache-Control")
          .map(CacheControl::parse)
          .orElse(CacheControl.empty());
    }

    private static boolean hasPreconditions(HttpHeaders headers) {
      for (var name : headers.map().keySet()) {
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
        }
      }
      return false;
    }

    /** Returns whether the network response can be cached as specified by rfc7234 section 3. */
    // TODO: figure out what "is understood by the cache" means
    private static boolean isCacheable(HttpRequest initiatingRequest, TrackedResponse<?> response) {
      // Refuse anything but GETs
      if (!"GET".equalsIgnoreCase(initiatingRequest.method())) {
        return false;
      }

      // Refuse partial content
      if (response.statusCode() == 206) {
        return false;
      }

      // Refuse if response has a different URI or method (e.g. redirection)
      if (!initiatingRequest.uri().equals(response.uri())
          || !initiatingRequest.method().equalsIgnoreCase(response.request().method())) {
        return false;
      }

      // Refuse if caching is prohibited by server or client
      var responseCacheControl = cacheControl(response.headers());
      if (responseCacheControl.noStore() || cacheControl(initiatingRequest.headers()).noStore()) {
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
          || responseCacheControl.isPrivate() // Private implies cacheable by default
          || isCacheableByDefault(response.statusCode());
    }

    /** rfc7231 6.1. */
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

        case 206: // Partial content not supported
        default:
          return false;
      }
    }

    /** Checks if a corresponding stored response should be invalidated as per rfc7234 4.4. */
    private static boolean invalidatesCache(
        HttpRequest initiatingRequest, TrackedResponse<?> response) {
      return isUnsafe(initiatingRequest.method())
          && response.statusCode() >= 200
          && response.statusCode() < 400;
    }

    /** rfc7231 4.2.1. */
    private static boolean isUnsafe(String method) {
      switch (method.toUpperCase(Locale.ENGLISH)) {
        case "GET":
        case "HEAD":
        case "OPTIONS":
        case "TRACE":
          return false;
        default:
          return true;
      }
    }

    private static final class ExchangeContext {
      final Clock clock;
      final Instant requestTime;
      final HttpRequest request;
      final Chain<Publisher<List<ByteBuffer>>> chain;
      final AsyncAdapter asyncAdapter;
      final @Nullable CacheResponse cacheResponse;
      final @Nullable RawResponse networkResponse;

      ExchangeContext(
          Clock clock,
          HttpRequest request,
          Chain<Publisher<List<ByteBuffer>>> chain,
          AsyncAdapter asyncAdapter) {
        this(clock, clock.instant(), request, chain, asyncAdapter, null, null);
      }

      private ExchangeContext(
          Clock clock,
          Instant requestTime,
          HttpRequest request,
          Chain<Publisher<List<ByteBuffer>>> chain,
          AsyncAdapter asyncAdapter,
          @Nullable CacheResponse cacheResponse,
          @Nullable RawResponse networkResponse) {
        this.clock = clock;
        this.requestTime = requestTime;
        this.request = request;
        this.chain = chain;
        this.asyncAdapter = asyncAdapter;
        this.cacheResponse = cacheResponse;
        this.networkResponse = networkResponse;
      }

      ExchangeContext withResponse(
          @Nullable CacheResponse cacheResponse, @Nullable RawResponse networkResponse) {
        return new ExchangeContext(
            clock, requestTime, request, chain, asyncAdapter, cacheResponse, networkResponse);
      }

      ExchangeContext withRequest(HttpRequest request) {
        return new ExchangeContext(
            clock, requestTime, request, chain, asyncAdapter, cacheResponse, networkResponse);
      }

      CompletableFuture<ExchangeContext> forwardToNetwork() {
        return asyncAdapter
            .send(chain, request)
            .thenApply(
                response ->
                    RawResponse.from(
                        ResponseBuilder.newBuilder(response)
                            .timeRequestSent(requestTime)
                            .timeResponseReceived(clock.instant())
                            .build()))
            .thenApply(networkResponse -> withResponse(this.cacheResponse, networkResponse));
      }
    }
  }

  private void updateMetadata(CacheResponse servedCacheResponse) {
    var response = servedCacheResponse.get();
    try (var editor = servedCacheResponse.edit()) {
      if (editor != null) {
        editor.metadata(CacheResponseMetadata.from(response).encode());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private @Nullable RawResponse updating(
      RawResponse networkResponse, @Nullable CacheResponse cacheResponse) {
    var editor =
        cacheResponse != null
            ? cacheResponse.edit()
            : store.edit(key(networkResponse.get().request()));
    var metadata = tryEncodeMetadata(networkResponse);
    if (editor == null || metadata == null) {
      return null;
    }

    var cacheWritingResponse =
        networkResponse
            .handleAsync(
                __ -> new CacheWritingBodySubscriber(editor, metadata), FlowSupport.SYNC_EXECUTOR)
            .join(); // CacheWritingBodySubscriber completes immediately and never throws
    return RawResponse.from(cacheWritingResponse);
  }

  private static @Nullable ByteBuffer tryEncodeMetadata(RawResponse response) {
    try {
      return CacheResponseMetadata.from(response.get()).encode();
    } catch (IOException ioe) {
      // TODO log
      return null;
    }
  }

  /** Returns a new {@code HttpCache.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** A builder of {@code HttpCaches}. */
  public static final class Builder {
    // Guard against ridiculously small values
    private static final int MAX_SIZE_THRESHOLD = 2 * 1024;

    @MonotonicNonNull Store store;
    @MonotonicNonNull Executor executor;

    // Use a clock that ticks in millis under UTC, which suffices for freshness
    // calculations and makes saving Instants more compact (no nanos-of-second part
    // is written).
    Clock clock = Clock.tickMillis(ZoneOffset.UTC);

    Builder() {}

    /** Specifies that HTTP responses are to be cached on memory with the given size bound. */
    public Builder cacheOnMemory(long maxSize) {
      checkMaxSize(maxSize);
      store = new MemoryStore(maxSize);
      return this;
    }

    /**
     * Specifies that HTTP responses are to be persisted on disk, under the given directory, with
     * the given size bound.
     */
    public Builder cacheOnDisk(Path directory, long maxSize) {
      checkMaxSize(maxSize);
      store = new DiskStore(directory, maxSize);
      return this;
    }

    /** Sets the executor to be used by the cache. */
    public Builder executor(Executor executor) {
      this.executor = requireNonNull(executor);
      return this;
    }

    Builder clockForTesting(Clock clock) {
      this.clock = requireNonNull(clock);
      return this;
    }

    /** Builds a new {@code HttpCache}. */
    public HttpCache build() {
      requireState(store != null, "caching method must be specified");
      return new HttpCache(this);
    }

    private void checkMaxSize(long maxSize) {
      requireArgument(
          maxSize >= MAX_SIZE_THRESHOLD,
          "a maxSize of %d doesn't seem reasonable, please set a value >= %d",
          maxSize,
          MAX_SIZE_THRESHOLD);
    }
  }
}
