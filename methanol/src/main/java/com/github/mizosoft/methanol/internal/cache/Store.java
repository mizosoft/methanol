/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A repository of binary data entries each identified by a string key. Each entry has a metadata
 * block and a data stream. An entry's metadata block and data stream can be accessed by a {@link
 * Viewer} and modified by an {@link Editor}. An entry can have at most one active editor but can
 * have multiple concurrent viewers. No data written through an editor is visible to subsequently
 * opened viewers unless {@link Editor#commit(ByteBuffer) committed}. A viewer sees a consistent
 * snapshot of the entry as perceived at the time it was opened at.
 *
 * <p>A store may bound its size by automatic eviction of entries, possibly in background. The size
 * bound is not strict. A store's {@link #size} might temporarily exceed its bound in case the store
 * is being actively expanded while eviction is in progress. Additionally, a store's size doesn't
 * include the overhead of the underlying filesystem or any metadata the store itself uses for
 * indexing purposes. Thus, a store's {@link #maxSize} is not exact and might be slightly exceeded
 * as necessary.
 *
 * <p>Functions that are expected to be called frequently have two variants: one synchronous, and
 * another asynchronous variant that takes an additional {@link Executor} parameter. The former has
 * a default implementation that calls the latter and waits for the result. The executor parameter
 * only expresses caller's preferred asynchronous execution strategy, and may be ignored by the
 * store if seen fit. The executor parameter can be {@code Runnable::run} if same-thread execution
 * is preferred.
 *
 * <p>{@code Store} is thread-safe and is suitable for concurrent use.
 */
public interface Store extends Closeable, Flushable {

  /** Returns this store's max size in bytes. */
  long maxSize();

  /**
   * Synchronous variant of {@link #view(String, Executor)}.
   *
   * @throws IllegalStateException if closed
   */
  default Optional<Viewer> view(String key) throws IOException {
    return Utils.getIo(view(key, FlowSupport.SYNC_EXECUTOR));
  }

  /**
   * Opens a viewer for the entry associated with the given key. An empty optional is returned if
   * there's no such entry.
   *
   * @throws IllegalStateException if closed
   */
  CompletableFuture<Optional<Viewer>> view(String key, Executor executor);

  /**
   * Synchronous variant of {@link #edit(String, Executor)}.
   *
   * @throws IllegalStateException if closed
   */
  default Optional<Editor> edit(String key) throws IOException {
    return Utils.getIo(edit(key, FlowSupport.SYNC_EXECUTOR));
  }

  /**
   * Opens an editor for the entry associated with the given key. An empty optional is returned
   * either if there's no such entry, or such entry cannot be edited at the moment.
   *
   * @throws IllegalStateException if closed
   */
  CompletableFuture<Optional<Editor>> edit(String key, Executor executor);

  /**
   * Returns an iterator of {@code Viewers} over the entries in this store. The iterator doesn't
   * throw {@code ConcurrentModificationException} when the store is asynchronously modified, but
   * there's no guarantee such changes are reflected.
   */
  Iterator<Viewer> iterator() throws IOException;

  /**
   * Removes the entry associated with the given key.
   *
   * @throws IllegalStateException if closed
   */
  @CanIgnoreReturnValue
  boolean remove(String key) throws IOException;

  /**
   * Removes all the entries associated with the given keys.
   *
   * @throws IllegalStateException if closed
   */
  @CanIgnoreReturnValue
  default boolean removeAll(List<String> keys) throws IOException {
    boolean removedAny = false;
    for (var key : keys) {
      removedAny |= remove(key);
    }
    return removedAny;
  }

  /**
   * Removes all entries from this store.
   *
   * @throws IllegalStateException if closed
   */
  void clear() throws IOException;

  /** Returns the size in bytes of all entries in this store. */
  long size() throws IOException;

  /** Atomically clears and closes this store. */
  void dispose() throws IOException;

  /**
   * Closes this store. Once the store is closed, all ongoing edits fail, either silently or by
   * throwing an exception, to write or commit anything.
   */
  @Override
  void close() throws IOException;

  /**
   * Flushes any indexing data buffered by this store.
   *
   * @throws IllegalStateException if the store is closed
   */
  @Override
  void flush() throws IOException;

  /** Reads an entry's metadata block and data stream. */
  interface Viewer extends Closeable {

    /** Returns the entry's key. */
    String key();

    /** Returns the entry's metadata as a readonly buffer. */
    ByteBuffer metadata();

    /**
     * Returns a new {@link EntryReader} that reads the entry's data stream from the start. A viewer
     * can have multiple concurrent readers, each with an independent position.
     */
    EntryReader newReader();

    /** Returns the size in bytes of the data stream. */
    long dataSize();

    /** Returns the size in bytes of the metadata block and data stream. */
    long entrySize();

    /**
     * Synchronous variant of {@link #edit(Executor)}.
     *
     * @throws IllegalStateException if closed
     */
    default Optional<Editor> edit() throws IOException {
      return Utils.getIo(edit(FlowSupport.SYNC_EXECUTOR));
    }

    /**
     * Opens an editor for the entry this viewer was opened for. An empty optional is returned if
     * another edit is in progress or if the entry has been modified since this viewer was created.
     * Changes made by the returned editor are not reflected by this viewer.
     *
     * @throws IllegalStateException if closed
     */
    CompletableFuture<Optional<Editor>> edit(Executor executor);

    /**
     * Removes the entry associated with this viewer only if it hasn't changed since this viewer was
     * opened.
     *
     * @throws IllegalStateException if closed
     */
    boolean removeEntry() throws IOException;

    /** Closes this viewer. */
    @Override
    void close();
  }

  /** Writes an entry's metadata block and data stream. */
  interface Editor extends Closeable {

    /** Returns the entry's key. */
    String key();

    /**
     * Returns the writer associated with this editor, which is at most one. Although a writer is
     * safe for concurrent use, it is expected to be used <i>serially</i>, that is, one write at a
     * time.
     */
    EntryWriter writer();

    /**
     * Synchronous variant of {@link #commit(ByteBuffer, Executor)}.
     *
     * @throws IllegalStateException if closed
     */
    default void commit(ByteBuffer metadata) throws IOException {
      Utils.getIo(commit(metadata, FlowSupport.SYNC_EXECUTOR));
    }

    /**
     * Commits the changes made so far.
     *
     * @throws IllegalStateException if closed
     */
    CompletableFuture<Void> commit(ByteBuffer metadata, Executor executor);

    /**
     * Closes this editor. If {@link #commit(ByteBuffer)} hasn't been called prior to this method,
     * changes made by this editor are discarded.
     */
    @Override
    void close();
  }

  /** A reader for an entry's data stream. */
  interface EntryReader {

    /**
     * Synchronous variant of {@link #read(ByteBuffer, Executor)}.
     *
     * @throws IllegalStateException if the viewer is closed or if a read is currently in progress
     *     and the store doesn't allow concurrent reads
     */
    default int read(ByteBuffer dst) throws IOException {
      return (int) read(List.of(dst));
    }

    /**
     * Reads as many bytes as possible from the data stream into the given buffer, and returns the
     * number of read bytes, or {@code -1} if reached EOF. The number returned is the maximum of the
     * number of bytes remaining in the buffer or the stream.
     *
     * @implSpec The default implementation calls {@link #read(List, Executor)}, where the passed
     *     list contains only the given buffer.
     * @throws IllegalStateException if the viewer is closed or if a read is currently in progress
     *     and the store doesn't allow concurrent reads
     */
    default CompletableFuture<Integer> read(ByteBuffer dst, Executor executor) {
      return read(List.of(dst), executor).thenApply(Long::intValue);
    }

    /**
     * Synchronous variant of {@link #read(List, Executor)}.
     *
     * @throws IllegalStateException if the viewer is closed or if a read is currently in progress
     *     and the store doesn't allow concurrent reads
     */
    default long read(List<ByteBuffer> dsts) throws IOException {
      return Utils.getIo(read(dsts, FlowSupport.SYNC_EXECUTOR));
    }

    /**
     * Reads as many bytes as possible from the data stream into the given buffers, in sequential
     * order, and returns the number of read bytes, or {@code -1} if reached EOF. The number
     * returned is the maximum of the number of bytes remaining in the buffer or the stream.
     *
     * @throws IllegalStateException if the viewer is closed or if a read is currently in progress
     *     and the store doesn't allow concurrent reads
     */
    CompletableFuture<Long> read(List<ByteBuffer> dsts, Executor executor);
  }

  /** A writer for an entry's data stream. */
  interface EntryWriter {

    /**
     * Synchronous variant of {@link #write(ByteBuffer, Executor)}.
     *
     * @throws IllegalStateException if the editor is closed or if a write is currently in progress
     *     and the store doesn't allow concurrent writes
     */
    default int write(ByteBuffer src) throws IOException {
      return (int) write(List.of(src));
    }

    /**
     * Writes all the bytes from the given buffer into the editor's data stream, and returns the
     * number of bytes actually written.
     *
     * @implSpec The default implementation calls {@link #write(List, Executor)}, where the passed
     *     list contains only the given buffer.
     * @throws IllegalStateException if the editor is closed or if a write is currently in progress
     *     and the store doesn't allow concurrent writes
     */
    default CompletableFuture<Integer> write(ByteBuffer src, Executor executor) {
      return write(List.of(src), executor).thenApply(Long::intValue);
    }

    /**
     * Synchronous variant of {@link #write(List, Executor)}.
     *
     * @throws IllegalStateException if the editor is closed or if a write is currently in progress
     *     and the store doesn't allow concurrent writes
     */
    default long write(List<ByteBuffer> srcs) throws IOException {
      return Utils.getIo(write(srcs, FlowSupport.SYNC_EXECUTOR));
    }

    /**
     * Writes all the bytes from the given buffers into the editor's data stream, in sequential
     * order, and returns the number of bytes actually written.
     *
     * @throws IllegalStateException if the editor is closed or if a write is currently in progress
     *     and the store doesn't allow concurrent writes
     */
    CompletableFuture<Long> write(List<ByteBuffer> srcs, Executor executor);
  }
}
