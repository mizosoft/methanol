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
import static java.util.Objects.requireNonNullElseGet;

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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.LongAdder;
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
  private final StatsRecorder statsRecorder;
  private final Clock clock;

  private HttpCache(
      StoreFactory storeFactory,
      @Nullable Executor userExecutor,
      @Nullable Path cacheDirectory,
      long maxSize,
      @Nullable StatsRecorder statsRecorder,
      Clock clock) {
    if (userExecutor != null) {
      executor = userExecutor;
      ownedExecutor = false;
      userVisibleExecutor = true;
    } else if (storeFactory
        == StoreFactory.MEMORY) { // Can use common ForkJoinPool as there's no IO
      executor = ForkJoinPool.commonPool();
      ownedExecutor = false;
      userVisibleExecutor = false;
    } else {
      executor = Executors.newCachedThreadPool();
      ownedExecutor = true;
      userVisibleExecutor = false;
    }
    store = storeFactory.create(cacheDirectory, maxSize, executor);
    this.statsRecorder =
        requireNonNullElseGet(statsRecorder, StatsRecorder::createConcurrentRecorder);
    this.clock = clock;
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

  /** Returns a snapshot of the statistics accumulated so far. */
  public Stats stats() {
    return statsRecorder.snapshot();
  }

  /** Returns a snapshot of the statistics accumulated so far for the given {@code URI}. */
  public Stats stats(URI uri) {
    return statsRecorder.snapshot(uri);
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

  /** Called by {@code Methanol} when building interceptor chain. */
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

  private @Nullable RawResponse update(
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

  private void onRequest(HttpRequest request) {
    statsRecorder.recordRequest(request.uri());
  }

  private void onNetworkUse(HttpRequest request) {
    statsRecorder.recordNetworkUse(request.uri());
  }

  private void onStatus(HttpRequest request, CacheStatus status) {
    switch (status) {
      case MISS:
      case LOCALLY_GENERATED:
        statsRecorder.recordMiss(request.uri());
        break;

      case HIT:
      case CONDITIONAL_HIT:
        statsRecorder.recordHit(request.uri());
        break;

      default:
        throw new AssertionError("unexpected status: " + status);
    }
  }

  private static String key(HttpRequest request) {
    // Since the cache is restricted to GETs, only the URI is needed as a primary key
    return key(request.uri());
  }

  private static String key(URI uri) {
    return uri.toString();
  }

  private static @Nullable ByteBuffer tryEncodeMetadata(RawResponse response) {
    try {
      return CacheResponseMetadata.from(response.get()).encode();
    } catch (IOException ioe) {
      // TODO log
      return null;
    }
  }

  static Set<String> implicitlyAddedFieldsForTesting() {
    return CacheInterceptor.IMPLICITLY_ADDED_FIELDS;
  }

  /** Returns a new {@code HttpCache.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
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

    private static <T> CompletableFuture<T> adapt(IOSupplier<T> supplier) {
      return CompletableFuture.supplyAsync(
          supplier.toUncheckedForAsyncCompletion(), FlowSupport.SYNC_EXECUTOR);
    }

    <T> CompletableFuture<HttpResponse<T>> forward(Chain<T> chain, HttpRequest request) {
      return async ? chain.forwardAsync(request) : adapt(() -> chain.forward(request));
    }

    CompletableFuture<@Nullable Viewer> view(Store store, String key) {
      return async ? store.viewAsync(key) : adapt(() -> store.view(key));
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

    CacheInterceptor(HttpCache cache, @Nullable Executor clientExecutor) {
      this.cache = cache;
      cacheExecutor = cache.executor;
      handlerExecutor = requireNonNullElse(clientExecutor, cacheExecutor);
    }

    // TODO figure out what to do with push promises
    // TODO are resources held by CacheResponse handled correctly?
    // TODO figure out what to do with HEADs
    // TODO properly handle logging
    // TODO merging headers doesn't quite work correctly,
    //      for example Content-Length: 0 replaces original
    // TODO consider implementing our own redirecting interceptor
    //      to be above the caching layer so they get cached

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
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
      return doIntercept(request, chain.with(BodyHandlers.ofPublisher(), null), true)
          .thenCompose(rawResponse -> rawResponse.handleAsync(chain.bodyHandler(), handlerExecutor))
          .thenApply(Function.identity()); // Downcast from TrackedResponse<T> to HttpResponse<T>
    }

    private CompletableFuture<RawResponse> doIntercept(
        HttpRequest request, Chain<Publisher<List<ByteBuffer>>> chain, boolean async) {
      cache.onRequest(request);
      var asyncAdapter = new AsyncAdapter(async);
      var context = new ExchangeContext(cache, request, chain, asyncAdapter);
      if (chain.pushPromiseHandler().isPresent()) {
        // Forward client's request to origin in case of HTTP2 push support as we
        // don't know what might be pushed by the server
        return context.forwardToNetwork().thenApply(ctx -> castNonNull(ctx.networkResponse));
      }
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
        // Don't forward the request if the client prohibits network
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
      var now = context.now();
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

      // Don't revalidate if the client prohibits network
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

    private RawResponse updateCacheAndServe(ExchangeContext context) {
      var request = context.request;
      var cacheResponse = context.cacheResponse;
      var networkResponse = context.networkResponse;

      // If neither the cache nor the network was applicable,
      // serve a 504 Gateway Timeout response as per rfc7234 5.2.1.7.
      if (cacheResponse == null && networkResponse == null) {
        cache.onStatus(request, CacheStatus.LOCALLY_GENERATED);
        return RawResponse.from(
            new ResponseBuilder<>()
                .uri(request.uri())
                .request(request)
                .cacheStatus(CacheStatus.LOCALLY_GENERATED)
                .statusCode(HTTP_GATEWAY_TIMEOUT)
                .version(Version.HTTP_1_1)
                .timeRequestSent(context.requestTime)
                .timeResponseReceived(context.now())
                .body(FlowSupport.<List<ByteBuffer>>emptyPublisher())
                .build());
      }

      // Serve the cacheResponse directly if no network was used
      if (networkResponse == null) {
        cache.onStatus(request, CacheStatus.HIT);
        return cacheResponse.with(
            builder ->
                builder
                    .request(request)
                    .cacheStatus(CacheStatus.HIT)
                    .cacheResponse(cacheResponse.get())
                    .timeRequestSent(context.requestTime)
                    .timeResponseReceived(context.now()));
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
        cache.onStatus(request, CacheStatus.CONDITIONAL_HIT);
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
        var cacheUpdatingResponse = cache.update(servedNetworkResponse, cacheResponse);
        if (cacheUpdatingResponse != null) {
          servedNetworkResponse = cacheUpdatingResponse;
        }
      } else if (invalidatesCache(request, servedNetworkResponse.get())) {
        cache.remove(request.uri());
      }

      if (cacheResponse != null) {
        cacheResponse.close();
      }
      cache.onStatus(request, CacheStatus.MISS);
      return servedNetworkResponse;
    }

    private static CacheControl cacheControl(HttpHeaders headers) {
      return headers
          .firstValue("Cache-Control")
          .map(CacheControl::parse)
          .orElse(CacheControl.empty());
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
      final HttpCache cache;
      final Instant requestTime;
      final HttpRequest request;
      final Chain<Publisher<List<ByteBuffer>>> chain;
      final AsyncAdapter asyncAdapter;
      final @Nullable CacheResponse cacheResponse;
      final @Nullable RawResponse networkResponse;

      ExchangeContext(
          HttpCache cache,
          HttpRequest request,
          Chain<Publisher<List<ByteBuffer>>> chain,
          AsyncAdapter asyncAdapter) {
        this(cache, cache.clock.instant(), request, chain, asyncAdapter, null, null);
      }

      private ExchangeContext(
          HttpCache cache,
          Instant requestTime,
          HttpRequest request,
          Chain<Publisher<List<ByteBuffer>>> chain,
          AsyncAdapter asyncAdapter,
          @Nullable CacheResponse cacheResponse,
          @Nullable RawResponse networkResponse) {
        this.cache = cache;
        this.requestTime = requestTime;
        this.request = request;
        this.chain = chain;
        this.asyncAdapter = asyncAdapter;
        this.cacheResponse = cacheResponse;
        this.networkResponse = networkResponse;
      }

      Instant now() {
        return cache.clock.instant();
      }

      ExchangeContext withResponse(
          @Nullable CacheResponse cacheResponse, @Nullable RawResponse networkResponse) {
        return new ExchangeContext(
            cache, requestTime, request, chain, asyncAdapter, cacheResponse, networkResponse);
      }

      ExchangeContext withRequest(HttpRequest request) {
        return new ExchangeContext(
            cache, requestTime, request, chain, asyncAdapter, cacheResponse, networkResponse);
      }

      CompletableFuture<ExchangeContext> forwardToNetwork() {
        cache.onNetworkUse(request);
        return asyncAdapter
            .forward(chain, request)
            .thenApply(
                response ->
                    RawResponse.from(
                        ResponseBuilder.newBuilder(response)
                            .timeRequestSent(requestTime)
                            .timeResponseReceived(now())
                            .build()))
            .thenApply(networkResponse -> withResponse(this.cacheResponse, networkResponse));
      }
    }
  }

  /** Statistics of an {@code HttpCache}. */
  public interface Stats {

    /** Returns the number of requests intercepted by the cache. */
    long requestCount();

    /** Returns the number of requests resulting in a cache hit, including conditional hits. */
    long hitCount();

    /**
     * Returns the number of requests resulting in a cache miss, including conditional misses
     * (unsatisfied revalidation requests).
     */
    long missCount();

    /** Returns the number of times the cache had to use the network. */
    long networkUseCount();

    /**
     * Returns a value between {@code 0.0} and {@code 1.0} representing the ratio between the hit
     * and request counts.
     */
    default double hitRate() {
      return rate(hitCount(), requestCount());
    }

    /**
     * Returns a value between {@code 0.0} and {@code 1.0} representing the ratio between the miss
     * and request counts.
     */
    default double missRate() {
      return rate(missCount(), requestCount());
    }

    private static double rate(long x, long y) {
      return y == 0 || x >= y ? 1.0 : (double) x / y;
    }
  }

  /**
   * Strategy for recoding {@code HttpCache} statistics. Recording methods are given the {@code URI}
   * of the request being intercepted by the cache.
   *
   * <p>{@code StatsRecorders} must be thread-safe.
   */
  public interface StatsRecorder {

    /** Called when a request is intercepted. */
    void recordRequest(URI uri);

    /**
     * Called when a request results in a cache hit, either directly or after successful
     * revalidation with the server.
     */
    void recordHit(URI uri);

    /**
     * Called when a request results in a cache miss, either directly or after failed revalidation
     * with the server.
     */
    void recordMiss(URI uri);

    /** Called when the cache is about to use the network. */
    void recordNetworkUse(URI uri);

    /** Returns a {@code Stats} snapshot for the recorded statistics for all {@code URIs}. */
    Stats snapshot();

    /** Returns a {@code Stats} snapshot for the recorded statistics of the given {@code URI}. */
    Stats snapshot(URI uri);

    /**
     * Creates a {@code StatsRecorder} that atomically increments each count. The recorder is
     * thread-safe but there's a very slight chance that returned {@code Stats} has inconsistent
     * counts due to concurrent increments (e.g. sum of hit and miss counts might be less than
     * request count). Additionally, independence of per-{@code URI} stats is dictated by {@link
     * URI#equals(Object)}. That is, stats of {@code https://example.com/a} and {@code
     * https://example.com/a?x=y} are recorded independently as they are not equal although they
     * have the same host and path.
     *
     * <p>This is the {@code StatsRecorder} used by default.
     */
    static StatsRecorder createConcurrentRecorder() {
      return new ConcurrentStatsRecorder();
    }

    /** Returns a disabled {@code StatsRecorder}. */
    static StatsRecorder disabled() {
      return DisabledStatsRecorder.INSTANCE;
    }
  }

  private static final class ConcurrentStatsRecorder implements StatsRecorder {
    private final StatsCounters globalCounters = new StatsCounters();
    private final ConcurrentMap<URI, StatsCounters> perUriCounters = new ConcurrentHashMap<>();

    ConcurrentStatsRecorder() {}

    @Override
    public void recordRequest(URI uri) {
      requireNonNull(uri);
      globalCounters.requestCounter.increment();
      perUriCounters.computeIfAbsent(uri, __ -> new StatsCounters()).requestCounter.increment();
    }

    @Override
    public void recordHit(URI uri) {
      requireNonNull(uri);
      globalCounters.hitCounter.increment();
      perUriCounters.computeIfAbsent(uri, __ -> new StatsCounters()).hitCounter.increment();
    }

    @Override
    public void recordMiss(URI uri) {
      requireNonNull(uri);
      globalCounters.missCounter.increment();
      perUriCounters.computeIfAbsent(uri, __ -> new StatsCounters()).missCounter.increment();
    }

    @Override
    public void recordNetworkUse(URI uri) {
      requireNonNull(uri);
      globalCounters.networkUseCounter.increment();
      perUriCounters.computeIfAbsent(uri, __ -> new StatsCounters()).networkUseCounter.increment();
    }

    @Override
    public Stats snapshot() {
      return globalCounters.snapshot();
    }

    @Override
    public Stats snapshot(URI uri) {
      var counters = perUriCounters.get(uri);
      return counters != null ? counters.snapshot() : StatsSnapshot.EMPTY;
    }

    private static final class StatsCounters {
      final LongAdder requestCounter = new LongAdder();
      final LongAdder hitCounter = new LongAdder();
      final LongAdder missCounter = new LongAdder();
      final LongAdder networkUseCounter = new LongAdder();

      StatsCounters() {}

      Stats snapshot() {
        return new StatsSnapshot(
            requestCounter.sum(), hitCounter.sum(), missCounter.sum(), networkUseCounter.sum());
      }
    }
  }

  private enum DisabledStatsRecorder implements StatsRecorder {
    INSTANCE;

    @Override
    public void recordRequest(URI uri) {}

    @Override
    public void recordHit(URI uri) {}

    @Override
    public void recordMiss(URI uri) {}

    @Override
    public void recordNetworkUse(URI uri) {}

    @Override
    public Stats snapshot() {
      return StatsSnapshot.EMPTY;
    }

    @Override
    public Stats snapshot(URI uri) {
      return StatsSnapshot.EMPTY;
    }
  }

  private static final class StatsSnapshot implements Stats {
    static final Stats EMPTY = new StatsSnapshot(0L, 0L, 0L, 0L);

    private final long requestCount;
    private final long hitCount;
    private final long missCount;
    private final long networkUseCount;

    StatsSnapshot(long requestCount, long hitCount, long missCount, long networkUseCount) {
      this.requestCount = requestCount;
      this.hitCount = hitCount;
      this.missCount = missCount;
      this.networkUseCount = networkUseCount;
    }

    @Override
    public long requestCount() {
      return requestCount;
    }

    @Override
    public long hitCount() {
      return hitCount;
    }

    @Override
    public long missCount() {
      return missCount;
    }

    @Override
    public long networkUseCount() {
      return networkUseCount;
    }

    @Override
    public int hashCode() {
      return Objects.hash(requestCount, hitCount, missCount, networkUseCount);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Stats)) {
        return false;
      }

      var other = (Stats) obj;
      return requestCount == other.requestCount()
          && hitCount == other.hitCount()
          && missCount == other.missCount()
          && networkUseCount == other.networkUseCount();
    }

    @Override
    public String toString() {
      return String.format(
          "Stats[requestCount=%d, hitCount=%d, missCount=%d, networkUseCount=%d, hitRate=%f]",
          requestCount, hitCount, missCount, networkUseCount, hitRate());
    }
  }

  /** A builder of {@code HttpCaches}. */
  public static final class Builder {
    // Guard against ridiculously small values
    private static final int MAX_SIZE_THRESHOLD = 2 * 1024;

    @MonotonicNonNull StoreFactory storeFactory;
    @MonotonicNonNull Path cacheDirectory;
    @MonotonicNonNull Executor executor;
    @MonotonicNonNull StatsRecorder statsRecorder;
    long maxSize;

    // Use a clock that ticks in millis under UTC, which suffices for freshness
    // calculations and makes saving Instants more compact (no nanos-of-second part
    // is written).
    Clock clock = Clock.tickMillis(ZoneOffset.UTC);

    Builder() {}

    /** Specifies that HTTP responses are to be cached on memory with the given size bound. */
    public Builder cacheOnMemory(long maxSize) {
      checkMaxSize(maxSize);
      this.maxSize = maxSize;
      storeFactory = StoreFactory.MEMORY;
      return this;
    }

    /**
     * Specifies that HTTP responses are to be persisted on disk, under the given directory, with
     * the given size bound.
     */
    public Builder cacheOnDisk(Path directory, long maxSize) {
      checkMaxSize(maxSize);
      this.cacheDirectory = requireNonNull(directory);
      this.maxSize = maxSize;
      storeFactory = StoreFactory.DISK;
      return this;
    }

    /** Sets the executor to be used by the cache. */
    public Builder executor(Executor executor) {
      this.executor = requireNonNull(executor);
      return this;
    }

    /** Sets the {@code StatsRecorder}. */
    public Builder statsRecorder(StatsRecorder statsRecorder) {
      this.statsRecorder = requireNonNull(statsRecorder);
      return this;
    }

    Builder clockForTesting(Clock clock) {
      this.clock = requireNonNull(clock);
      return this;
    }

    /** Builds a new {@code HttpCache}. */
    public HttpCache build() {
      var appliedStoreFactory = storeFactory;
      requireState(appliedStoreFactory != null, "caching method must be specified");
      return new HttpCache(
          appliedStoreFactory, executor, cacheDirectory, maxSize, statsRecorder, clock);
    }

    private void checkMaxSize(long maxSize) {
      requireArgument(
          maxSize >= MAX_SIZE_THRESHOLD,
          "a maxSize of %d doesn't seem reasonable, please set a value >= %d",
          maxSize,
          MAX_SIZE_THRESHOLD);
    }
  }

  private enum StoreFactory {
    MEMORY {
      @Override
      Store create(@Nullable Path directory, long maxSize, Executor executor) {
        return new MemoryStore(maxSize);
      }
    },
    DISK {
      @Override
      Store create(@Nullable Path directory, long maxSize, Executor executor) {
        return new DiskStore(requireNonNull(directory), maxSize, executor);
      }
    };

    abstract Store create(@Nullable Path directory, long maxSize, Executor executor);
  }
}
