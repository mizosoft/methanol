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

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
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
 * <p>{@code Store} is thread-safe and is suitable for concurrent use.
 */
public interface Store extends Closeable, Flushable {
  /** Returns this store's max size in bytes. */
  long maxSize();

  /** Returns the optionally specified executor used for asynchronous or background operations. */
  Optional<Executor> executor();

  /**
   * Returns an optional containing a viewer for the entry associated with the given key, or an
   * empty optional if there's no such entry.
   *
   * @throws IllegalStateException if closed
   */
  Optional<Viewer> view(String key) throws IOException, InterruptedException;

  /**
   * Returns an optional containing an editor for the entry associated with the given key
   * (atomically creating a new one if necessary), or an empty optional if such entry can't be
   * edited.
   *
   * @throws IllegalStateException if closed
   */
  Optional<Editor> edit(String key) throws IOException, InterruptedException;

  /**
   * Returns an iterator of {@code Viewers} over the entries in this store. The iterator doesn't
   * throw {@code ConcurrentModificationException} when the store is modified, but there's no
   * guarantee these changes are reflected.
   */
  Iterator<Viewer> iterator() throws IOException;

  /**
   * Removes the entry associated with the given key.
   *
   * @throws IllegalStateException if closed
   */
  boolean remove(String key) throws IOException, InterruptedException;

  /**
   * Removes the entries associated with all of the given keys
   *
   * @throws IllegalStateException if closed
   */
  default boolean removeAll(List<String> keys) throws IOException, InterruptedException {
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
   * Closes this store. Once the store is closed, all ongoing edits fail to write or commit
   * anything.
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
     * Returns an editor for this viewer's entry, or {@code null} if another edit is in progress or
     * if the entry has been modified since this viewer was created. Changes made by the returned
     * editor are not reflected by this viewer.
     *
     * @throws IllegalStateException if the store is closed
     */
    Optional<Editor> edit() throws IOException, InterruptedException;

    /**
     * Removes the entry associated with this viewer only if it hasn't changed since this viewer was
     * opened.
     *
     * @throws IllegalStateException if the store is closed
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
     * Commits the changes made so far.
     *
     * @throws IllegalStateException if the edit is invalidated
     */
    void commit(ByteBuffer metadata) throws IOException;

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
     * Reads bytes from the data stream into the given buffer, and returns the number of read bytes,
     * or {@code -1} if reached EOF.
     */
    int read(ByteBuffer dst) throws IOException;

    /**
     * Reads bytes from the data stream into the given buffers, in sequential order, and returns the
     * number of read bytes, or {@code -1} if reached EOF.
     */
    default long read(List<ByteBuffer> dsts) throws IOException {
      long totalRead = 0;
      outerLoop:
      for (var dst : dsts) {
        int read;
        while (dst.hasRemaining()) {
          read = read(dst);
          if (read >= 0) {
            totalRead = Math.addExact(totalRead, read);
          } else if (totalRead > 0) {
            break outerLoop;
          } else {
            return -1;
          }
        }
      }
      return totalRead;
    }
  }

  /** A writer for an entry's data stream. */
  interface EntryWriter {

    /**
     * Writes bytes from the given buffer into the editor's data stream, and returns the number of
     * bytes actually written.
     */
    int write(ByteBuffer src) throws IOException;

    /**
     * Writes bytes from the given buffers into the editor's data stream, in sequential order, and
     * returns the number of bytes actually written.
     */
    default long write(List<ByteBuffer> srcs) throws IOException {
      long totalWritten = 0;
      for (var src : srcs) {
        while (src.hasRemaining()) {
          int written = write(src);
          totalWritten = Math.addExact(totalWritten, written);
        }
      }
      return totalWritten;
    }
  }
}
