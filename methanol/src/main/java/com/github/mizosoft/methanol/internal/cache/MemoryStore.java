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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
  public Optional<Executor> executor() {
    return Optional.empty();
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
  public @Nullable Viewer view(String key) {
    requireNonNull(key);
    synchronized (entries) {
      var entry = entries.get(key);
      return entry != null ? entry.view() : null;
    }
  }

  @Override
  public CompletableFuture<@Nullable Viewer> viewAsync(String key) {
    return CompletableFuture.completedFuture(view(key));
  }

  @Override
  public @Nullable Editor edit(String key) {
    requireNonNull(key);
    synchronized (entries) {
      return entries.computeIfAbsent(key, Entry::new).edit();
    }
  }

  @Override
  public CompletableFuture<@Nullable Editor> editAsync(String key) {
    return CompletableFuture.completedFuture(edit(key));
  }

  @Override
  public Iterator<Viewer> viewAll() {
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
   * Marks entry for eviction and decrements it's last committed size. Called before removal from
   * the LRU map. Returns the current size after decrementing the evicted entry's size.
   */
  private long evict(Entry entry) {
    // Lock must be held to avoid concurrent decrements on `size`
    // which can cause evictExcessiveEntries() to evict more entries than necessary.
    assert Thread.holdsLock(entries);

    entry.markEvicted(); // Prevent the entry from increasing size if an edit is yet to be committed
    var viewer = entry.view();
    return viewer != null // Entry has committed data
        ? size.addAndGet(-viewer.entrySize())
        : size.get();
  }

  /** Keeps evicting entries in LRU order till size becomes <= maxSize. */
  private void evictExcessiveEntries() {
    synchronized (entries) {
      long currentSize = size.get();
      var iter = entries.values().iterator();
      while (iter.hasNext() && currentSize > maxSize) {
        currentSize = evict(iter.next());
        iter.remove();
      }
    }
  }

  private final class ViewerIterator implements Iterator<Viewer> {
    /**
     * Iterator over a snapshot of currently available keys to avoid CMEs. This however will miss
     * keys added after this iterator is returned, which is OK.
     */
    private final Iterator<String> keysIterator;

    private @Nullable MemoryViewer nextViewer;

    /** The entry remove() would evict. */
    private @Nullable Entry currentEntry;

    ViewerIterator(Set<String> keysSnapshot) {
      keysIterator = keysSnapshot.iterator();
    }

    @Override
    @EnsuresNonNullIf(expression = "nextViewer", result = true)
    public boolean hasNext() {
      return nextViewer != null || findNextViewer();
    }

    @Override
    public Viewer next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      var viewer = castNonNull(nextViewer);
      nextViewer = null;
      currentEntry = viewer.entry;
      return viewer;
    }

    @Override
    public void remove() {
      var entry = currentEntry;
      requireState(entry != null, "next() must be called before remove()");
      currentEntry = null;
      synchronized (entries) {
        // Only apply eviction if entry was still in the map
        if (entries.remove(entry.key, entry)) {
          evict(entry);
        }
      }
    }

    private boolean findNextViewer() {
      assert nextViewer == null;
      synchronized (entries) {
        while (keysIterator.hasNext()) {
          var entry = entries.get(keysIterator.next());
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

    /** The number of committed edits. 0 means the entry can't be viewed. */
    private int version;

    Entry(String key) {
      this.key = key;
    }

    @Nullable
    MemoryViewer view() {
      lock.lock();
      try {
        return version > 0
            ? new MemoryViewer(this, version, metadata.duplicate(), data.duplicate())
            : null;
      } finally {
        lock.unlock();
      }
    }

    @Nullable
    MemoryEditor edit() {
      return edit(ANY_VERSION);
    }

    @Nullable
    MemoryEditor edit(int targetVersion) {
      lock.lock();
      try {
        if (currentEditor == null
            && (targetVersion == ANY_VERSION || targetVersion == version)
            && !evicted) {
          var editor = new MemoryEditor(this);
          currentEditor = editor;
          return editor;
        }
        return null; // Ongoing edit or entry is modified
      } finally {
        lock.unlock();
      }
    }

    /** Prevents any ongoing edit from committing it's data. */
    void markEvicted() {
      lock.lock();
      try {
        evicted = true;
      } finally {
        lock.unlock();
      }
    }

    void commitEdit(
        MemoryEditor editor, @Nullable ByteBuffer newMetadata, @Nullable ByteBuffer newData) {
      long entrySize;
      long previousEntrySize;
      boolean evictAfterDiscardedFirstEdit = false;
      lock.lock();
      try {
        if (currentEditor != editor) { // Unowned editor
          return;
        }
        currentEditor = null;
        if ((newMetadata == null && newData == null) || evicted) { // Discarded edit or evicted
          evictAfterDiscardedFirstEdit = !evicted && version == 0;
          return;
        }

        previousEntrySize = (long) metadata.remaining() + data.remaining();
        if (newMetadata != null) {
          metadata = newMetadata.asReadOnlyBuffer();
        }
        if (newData != null) {
          data = newData.asReadOnlyBuffer();
        }
        entrySize = (long) metadata.remaining() + data.remaining();
        version++;
      } finally {
        lock.unlock();

        // Evict the entry if its first edit ever was discarded. This would be
        // inside the try block above but that risks a deadlock as lock
        // and entries lock are held in reverse order in other methods.
        if (evictAfterDiscardedFirstEdit) {
          synchronized (entries) {
            lock.lock();
            try {
              if (version == 0) { // Recheck as another edit might have been made successfully
                entries.remove(key, this);
              }
            } finally {
              lock.unlock();
            }
          }
        }
      }

      long netEntrySize = entrySize - previousEntrySize; // Might be negative
      if (size.addAndGet(netEntrySize) > maxSize) {
        evictExcessiveEntries();
      }
    }
  }

  private static final class MemoryViewer implements Viewer {
    final Entry entry;
    private final int snapshotVersion;
    private final ByteBuffer data;
    private final ByteBuffer metadata;

    MemoryViewer(Entry entry, int snapshotVersion, ByteBuffer metadata, ByteBuffer data) {
      this.entry = entry;
      this.snapshotVersion = snapshotVersion;
      this.data = data;
      this.metadata = metadata;
    }

    @Override
    public String key() {
      return entry.key;
    }

    @Override
    public final ByteBuffer metadata() {
      return metadata.duplicate(); // Duplicate for independent position/limit
    }

    @Override
    public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
      requireNonNull(dst);
      int readCount;
      if (position < data.limit()) {
        // duplicate to change position independently in case of concurrent reads
        var duplicateData = data.duplicate();
        readCount = Utils.copyRemaining(duplicateData.position((int) position), dst);
      } else {
        readCount = -1;
      }
      return CompletableFuture.completedFuture(readCount);
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
    public @Nullable Editor edit() {
      return entry.edit(snapshotVersion);
    }

    @Override
    public void close() {}
  }

  private static final class MemoryEditor implements Editor {
    private final Entry entry;
    private final GrowableBuffer data = new GrowableBuffer();
    private final Lock lock = new ReentrantLock();

    private ByteBuffer metadata = EMPTY_BUFFER;
    private boolean metadataIsUpdated;
    private boolean committed;

    MemoryEditor(Entry entry) {
      this.entry = entry;
    }

    @Override
    public String key() {
      return entry.key;
    }

    @Override
    public void metadata(ByteBuffer metadata) {
      requireNonNull(metadata);
      lock.lock();
      try {
        requireNotCommitted();
        this.metadata = Utils.copy(metadata, this.metadata);
        metadataIsUpdated = true;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
      requireNonNull(src);
      lock.lock();
      try {
        requireNotCommitted();
        return CompletableFuture.completedFuture(data.write(position, src));
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void commit() {
      lock.lock();
      try {
        committed = true;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void close() {
      ByteBuffer newMetadata;
      ByteBuffer newData;
      lock.lock();
      try {
        if (committed) {
          newMetadata = metadataIsUpdated ? Utils.copy(metadata) : null;
          newData = data.writtenCount() > 0 ? data.snapshot() : null;
        } else {
          newMetadata = null;
          newData = null;
        }
      } finally {
        lock.unlock();
      }
      entry.commitEdit(this, newMetadata, newData);
    }

    private void requireNotCommitted() {
      requireState(!committed, "committed");
    }
  }

  /** Growable buffer allowing writing/reading to arbitrary positions within last-written bounds. */
  private static final class GrowableBuffer {
    private final SeekableByteArrayOutputStream output = new SeekableByteArrayOutputStream();

    int write(long position, ByteBuffer src) {
      output.position(position); // Handles out-of-range position
      int writeCount = src.remaining();
      if (src.hasArray()) {
        output.write(src.array(), src.arrayOffset() + src.position(), writeCount);
        src.position(writeCount);
      } else {
        byte[] srcCopy = new byte[writeCount];
        src.get(srcCopy);
        output.write(srcCopy);
      }
      return writeCount;
    }

    int writtenCount() {
      return output.fence();
    }

    ByteBuffer snapshot() {
      return ByteBuffer.allocate(output.fence()).put(output.array(), 0, output.fence()).flip();
    }

    /** ByteArrayOutputStream that exposes underlying array buffer & write position. */
    @SuppressWarnings("UnsynchronizedOverridesSynchronized") // Synchronization is done by caller
    private static final class SeekableByteArrayOutputStream extends ByteArrayOutputStream {
      /** Position right after the last written byte. */
      private int fence;

      SeekableByteArrayOutputStream() {}

      /** Single-byte writes are not used. */
      @Override
      public void write(int b) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void write(byte[] b, int off, int len) {
        super.write(b, off, len);
        fence = Math.max(fence, count);
      }

      @Override
      public void write(byte[] b) {
        write(b, 0, b.length);
      }

      byte[] array() {
        return buf;
      }

      int fence() {
        return fence;
      }

      void position(long position) {
        requireArgument(position >= 0 && position <= fence, "position out of range: %d", position);
        count = (int) position;
      }
    }
  }
}
