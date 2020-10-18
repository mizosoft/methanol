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
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

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
    } else if (store instanceof MemoryStore) { // Can use ForkJoinPool as there's no IO
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
   * Returns the directory this cache uses for persisting HTTP response entries.
   *
   * @throws UnsupportedOperationException if the cache doesn't persist data on disk
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
    return Optional.ofNullable(userVisibleExecutor ? executor : null);
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

  /** Removes the HTTP response entry associated with the given uri if one is present. */
  public boolean remove(URI uri) {
    return TODO();
  }

  /** Atomically clears this cache and closes it. */
  public void destroy() {
    store.destroy();
  }

  /** Closes this cache. */
  // TODO specify what is closed exactly
  @Override
  public void close() {
    store.close();
  }

  Interceptor interceptor() {
    return new CacheInterceptor();
  }

  private static class CacheInterceptor implements Methanol.Interceptor {

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      return TODO();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(HttpRequest request,
        Chain<T> chain) {
      return TODO();
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

    // Use a milli-precision clock as it suffices for freshness calculations, is more
    // efficient than nanosecond-of-second precision and makes saving Instants more compact
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
      return TODO();
    }

    /** Sets an executor for use by the cache. */
    public Builder executor(Executor executor) {
      this.executor = requireNonNull(executor);
      return this;
    }

    Builder clockForTesting(Clock clock) {
      this.clock = clock;
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
