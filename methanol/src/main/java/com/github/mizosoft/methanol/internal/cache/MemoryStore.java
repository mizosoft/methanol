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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@link Store} implementation that buffers entries in memory. Each entry can have at most about
 * 2GB of data.
 */
public final class MemoryStore implements Store {
  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final long maxSize;
  private final AtomicLong size = new AtomicLong();
  private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

  public MemoryStore(long maxSize) {
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
    synchronized (entries) {
      var entry = entries.get(key);
      return entry != null ? entry.view() : null;
    }
  }

  @Override
  public @Nullable Editor edit(String key) {
    synchronized (entries) {
      return entries.computeIfAbsent(key, Entry::new).edit();
    }
  }

  @Override
  public CompletableFuture<@Nullable Viewer> viewAsync(String key) {
    return CompletableFuture.completedFuture(view(key));
  }

  @Override
  public Iterator<Viewer> viewAll() {
    synchronized (entries) {
      return new ViewerIterator(Set.copyOf(entries.keySet()));
    }
  }

  @Override
  public boolean remove(String key) {
    synchronized (entries) {
      var entry = entries.get(key);
      if (entry != null) {
        unlink(entry);
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
        unlink(iter.next());
        iter.remove();
      }
    }
  }

  @Override
  public void close() {} // Nothing to do

  /**
   * Marks entry for eviction and decrements it's last committed size. Called before removal from
   * the LRU map. Returns the current size after decrementing the unlinked entry's size.
   */
  private long unlink(Entry entry) {
    // Lock must be held to avoid concurrent decrements on `size`
    // which can cause evictExcessiveEntries() to evict more entries than necessary
    assert Thread.holdsLock(entries);

    entry.markEvicted(); // Prevent the entry from increasing size if an edit is yet to be committed
    var viewer = entry.view();
    if (viewer != null) { // Entry has committed data
      return size.addAndGet(-viewer.entrySize());
    }
    return size.get();
  }

  /** Keeps evicting entries in LRU order till size becomes <= maxSize. */
  private void evictExcessiveEntries() {
    synchronized (entries) {
      long currentSize = size.get();
      var iter = entries.values().iterator();
      while (iter.hasNext() && currentSize > maxSize) {
        currentSize = unlink(iter.next());
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

    private @Nullable Viewer nextViewer;

    /** The key remove() would evict. */
    private @Nullable String currentKey;

    ViewerIterator(Set<String> keysSnapshot) {
      keysIterator = keysSnapshot.iterator();
    }

    @Override
    @EnsuresNonNullIf(expression = "nextViewer", result = true)
    public boolean hasNext() {
      return nextViewer != null || (nextViewer = viewNextEntry()) != null;
    }

    @Override
    public Viewer next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      var viewer = castNonNull(nextViewer);
      nextViewer = null;
      currentKey = viewer.key();
      return viewer;
    }

    @Override
    public void remove() {
      var key = currentKey;
      requireState(key != null, "next() must be called before remove()");
      currentKey = null;
      MemoryStore.this.remove(castNonNull(key));
    }

    private @Nullable Viewer viewNextEntry() {
      Viewer nextViewer = null;
      synchronized (entries) {
        while (keysIterator.hasNext() && nextViewer == null) {
          var entry = entries.get(keysIterator.next());
          if (entry != null) {
            nextViewer = entry.view();
          }
        }
      }
      return nextViewer;
    }
  }

  private final class Entry {
    private static final int ANY_VERSION = -1;

    final String key;
    private ByteBuffer metadata = EMPTY_BUFFER;
    private ByteBuffer data = EMPTY_BUFFER;
    private @Nullable MemoryEditor currentEditor;
    private boolean evicted;

    /** The number of committed edits. 0 means the entry can't be viewed. */
    private int version;

    /** Guards non-final fields on edits but allows concurrent reads on views. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    Entry(String key) {
      this.key = key;
    }

    @Nullable
    Viewer view() {
      lock.readLock().lock();
      try {
        return version > 0
            ? new SnapshotViewer(this, version, metadata.duplicate(), data.duplicate())
            : null;
      } finally {
        lock.readLock().unlock();
      }
    }

    @Nullable
    Editor edit() {
      return edit(ANY_VERSION);
    }

    @Nullable
    Editor edit(int targetVersion) {
      lock.writeLock().lock();
      try {
        if (currentEditor != null
            || (targetVersion != ANY_VERSION && targetVersion != version)
            || evicted) {
          // Ongoing edit or entry is modified
          return null;
        }

        var editor = new MemoryEditor(this);
        currentEditor = editor;
        return editor;
      } finally {
        lock.writeLock().unlock();
      }
    }

    /** Prevents any ongoing edit from committing it's data. */
    void markEvicted() {
      lock.writeLock().lock();
      try {
        evicted = true;
      } finally {
        lock.writeLock().unlock();
      }
    }

    void finishEdit(
        MemoryEditor editor, @Nullable ByteBuffer newMetadata, @Nullable ByteBuffer newData) {
      long previousEntrySize;
      long currentEntrySize;
      boolean evictAfterDiscardedFirstEdit = false;
      lock.writeLock().lock();
      try {
        if (currentEditor != editor) { // Unowned editor
          return;
        }

        currentEditor = null;
        if ((newMetadata == null && newData == null) || evicted) { // Discarded edit or evicted
          evictAfterDiscardedFirstEdit = !evicted && version == 0;
          return;
        }

        version++;
        previousEntrySize = (long) metadata.remaining() + data.remaining();
        if (newMetadata != null) {
          metadata = newMetadata.asReadOnlyBuffer();
        }
        if (newData != null) {
          data = newData.asReadOnlyBuffer();
        }
        currentEntrySize = (long) metadata.remaining() + data.remaining();
      } finally {
        lock.writeLock().unlock();

        // Evict the entry if it's first edit ever was discarded. This would be
        // inside the try block above but that risks a deadlock as lock.writeLock()
        // and entries lock are held in reverse order in evict(String key).
        if (evictAfterDiscardedFirstEdit) {
          synchronized (entries) {
            lock.writeLock().lock();
            try {
              if (version == 0) { // Recheck as another edit might have been made successfully
                entries.remove(key);
              }
            } finally {
              lock.writeLock().unlock();
            }
          }
        }
      }

      long netEntrySize = currentEntrySize - previousEntrySize; // Might be negative
      if (size.addAndGet(netEntrySize) > maxSize) {
        evictExcessiveEntries();
      }
    }
  }

  /** Views a snapshot of committed entry's metadata/data. */
  private static final class SnapshotViewer implements Viewer {
    private final Entry entry;
    private final int snapshotVersion;
    private final ByteBuffer data;
    private final ByteBuffer metadata;

    SnapshotViewer(Entry entry, int snapshotVersion, ByteBuffer metadata, ByteBuffer data) {
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

    private ByteBuffer metadata = EMPTY_BUFFER;

    /** Guards writes to {@code buffer} but allows concurrent reads by viewers. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

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
      lock.writeLock().lock();
      try {
        // Make a defensive copy, reusing previous buffer if big enough
        var myMetadata = this.metadata;
        int len = metadata.remaining();
        if (myMetadata.capacity() < len) {
          myMetadata = ByteBuffer.allocate(len);
          this.metadata = myMetadata;
        }
        Utils.copyRemaining(metadata, myMetadata.clear());
        myMetadata.flip();
      } finally {
        lock.writeLock().unlock();
      }
    }

    @Override
    public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
      lock.writeLock().lock();
      try {
        return CompletableFuture.completedFuture(data.write(position, src));
      } finally {
        lock.writeLock().unlock();
      }
    }

    @Override
    public Viewer view() {
      return new LiveViewer();
    }

    @Override
    public void discard() {
      entry.finishEdit(this, null, null);
    }

    @Override
    public void close() {
      var newMetadata = metadata.hasRemaining() ? metadata : null;
      ByteBuffer newData;
      lock.readLock().lock();
      try {
        newData = data.writtenCount() > 0 ? data.snapshot() : null;
      } finally {
        lock.readLock().unlock();
      }
      entry.finishEdit(this, newMetadata, newData);
    }

    /** Views data currently being edited. */
    private final class LiveViewer implements Viewer {
      LiveViewer() {}

      @Override
      public String key() {
        return entry.key;
      }

      @Override
      public ByteBuffer metadata() {
        lock.readLock().lock();
        try {
          return metadata.duplicate();
        } finally {
          lock.readLock().unlock();
        }
      }

      @Override
      public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
        lock.readLock().lock();
        try {
          return CompletableFuture.completedFuture(data.read(position, dst));
        } finally {
          lock.readLock().unlock();
        }
      }

      @Override
      public long dataSize() {
        lock.readLock().lock();
        try {
          return data.writtenCount();
        } finally {
          lock.readLock().unlock();
        }
      }

      @Override
      public long entrySize() {
        lock.readLock().lock();
        try {
          return (long) metadata.remaining() + data.writtenCount();
        } finally {
          lock.readLock().unlock();
        }
      }

      @Override
      public @Nullable Editor edit() {
        // An edit is always still in progress (by the editor that created this viewer)
        return null;
      }

      @Override
      public void close() {}
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

    int read(long position, ByteBuffer dst) {
      if (position >= output.fence()) {
        return -1;
      }
      int available = output.fence() - (int) position;
      int readCount = Math.min(available, dst.remaining());
      dst.put(output.array(), (int) position, readCount);
      return readCount;
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
