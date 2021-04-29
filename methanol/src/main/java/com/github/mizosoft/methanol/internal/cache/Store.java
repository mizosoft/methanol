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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Validate.TODO;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A bounded repository of data where each entry is identified by a {@code String} key. Each entry
 * has both a metadata block and a data stream that can be read from or written to asynchronously.
 * Each entry's metadata block and data stream can be accessed by a {@link Viewer} and modified by
 * an {@link Editor}. An entry can have at most one active {@code Editor} but can have multiple
 * concurrent {@code Viewers} after it has been first modified.
 *
 * <p>A {@code Store} bounds the size of data it stores to it's {@link #maxSize()} by automatic
 * eviction of entries, possibly in background. A store's {@link #size()} might exceed it's bound
 * temporarily in case entries are being actively expanded while eviction is in progress. A store's
 * size doesn't include the overhead of the underlying filesystem or any metadata the store itself
 * uses for indexing purposes. Thus, a store's {@code maxSize()} is not exact and might be slightly
 * exceeded as necessary.
 *
 * A {@code Store} is thread-safe and is suitable for concurrent use.
 */
public interface Store extends Iterable<Store.Entry>, AutoCloseable {

  // TODO most of these method should be able to throw IOException (use UncheckedIOException?)
  // TODO is Entry really needed? why not just have view(string key) / edit(String key)?

  /** Returns this store's max size in bytes. */
  long maxSize();

  /** Returns the optional executor used for background operations. */
  Optional<Executor> executor();

  /** Returns the entry associated with the given key if present, otherwise returns {@code null}. */
  @Nullable
  Entry open(String key);

  /** Returns either the entry associated with the given key or a new one created atomically. */
  Entry openOrCreate(String key);

  /** Async version of {@link #open(String)}. */
  CompletableFuture<@Nullable Entry> openAsync(String key);

  /** Async version of {@link #openOrCreate(String)} (String)}. */
  CompletableFuture<Entry> openOrCreateAsync(String key);

  /** Evicts the entry associated with the given key. */
  boolean evict(String key);

  /** Evicts all entries in this store. */
  void evictAll();

  /**
   * Returns the size in bytes all entries in this store consume. Actual consumed size might exceed
   * the returned value.
   */
  long size();

  /** Resets this store's max size. Might evict any excessive entries as needed. */
  default void truncateTo(long maxSize) {
    TODO();
  }

  /** Returns a <em>fail-safe</em> iterator over the entries in this store. */
  @Override
  Iterator<Entry> iterator();

  @Override
  void close();

  // TODO complete docs

  interface Entry extends Closeable {
    String key();

    @Nullable
    Viewer view();

    @Nullable
    Editor edit();

    void evict();

    @Override
    void close();
  }

  interface Viewer extends Closeable {
    Entry entry();

    ByteBuffer metadata();

    CompletableFuture<Integer> readAsync(long position, ByteBuffer dst);

    long dataSize();

    long entrySize();

    @Override
    void close();
  }

  interface Editor extends Closeable {
    Entry entry();

    void metadata(ByteBuffer metadata);

    CompletableFuture<Integer> writeAsync(long position, ByteBuffer dst);

    Viewer view();

    void discard();

    @Override
    void close();
  }
}
