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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.CacheInterceptor;
import com.github.mizosoft.methanol.internal.cache.CacheReadingPublisher;
import com.github.mizosoft.methanol.internal.cache.CacheResponse;
import com.github.mizosoft.methanol.internal.cache.CacheResponseMetadata;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher;
import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.InternalStorageExtension;
import com.github.mizosoft.methanol.internal.cache.LocalCache;
import com.github.mizosoft.methanol.internal.cache.NetworkResponse;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.Flushable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching">HTTP cache</a> that
 * resides between a {@link Methanol} client and its backend {@code HttpClient}.
 *
 * <p>An {@code HttpCache} instance is utilized by configuring it with {@link
 * Methanol.Builder#cache(HttpCache)}. The cache operates by inserting itself as an {@code
 * Interceptor} that can short-circuit requests by serving responses from a specified storage.
 *
 * @see <a href="https://mizosoft.github.io/methanol/caching/">Caching with Methanol</a>
 */
public final class HttpCache implements AutoCloseable, Flushable {
  private static final Logger logger = System.getLogger(HttpCache.class.getName());

  private static final int CACHE_VERSION = 1;

  private final Store store;
  private final Executor executor;
  private final boolean isDefaultExecutor;
  private final StatsRecorder statsRecorder;

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private final Optional<Listener> userListener;

  private final Listener listener;
  private final Clock clock;

  private final LocalCache localCache = new LocalCacheImpl();

  private HttpCache(Store store, Executor executor, boolean isDefaultExecutor, Builder builder) {
    this.store = requireNonNull(store);
    this.executor = requireNonNull(executor);
    this.isDefaultExecutor = isDefaultExecutor;
    this.statsRecorder =
        requireNonNullElseGet(builder.statsRecorder, StatsRecorder::createConcurrentRecorder);
    this.userListener = Optional.ofNullable(builder.listener);
    this.listener =
        new CompositeListener(
            userListener.orElse(DisabledListener.INSTANCE),
            new StatsRecordingListener(statsRecorder));
    this.clock = requireNonNullElse(builder.clock, Utils.systemMillisUtc());
  }

  Store store() {
    return store;
  }

  /** Returns the directory used by this cache if entries are being cached on disk. */
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
    return isDefaultExecutor ? Optional.of(executor) : Optional.empty();
  }

  /** Returns the {@link Listener} set by the user. */
  public Optional<Listener> listener() {
    return userListener;
  }

  /** Returns the size this cache occupies in bytes. */
  public long size() throws IOException {
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

  /**
   * Initializes this cache. A cache that operates on disk needs to initialize its in-memory data
   * structures before usage to restore indexing data from previous sessions. Initialization entails
   * reading index files, iterating over entries available on cache's directory and possibly
   * creating new index files.
   *
   * <p>The cache initializes itself automatically on first use. An application might choose to call
   * this method (or {@link #initializeAsync()}) during its startup sequence to allow the cache to
   * operate directly when it's first used.
   *
   * @deprecated As of {@code 1.8.0}, a cache is always initialized when created. For background
   *     initialization, please use {@link Builder#buildAsync()}.
   */
  @Deprecated(since = "1.8.0", forRemoval = true)
  public void initialize() throws IOException {}

  /**
   * Asynchronously {@link #initialize() initializes} this cache
   *
   * @deprecated As of {@code 1.8.0}, a cache is always initialized when created. For background
   *     initialization, please use {@link Builder#buildAsync()}.
   */
  @Deprecated(since = "1.8.0", forRemoval = true)
  public CompletableFuture<Void> initializeAsync() {
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Returns an iterator over the {@code URIs} of responses known to this cache. The returned
   * iterator supports removal.
   */
  public Iterator<URI> uris() throws IOException {
    return new Iterator<>() {
      private final Iterator<Viewer> viewerIterator = store.iterator();

      private @Nullable URI nextUri;
      private boolean canRemove;

      @Override
      public boolean hasNext() {
        // Prevent any later remove() from removing the wrong entry as findNext() causes the
        // underlying store iterator to advance.
        canRemove = false;
        return nextUri != null || findNext();
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
        viewerIterator.remove();
      }

      private boolean findNext() {
        while (nextUri == null && viewerIterator.hasNext()) {
          try (var viewer = viewerIterator.next()) {
            var metadata = tryDecodeMetadata(viewer);
            if (metadata != null) {
              nextUri = metadata.uri();
              return true;
            } else {
              viewerIterator.remove();
            }
          }
        }
        return false;
      }
    };
  }

  /**
   * Removes all entries from this cache.
   *
   * @throws IllegalStateException if closed
   */
  public void clear() throws IOException {
    store.clear();
  }

  /**
   * Removes the entry associated with the given URI if one is present.
   *
   * @throws IllegalStateException if closed
   */
  public boolean remove(URI uri) throws IOException {
    try {
      return store.remove(toCacheKey(uri));
    } catch (InterruptedException e) {
      throw (IOException) new InterruptedIOException().initCause(e);
    }
  }

  /**
   * Removes the entry associated with the given request if one is present.
   *
   * @throws IllegalStateException if closed
   */
  public boolean remove(HttpRequest request) throws IOException {
    try (var viewer = store.view(toCacheKey(request)).orElse(null)) {
      if (viewer != null) {
        var metadata = tryDecodeMetadata(viewer);
        if (metadata != null && metadata.matches(request)) {
          return viewer.removeEntry();
        } else if (metadata == null) {
          // The entry is corrupt, try removing it anyway.
          try {
            viewer.removeEntry();
          } catch (IOException e) {
            logger.log(Level.WARNING, "exception when removing corrupt entry", e);
          }
        }
      }
    } catch (InterruptedException e) {
      throw (IOException) new InterruptedIOException().initCause(e);
    }
    return false;
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

  /**
   * Returns an interceptor that serves responses from this cache if applicable. Called by {@code
   * Methanol} when building the interceptor chain.
   */
  Interceptor interceptor(@Nullable Executor clientExecutor) {
    return new CacheInterceptor(
        localCache, listener, requireNonNullElse(clientExecutor, executor), clock);
  }

  private static String toCacheKey(HttpRequest request) {
    // Since the cache is restricted to GETs, only the URI is needed as a primary key.
    return toCacheKey(request.uri());
  }

  private static String toCacheKey(URI uri) {
    return uri.toString();
  }

  private static @Nullable CacheResponseMetadata tryDecodeMetadata(@Nullable Viewer viewer) {
    if (viewer != null) {
      try {
        return CacheResponseMetadata.decode(viewer.metadata());
      } catch (IOException e) {
        logger.log(Level.WARNING, "unrecoverable cache entry", e);
      }
    }
    return null;
  }

  /** Returns a new {@code HttpCache.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  private static CacheReadingPublisher.Listener toReadListener(
      Listener listener, HttpRequest request) {
    return new CacheReadingPublisher.Listener() {
      @Override
      public void onReadSuccess() {
        listener.onReadSuccess(request);
      }

      @Override
      public void onReadFailure(Throwable exception) {
        listener.onReadFailure(request, exception);
      }
    };
  }

  private static CacheWritingPublisher.Listener toWriteListener(
      Listener listener, HttpRequest request) {
    return new CacheWritingPublisher.Listener() {
      @Override
      public void onWriteSuccess() {
        listener.onWriteSuccess(request);
      }

      @Override
      public void onWriteFailure(Throwable exception) {
        listener.onWriteFailure(request, exception);
      }
    };
  }

  private final class LocalCacheImpl implements LocalCache {
    LocalCacheImpl() {}

    @Override
    public Optional<CacheResponse> get(HttpRequest request)
        throws IOException, InterruptedException {
      return tryReadCacheResponse(request, store.view(toCacheKey(request)));
    }

    @Override
    public CompletableFuture<Optional<CacheResponse>> getAsync(HttpRequest request) {
      return store
          .viewAsync(toCacheKey(request))
          .thenApply(viewer -> tryReadCacheResponse(request, viewer));
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Optional<CacheResponse> tryReadCacheResponse(
        HttpRequest request, Optional<Viewer> viewer) {
      var cacheResponse =
          viewer
              .map(HttpCache::tryDecodeMetadata)
              .filter(metadata -> metadata.matches(request))
              .map(
                  metadata ->
                      new CacheResponse(
                          metadata,
                          viewer.orElseThrow(), // We're sure we have a Viewer here.
                          executor,
                          toReadListener(listener, request),
                          request,
                          clock.instant()));
      if (cacheResponse.isEmpty()) {
        viewer.ifPresent(Viewer::close);
      }
      return cacheResponse;
    }

    @Override
    public CompletableFuture<Boolean> updateAsync(CacheResponse cacheResponse) {
      // TODO don't forget to notify Listener of this write's status
      return cacheResponse
          .editAsync()
          .thenCompose(
              optionalEditor ->
                  optionalEditor
                      .map(
                          Unchecked.func(
                              editor ->
                                  editor.commitAsync(
                                      CacheResponseMetadata.from(cacheResponse.get()).encode())))
                      .orElseGet(() -> CompletableFuture.completedFuture(false)));
    }

    @Override
    public CompletableFuture<Optional<NetworkResponse>> putAsync(
        HttpRequest request,
        NetworkResponse networkResponse,
        @Nullable CacheResponse cacheResponse) {
      var editorFuture =
          cacheResponse != null ? cacheResponse.editAsync() : store.editAsync(toCacheKey(request));
      return editorFuture.thenApply(
          optionalEditor ->
              optionalEditor.map(
                  Unchecked.func(
                      editor ->
                          networkResponse.writingWith(
                              editor, toWriteListener(listener, request)))));
    }

    @Override
    public CompletableFuture<Void> removeAllAsync(List<URI> uris) {
      return store.removeAllAsync(
          uris.stream().map(HttpCache::toCacheKey).collect(Collectors.toUnmodifiableList()));
    }
  }

  /** A listener to request/response {@literal &} read/write events within the cache. */
  public interface Listener {

    /** Called when the cache receives a request. */
    default void onRequest(HttpRequest request) {}

    /**
     * Called when the cache is about to use the network due to a cache miss. The given response
     * represents the inapplicable cache response if one was available.
     */
    default void onNetworkUse(HttpRequest request, @Nullable TrackedResponse<?> cacheResponse) {}

    /**
     * Called when the cache is ready to serve the response. The given response's {@link
     * CacheAwareResponse#cacheStatus() cache status} can be examined to know how the response was
     * constructed by the cache. This method is called before the response body is read.
     */
    default void onResponse(HttpRequest request, CacheAwareResponse<?> response) {}

    /** Called when the response body has been successfully read from cache. */
    default void onReadSuccess(HttpRequest request) {}

    /** Called when a failure is encountered while reading the response body from cache. */
    default void onReadFailure(HttpRequest request, Throwable exception) {}

    /** Called when the response has been successfully written to cache. */
    default void onWriteSuccess(HttpRequest request) {}

    /** Called when a failure is encountered while writing the response to cache. */
    default void onWriteFailure(HttpRequest request, Throwable exception) {}
  }

  private enum DisabledListener implements Listener {
    INSTANCE
  }

  private static final class StatsRecordingListener implements Listener {
    private final StatsRecorder statsRecorder;

    StatsRecordingListener(StatsRecorder statsRecorder) {
      this.statsRecorder = statsRecorder;
    }

    @Override
    public void onRequest(HttpRequest request) {
      statsRecorder.recordRequest(request.uri());
    }

    @Override
    public void onNetworkUse(HttpRequest request, @Nullable TrackedResponse<?> cacheResponse) {
      statsRecorder.recordNetworkUse(request.uri());
    }

    @Override
    public void onResponse(HttpRequest request, CacheAwareResponse<?> response) {
      switch (response.cacheStatus()) {
        case MISS:
        case UNSATISFIABLE:
          statsRecorder.recordMiss(request.uri());
          break;
        case HIT:
        case CONDITIONAL_HIT:
          statsRecorder.recordHit(request.uri());
          break;
        default:
          throw new AssertionError("unexpected status: " + response.cacheStatus());
      }
    }

    @Override
    public void onReadSuccess(HttpRequest request) {
      // TODO
      // statsRecorder.recordReadSuccess(request.uri());
    }

    @Override
    public void onReadFailure(HttpRequest request, Throwable exception) {
      // TODO
      // statsRecorder.recordReadFailure(request.uri());
    }

    @Override
    public void onWriteSuccess(HttpRequest request) {
      statsRecorder.recordWriteSuccess(request.uri());
    }

    @Override
    public void onWriteFailure(HttpRequest request, Throwable exception) {
      statsRecorder.recordWriteFailure(request.uri());
    }
  }

  private static final class CompositeListener implements Listener {
    private final List<Listener> listeners;

    CompositeListener(Listener... listeners) {
      this.listeners = List.of(listeners);
    }

    @Override
    public void onRequest(HttpRequest request) {
      listeners.forEach(listener -> listener.onRequest(request));
    }

    @Override
    public void onNetworkUse(HttpRequest request, @Nullable TrackedResponse<?> cacheResponse) {
      listeners.forEach(listener -> listener.onNetworkUse(request, cacheResponse));
    }

    @Override
    public void onResponse(HttpRequest request, CacheAwareResponse<?> response) {
      listeners.forEach(listener -> listener.onResponse(request, response));
    }

    @Override
    public void onReadSuccess(HttpRequest request) {
      listeners.forEach(listener -> listener.onReadSuccess(request));
    }

    @Override
    public void onReadFailure(HttpRequest request, Throwable exception) {
      listeners.forEach(listener -> listener.onReadFailure(request, exception));
    }

    @Override
    public void onWriteSuccess(HttpRequest request) {
      listeners.forEach(listener -> listener.onWriteSuccess(request));
    }

    @Override
    public void onWriteFailure(HttpRequest request, Throwable exception) {
      listeners.forEach(listener -> listener.onWriteFailure(request, exception));
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

    /** Returns the number of times a response was successfully written to cache. */
    long writeSuccessCount();

    /** Returns the number of times a response wasn't written to cache due to a write failure. */
    long writeFailureCount();

    // TODO add read success/failure count & write discard count.

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

    /** Returns a {@code Stats} with zero counters. */
    static Stats empty() {
      return StatsSnapshot.EMPTY;
    }
  }

  /**
   * Strategy for recoding {@code HttpCache} statistics. Recording methods are given the {@code URI}
   * of the request being intercepted by the cache.
   *
   * <p>A {@code StatsRecorder} must be thread-safe.
   */
  public interface StatsRecorder {

    /** Called when a request is intercepted by the cache. */
    void recordRequest(URI uri);

    /**
     * Called when a request results in a cache hit, either directly or after successful
     * revalidation with the server.
     */
    void recordHit(URI uri);

    /**
     * Called when a request results in a cache miss, either due to a missing cache entry or after
     * failed revalidation with the server.
     */
    void recordMiss(URI uri);

    /** Called when the cache is about to use the network. */
    void recordNetworkUse(URI uri);

    /** Called when a response is successfully written to cache. */
    void recordWriteSuccess(URI uri);

    /** Called when a write failure is encountered while writing a response to cache. */
    void recordWriteFailure(URI uri);

    /** Returns a {@code Stats} snapshot for the recorded statistics for all {@code URIs}. */
    Stats snapshot();

    /** Returns a {@code Stats} snapshot for the recorded statistics of the given {@code URI}. */
    Stats snapshot(URI uri);

    /**
     * Creates a {@code StatsRecorder} that atomically increments each count and doesn't record per
     * {@code URI} stats.
     *
     * <p>This is the {@code StatsRecorder} used by default.
     */
    static StatsRecorder createConcurrentRecorder() {
      return new ConcurrentStatsRecorder();
    }

    /**
     * Creates a {@code StatsRecorder} that atomically increments each count and records per {@code
     * URI} stats.
     *
     * <p>Independence of per {@code URI} stats is dictated by {@link URI#equals(Object)}. That is,
     * stats of {@code https://example.com/a} and {@code https://example.com/a?x=y} are recorded
     * independently as the {@code URIs} are not equal, although they have the same host and path.
     */
    static StatsRecorder createConcurrentPerUriRecorder() {
      return new ConcurrentPerUriStatsRecorder();
    }

    /** Returns a disabled {@code StatsRecorder}. */
    static StatsRecorder disabled() {
      return DisabledStatsRecorder.INSTANCE;
    }
  }

  private static class ConcurrentStatsRecorder implements StatsRecorder {
    private final LongAdder requestCounter = new LongAdder();
    private final LongAdder hitCounter = new LongAdder();
    private final LongAdder missCounter = new LongAdder();
    private final LongAdder networkUseCounter = new LongAdder();
    private final LongAdder writeSuccessCounter = new LongAdder();
    private final LongAdder writeFailureCounter = new LongAdder();

    ConcurrentStatsRecorder() {}

    @Override
    public void recordRequest(URI uri) {
      requireNonNull(uri);
      requestCounter.increment();
    }

    @Override
    public void recordHit(URI uri) {
      requireNonNull(uri);
      hitCounter.increment();
    }

    @Override
    public void recordMiss(URI uri) {
      requireNonNull(uri);
      missCounter.increment();
    }

    @Override
    public void recordNetworkUse(URI uri) {
      requireNonNull(uri);
      networkUseCounter.increment();
    }

    @Override
    public void recordWriteSuccess(URI uri) {
      requireNonNull(uri);
      writeSuccessCounter.increment();
    }

    @Override
    public void recordWriteFailure(URI uri) {
      requireNonNull(uri);
      writeFailureCounter.increment();
    }

    @Override
    public Stats snapshot() {
      return new StatsSnapshot(
          requestCounter.sum(),
          hitCounter.sum(),
          missCounter.sum(),
          networkUseCounter.sum(),
          writeSuccessCounter.sum(),
          writeFailureCounter.sum());
    }

    @Override
    public Stats snapshot(URI uri) {
      return Stats.empty();
    }
  }

  private static final class ConcurrentPerUriStatsRecorder extends ConcurrentStatsRecorder {
    private final ConcurrentMap<URI, StatsRecorder> perUriRecorders = new ConcurrentHashMap<>();

    ConcurrentPerUriStatsRecorder() {}

    @Override
    public void recordRequest(URI uri) {
      super.recordRequest(uri);
      perUriRecorders.computeIfAbsent(uri, __ -> new ConcurrentStatsRecorder()).recordRequest(uri);
    }

    @Override
    public void recordHit(URI uri) {
      super.recordHit(uri);
      perUriRecorders.computeIfAbsent(uri, __ -> new ConcurrentStatsRecorder()).recordHit(uri);
    }

    @Override
    public void recordMiss(URI uri) {
      super.recordMiss(uri);
      perUriRecorders.computeIfAbsent(uri, __ -> new ConcurrentStatsRecorder()).recordMiss(uri);
    }

    @Override
    public void recordNetworkUse(URI uri) {
      super.recordNetworkUse(uri);
      perUriRecorders
          .computeIfAbsent(uri, __ -> new ConcurrentStatsRecorder())
          .recordNetworkUse(uri);
    }

    @Override
    public void recordWriteSuccess(URI uri) {
      super.recordWriteSuccess(uri);
      perUriRecorders
          .computeIfAbsent(uri, __ -> new ConcurrentStatsRecorder())
          .recordWriteSuccess(uri);
    }

    @Override
    public void recordWriteFailure(URI uri) {
      super.recordWriteFailure(uri);
      perUriRecorders
          .computeIfAbsent(uri, __ -> new ConcurrentStatsRecorder())
          .recordWriteFailure(uri);
    }

    @Override
    public Stats snapshot(URI uri) {
      var recorder = perUriRecorders.get(uri);
      return recorder != null ? recorder.snapshot() : Stats.empty();
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
    public void recordWriteSuccess(URI uri) {}

    @Override
    public void recordWriteFailure(URI uri) {}

    @Override
    public Stats snapshot() {
      return Stats.empty();
    }

    @Override
    public Stats snapshot(URI uri) {
      return Stats.empty();
    }
  }

  private static final class StatsSnapshot implements Stats {
    static final Stats EMPTY = new StatsSnapshot(0L, 0L, 0L, 0L, 0L, 0L);

    private final long requestCount;
    private final long hitCount;
    private final long missCount;
    private final long networkUseCount;
    private final long writeSuccessCount;
    private final long writeFailureCount;

    StatsSnapshot(
        long requestCount,
        long hitCount,
        long missCount,
        long networkUseCount,
        long writeSuccessCount,
        long writeFailureCount) {
      this.requestCount = requestCount;
      this.hitCount = hitCount;
      this.missCount = missCount;
      this.networkUseCount = networkUseCount;
      this.writeSuccessCount = writeSuccessCount;
      this.writeFailureCount = writeFailureCount;
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
    public long writeSuccessCount() {
      return writeSuccessCount;
    }

    @Override
    public long writeFailureCount() {
      return writeFailureCount;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          requestCount, hitCount, missCount, networkUseCount, writeSuccessCount, writeFailureCount);
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
          && networkUseCount == other.networkUseCount()
          && writeSuccessCount == other.writeSuccessCount()
          && writeFailureCount == other.writeFailureCount();
    }

    @Override
    public String toString() {
      return String.format(
          "Stats[requestCount=%d, hitCount=%d, missCount=%d, networkUseCount=%d, writeSuccessCount=%d, writeFailureCount=%d]",
          requestCount, hitCount, missCount, networkUseCount, writeSuccessCount, writeFailureCount);
    }
  }

  /** A builder of {@code HttpCaches}. */
  public static final class Builder {
    @MonotonicNonNull InternalStorageExtension storageExtension;
    @MonotonicNonNull Executor executor;
    @MonotonicNonNull StatsRecorder statsRecorder;
    @MonotonicNonNull Listener listener;
    @MonotonicNonNull Clock clock;

    Builder() {}

    /** Specifies that HTTP responses are to be cached on memory with the given size bound. */
    public Builder cacheOnMemory(long maxSize) {
      return cacheOn(StorageExtension.memory(maxSize));
    }

    /**
     * Specifies that HTTP responses are to be persisted on disk, under the given directory, with
     * the given size bound.
     */
    public Builder cacheOnDisk(Path directory, long maxSize) {
      return cacheOn(StorageExtension.disk(directory, maxSize));
    }

    public Builder cacheOn(StorageExtension storageExtension) {
      requireNonNull(storageExtension);
      requireArgument(
          storageExtension instanceof InternalStorageExtension,
          "unrecognized StorageExtension: %s",
          storageExtension);
      this.storageExtension = (InternalStorageExtension) storageExtension;
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

    /** Sets the cache's {@code Listener}. */
    public Builder listener(Listener listener) {
      this.listener = requireNonNull(listener);
      return this;
    }

    Builder clock(Clock clock) {
      this.clock = requireNonNull(clock);
      return this;
    }

    private Executor getOrCreateExecutor(boolean[] isDefaultExecutor) {
      var executor = this.executor;
      if (executor != null) {
        isDefaultExecutor[0] = false;
      } else {
        executor =
            Executors.newCachedThreadPool(
                r -> {
                  var t = new Thread(r);
                  t.setDaemon(true);
                  return t;
                });
        isDefaultExecutor[0] = true;
      }
      return executor;
    }

    private InternalStorageExtension storageExtension() {
      var storeExtension = this.storageExtension;
      requireState(storeExtension != null, "a storage backend must be specified");
      return storeExtension;
    }

    /** Creates a new {@code HttpCache}. */
    public HttpCache build() {
      var isDefaultExecutor = new boolean[1];
      var executor = getOrCreateExecutor(isDefaultExecutor);
      var store = storageExtension().createStore(executor, CACHE_VERSION);
      return new HttpCache(store, executor, isDefaultExecutor[0], this);
    }

    /** Asynchronously creates a new {@code HttpCache}. */
    public CompletableFuture<HttpCache> buildAsync() {
      var isDefaultExecutor = new boolean[1];
      var executor = getOrCreateExecutor(isDefaultExecutor);
      return storageExtension()
          .createStoreAsync(executor, CACHE_VERSION)
          .thenApply(store -> new HttpCache(store, executor, isDefaultExecutor[0], this));
    }
  }
}
