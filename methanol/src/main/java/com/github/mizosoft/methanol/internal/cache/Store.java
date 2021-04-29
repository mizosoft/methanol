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
 * A bounded repository of data entries each identified by a {@code String} key. Each entry has both
 * a metadata block and a data stream that can be read from or written to asynchronously. Each
 * entry's metadata block and data stream can be accessed by a {@link Viewer} and modified by an
 * {@link Editor}. An entry can have at most one active {@code Editor} but can have multiple
 * concurrent {@code Viewers} after it has been first modified.
 *
 * <p>A {@code Store} bounds the size of data it stores to it's {@link #maxSize()} by automatic
 * eviction of entries, possibly in background. A store's {@link #size()} might exceed it's bound
 * temporarily in case entries are being actively expanded while eviction is in progress. A store's
 * size doesn't include the overhead of the underlying filesystem or any metadata the store itself
 * uses for indexing purposes. Thus, a store's {@code maxSize()} is not exact and might be slightly
 * exceeded as necessary.
 *
 * <p>A {@code Store} is thread-safe and is suitable for concurrent use.
 */
public interface Store extends AutoCloseable {

  // TODO most of these method should be able to throw IOException (use UncheckedIOException?)
  // TODO document IllegalStateException thrown when the store is closed

  /** Returns this store's max size in bytes. */
  long maxSize();

  /** Returns the optional executor used for background operations. */
  Optional<Executor> executor();

  /**
   * Returns a {@code Viewer} for the entry associated with the given key, or {@code null} if
   * there's no such entry.
   */
  @Nullable
  Viewer view(String key);

  /**
   * Returns an {@code Editor} for the entry associated with given key, atomically creating a new
   * entry if necessary, or {@code null} if such entry is currently being edited.
   */
  @Nullable
  Editor edit(String key);

  /**
   * Version of {@link #view(String)} that opens the entry and reads it's metadata block
   * asynchronously.
   */
  CompletableFuture<@Nullable Viewer> viewAsync(String key);

  /** Returns a <em>fail-safe</em> iterator of {@code Viewers} over the entries in this store. */
  Iterator<Viewer> viewAll();

  /** Removes the entry associated with the given key. */
  boolean remove(String key);

  /** Removes all entries from this store. */
  void clear();

  /**
   * Returns the size in bytes all entries in this store consume. Exact consumed size might exceed
   * the returned value.
   */
  long size();

  /** Resets this store's max size. Might evict any excessive entries as necessary. */
  default void resetMaxSize(long maxSize) {
    TODO();
  }

  /** Atomically clears this store and closes it. */
  default void destroy() {
    TODO();
  }

  /** Closes this store. */
  // TODO specify what is closed exactly
  @Override
  void close();

  /** Reads this entry's metadata block and data stream. */
  interface Viewer extends Closeable {

    /** Returns entry's key. */
    String key();

    /** Returns a readonly buffer containing this entry's metadata. */
    ByteBuffer metadata();

    /**
     * Asynchronously copies this entry's data from the given position to the given destination
     * buffer, returning either the number of read bytes or {@code -1} if end-of-file is reached.
     */
    CompletableFuture<Integer> readAsync(long position, ByteBuffer dst);

    /** Returns the size in bytes of the data stream. */
    long dataSize();

    /** Returns the size in bytes the metadata block and data stream occupy. */
    long entrySize();

    /**
     * Returns an editor for this viewer's entry, or {@code null} if another edit is in progress or
     * if the entry has been modified since this viewer was created. Changes made by the returned
     * editor are not reflected by this viewer.
     */
    @Nullable
    Editor edit();

    /** Closes this viewer. */
    @Override
    void close();
  }

  /** Writes this entry's metadata block and data stream. */
  interface Editor extends Closeable {

    /** Returns entry's key. */
    String key();

    /** Sets the metadata block. */
    void metadata(ByteBuffer metadata);

    /**
     * Asynchronously writes the given source buffer to this entry's data at the given position,
     * returning either the number of read bytes or {@code -1} if end-of-file is reached.
     */
    CompletableFuture<Integer> writeAsync(long position, ByteBuffer src);

    /** Returns a {@code Viewer} that reflects the metadata/data being written by this editor. */
    Viewer view();

    /** Discards anything written. */
    void discard();

    /**
     * Closes this editor. Unless the edit is discarded, changes made by this editor are committed.
     */
    @Override
    void close();
  }
}
