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
import com.github.mizosoft.methanol.internal.cache.DateUtils;
import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.FreshnessPolicy;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.NetworkResponse;
import com.github.mizosoft.methanol.internal.cache.RawResponse;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.Flushable;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
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
// TODO consider logging more events
public final class HttpCache implements AutoCloseable, Flushable {
  private static final Logger LOGGER = Logger.getLogger(HttpCache.class.getName());

  private static final int CACHE_VERSION = 1;

  private final Store store;
  private final Executor executor;
  private final boolean userVisibleExecutor;
  private final StatsRecorder statsRecorder;
  private final Clock clock;

  private HttpCache(Builder builder) {
    var userExecutor = builder.executor;
    var storeFactory = builder.storeFactory;
    if (userExecutor != null) {
      executor = userExecutor;
      userVisibleExecutor = true;
    } else {
      executor = Executors.newCachedThreadPool();
      userVisibleExecutor = false;
    }
    store =
        requireNonNullElseGet(
            builder.store,
            () -> storeFactory.create(builder.cacheDirectory, builder.maxSize, executor));
    this.statsRecorder =
        requireNonNullElseGet(builder.statsRecorder, StatsRecorder::createConcurrentRecorder);
    this.clock = requireNonNullElseGet(builder.clock, Utils::systemMillisUtc);
  }

  Store storeForTesting() {
    return store;
  }

  /** Returns the directory used by the HTTP cache if entries are being cached on disk. */
  public Optional<Path> directory() {
    return store instanceof DiskStore
        ? Optional.of(((DiskStore) store).directory())
        : Optional.empty();
  }

  /** Returns this cache's max size in bytes. */
  public long maxSize() {
    return store.maxSize();
  }

  /** Returns an {@code Optional} containing this cache's executor if one is explicitly set. */
  public Optional<Executor> executor() {
    return userVisibleExecutor ? Optional.of(executor) : Optional.empty();
  }

  /** Returns the size this cache occupies in bytes. */
  public long size() {
    return store.size();
  }

  /** Returns a snapshot of statistics accumulated so far. */
  public Stats stats() {
    return statsRecorder.snapshot();
  }

  /** Returns a snapshot of statistics accumulated so far for the given {@code URI}. */
  public Stats stats(URI uri) {
    return statsRecorder.snapshot(uri);
  }

  /** Returns an {@code Iterator} that for the {@code URIs} of responses known to this cache. */
  public Iterator<URI> uris() throws IOException {
    return new Iterator<>() {
      private final Iterator<Viewer> storeIterator = store.iterator();

      private @Nullable URI nextUri;
      private boolean canRemove;

      @Override
      public boolean hasNext() {
        // Prevent any later remove() from remove the wrong entry as hasNext
        // causes the underlying store iterator to advance.
        canRemove = false;
        return nextUri != null || findNextUri();
      }

      @Override
      public URI next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        var uri = castNonNull(nextUri);
        nextUri = null;
        canRemove = true;
        return uri;
      }

      @Override
      public void remove() {
        requireState(canRemove, "next() must be called before remove()");
        canRemove = false;
        storeIterator.remove();
      }

      private boolean findNextUri() {
        while (nextUri == null && storeIterator.hasNext()) {
          try (var viewer = storeIterator.next()) {
            nextUri = CacheResponseMetadata.decode(viewer.metadata()).uri();
            return true;
          } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "unrecoverable cache entry", ioe);

            // Try next entry...
          }
        }
        return false;
      }
    };
  }

  /** Removes all entries from this cache. */
  public void clear() throws IOException {
    store.clear();
  }

  /**
   * Removes the entry associated with the given URI if one is present.
   *
   * @throws IllegalStateException if closed
   */
  public boolean remove(URI uri) throws IOException {
    return store.remove(key(uri));
  }

  /**
   * Removes the entry associated with the given request if one is present.
   *
   * @throws IllegalStateException if closed
   */
  public boolean remove(HttpRequest request) throws IOException {
    return store.remove(key(request));
  }

  /**
   * Atomically clears and closes this cache.
   *
   * @throws IllegalStateException if closed
   */
  public void dispose() throws IOException {
    store.dispose();
  }

  @Override
  public void flush() throws IOException {
    store.flush();
  }

  /**
   * Closes this cache. Attempting to operate on a closed cache either directly (e.g. removing an
   * entry) or indirectly (e.g. sending requests over a client that uses this cache) will likely
   * cause an {@code IllegalStateException} to be thrown.
   */
  @Override
  public void close() throws IOException {
    store.close();
  }

  /** Called by {@code Methanol} when building the interceptor chain. */
  Interceptor interceptor(@Nullable Executor clientExecutor) {
    return new CacheInterceptor(this, clientExecutor);
  }

  private CompletableFuture<@Nullable CacheResponse> get(
      HttpRequest request,
      BiFunction<Store, String, CompletableFuture<@Nullable Viewer>> viewAdapter) {
    if (!"GET".equalsIgnoreCase(request.method())) {
      // The implementation only caches GETs
      return CompletableFuture.completedFuture(null);
    }

    return viewAdapter
        .apply(store, key(request))
        .thenApply(
            Unchecked.func(viewer -> viewer != null ? createCacheResponse(request, viewer) : null));
  }

  private @Nullable CacheResponse createCacheResponse(HttpRequest request, Viewer viewer) {
    CacheResponseMetadata metadata;
    try {
      metadata = CacheResponseMetadata.decode(viewer.metadata());
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "unrecoverable cache entry", e);

      viewer.close();
      return null;
    }

    if (!metadata.matches(request)) {
      viewer.close();
      return null;
    }
    return new CacheResponse(metadata, viewer, executor);
  }

  private void updateMetadata(CacheResponse servedCacheResponse) {
    var response = servedCacheResponse.get();
    try (var editor = servedCacheResponse.edit()) {
      if (editor != null) {
        editor.metadata(CacheResponseMetadata.from(response).encode());
        editor.commitOnClose();
      }
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "failed to update the cache after successful revalidation", e);
    }
  }

  private @Nullable NetworkResponse update(
      NetworkResponse networkResponse, @Nullable CacheResponse cacheResponse) {
    ByteBuffer metadata;
    Editor editor;
    try {
      metadata = CacheResponseMetadata.from(networkResponse.get()).encode();
      editor =
          cacheResponse != null
              ? cacheResponse.edit()
              : store.edit(key(networkResponse.get().request()));
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "exception while attempting to update the cache", e);
      return null;
    }
    if (editor == null) {
      return null;
    }

    return networkResponse.cachingWith(editor, metadata);
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

  static Set<String> implicitlyAddedFieldsForTesting() {
    return CacheInterceptor.IMPLICITLY_ADDED_FIELDS;
  }

  /** Returns a new {@code HttpCache.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * A dirty hack that masks synchronous operations as {@code CompletableFuture} calls that are
   * executed on the caller thread and hence are always completed when returned. This is important
   * in order to share major logic between {@code intercept} and {@code interceptAsync}, which
   * facilitates implementation & maintenance.
   */
  private static final class AsyncAdapter {
    private final boolean async;
    private final Executor executor;

    AsyncAdapter(boolean async, Executor executor) {
      this.async = async;
      this.executor = executor;
    }

    <T> CompletableFuture<HttpResponse<T>> forward(Chain<T> chain, HttpRequest request) {
      return async
          ? chain.forwardAsync(request)
          : Unchecked.supplyAsync(() -> chain.forward(request), FlowSupport.SYNC_EXECUTOR);
    }

    CompletableFuture<@Nullable Viewer> view(Store store, String key) {
      return Unchecked.supplyAsync(
          () -> store.view(key), async ? executor : FlowSupport.SYNC_EXECUTOR);
    }
  }

  /**
   * This interceptor does most HTTP caching workload: restore a response from the underlying store,
   * determine freshness, forward to network or revalidate if required, update or invalidate the
   * cached response if necessary and finally serve the response.
   */
  private static class CacheInterceptor implements Methanol.Interceptor {
    /**
     * Fields that can be added implicitly by HttpClient's own filters. This can happen if an
     * Authenticator or a CookieHandler is installed. If the response varies with these (unlikely
     * but possible) they're not accepted as we can't access their values.
     */
    static final Set<String> IMPLICITLY_ADDED_FIELDS;

    static {
      var fields = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      fields.addAll(Set.of("Cookie", "Cookie2", "Authorization", "Proxy-Authorization"));
      IMPLICITLY_ADDED_FIELDS = fields;
    }

    private static final Duration ONE_DAY = Duration.ofDays(1);

    private final HttpCache cache;
    private final Executor cacheExecutor;

    /**
     * The {@code Executor} used for handling responses. Will be either the client executor or the
     * cache executor if the former is not accessible.
     */
    private final Executor handlerExecutor;

    CacheInterceptor(HttpCache cache, @Nullable Executor clientExecutor) {
      this.cache = cache;
      cacheExecutor = cache.executor;
      handlerExecutor = requireNonNullElse(clientExecutor, cacheExecutor);
    }

    // TODO figure out what to do with HEADs
    // TODO consider implementing our own redirecting interceptor
    //      to be above the caching layer so redirects get cached

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      return Utils.block(doIntercept(request, chain.with(BodyHandlers.ofPublisher(), null), false))
          .handle(chain.bodyHandler());
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
      return initiateExchange(request, chain, async)
          .thenCompose(this::performExchange)
          .thenApply(this::updateCache)
          .thenApply(this::serveResponse);
    }

    private CompletableFuture<ExchangeContext> initiateExchange(
        HttpRequest request, Chain<Publisher<List<ByteBuffer>>> chain, boolean async) {
      var asyncAdapter = new AsyncAdapter(async, cacheExecutor);
      var context = new ExchangeContext(cache, request, chain, asyncAdapter);
      // Requests accepting HTTP/2 pushes are forwarded as
      // we don't know what might be pushed by the server.
      if (chain.pushPromiseHandler().isPresent() || hasPreconditions(request.headers())) {
        return context.forward();
      }
      return cache.get(request, asyncAdapter::view).thenApply(context::withCacheResponse);
    }

    private CompletableFuture<ExchangeContext> performExchange(ExchangeContext context) {
      var requestCacheControl = cacheControl(context.request.headers());
      var cacheResponse = context.cacheResponse;
      if (cacheResponse == null) {
        // Don't forward the request if network is prohibited
        return requestCacheControl.onlyIfCached()
            ? CompletableFuture.completedFuture(context)
            : context.forward();
      }

      var responseCacheControl = cacheControl(cacheResponse.get().headers());
      var freshnessPolicy =
          new FreshnessPolicy(
              requestCacheControl.maxAge().or(responseCacheControl::maxAge),
              cacheResponse.get().timeRequestSent(),
              cacheResponse.get().timeResponseReceived(),
              cacheResponse.get().headers());
      var now = context.now();
      var age = freshnessPolicy.computeAge(now);
      var lifetime =
          freshnessPolicy
              .computeFreshnessLifetime()
              .orElseGet(freshnessPolicy::computeHeuristicLifetime);
      var freshness = lifetime.minus(age);
      var staleness = freshness.negated();
      boolean heuristicFreshness = !freshnessPolicy.hasExplicitExpiration();
      if (canServeFromCache(requestCacheControl, responseCacheControl, freshness, staleness)) {
        // Revalidate the response asynchronously if required by the server.
        // stale-while-revalidate's applicability is rechecked as a more tolerating
        // max-stale shouldn't cause revalidation if response's stale-while-revalidate
        // isn't satisfied.
        var staleWhileRevalidate = responseCacheControl.staleWhileRevalidate();
        if (staleWhileRevalidate.isPresent()
            && freshness.isNegative() // Response is stale
            && staleness.compareTo(staleWhileRevalidate.get()) <= 0 // Staleness is acceptable
            && !requestCacheControl.onlyIfCached()) { // Network isn't prohibited
          // TODO implement a bounding policy on asynchronous revalidation
          // TODO find a mechanism to notify caller for revalidation's completion
          context
              .forwardWithRevalidation(freshnessPolicy.computeEffectiveLastModified())
              .thenApply(this::updateCache)
              .thenApply(networkContext -> castNonNull(networkContext.networkResponse))
              .thenAccept(networkResponse -> networkResponse.drainInBackground(handlerExecutor));
        }

        var servableCacheResponse =
            cacheResponse.with(
                builder -> addAdditionalCacheHeaders(builder, age, freshness, heuristicFreshness));
        return CompletableFuture.completedFuture(context.withCacheResponse(servableCacheResponse));
      }

      // Don't revalidate if network is prohibited
      if (requestCacheControl.onlyIfCached()) {
        cacheResponse.close();
        return CompletableFuture.completedFuture(context.withCacheResponse(null));
      }

      var networkContextFuture =
          context.forwardWithRevalidation(freshnessPolicy.computeEffectiveLastModified());

      // Try recovering from network failures if stale-if-error is set
      var staleIfError = requestCacheControl.staleIfError().or(responseCacheControl::staleIfError);
      if (staleIfError.isPresent()) {
        networkContextFuture =
            networkContextFuture.handle(
                (networkContext, error) -> {
                  if (isApplicableToStaleIfError(networkContext, error)
                      && staleness.compareTo(staleIfError.get()) <= 0) {
                    // Serve the stale cache response we've got
                    var servableCacheResponse =
                        cacheResponse.with(
                            builder ->
                                addAdditionalCacheHeaders(
                                    builder, age, freshness, heuristicFreshness));
                    return context.withCacheResponse(servableCacheResponse);
                  }

                  // stale-if-error isn't satisfied, forward error or networkContext as is
                  if (error != null) {
                    throw new CompletionException(error);
                  }
                  return networkContext;
                });
      }

      // Let's not forget to release the cacheResponse if network fails
      networkContextFuture.whenComplete(
          (__, error) -> {
            if (error != null) {
              cacheResponse.close();
            }
          });
      return networkContextFuture;
    }

    private ExchangeContext updateCache(ExchangeContext context) {
      var cacheResponse = context.cacheResponse;
      var networkResponse = context.networkResponse;
      if (networkResponse == null) { // Nothing to update
        return context;
      }

      // Update the stored response as specified in rfc7234 4.3.4
      // if cacheResponse was successfully revalidated.
      if (cacheResponse != null && networkResponse.get().statusCode() == HTTP_NOT_MODIFIED) {
        var storedHeaders = cacheResponse.get().headers();
        var mergedHeaders = new HeadersBuilder();
        mergedHeaders.addAll(storedHeaders);
        // Remove Warning headers with a 1xx warn code in the stored response
        mergedHeaders.removeIf(
            (name, value) -> "Warning".equalsIgnoreCase(name) && value.startsWith("1"));
        // Use the 304 response fields to replace those with corresponding
        // names in the stored response. The Content-Length of the stored
        // response however is restored to avoid replacing it with the
        // Content-Length: 0 some servers incorrectly send with their 304 responses.
        mergedHeaders.setAll(networkResponse.get().headers());
        storedHeaders
            .firstValue("Content-Length")
            .ifPresent(value -> mergedHeaders.set("Content-Length", value));

        // Update headers & request/response timestamps
        var updatedCacheResponse =
            cacheResponse.with(
                builder ->
                    builder
                        .setHeaders(mergedHeaders.build())
                        .timeRequestSent(networkResponse.get().timeRequestSent())
                        .timeResponseReceived(networkResponse.get().timeResponseReceived()));
        // Update cache in background
        cacheExecutor.execute(() -> cache.updateMetadata(updatedCacheResponse));
        return context.withCacheResponse(updatedCacheResponse);
      }

      var request = context.request;
      if (isCacheable(request, networkResponse.get())) {
        var cacheUpdatingNetworkResponse = cache.update(networkResponse, cacheResponse);
        if (cacheUpdatingNetworkResponse != null) {
          // The cache is updated as the cacheUpdatingNetworkResponse is consumed
          return context.withNetworkResponse(cacheUpdatingNetworkResponse);
        }
      } else if (invalidatesCache(request, networkResponse.get())) {
        try {
          cache.remove(request.uri());
        } catch (IOException e) {
          LOGGER.log(Level.WARNING, "failed to remove invalidated cache response", e);
        }
      }
      return context;
    }

    private RawResponse serveResponse(ExchangeContext context) {
      var request = context.request;
      var cacheResponse = context.cacheResponse;
      var networkResponse = context.networkResponse;

      // If neither the cache nor the network was applicable,
      // serve a 504 Gateway Timeout response as per rfc7234 5.2.1.7.
      if (cacheResponse == null && networkResponse == null) {
        cache.onStatus(request, CacheStatus.LOCALLY_GENERATED);
        return NetworkResponse.from(
            new ResponseBuilder<>()
                .uri(request.uri())
                .request(request)
                .cacheStatus(CacheStatus.LOCALLY_GENERATED)
                .statusCode(HTTP_GATEWAY_TIMEOUT)
                .version(Version.HTTP_1_1)
                .timeRequestSent(context.requestTime)
                .timeResponseReceived(context.now())
                .body(FlowSupport.<List<ByteBuffer>>emptyPublisher())
                .buildTracked());
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

      // If a revalidation request was sent, the precondition fields shouldn't be
      // present in the served response's request. This doesn't remove any user-set
      // fields since requests with preconditions are forwarded to network so they
      // won't have a cacheResponse.
      var initiatingRequest =
          cacheResponse != null
              ? MutableRequest.copyOf(request)
                  .removeHeadersIf((name, __) -> isPreconditionField(name))
                  .toImmutableRequest()
              : request;

      // Serve the cacheResponse if it was successfully revalidated
      if (cacheResponse != null && networkResponse.get().statusCode() == HTTP_NOT_MODIFIED) {
        // Make sure networkResponse is properly consumed
        networkResponse.handleAsync(BodyHandlers.discarding(), handlerExecutor);

        cache.onStatus(request, CacheStatus.CONDITIONAL_HIT);
        return cacheResponse.with(
            builder ->
                builder
                    .request(initiatingRequest)
                    .cacheStatus(CacheStatus.CONDITIONAL_HIT)
                    .cacheResponse(cacheResponse.get())
                    .networkResponse(networkResponse.get())
                    .timeRequestSent(networkResponse.get().timeRequestSent())
                    .timeResponseReceived(networkResponse.get().timeResponseReceived()));
      }

      // Release the inapplicable cache response if there is one
      if (cacheResponse != null) {
        cacheResponse.close();
      }
      cache.onStatus(request, CacheStatus.MISS);
      return networkResponse.with(
          builder ->
              builder
                  .request(initiatingRequest)
                  .cacheStatus(CacheStatus.MISS)
                  .cacheResponse(cacheResponse != null ? cacheResponse.get() : null)
                  .networkResponse(networkResponse.get()));
    }

    private static CacheControl cacheControl(HttpHeaders headers) {
      return CacheControl.parse(headers.allValues("Cache-Control"));
    }

    private boolean canServeFromCache(
        CacheControl requestCacheControl,
        CacheControl responseCacheControl,
        Duration freshness,
        Duration staleness) {
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

      // The response is stale, but some staleness might be acceptable
      var toleratedStaleness =
          requestCacheControl.maxStale().or(responseCacheControl::staleWhileRevalidate);
      return requestCacheControl.anyMaxStale()
          || (toleratedStaleness.isPresent() && staleness.compareTo(toleratedStaleness.get()) <= 0);
    }

    /** Add the additional cache headers advised by rfc7234. */
    private static void addAdditionalCacheHeaders(
        ResponseBuilder<?> builder, Duration age, Duration freshness, boolean heuristicFreshness) {
      builder.setHeader("Age", Long.toString(age.toSeconds()));
      if (freshness.isNegative()) {
        builder.header("Warning", "110 - \"Response is Stale\"");
      }
      if (heuristicFreshness && age.compareTo(ONE_DAY) > 0) {
        builder.header("Warning", "113 - \"Heuristic Expiration\"");
      }
    }

    private static boolean isApplicableToStaleIfError(
        @Nullable ExchangeContext networkContext, @Nullable Throwable error) {
      if (networkContext != null) {
        int code = castNonNull(networkContext.networkResponse).get().statusCode();
        return code >= 500 && code <= 599; // Only accept server error codes
      }
      // It seems silly to regard an IllegalArgumentException or something as a
      // candidate for stale-if-error treatment. IOException and subclasses are
      // regarded as so since they're usually situational errors for network usage,
      // similar to 5xx response codes, which are themselves perfect candidates for
      // stale-if-error.
      var cause = Utils.getDeepCompletionCause(error); // Might be a CompletionException
      return cause instanceof IOException || cause instanceof UncheckedIOException;
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

    /** Returns whether the network response can be cached as specified by rfc7234 section 3. */
    private static boolean isCacheable(HttpRequest initiatingRequest, TrackedResponse<?> response) {
      // Refuse anything but GETs
      if (!"GET".equalsIgnoreCase(initiatingRequest.method())) {
        return false;
      }

      // Refuse partial content
      if (response.statusCode() == 206) {
        return false;
      }

      // Refuse if the response has a different URI or method (e.g. redirection)
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
          // Public & Private imply cacheable by default
          || responseCacheControl.isPublic()
          || responseCacheControl.isPrivate()
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

    /** Checks if the matching stored response should be invalidated as per rfc7234 4.4. */
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
      final @Nullable NetworkResponse networkResponse;
      final @Nullable CacheResponse cacheResponse;

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
          @Nullable NetworkResponse networkResponse,
          @Nullable CacheResponse cacheResponse) {
        this.cache = cache;
        this.requestTime = requestTime;
        this.request = request;
        this.chain = chain;
        this.asyncAdapter = asyncAdapter;
        this.networkResponse = networkResponse;
        this.cacheResponse = cacheResponse;
      }

      Instant now() {
        return cache.clock.instant();
      }

      ExchangeContext withCacheResponse(CacheResponse cacheResponse) {
        return withResponses(networkResponse, cacheResponse);
      }

      ExchangeContext withNetworkResponse(NetworkResponse networkResponse) {
        return withResponses(networkResponse, cacheResponse);
      }

      private ExchangeContext withResponses(
          @Nullable NetworkResponse networkResponse, @Nullable CacheResponse cacheResponse) {
        return new ExchangeContext(
            cache, requestTime, request, chain, asyncAdapter, networkResponse, cacheResponse);
      }

      CompletableFuture<ExchangeContext> forward() {
        cache.onNetworkUse(request);
        return asyncAdapter
            .forward(chain, request)
            .thenApply(
                response ->
                    NetworkResponse.from(
                        ResponseBuilder.newBuilder(response)
                            .timeRequestSent(requestTime)
                            .timeResponseReceived(now())
                            .buildTracked()))
            .thenApply(this::withNetworkResponse);
      }

      CompletableFuture<ExchangeContext> forwardWithRevalidation(LocalDateTime lastModified) {
        return withRevalidation(lastModified).forward();
      }

      private ExchangeContext withRevalidation(LocalDateTime lastModified) {
        requireState(cacheResponse != null, "must have a cacheResponse");
        // rfc7232 2.4 encourages sending both If-None-Match & If-Modified-Since validators
        var etag = castNonNull(cacheResponse).get().headers().firstValue("ETag");
        var request =
            MutableRequest.copyOf(this.request)
                .setHeader("If-Modified-Since", formatHttpDate(lastModified))
                .apply(
                    builder -> etag.ifPresent(value -> builder.setHeader("If-None-Match", value)));
        return new ExchangeContext(
            cache, requestTime, request, chain, asyncAdapter, networkResponse, cacheResponse);
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
     * thread-safe but there's a very slight chance that returned {@code Stats} have inconsistent
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
    long maxSize;
    @MonotonicNonNull StoreFactory storeFactory;
    @MonotonicNonNull Path cacheDirectory;
    @MonotonicNonNull Executor executor;
    @MonotonicNonNull StatsRecorder statsRecorder;

    @MonotonicNonNull Clock clock;
    @MonotonicNonNull Store store;

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

    /** Sets the cache's {@code StatsRecorder}. */
    public Builder statsRecorder(StatsRecorder statsRecorder) {
      this.statsRecorder = requireNonNull(statsRecorder);
      return this;
    }

    Builder clockForTesting(Clock clock) {
      this.clock = requireNonNull(clock);
      return this;
    }

    Builder storeForTesting(Store store) {
      this.store = requireNonNull(store);
      return this;
    }

    /** Builds a new {@code HttpCache}. */
    public HttpCache build() {
      requireState(storeFactory != null || store != null, "caching method must be specified");
      return new HttpCache(this);
    }

    private void checkMaxSize(long maxSize) {
      requireArgument(maxSize > 0, "non-positive maxSize");
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
        return DiskStore.newBuilder()
            .directory(directory)
            .maxSize(maxSize)
            .executor(executor)
            .appVersion(CACHE_VERSION)
            .build();
      }
    };

    abstract Store create(@Nullable Path directory, long maxSize, Executor executor);
  }
}
