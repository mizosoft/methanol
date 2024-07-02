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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;

/** {@link Store} implementation that stores entries in memory. */
public final class MemoryStore implements Store {
  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final long maxSize;
  private final AtomicLong size = new AtomicLong();
  private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

  public MemoryStore(long maxSize) {
    requireArgument(maxSize > 0, "non-positive maxSize: %s", maxSize);
    this.maxSize = maxSize;
  }

  @Override
  public long maxSize() {
    return maxSize;
  }

  @Override
  public long size() {
    return size.get();
  }

  @Override
  public Optional<Viewer> view(String key) {
    requireNonNull(key);
    synchronized (entries) {
      return Optional.ofNullable(entries.get(key)).map(Entry::view);
    }
  }

  @Override
  public CompletableFuture<Optional<Viewer>> view(String key, Executor executor) {
    return CompletableFuture.completedFuture(view(key));
  }

  @Override
  public Optional<Editor> edit(String key) {
    requireNonNull(key);
    synchronized (entries) {
      return Optional.ofNullable(entries.computeIfAbsent(key, Entry::new).edit(Entry.ANY_VERSION));
    }
  }

  @Override
  public CompletableFuture<Optional<Editor>> edit(String key, Executor executor) {
    return CompletableFuture.completedFuture(edit(key));
  }

  @Override
  public Iterator<Viewer> iterator() {
    synchronized (entries) {
      return new ViewerIterator(Set.copyOf(entries.keySet()));
    }
  }

  @Override
  public boolean remove(String key) {
    requireNonNull(key);
    synchronized (entries) {
      var entry = entries.get(key);
      if (entry != null) {
        evict(entry);
        entries.remove(key);
        return true;
      }
      return false;
    }
  }

  @Override
  public void clear() {
    synchronized (entries) {
      var iter = entries.values().iterator();
      while (iter.hasNext()) {
        evict(iter.next());
        iter.remove();
      }
    }
  }

  @Override
  public void dispose() {
    clear();
  }

  @Override
  public void close() {}

  @Override
  public void flush() {}

  /**
   * Marks the given entry for eviction and decrements its last committed size. Called before
   * removal from the LRU map. Returns the current size after decrementing the evicted entry's size.
   */
  private long evict(Entry entry) {
    // Lock must be held to avoid concurrent decrements on `size` which can cause
    // evictExcessiveEntries() to evict more entries than necessary.
    assert Thread.holdsLock(entries);

    // Prevent the entry from increasing this store's size if an edit is yet to be committed.
    entry.markEvicted();

    var viewer = entry.view();
    return viewer != null ? size.addAndGet(-viewer.entrySize()) : size.get();
  }

  /** Keeps evicting entries in LRU order till size becomes <= maxSize. */
  private void evictExcessiveEntries() {
    synchronized (entries) {
      long currentSize = size.get();
      var iter = entries.values().iterator();
      while (currentSize > maxSize && iter.hasNext()) {
        currentSize = evict(iter.next());
        iter.remove();
      }
    }
  }

  private final class ViewerIterator implements Iterator<Viewer> {
    /**
     * Iterator over a snapshot of currently available keys to avoid CMEs. This however will miss
     * keys added after this iterator is returned, which is acceptable.
     */
    private final Iterator<String> keysSnapshotIterator;

    private @Nullable MemoryViewer nextViewer;
    private @Nullable MemoryViewer currentViewer;

    ViewerIterator(Set<String> keysSnapshot) {
      keysSnapshotIterator = keysSnapshot.iterator();
    }

    @Override
    @EnsuresNonNullIf(expression = "nextViewer", result = true)
    public boolean hasNext() {
      return nextViewer != null || findNext();
    }

    @Override
    public Viewer next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      var viewer = castNonNull(nextViewer);
      nextViewer = null;
      currentViewer = viewer;
      return viewer;
    }

    @Override
    public void remove() {
      var viewer = currentViewer;
      requireState(viewer != null, "next() must be called before remove()");
      currentViewer = null;
      castNonNull(viewer).removeEntry();
    }

    @EnsuresNonNullIf(expression = "nextViewer", result = true)
    private boolean findNext() {
      assert nextViewer == null;
      synchronized (entries) {
        while (keysSnapshotIterator.hasNext()) {
          var entry = entries.get(keysSnapshotIterator.next());
          var viewer = entry != null ? entry.view() : null;
          if (viewer != null) {
            nextViewer = viewer;
            return true;
          }
        }
      }
      return false;
    }
  }

  private final class Entry {
    private static final int ANY_VERSION = -1;

    private final Lock lock = new ReentrantLock();

    final String key;
    private ByteBuffer metadata = EMPTY_BUFFER;
    private ByteBuffer data = EMPTY_BUFFER;
    private @Nullable MemoryEditor currentEditor;
    private boolean evicted;

    /**
     * This entry's version, also indicating the number of committed edits. A value of 0 means the
     * entry can't be viewed as it has had 0 edits. This is the case for newly created entries
     * living in the map during their first edit.
     */
    private int version;

    Entry(String key) {
      this.key = key;
    }

    @Nullable MemoryViewer view() {
      lock.lock();
      try {
        return version > 0 ? new MemoryViewer(this, version, metadata, data) : null;
      } finally {
        lock.unlock();
      }
    }

    @Nullable MemoryEditor edit(int targetVersion) {
      lock.lock();
      try {
        if (currentEditor != null
            || (targetVersion != ANY_VERSION && targetVersion != version)
            || evicted) {
          return null;
        }

        var editor = new MemoryEditor(this);
        currentEditor = editor;
        return editor;
      } finally {
        lock.unlock();
      }
    }

    /**
     * Tells this entry it's been evicted, which prevents any ongoing edit from committing changes.
     */
    void markEvicted() {
      lock.lock();
      try {
        evicted = true;
      } finally {
        lock.unlock();
      }
    }

    boolean versionEquals(int targetVersion) {
      lock.lock();
      try {
        return version == targetVersion;
      } finally {
        lock.unlock();
      }
    }

    void commit(
        MemoryEditor editor, @Nullable ByteBuffer newMetadata, @Nullable ByteBuffer newData) {
      long entrySizeDifference;
      boolean evictAfterDiscardedFirstEdit = false;
      lock.lock();
      try {
        if (currentEditor == null) {
          return;
        }

        assert currentEditor == editor;
        currentEditor = null;
        if ((newMetadata == null && newData == null) || evicted) {
          evictAfterDiscardedFirstEdit = version == 0 && !evicted;
          return;
        }

        long oldEntrySize = (long) metadata.remaining() + data.remaining();
        if (newMetadata != null) {
          metadata = newMetadata.asReadOnlyBuffer();
        }
        if (newData != null) {
          data = newData.asReadOnlyBuffer();
        }
        entrySizeDifference = (long) metadata.remaining() + data.remaining() - oldEntrySize;
        version++;
      } finally {
        lock.unlock();

        // Evict the entry if its first edit ever was discarded. This would be inside the try block
        // above but that risks a deadlock as this lock and store's lock are held in reverse order
        // in other methods.
        if (evictAfterDiscardedFirstEdit) {
          synchronized (entries) {
            lock.lock();
            try {
              if (version == 0) { // Recheck as another edit might have been committed successfully.
                entries.remove(key, this);
              }
            } finally {
              lock.unlock();
            }
          }
        }
      }

      if (size.addAndGet(entrySizeDifference) > maxSize) {
        evictExcessiveEntries();
      }
    }
  }

  private final class MemoryViewer implements Viewer {
    final Entry entry;
    private final int entryVersion;
    private final ByteBuffer data;
    private final ByteBuffer metadata;

    MemoryViewer(Entry entry, int entryVersion, ByteBuffer metadata, ByteBuffer data) {
      this.entry = entry;
      this.entryVersion = entryVersion;
      this.data = data;
      this.metadata = metadata;
    }

    @Override
    public String key() {
      return entry.key;
    }

    @Override
    public ByteBuffer metadata() {
      return metadata.duplicate();
    }

    @Override
    public EntryReader newReader() {
      return new EntryReader() {
        private final ByteBuffer data = MemoryViewer.this.data.duplicate();

        @Override
        public int read(ByteBuffer dst) {
          requireNonNull(dst);
          synchronized (data) {
            return data.hasRemaining() ? Utils.copyRemaining(data, dst) : -1;
          }
        }

        @Override
        public CompletableFuture<Integer> read(ByteBuffer dst, Executor ignored) {
          return CompletableFuture.completedFuture(read(dst));
        }

        @Override
        public long read(List<ByteBuffer> dsts) {
          requireNonNull(dsts);
          synchronized (data) {
            if (!data.hasRemaining()) {
              return -1;
            }

            int totalRead = 0;
            for (var dst : dsts) {
              int read = Utils.copyRemaining(data, dst);
              if (read == 0) {
                break;
              }
              totalRead += read;
            }
            return totalRead;
          }
        }

        @Override
        public CompletableFuture<Long> read(List<ByteBuffer> dsts, Executor ignored) {
          return CompletableFuture.completedFuture(read(dsts));
        }
      };
    }

    @Override
    public long dataSize() {
      return data.remaining();
    }

    @Override
    public long entrySize() {
      return (long) metadata.remaining() + data.remaining();
    }

    @Override
    public Optional<Editor> edit() {
      return Optional.ofNullable(entry.edit(entryVersion));
    }

    @Override
    public CompletableFuture<Optional<Editor>> edit(Executor executor) {
      return CompletableFuture.completedFuture(edit());
    }

    @Override
    public boolean removeEntry() {
      synchronized (entries) {
        if (entry.versionEquals(entryVersion) && entries.remove(entry.key, entry)) {
          evict(entry);
          return true;
        }
      }
      return false;
    }

    @Override
    public void close() {}
  }

  private static final class MemoryEditor implements Editor, EntryWriter {
    private final Entry entry;
    private final Lock lock = new ReentrantLock();
    private final MemoryBuffer data = new MemoryBuffer();

    @GuardedBy("lock")
    private boolean isDataWritten;

    MemoryEditor(Entry entry) {
      this.entry = entry;
    }

    @Override
    public String key() {
      return entry.key;
    }

    @Override
    public EntryWriter writer() {
      return this;
    }

    @Override
    public int write(ByteBuffer src) {
      requireNonNull(src);
      lock.lock();
      try {
        isDataWritten = true;
        return data.write(src);
      } finally {
        lock.unlock();
      }
    }

    @Override
    public CompletableFuture<Integer> write(ByteBuffer src, Executor ignored) {
      return CompletableFuture.completedFuture(write(src));
    }

    @Override
    public long write(List<ByteBuffer> srcs) {
      requireNonNull(srcs);
      lock.lock();
      try {
        isDataWritten = true;
        return data.write(srcs);
      } finally {
        lock.unlock();
      }
    }

    @Override
    public CompletableFuture<Long> write(List<ByteBuffer> srcs, Executor executor) {
      return CompletableFuture.completedFuture(write(srcs));
    }

    @Override
    public void commit(ByteBuffer metadata) {
      entry.commit(this, metadata, dataIfWritten());
    }

    @Override
    public CompletableFuture<Void> commit(ByteBuffer metadata, Executor executor) {
      entry.commit(this, metadata, dataIfWritten());
      return CompletableFuture.completedFuture(null);
    }

    private @Nullable ByteBuffer dataIfWritten() {
      lock.lock();
      try {
        return isDataWritten ? ByteBuffer.wrap(data.toByteArray()) : null;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void close() {
      entry.commit(this, null, null);
    }

    private static final class MemoryBuffer extends ByteArrayOutputStream {
      MemoryBuffer() {}

      int write(ByteBuffer src) {
        int position = src.position();
        int limit = src.limit();
        int remaining = position <= limit ? limit - position : 0;
        if (remaining <= buf.length - count) {
          // Fast path, copy to buf directly.
          src.get(buf, count, remaining);
          count += remaining;
        } else {
          // Unfortunately, we can't call super.ensureCapacity(remaining) as it's private, so we'll
          // have to pass the data to super as a byte[] copy.
          var srcCopy = new byte[remaining];
          src.get(srcCopy);
          super.write(srcCopy, 0, srcCopy.length);
        }
        return remaining;
      }

      int write(List<ByteBuffer> srcs) {
        int totalRemaining = Math.toIntExact(Utils.remaining(srcs));
        if (totalRemaining <= buf.length - count) {
          // Fast path, copy to buf directly.
          for (var src : srcs) {
            int remaining = src.remaining();
            src.get(buf, count, remaining);
            count += remaining;
          }
        } else {
          int i = 0;
          int size = srcs.size();
          for (; i < size && totalRemaining > buf.length - count; i++) {
            totalRemaining -= write(srcs.get(i)); // Increments count.
          }
          // Continue with fast path.
          for (; i < size; i++) {
            var src = srcs.get(i);
            int remaining = src.remaining();
            src.get(buf, count, remaining);
            count += remaining;
          }
        }
        return totalRemaining;
      }
    }
  }
}
