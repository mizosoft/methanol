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
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.CacheInterceptor;
import com.github.mizosoft.methanol.internal.cache.CacheResponse;
import com.github.mizosoft.methanol.internal.cache.CacheResponseMetadata;
import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.InternalCache;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.NetworkResponse;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.Flushable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
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
  private final InternalCache internalCache = new InternalCacheView();

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
   * reading index files, iterating over entries available in its directory and possibly creating
   * new index files.
   *
   * <p>The cache initializes itself automatically on first use. An application might choose to call
   * this method (or {@link #initializeAsync()}) during its startup sequence to allow the cache to
   * operate directly when it's first used.
   */
  public void initialize() throws IOException {
    store.initialize();
  }

  /** Asynchronously {@link #initialize() initializes} this cache. */
  public CompletableFuture<Void> initializeAsync() {
    return store.initializeAsync();
  }

  /** Returns an {@code Iterator} for the {@code URIs} of responses known to this cache. */
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
    requireNonNull(uri);
    return store.remove(key(uri));
  }

  /**
   * Removes the entry associated with the given request if one is present.
   *
   * @throws IllegalStateException if closed
   */
  public boolean remove(HttpRequest request) throws IOException {
    requireNonNull(request);
    try (var viewer = store.view(key(request))) {
      if (viewer != null && CacheResponseMetadata.decode(viewer.metadata()).matches(request)) {
        return viewer.removeEntry();
      }
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

  /** Called by {@code Methanol} when building the interceptor chain. */
  Interceptor interceptor(@Nullable Executor clientExecutor) {
    return new CacheInterceptor(
        internalCache, executor, requireNonNullElse(clientExecutor, executor), clock);
  }

  private static String key(HttpRequest request) {
    // Since the cache is restricted to GETs, only the URI is needed as a primary key
    return key(request.uri());
  }

  private static String key(URI uri) {
    return uri.toString();
  }

  /** Returns a new {@code HttpCache.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  private final class InternalCacheView implements InternalCache {
    InternalCacheView() {}

    @Override
    public @Nullable CacheResponse get(HttpRequest request) throws IOException {
      var viewer = store.view(key(request));
      return viewer != null ? createCacheResponse(request, viewer) : null;
    }

    @Override
    public CompletableFuture<@Nullable CacheResponse> getAsync(HttpRequest request) {
      return Unchecked.supplyAsync(() -> store.view(key(request)), executor)
          .thenApply(viewer -> viewer != null ? createCacheResponse(request, viewer) : null);
    }

    private @Nullable CacheResponse createCacheResponse(HttpRequest request, Viewer viewer) {
      try {
        var metadata = CacheResponseMetadata.decode(viewer.metadata());
        if (metadata.matches(request)) {
          return new CacheResponse(metadata, viewer, executor, request, clock.instant());
        }
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "unrecoverable cache entry", e);
      }

      viewer.close();
      return null;
    }

    @Override
    public void update(CacheResponse cacheResponse) {
      var response = cacheResponse.get();
      try (var editor = cacheResponse.edit()) {
        if (editor != null) {
          editor.metadata(CacheResponseMetadata.from(response).encode());
          editor.commitOnClose();
        }
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "exception while updating the cache", e);
      }
    }

    @Override
    public @Nullable NetworkResponse put(
        @Nullable CacheResponse cacheResponse, NetworkResponse networkResponse) {
      try {
        var editor =
            cacheResponse != null
                ? cacheResponse.edit()
                : store.edit(key(networkResponse.get().request()));

        if (editor != null) {
          editor.metadata(CacheResponseMetadata.from(networkResponse.get()).encode());
          return networkResponse.writingWith(editor);
        }
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "exception while opening a cache entry for writing", e);
      }
      return null;
    }

    @Override
    public void remove(URI uri) {
      try {
        HttpCache.this.remove(uri);
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "failed to invalidate a cache entry", e);
      }
    }

    @Override
    public void onRequest(URI uri) {
      statsRecorder.recordRequest(uri);
    }

    @Override
    public void onNetworkUse(URI uri) {
      statsRecorder.recordNetworkUse(uri);
    }

    @Override
    public void onStatus(URI uri, CacheStatus status) {
      switch (status) {
        case MISS:
        case UNSATISFIABLE_REQUEST:
          statsRecorder.recordMiss(uri);
          break;

        case HIT:
        case CONDITIONAL_HIT:
          statsRecorder.recordHit(uri);
          break;

        default:
          throw new AssertionError("unexpected status: " + status);
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

    /** Called when a request is intercepted by the cache. */
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
        requireNonNull(directory, "DiskStore requires a directory");
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
