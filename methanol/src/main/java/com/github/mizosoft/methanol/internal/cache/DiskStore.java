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

import static com.github.mizosoft.methanol.internal.Utils.requireNonNegativeDuration;
import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import com.github.mizosoft.methanol.internal.DebugUtils;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.concurrent.SerialExecutor;
import com.github.mizosoft.methanol.internal.function.ThrowingRunnable;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.internal.util.Compare;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A persistent {@link Store} implementation that saves entries on disk under a specified directory.
 * A {@code DiskStore} instance assumes exclusive ownership of its directory; only a single {@code
 * DiskStore} from a single JVM process can safely operate on a given directory. This assumption is
 * cooperatively enforced among {@code DiskStore} instances such that attempting to initialize a
 * store with a directory that is in use by another store in the same or a different JVM process
 * will cause an {@code IOException} to be thrown.
 *
 * <p>The store keeps track of entries known to it across sessions by maintaining an on-disk
 * hashtable called the index. As changes are made to the store by adding, accessing or removing
 * entries, the index is transparently updated in a time-limited manner. By default, there's at most
 * one index update every 2 seconds. This rate can be changed by setting the system property: {@code
 * com.github.mizosoft.methanol.internal.cache.DiskStore.indexUpdateDelayMillis}. Setting a small
 * delay can result in too often index updates, which extracts a noticeable toll on IO and CPU,
 * especially if there's a relatively large number of entries (updating entails reconstructing then
 * rewriting the whole index). On the other hand, scarcely updating the index affords less
 * durability against crashes as entries that aren't indexed are dropped on initialization. Calling
 * the {@code flush} method forces an index update, regardless of the time limit.
 *
 * <p>To ensure entries are not lost across sessions, a store must be {@link #close() closed} after
 * it has been done with. The {@link #dispose()} method can be called to atomically close the store
 * and clear its directory if persistence isn't needed (e.g. using temp directories for storage). A
 * closed store usually throws an {@code IllegalStateException} when used.
 */
public final class DiskStore implements Store {
  /*
   * The store's layout on disk is as follows:
   *
   *   - An 'index' file.
   *   - A corresponding file for each entry with its name being the hex string of the first 80
   *     bits of the key's SHA-245, concatenated to the suffix '.ch3oh'.
   *   - A '.lock' indicating that the directory is currently being used.
   *
   * The index and entry files are formatted as follows (in slang BNF):
   *
   *   <index> = <index-header> <index-entry>*
   *   <index-header> = 8-bytes-index-magic
   *                    4-bytes-store-version
   *                    4-bytes-app-version
   *                    8-bytes-entry-count
   *   <index-entry> = 10-bytes-entry-hash
   *                        8-bytes-last-used-millis (maintained for LRU eviction)
   *                        8-bytes-entry-size
   *
   *   <entry> = <data> <entry-epilogue>
   *   <data> = byte*
   *   <entry-epilogue> = <key> <metadata> <entry-trailer>
   *   <key> = utf8-byte*
   *   <metadata> = byte*
   *   <entry-trailer> = 8-bytes-entry-magic
   *                     4-bytes-store-version
   *                     4-bytes-app-version
   *                     4-bytes-key-size
   *                     4-bytes-metadata-size
   *                     8-bytes-data-size
   *
   * Having the key, metadata & their sizes at the end of the file makes it easier and quicker to
   * update an entry when only its metadata block changes (and possibly its key in case there's a
   * hash collision). In such case, an entry update only overwrites <entry-epilogue> next to an
   * existing <data>, truncating the file if necessary. Having an <entry-trailer> instead of an
   * <entry-header> allows validating the entry file and knowing its key & metadata sizes in a
   * single read.
   *
   * An effort is made to ensure store operations on disk are atomic. Index and entry writers first
   * do their work on a temp file. After they're done, a channel::force is issued then the previous
   * version of the file, if any, is atomically replaced. Viewers opened for an entry see a constant
   * snapshot of that entry's data even if the entry is removed or edited one or more times.
   */

  private static final Logger logger = System.getLogger(DiskStore.class.getName());

  /** Indicates whether a task is currently being run by the index executor. Used for debugging. */
  private static final ThreadLocal<Boolean> isIndexExecutor = ThreadLocal.withInitial(() -> false);

  /**
   * The max number of entries an index file can contain. This caps on what to be read from the
   * index so that an {@code OutOfMemoryError} is not thrown when reading some corrupt index file.
   */
  private static final int MAX_ENTRY_COUNT = 1_000_000;

  static final long INDEX_MAGIC = 0x6d657468616e6f6cL;
  static final long ENTRY_MAGIC = 0x7b6368332d6f687dL;
  static final int STORE_VERSION = 1;
  static final int INDEX_HEADER_SIZE = 2 * Long.BYTES + 2 * Integer.BYTES;
  static final int INDEX_ENTRY_SIZE = Hash.BYTES + 2 * Long.BYTES;
  static final int ENTRY_TRAILER_SIZE = 2 * Long.BYTES + 4 * Integer.BYTES;

  static final String LOCK_FILENAME = ".lock";
  static final String INDEX_FILENAME = "index";
  static final String TEMP_INDEX_FILENAME = "index.tmp";
  static final String ENTRY_FILE_SUFFIX = ".ch3oh";
  static final String TEMP_ENTRY_FILE_SUFFIX = ".ch3oh.tmp";
  static final String ISOLATED_FILE_PREFIX = "RIP_";

  private final long maxSize;
  private final int appVersion;
  private final Path directory;
  private final Executor executor;
  private final Hasher hasher;
  private final SerialExecutor indexExecutor;
  private final IndexOperator indexOperator;
  private final IndexWriteScheduler indexWriteScheduler;
  private final EvictionScheduler evictionScheduler;
  private final DirectoryLock directoryLock;
  private final ConcurrentHashMap<Hash, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicLong size = new AtomicLong();

  /**
   * A monotonic clock used for ordering entries based on recency. The clock is not completely
   * monotonic, however, as the clock value can overflow. But a signed long gives us about 300 years
   * of monotonicity assuming the clock is incremented every 1 ns, which is not bad at all.
   */
  private final AtomicLong lruClock = new AtomicLong();

  private final ReadWriteLock closeLock = new ReentrantReadWriteLock();

  @GuardedBy("closeLock")
  private boolean closed;

  private DiskStore(Builder builder, boolean debugIndexOps) throws IOException {
    maxSize = builder.maxSize();
    appVersion = builder.appVersion();
    directory = builder.directory();
    executor = builder.executor();
    hasher = builder.hasher();
    indexExecutor =
        new SerialExecutor(debugIndexOps ? toDebuggingIndexExecutorDelegate(executor) : executor);
    indexOperator =
        debugIndexOps
            ? new DebugIndexOperator(directory, appVersion)
            : new IndexOperator(directory, appVersion);
    indexWriteScheduler =
        new IndexWriteScheduler(
            indexOperator,
            indexExecutor,
            this::indexEntriesSnapshot,
            builder.indexUpdateDelay(),
            builder.delayer(),
            builder.clock());
    evictionScheduler = new EvictionScheduler(this, executor);

    if (debugIndexOps) {
      isIndexExecutor.set(true);
    }
    try {
      directoryLock = initialize();
    } finally {
      if (debugIndexOps) {
        isIndexExecutor.set(false);
      }
    }
  }

  Clock clock() {
    return indexWriteScheduler.clock();
  }

  Delayer delayer() {
    return indexWriteScheduler.delayer();
  }

  private DirectoryLock initialize() throws IOException {
    var lock = DirectoryLock.acquire(Files.createDirectories(directory));

    long totalSize = 0L;
    long maxLastUsed = -1;
    for (var indexEntry : indexOperator.recoverEntries()) {
      entries.put(indexEntry.hash, new Entry(indexEntry));
      totalSize += indexEntry.size;
      maxLastUsed = Math.max(maxLastUsed, indexEntry.lastUsed);
    }
    size.set(totalSize);
    lruClock.set(maxLastUsed + 1);

    // Make sure we start within bounds.
    if (totalSize > maxSize) {
      evictionScheduler.schedule();
    }
    return lock;
  }

  public Path directory() {
    return directory;
  }

  @Override
  public long maxSize() {
    return maxSize;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.of(executor);
  }

  @Override
  public Optional<Viewer> view(String key) throws IOException {
    requireNonNull(key);
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      var entry = entries.get(hasher.hash(key));
      return Optional.ofNullable(entry != null ? entry.view(key) : null);
    } finally {
      closeLock.readLock().unlock();
    }
  }

  @Override
  public Optional<Editor> edit(String key) throws IOException {
    requireNonNull(key);
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      return Optional.ofNullable(
          entries.computeIfAbsent(hasher.hash(key), Entry::new).edit(key, Entry.ANY_VERSION));
    } finally {
      closeLock.readLock().unlock();
    }
  }

  @Override
  public Iterator<Viewer> iterator() {
    return new ConcurrentViewerIterator();
  }

  @Override
  public boolean remove(String key) throws IOException {
    requireNonNull(key);
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      var entry = entries.get(hasher.hash(key));
      if (entry != null) {
        var versionHolder = new int[1];
        var keyIfKnown = entry.keyIfKnown(versionHolder);
        if (keyIfKnown == null || key.equals(keyIfKnown) || key.equals(entry.currentEditorKey())) {
          return removeEntry(entry, versionHolder[0]);
        }
      }
      return false;
    } finally {
      closeLock.readLock().unlock();
    }
  }

  @Override
  public void clear() throws IOException {
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      for (var entry : entries.values()) {
        removeEntry(entry);
      }
    } finally {
      closeLock.readLock().unlock();
    }
  }

  @Override
  public long size() {
    return size.get();
  }

  @Override
  public void dispose() throws IOException {
    doClose(true);
    size.set(0);
  }

  @Override
  public void close() throws IOException {
    doClose(false);
  }

  private void doClose(boolean disposing) throws IOException {
    closeLock.writeLock().lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
    } finally {
      closeLock.writeLock().unlock();
    }

    // Make sure our final index write captures each entry's final state.
    entries.values().forEach(Entry::freeze);

    try (directoryLock) {
      if (disposing) {
        // Shutdown the scheduler to avoid overlapping an index write with store directory deletion.
        indexWriteScheduler.shutdown();
        deleteStoreContent(directory);
      } else {
        evictExcessiveEntries();
        indexWriteScheduler.forceSchedule();
        indexWriteScheduler.shutdown();
      }
    }
    indexExecutor.shutdown();
    evictionScheduler.shutdown();
    entries.clear();
  }

  @Override
  public void flush() throws IOException {
    indexWriteScheduler.forceSchedule();
  }

  private Set<IndexEntry> indexEntriesSnapshot() {
    var snapshot = new HashSet<IndexEntry>();
    for (var entry : entries.values()) {
      var indexEntry = entry.toIndexEntry();
      if (indexEntry != null) {
        snapshot.add(indexEntry);
      }
    }
    return Collections.unmodifiableSet(snapshot);
  }

  private boolean removeEntry(Entry entry) throws IOException {
    return removeEntry(entry, Entry.ANY_VERSION);
  }

  /**
   * Atomically evicts the given entry and decrements its size, returning {@code true} if the entry
   * was evicted by this call.
   */
  private boolean removeEntry(Entry entry, int targetVersion) throws IOException {
    long evictedSize = evict(entry, targetVersion);
    if (evictedSize >= 0) {
      size.addAndGet(-evictedSize);
      return true;
    }
    return false;
  }

  /**
   * Atomically evicts the given entry if it matches the given version, returning its last committed
   * size if evicted by this call or -1 otherwise.
   */
  private long evict(Entry entry, int targetVersion) throws IOException {
    long evictedSize = entry.evict(targetVersion);
    if (evictedSize >= 0) {
      entries.remove(entry.hash, entry);
      indexWriteScheduler.trySchedule();
    }
    return evictedSize;
  }

  /** Attempts to call {@link #evictExcessiveEntries()} if not closed. */
  private boolean evictExcessiveEntriesIfOpen() throws IOException {
    closeLock.readLock().lock();
    try {
      if (closed) {
        return false;
      }
      evictExcessiveEntries();
      return true;
    } finally {
      closeLock.readLock().unlock();
    }
  }

  /** Keeps evicting entries in LRU order till the size bound is satisfied. */
  private void evictExcessiveEntries() throws IOException {
    Iterator<Entry> lruIterator = null;
    for (long currentSize = size.get(); currentSize > maxSize; ) {
      if (lruIterator == null) {
        lruIterator = entriesSnapshotInLruOrder().iterator();
      }
      if (!lruIterator.hasNext()) {
        break;
      }

      long evictedSize = evict(lruIterator.next(), Entry.ANY_VERSION);
      if (evictedSize >= 0) {
        currentSize = size.addAndGet(-evictedSize);
      } else {
        // Get fresh size in case of eviction races.
        currentSize = size.get();
      }
    }
  }

  private Collection<Entry> entriesSnapshotInLruOrder() {
    var lruEntries = new TreeMap<IndexEntry, Entry>(IndexEntry.LRU_ORDER);
    for (var entry : entries.values()) {
      var indexEntry = entry.toIndexEntry();
      if (indexEntry != null) {
        lruEntries.put(indexEntry, entry);
      }
    }
    return Collections.unmodifiableCollection(lruEntries.values());
  }

  private void requireNotClosed() {
    assert holdsCloseLock();
    requireState(!closed, "closed");
  }

  private boolean holdsCloseLock() {
    var lock = (ReentrantReadWriteLock) closeLock;
    return lock.isWriteLocked() || lock.getReadLockCount() > 0;
  }

  int indexWriteCount() {
    requireState(indexOperator instanceof DebugIndexOperator, "not debugging!");
    return ((DebugIndexOperator) indexOperator).writeCount();
  }

  long lruTime() {
    return lruClock.get();
  }

  private static Executor toDebuggingIndexExecutorDelegate(Executor delegate) {
    return runnable ->
        delegate.execute(
            () -> {
              isIndexExecutor.set(true);
              try {
                runnable.run();
              } finally {
                isIndexExecutor.set(false);
              }
            });
  }

  private static void checkValue(long expected, long found, String msg)
      throws StoreCorruptionException {
    if (expected != found) {
      throw new StoreCorruptionException(
          format("%s; expected: %#x, found: %#x", msg, expected, found));
    }
  }

  private static void checkValue(boolean valueIsValid, String msg, long value)
      throws StoreCorruptionException {
    if (!valueIsValid) {
      throw new StoreCorruptionException(format("%s: %d", msg, value));
    }
  }

  private static int getNonNegativeInt(ByteBuffer buffer) throws StoreCorruptionException {
    int value = buffer.getInt();
    checkValue(value >= 0, "expected a value >= 0", value);
    return value;
  }

  private static long getNonNegativeLong(ByteBuffer buffer) throws StoreCorruptionException {
    long value = buffer.getLong();
    checkValue(value >= 0, "expected a value >= 0", value);
    return value;
  }

  private static long getPositiveLong(ByteBuffer buffer) throws StoreCorruptionException {
    long value = buffer.getLong();
    checkValue(value > 0, "expected a positive value", value);
    return value;
  }

  private static @Nullable Hash entryFileToHash(String filename) {
    assert filename.endsWith(ENTRY_FILE_SUFFIX) || filename.endsWith(TEMP_ENTRY_FILE_SUFFIX);
    int suffixLength =
        filename.endsWith(ENTRY_FILE_SUFFIX)
            ? ENTRY_FILE_SUFFIX.length()
            : TEMP_ENTRY_FILE_SUFFIX.length();
    return Hash.tryParse(filename.substring(0, filename.length() - suffixLength));
  }

  private static void replace(Path source, Path target) throws IOException {
    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING);
  }

  private static void deleteStoreContent(Path directory) throws IOException {
    // Retain the lock file as we're still using the directory.
    var lockFile = directory.resolve(LOCK_FILENAME);
    try (var stream = Files.newDirectoryStream(directory, file -> !file.equals(lockFile))) {
      for (var file : stream) {
        safeDeleteIfExists(file);
      }
    } catch (DirectoryIteratorException e) {
      throw e.getCause();
    }
  }

  /**
   * Deletes the given file in isolation from its original name. This is done by randomly renaming
   * it beforehand.
   *
   * <p>Typically, Windows denys access to names of files deleted while having open handles (these
   * are deletable when opened with FILE_SHARE_DELETE, which is NIO's case). The reason seems to be
   * that 'deletion' in such case merely tags the file for physical deletion when all open handles
   * are closed. However, it appears that handles in Windows are associated with the names of files
   * they're opened for (https://devblogs.microsoft.com/oldnewthing/20040607-00/?p=38993).
   *
   * <p>This causes problems when an entry is deleted while being viewed. We're prevented from using
   * that entry's file name in case it's recreated (i.e. by committing an edit) while at least one
   * viewer is still open. The solution is to randomly rename these files before deletion, so the OS
   * associates any open handles with that random name instead. The original name is reusable
   * thereafter.
   */
  private static void isolatedDeleteIfExists(Path file) throws IOException {
    try {
      Files.deleteIfExists(isolate(file));
    } catch (NoSuchFileException ignored) {
      // This can be thrown by isolate(Path), meaning the file is already gone!
    }
  }

  private static Path isolate(Path file) throws IOException {
    while (true) {
      var randomFilename =
          ISOLATED_FILE_PREFIX + Long.toHexString(ThreadLocalRandom.current().nextLong());
      try {
        return Files.move(file, file.resolveSibling(randomFilename), ATOMIC_MOVE);
      } catch (FileAlreadyExistsException | AccessDeniedException filenameAlreadyInUse) {
        // We can then try again with a new random name. Note that an AccessDeniedException is
        // thrown on a name clash with another 'isolated' file that still has open handles.
      }
    }
  }

  /**
   * Deletes the given file with {@link DiskStore#isolatedDeleteIfExists(Path)} if it's an entry
   * file (and thus may have open handles), otherwise deletes it with {@link
   * Files#deleteIfExists(Path)}.
   */
  private static void safeDeleteIfExists(Path file) throws IOException {
    var filenameComponent = file.getFileName();
    var filename = filenameComponent != null ? filenameComponent.toString() : "";
    if (filename.endsWith(ENTRY_FILE_SUFFIX)) {
      isolatedDeleteIfExists(file);
    } else if (filename.startsWith(ISOLATED_FILE_PREFIX)) {
      try {
        Files.deleteIfExists(file);
      } catch (AccessDeniedException ignored) {
        // An isolated file can be awaiting deletion if it has open handles. In this case, an
        // AccessDeniedException is always thrown on Windows, so there's nothing we can do.
      }
    } else {
      Files.deleteIfExists(file);
    }
  }

  private static void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      logger.log(Level.WARNING, "Exception thrown when closing: " + closeable, e);
    }
  }

  private static void deleteIfExistsQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      logger.log(Level.WARNING, "Exception thrown when deleting: " + path, e);
    }
  }

  private static boolean keyMismatches(
      @Nullable String keyIfKnown, @Nullable String expectedKeyIfKnown) {
    return keyIfKnown != null
        && expectedKeyIfKnown != null
        && !keyIfKnown.equals(expectedKeyIfKnown);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  private final class ConcurrentViewerIterator implements Iterator<Viewer> {
    private final Iterator<Entry> entryIterator = entries.values().iterator();

    private @Nullable Viewer nextViewer;
    private @Nullable Viewer currentViewer;

    ConcurrentViewerIterator() {}

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
      try {
        castNonNull(viewer).removeEntry();
      } catch (IOException e) {
        logger.log(Level.WARNING, "Exception thrown when removing entry", e);
      }
    }

    @EnsuresNonNullIf(expression = "nextViewer", result = true)
    private boolean findNext() {
      while (entryIterator.hasNext()) {
        var entry = entryIterator.next();
        try {
          var viewer = view(entry);
          if (viewer != null) {
            nextViewer = viewer;
            return true;
          }
        } catch (IOException e) {
          // Try next entry.
          logger.log(Level.WARNING, "Exception thrown when iterating over entries", e);
        } catch (IllegalStateException e) {
          // Handle closure gracefully by ending iteration.
          return false;
        }
      }
      return false;
    }

    private @Nullable Viewer view(Entry entry) throws IOException {
      closeLock.readLock().lock();
      try {
        requireState(!closed, "closed");
        return entry.view(null);
      } finally {
        closeLock.readLock().unlock();
      }
    }
  }

  /** A function that computes an 80-bit hash from a string key. */
  @FunctionalInterface
  public interface Hasher {
    /** A Hasher returning the first 80 bits of the SHA-256 of the key's UTF-8 encoded bytes. */
    Hasher TRUNCATED_SHA_256 = Hasher::truncatedSha256Hash;

    Hash hash(String key);

    private static Hash truncatedSha256Hash(String key) {
      var digest = sha256Digest();
      digest.update(UTF_8.encode(key));
      return new Hash(ByteBuffer.wrap(digest.digest()).limit(Hash.BYTES));
    }

    // TODO we can use a MessageDigest as a cloning template to avoid service lookup every time.
    private static MessageDigest sha256Digest() {
      try {
        return MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new UnsupportedOperationException("SHA-256 not available!", e);
      }
    }
  }

  private static class IndexOperator {
    private final Path directory;
    private final Path indexFile;
    private final Path tempIndexFile;
    private final int appVersion;

    IndexOperator(Path directory, int appVersion) {
      this.directory = directory;
      this.indexFile = directory.resolve(INDEX_FILENAME);
      this.tempIndexFile = directory.resolve(TEMP_INDEX_FILENAME);
      this.appVersion = appVersion;
    }

    Set<IndexEntry> recoverEntries() throws IOException {
      var diskEntries = scanDirectoryForEntries();
      var indexEntries = readOrCreateIndex();
      var retainedIndexEntries = new HashSet<IndexEntry>(indexEntries.size());
      var filesToDelete = new HashSet<Path>();
      for (var entry : indexEntries) {
        var entryFiles = diskEntries.get(entry.hash);
        if (entryFiles != null) {
          if (entryFiles.cleanFile != null) {
            retainedIndexEntries.add(entry);
          }
          if (entryFiles.dirtyFile != null) {
            // Delete trails of unsuccessful edits.
            filesToDelete.add(entryFiles.dirtyFile);
          }
        }
      }

      // Delete entries found on disk but not referenced by the index.
      // TODO consider trying to recover these entries.
      if (retainedIndexEntries.size() != diskEntries.size()) {
        var untrackedEntries = new HashMap<>(diskEntries);
        retainedIndexEntries.forEach(entries -> untrackedEntries.remove(entries.hash));
        for (var entryFiles : untrackedEntries.values()) {
          if (entryFiles.cleanFile != null) {
            filesToDelete.add(entryFiles.cleanFile);
          }
          if (entryFiles.dirtyFile != null) {
            filesToDelete.add(entryFiles.dirtyFile);
          }
        }
      }

      for (var file : filesToDelete) {
        safeDeleteIfExists(file);
      }
      return Collections.unmodifiableSet(retainedIndexEntries);
    }

    private Set<IndexEntry> readOrCreateIndex() throws IOException {
      try {
        return readIndex();
      } catch (NoSuchFileException e) {
        return Set.of();
      } catch (StoreCorruptionException | EOFException e) {
        // TODO consider trying to rebuild the index from a directory scan instead.
        logger.log(Level.WARNING, "Dropping store content due to an unreadable index", e);

        deleteStoreContent(directory);
        return Set.of();
      }
    }

    Set<IndexEntry> readIndex() throws IOException {
      try (var channel = FileChannel.open(indexFile, READ)) {
        var header = StoreIO.readNBytes(channel, INDEX_HEADER_SIZE);
        checkValue(INDEX_MAGIC, header.getLong(), "not in index format");
        checkValue(STORE_VERSION, header.getInt(), "unrecognized store version");
        checkValue(appVersion, header.getInt(), "unrecognized app version");

        long entryCount = header.getLong();
        checkValue(
            entryCount >= 0 && entryCount <= MAX_ENTRY_COUNT, "invalid entry count", entryCount);
        if (entryCount == 0) {
          return Set.of();
        }

        int intEntryCount = (int) entryCount;
        int entryTableSize = intEntryCount * INDEX_ENTRY_SIZE;
        var entryTable = StoreIO.readNBytes(channel, entryTableSize);
        var entries = new HashSet<IndexEntry>(intEntryCount);
        for (int i = 0; i < intEntryCount; i++) {
          entries.add(new IndexEntry(entryTable));
        }
        return Collections.unmodifiableSet(entries);
      }
    }

    void writeIndex(Set<IndexEntry> entries) throws IOException {
      requireArgument(entries.size() <= MAX_ENTRY_COUNT, "too many entries");
      try (var channel = FileChannel.open(tempIndexFile, CREATE, WRITE, TRUNCATE_EXISTING)) {
        var index =
            ByteBuffer.allocate(INDEX_HEADER_SIZE + INDEX_ENTRY_SIZE * entries.size())
                .putLong(INDEX_MAGIC)
                .putInt(STORE_VERSION)
                .putInt(appVersion)
                .putLong(entries.size());
        entries.forEach(entry -> entry.writeTo(index));
        StoreIO.writeBytes(channel, index.flip());
        channel.force(false);
      }
      replace(tempIndexFile, indexFile);
    }

    private Map<Hash, EntryFiles> scanDirectoryForEntries() throws IOException {
      var diskEntries = new HashMap<Hash, EntryFiles>();
      try (var stream = Files.newDirectoryStream(directory)) {
        for (var file : stream) {
          var filenameComponent = file.getFileName();
          var filename = filenameComponent != null ? filenameComponent.toString() : "";
          if (filename.equals(INDEX_FILENAME)
              || filename.equals(TEMP_INDEX_FILENAME)
              || filename.equals(LOCK_FILENAME)) {
            // Skip non-entry files.
            continue;
          }

          Hash hash;
          if ((filename.endsWith(ENTRY_FILE_SUFFIX) || filename.endsWith(TEMP_ENTRY_FILE_SUFFIX))
              && (hash = entryFileToHash(filename)) != null) {
            var files = diskEntries.computeIfAbsent(hash, __ -> new EntryFiles());
            if (filename.endsWith(ENTRY_FILE_SUFFIX)) {
              files.cleanFile = file;
            } else {
              files.dirtyFile = file;
            }
          } else if (filename.startsWith(ISOLATED_FILE_PREFIX)) {
            // Clean trails of isolatedDeleteIfExists in case it failed in a previous session.
            safeDeleteIfExists(file);
          } else {
            logger.log(
                Level.WARNING,
                "Unrecognized file or directory found during initialization <"
                    + file
                    + ">. "
                    + System.lineSeparator()
                    + "It is generally not a good idea to let the store directory be used by other entities.");
          }
        }
      }
      return diskEntries;
    }

    /** Entry related files found by a directory scan. */
    private static final class EntryFiles {
      @MonotonicNonNull Path cleanFile;
      @MonotonicNonNull Path dirtyFile;

      EntryFiles() {}
    }
  }

  private static final class DebugIndexOperator extends IndexOperator {
    private final AtomicReference<@Nullable String> runningOperation = new AtomicReference<>();
    private final AtomicInteger writeCount = new AtomicInteger(0);

    DebugIndexOperator(Path directory, int appVersion) {
      super(directory, appVersion);
    }

    @Override
    Set<IndexEntry> readIndex() throws IOException {
      enter("readIndex");
      try {
        return super.readIndex();
      } finally {
        exit();
      }
    }

    @Override
    void writeIndex(Set<IndexEntry> entries) throws IOException {
      enter("writeIndex");
      try {
        super.writeIndex(entries);
        writeCount.incrementAndGet();
      } finally {
        exit();
      }
    }

    private void enter(String operation) {
      if (!isIndexExecutor.get()) {
        logger.log(
            Level.ERROR,
            () -> "IndexOperator::" + operation + " isn't called by the index executor");
      }

      var currentOperation = runningOperation.compareAndExchange(null, operation);
      if (currentOperation != null) {
        logger.log(
            Level.ERROR,
            () ->
                "IndexOperator::"
                    + operation
                    + " is called while IndexOperator::"
                    + currentOperation
                    + " is running");
      }
    }

    private void exit() {
      runningOperation.set(null);
    }

    int writeCount() {
      return writeCount.get();
    }
  }

  /**
   * A time-limited scheduler for index writes that arranges no more than 1 write per the specified
   * time period.
   */
  private static final class IndexWriteScheduler {
    /** Terminal marker that is set when no more writes are to be scheduled. */
    private static final WriteTask TOMBSTONE =
        new WriteTask() {
          @Override
          Instant fireTime() {
            return Instant.MIN;
          }

          @Override
          void cancel() {}
        };

    private final IndexOperator indexOperator;
    private final Executor indexExecutor;
    private final Supplier<Set<IndexEntry>> indexEntriesSnapshotSupplier;
    private final Duration period;
    private final Delayer delayer;
    private final Clock clock;
    private final AtomicReference<WriteTask> scheduledWriteTask = new AtomicReference<>();

    /**
     * A barrier for shutdowns to await the currently running task. Scheduled WriteTasks have the
     * following transitions:
     *
     * <pre>{@code
     * T1 -> T2 -> .... -> Tn
     * }</pre>
     *
     * Where Tn is the currently scheduled and hence the only referenced task, and the time between
     * two consecutive Ts is generally the specified period, or less if there are immediate flushes
     * (note that Ts don't overlap since the executor is serialized). Ensuring no Ts are running
     * after shutdown entails awaiting the currently running task (if any) to finish then preventing
     * ones following it from starting. If the update delay is small enough, or if the executor
     * and/or the system-wide scheduler are busy, the currently running task might be lagging behind
     * Tn by multiple Ts, so it's not ideal to somehow keep a reference to it in order to await it
     * when needed. This Phaser solves this issue by having the currently running T to register
     * itself then arriveAndDeregister when finished. During shutdown, the scheduler de-registers
     * from, then attempts to await, the phaser, where it is only awaited if there is still one
     * registered party (a running T). When registerers reach 0, the phaser is terminated,
     * preventing yet to arrive tasks from registering, so they can choose not to run.
     */
    private final Phaser runningTaskAwaiter = new Phaser(1); // Register self.

    IndexWriteScheduler(
        IndexOperator indexOperator,
        Executor indexExecutor,
        Supplier<Set<IndexEntry>> indexEntriesSnapshotSupplier,
        Duration period,
        Delayer delayer,
        Clock clock) {
      this.indexOperator = indexOperator;
      this.indexExecutor = indexExecutor;
      this.indexEntriesSnapshotSupplier = indexEntriesSnapshotSupplier;
      this.period = period;
      this.delayer = delayer;
      this.clock = clock;
    }

    Clock clock() {
      return clock;
    }

    Delayer delayer() {
      return delayer;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void trySchedule() {
      // Decide whether to schedule and when as follows:
      //   - If TOMBSTONE is set, don't schedule anything.
      //   - If scheduledWriteTask is null, then this is the first call, so schedule immediately.
      //   - If scheduledWriteTask is set to run in the future, then it'll see the changes made so
      //     far and there's no need to schedule.
      //   - If less than INDEX_UPDATE_DELAY time has passed since the last write, then schedule the
      //     writer to run when the period evaluates from the last write.
      //   - Otherwise, a timeslot is available, so schedule immediately.
      //
      // This is retried in case of contention.

      var now = clock.instant();
      while (true) {
        var currentTask = scheduledWriteTask.get();
        var nextFireTime = currentTask != null ? currentTask.fireTime() : null;
        Duration delay;
        if (nextFireTime == null) {
          delay = Duration.ZERO;
        } else if (currentTask == TOMBSTONE || nextFireTime.isAfter(now)) {
          return; // No writes are needed.
        } else {
          var idleness = Duration.between(nextFireTime, now);
          delay = Compare.max(period.minus(idleness), Duration.ZERO);
        }

        var newTask = new RunnableWriteTask(now.plus(delay));
        if (scheduledWriteTask.compareAndSet(currentTask, newTask)) {
          delayer.delay(newTask::runUnchecked, delay, indexExecutor);
          return;
        }
      }
    }

    /** Forcibly submits an index write to the index executor, ignoring the time rate. */
    void forceSchedule() throws IOException {
      try {
        Utils.get(forceScheduleAsync());
      } catch (InterruptedException e) {
        throw (IOException) new InterruptedIOException().initCause(e);
      }
    }

    private CompletableFuture<Void> forceScheduleAsync() {
      var now = clock.instant();
      while (true) {
        var currentTask = scheduledWriteTask.get();
        requireState(currentTask != TOMBSTONE, "shutdown");

        var newTask = new RunnableWriteTask(now);
        if (scheduledWriteTask.compareAndSet(currentTask, newTask)) {
          if (currentTask != null) {
            currentTask.cancel();
          }
          return Unchecked.runAsync(newTask, indexExecutor);
        }
      }
    }

    void shutdown() throws InterruptedIOException {
      scheduledWriteTask.set(TOMBSTONE);
      try {
        runningTaskAwaiter.awaitAdvanceInterruptibly(runningTaskAwaiter.arriveAndDeregister());
        assert runningTaskAwaiter.isTerminated();
      } catch (InterruptedException e) {
        throw new InterruptedIOException();
      }
    }

    private abstract static class WriteTask {
      abstract Instant fireTime();

      abstract void cancel();
    }

    private final class RunnableWriteTask extends WriteTask implements ThrowingRunnable {
      private final Instant fireTime;
      private volatile boolean cancelled;

      RunnableWriteTask(Instant fireTime) {
        this.fireTime = fireTime;
      }

      @Override
      Instant fireTime() {
        return fireTime;
      }

      @Override
      void cancel() {
        cancelled = true;
      }

      @Override
      public void run() throws IOException {
        if (!cancelled && runningTaskAwaiter.register() >= 0) {
          try {
            indexOperator.writeIndex(indexEntriesSnapshotSupplier.get());
          } finally {
            runningTaskAwaiter.arriveAndDeregister();
          }
        }
      }

      void runUnchecked() {
        // TODO consider disabling the store if failure happens too often.
        try {
          run();
        } catch (IOException e) {
          logger.log(Level.ERROR, "Exception thrown when writing the index", e);
        }
      }
    }
  }

  /** Schedules eviction tasks on demand while ensuring they're run sequentially. */
  private static final class EvictionScheduler {
    private static final int RUN = 1;
    private static final int KEEP_ALIVE = 2;
    private static final int SHUTDOWN = 4;

    private static final VarHandle SYNC;

    static {
      try {
        var lookup = MethodHandles.lookup();
        SYNC = lookup.findVarHandle(EvictionScheduler.class, "sync", int.class);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private final DiskStore store;
    private final Executor executor;

    @SuppressWarnings("unused") // VarHandle indirection.
    private volatile int sync;

    EvictionScheduler(DiskStore store, Executor executor) {
      this.store = store;
      this.executor = executor;
    }

    void schedule() {
      for (int s; ((s = sync) & SHUTDOWN) == 0; ) {
        int bit = (s & RUN) == 0 ? RUN : KEEP_ALIVE; // Run or keep-alive.
        if (SYNC.compareAndSet(this, s, (s | bit))) {
          if (bit == RUN) {
            executor.execute(this::runEviction);
          }
          break;
        }
      }
    }

    private void runEviction() {
      for (int s; ((s = sync) & SHUTDOWN) == 0; ) {
        try {
          if (!store.evictExcessiveEntriesIfOpen()) {
            // Ignore eviction, the store ensures it's closed within bounds.
            break;
          }
        } catch (IOException e) {
          logger.log(Level.ERROR, "Exception thrown when evicting entries in background", e);
        }

        // Exit or consume keep-alive bit.
        int bit = (s & KEEP_ALIVE) != 0 ? KEEP_ALIVE : RUN;
        if (SYNC.compareAndSet(this, s, s & ~bit) && bit == RUN) {
          break;
        }
      }
    }

    void shutdown() {
      SYNC.getAndBitwiseOr(this, SHUTDOWN);
    }
  }

  /**
   * A lock on the store directory that ensures it's operated upon by a single DiskStore instance in
   * a single JVM process. This only works in a cooperative manner; it doesn't prevent other
   * entities from using the directory.
   */
  private static final class DirectoryLock implements AutoCloseable {
    private final Path lockFile;
    private final FileChannel channel;

    private DirectoryLock(Path lockFile, FileChannel channel) {
      this.lockFile = lockFile;
      this.channel = channel;
    }

    @Override
    public void close() {
      deleteIfExistsQuietly(lockFile);
      closeQuietly(channel); // Closing the channel releases the lock.
    }

    static DirectoryLock acquire(Path directory) throws IOException {
      var lockFile = directory.resolve(LOCK_FILENAME);
      var channel = FileChannel.open(lockFile, READ, WRITE, CREATE);
      try {
        var fileLock = channel.tryLock();
        if (fileLock == null) {
          throw new IOException(String.format("store directory <%s> already in use", directory));
        }
        return new DirectoryLock(lockFile, channel);
      } catch (IOException e) {
        closeQuietly(channel);
        deleteIfExistsQuietly(lockFile);
        throw e;
      }
    }
  }

  /** An immutable 80-bit hash code. */
  public static final class Hash {
    static final int BYTES = 10;
    private static final int HEX_STRING_LENGTH = 2 * BYTES;

    // Upper 64 bits + lower 16 bits in big-endian order.
    private final long upper64Bits;
    private final short lower16Bits;

    private @MonotonicNonNull String lazyHex;

    public Hash(ByteBuffer buffer) {
      this(buffer.getLong(), buffer.getShort());
    }

    Hash(long upper64Bits, short lower16Bits) {
      this.upper64Bits = upper64Bits;
      this.lower16Bits = lower16Bits;
    }

    void writeTo(ByteBuffer buffer) {
      buffer.putLong(upper64Bits);
      buffer.putShort(lower16Bits);
    }

    String toHexString() {
      var hex = lazyHex;
      if (hex == null) {
        hex =
            toPaddedHexString(upper64Bits, Long.BYTES)
                + toPaddedHexString(lower16Bits & 0xffff, Short.BYTES);
        lazyHex = hex;
      }
      return hex;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(upper64Bits) ^ Short.hashCode(lower16Bits);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof Hash)) {
        return false;
      }
      var other = (Hash) obj;
      return upper64Bits == other.upper64Bits && lower16Bits == other.lower16Bits;
    }

    @Override
    public String toString() {
      return toHexString();
    }

    static @Nullable Hash tryParse(String hex) {
      if (hex.length() != HEX_STRING_LENGTH) {
        return null;
      }
      try {
        // There's no Short.parseShort that accepts a CharSequence region, so use downcast result of
        // Integer.parseInt. This will certainly fit in a short since exactly 32 hex characters are
        // parsed, and we don't care about the sign.
        return new Hash(
            Long.parseUnsignedLong(hex, 0, Long.BYTES << 1, 16),
            (short) Integer.parseInt(hex, Long.BYTES << 1, hex.length(), 16));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }

    private static String toPaddedHexString(long value, int size) {
      var hex = Long.toHexString(value);
      int padding = (size << 1) - hex.length();
      assert padding >= 0;
      if (padding > 0) {
        hex = "0".repeat(padding) + hex;
      }
      return hex;
    }
  }

  private static final class IndexEntry {
    /**
     * A comparator that defines LRU eviction order. It is assumed that there can be no ties based
     * on latest usage time.
     */
    static final Comparator<IndexEntry> LRU_ORDER =
        Comparator.comparingLong(entry -> entry.lastUsed);

    final Hash hash;
    final long lastUsed;
    final long size;

    IndexEntry(Hash hash, long lastUsed, long size) {
      this.hash = hash;
      this.lastUsed = lastUsed;
      this.size = size;
    }

    IndexEntry(ByteBuffer buffer) throws StoreCorruptionException {
      hash = new Hash(buffer);
      lastUsed = buffer.getLong();
      size = getPositiveLong(buffer);
    }

    void writeTo(ByteBuffer buffer) {
      hash.writeTo(buffer);
      buffer.putLong(lastUsed);
      buffer.putLong(size);
    }

    @Override
    public int hashCode() {
      return hash.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof IndexEntry)) {
        return false;
      }
      return hash.equals(((IndexEntry) obj).hash);
    }
  }

  private static final class EntryDescriptor {
    final String key;
    final ByteBuffer metadata;
    final long dataSize;

    EntryDescriptor(String key, ByteBuffer metadata, long dataSize) {
      this.key = key;
      this.metadata = metadata.asReadOnlyBuffer();
      this.dataSize = dataSize;
    }

    ByteBuffer encodeToEpilogue(int appVersion) {
      var encodedKey = UTF_8.encode(key);
      int keySize = encodedKey.remaining();
      int metadataSize = metadata.remaining();
      return ByteBuffer.allocate(keySize + metadataSize + ENTRY_TRAILER_SIZE)
          .put(encodedKey)
          .put(metadata.duplicate())
          .putLong(ENTRY_MAGIC)
          .putInt(STORE_VERSION)
          .putInt(appVersion)
          .putInt(keySize)
          .putInt(metadataSize)
          .putLong(dataSize)
          .flip();
    }
  }

  private final class Entry {
    static final int ANY_VERSION = -1;

    final Hash hash;

    private final ReentrantLock lock = new ReentrantLock();

    @GuardedBy("lock")
    private long lastUsed;

    @GuardedBy("lock")
    private long size;

    @GuardedBy("lock")
    private int viewerCount;

    @GuardedBy("lock")
    private @Nullable DiskEditor currentEditor;

    @GuardedBy("lock")
    private int version;

    @GuardedBy("lock")
    private boolean readable;

    @GuardedBy("lock")
    private boolean writable;

    private @MonotonicNonNull Path lazyEntryFile;
    private @MonotonicNonNull Path lazyTempEntryFile;

    /** This entry's descriptor as known from the last read or write. */
    @GuardedBy("lock")
    private @MonotonicNonNull EntryDescriptor cachedDescriptor;

    Entry(Hash hash) {
      this.hash = hash;
      this.lastUsed = -1;
      this.readable = false;
      this.writable = true;
    }

    Entry(IndexEntry indexEntry) {
      this.hash = indexEntry.hash;
      this.lastUsed = indexEntry.lastUsed;
      this.size = indexEntry.size;
      this.readable = true;
      this.writable = true;
    }

    @Nullable IndexEntry toIndexEntry() {
      lock.lock();
      try {
        return readable ? new IndexEntry(hash, lastUsed, size) : null;
      } finally {
        lock.unlock();
      }
    }

    @Nullable Viewer view(@Nullable String expectedKey) throws IOException {
      var viewer = openViewerForKey(expectedKey);
      if (viewer != null) {
        indexWriteScheduler.trySchedule();
      }
      return viewer;
    }

    private @Nullable Viewer openViewerForKey(@Nullable String expectedKey) throws IOException {
      lock.lock();
      try {
        if (!readable) {
          return null;
        }

        var channel = FileChannel.open(entryFile(), READ);
        try {
          var descriptor = readDescriptorForKey(channel, expectedKey);
          if (descriptor != null) {
            return createViewer(channel, version, descriptor);
          }
          channel.close();
          return null;
        } catch (IOException e) {
          try {
            channel.close();
          } catch (IOException closeEx) {
            e.addSuppressed(closeEx);
          }
          throw e;
        }
      } catch (NoSuchFileException missingEntryFile) {
        // Our file disappeared! We'll handle this gracefully by making the store lose track of us.
        // This is done after releasing the lock to not incur a potential index write while holding
        // it.
        logger.log(Level.WARNING, "Dropping entry with missing file", missingEntryFile);
      } finally {
        lock.unlock();
      }

      try {
        removeEntry(this);
      } catch (IOException e) {
        logger.log(Level.WARNING, "Exception while deleting already non-existent entry");
      }
      return null;
    }

    @GuardedBy("lock") // Lock must be held due to potential write to cachedDescriptor.
    private @Nullable EntryDescriptor readDescriptorForKey(
        FileChannel channel, @Nullable String expectedKey) throws IOException {
      var descriptor = cachedDescriptor;
      if (descriptor == null) {
        descriptor = readDescriptor(channel);
      }
      if (keyMismatches(descriptor.key, expectedKey)) {
        return null;
      }
      cachedDescriptor = descriptor;
      return descriptor;
    }

    private EntryDescriptor readDescriptor(FileChannel channel) throws IOException {
      // TODO a smarter thing to do is to read a larger buffer from the end and optimistically
      //      expect key and metadata to be there. Or store the sizes of metadata, data & key in
      //      the index.
      long fileSize = channel.size();
      var trailer = StoreIO.readNBytes(channel, ENTRY_TRAILER_SIZE, fileSize - ENTRY_TRAILER_SIZE);
      long magic = trailer.getLong();
      int storeVersion = trailer.getInt();
      int appVersion = trailer.getInt();
      int keySize = getNonNegativeInt(trailer);
      int metadataSize = getNonNegativeInt(trailer);
      long dataSize = getNonNegativeLong(trailer);
      checkValue(ENTRY_MAGIC, magic, "not in entry file format");
      checkValue(STORE_VERSION, storeVersion, "unexpected store version");
      checkValue(DiskStore.this.appVersion, appVersion, "unexpected app version");
      var keyAndMetadata = StoreIO.readNBytes(channel, keySize + metadataSize, dataSize);
      var key = UTF_8.decode(keyAndMetadata.limit(keySize)).toString();
      var metadata =
          keyAndMetadata
              .limit(keySize + metadataSize)
              .slice() // Slice to have 0 position & metadataSize capacity.
              .asReadOnlyBuffer();
      return new EntryDescriptor(key, metadata, dataSize);
    }

    @GuardedBy("lock")
    private Viewer createViewer(FileChannel channel, int version, EntryDescriptor descriptor) {
      var viewer = new DiskViewer(this, version, descriptor, channel);
      viewerCount++;
      lastUsed = lruClock.getAndIncrement();
      return viewer;
    }

    @Nullable Editor edit(String key, int targetVersion) throws IOException {
      lock.lock();
      try {
        if (!writable
            || currentEditor != null
            || (targetVersion != ANY_VERSION && targetVersion != version)) {
          return null;
        }
        var editor = new DiskEditor(this, key, FileChannel.open(tempEntryFile(), WRITE, CREATE));
        currentEditor = editor;
        return editor;
      } finally {
        lock.unlock();
      }
    }

    void commit(
        DiskEditor editor,
        String key,
        ByteBuffer metadata,
        long dataSize,
        FileChannel editorChannel)
        throws IOException {
      long newSize;
      long sizeDifference;
      lock.lock();
      try {
        requireState(currentEditor == editor, "edit discarded");
        currentEditor = null;

        requireState(writable, "committing a non-discarded edit to a non-writable entry");

        EntryDescriptor committedDescriptor;
        boolean editInPlace = dataSize < 0 && readable;
        try (editorChannel;
            var existingEntryChannel =
                editInPlace ? FileChannel.open(entryFile(), READ, WRITE) : null) {
          var targetChannel = editorChannel;
          EntryDescriptor existingDescriptor = null;
          if (existingEntryChannel != null
              && (existingDescriptor = readDescriptorForKey(existingEntryChannel, key)) != null) {
            targetChannel = existingEntryChannel;
            committedDescriptor = new EntryDescriptor(key, metadata, existingDescriptor.dataSize);

            // Close editor's file channel before deleting. See isolatedDeleteIfExists(Path).
            closeQuietly(editorChannel);

            // Make the entry file temporarily unreadable before modifying it. This also has to
            // reflect on store's size.
            replace(entryFile(), tempEntryFile());
            readable = false;
            DiskStore.this.size.addAndGet(-size);
            size = 0;
          } else {
            committedDescriptor = new EntryDescriptor(key, metadata, Math.max(dataSize, 0));
          }

          int written =
              StoreIO.writeBytes(
                  targetChannel,
                  committedDescriptor.encodeToEpilogue(appVersion),
                  committedDescriptor.dataSize);

          if (existingDescriptor != null) {
            // Truncate to correct size in case the previous entry had a larger epilogue.
            targetChannel.truncate(committedDescriptor.dataSize + written);
          }
          targetChannel.force(false);
        } catch (IOException e) {
          discardCurrentEdit(editor);
          throw e;
        }

        if (viewerCount > 0) {
          isolatedDeleteIfExists(entryFile());
        }
        replace(tempEntryFile(), entryFile());

        version++;
        newSize = committedDescriptor.metadata.remaining() + committedDescriptor.dataSize;
        sizeDifference = newSize - size;
        size = newSize;
        readable = true;
        lastUsed = lruClock.getAndIncrement();
        cachedDescriptor = committedDescriptor;
      } finally {
        lock.unlock();
      }

      long newStoreSize = DiskStore.this.size.addAndGet(sizeDifference);

      // Don't bother with the entry if it'll cause everything to be evicted.
      if (newSize > maxSize) {
        removeEntry(this);
        return;
      }

      if (newStoreSize > maxSize) {
        evictionScheduler.schedule();
      }
      indexWriteScheduler.trySchedule();
    }

    /**
     * Evicts this entry if it matches the given version and returns its last committed size if it
     * did get evicted, otherwise returns -1.
     */
    long evict(int targetVersion) throws IOException {
      lock.lock();
      try {
        if (!writable || (targetVersion != ANY_VERSION && targetVersion != version)) {
          return -1;
        }

        if (viewerCount > 0) {
          isolatedDeleteIfExists(entryFile());
        } else {
          Files.deleteIfExists(entryFile());
        }
        discardCurrentEdit();
        readable = false;
        writable = false;
        return size;
      } finally {
        lock.unlock();
      }
    }

    void freeze() {
      lock.lock();
      try {
        writable = false;
        discardCurrentEdit();
      } finally {
        lock.unlock();
      }
    }

    @GuardedBy("lock")
    private void discardCurrentEdit() {
      var editor = currentEditor;
      if (editor != null) {
        currentEditor = null;
        discardCurrentEdit(editor);
      }
    }

    @GuardedBy("lock")
    private void discardCurrentEdit(DiskEditor editor) {
      if (!readable) {
        // Remove the entry as it could never be readable. It's safe to directly remove it from the
        // map since it's not visible to the outside world at this point (no views/edits) and
        // doesn't contribute to store size.
        entries.remove(hash, this);
      }

      editor.setClosed();
      closeQuietly(editor.channel);
      deleteIfExistsQuietly(tempEntryFile());
    }

    void discardIfCurrentEdit(DiskEditor editor) {
      lock.lock();
      try {
        if (editor == currentEditor) {
          currentEditor = null;
          discardCurrentEdit(editor);
        }
      } finally {
        lock.unlock();
      }
    }

    void decrementViewerCount() {
      lock.lock();
      try {
        viewerCount--;
      } finally {
        lock.unlock();
      }
    }

    @Nullable String keyIfKnown(int[] versionHolder) {
      lock.lock();
      try {
        var descriptor = cachedDescriptor;
        if (descriptor != null) {
          versionHolder[0] = version;
          return descriptor.key;
        }
        return null;
      } finally {
        lock.unlock();
      }
    }

    @Nullable String currentEditorKey() {
      lock.lock();
      try {
        var editor = currentEditor;
        return editor != null ? editor.key() : null;
      } finally {
        lock.unlock();
      }
    }

    Path entryFile() {
      var entryFile = lazyEntryFile;
      if (entryFile == null) {
        entryFile = directory.resolve(hash.toHexString() + ENTRY_FILE_SUFFIX);
        lazyEntryFile = entryFile;
      }
      return entryFile;
    }

    Path tempEntryFile() {
      var tempEntryFile = lazyTempEntryFile;
      if (tempEntryFile == null) {
        tempEntryFile = directory.resolve(hash.toHexString() + TEMP_ENTRY_FILE_SUFFIX);
        lazyTempEntryFile = tempEntryFile;
      }
      return tempEntryFile;
    }
  }

  private final class DiskViewer implements Viewer {
    private final Entry entry;

    /**
     * Entry's version at the time of opening this viewer. This is used to not edit or remove an
     * entry that's been updated after this viewer had been created.
     */
    private final int entryVersion;

    private final EntryDescriptor descriptor;
    private final FileChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicBoolean createdFirstReader = new AtomicBoolean();

    DiskViewer(Entry entry, int entryVersion, EntryDescriptor descriptor, FileChannel channel) {
      this.entry = entry;
      this.entryVersion = entryVersion;
      this.descriptor = descriptor;
      this.channel = channel;
    }

    @Override
    public String key() {
      return descriptor.key;
    }

    @Override
    public ByteBuffer metadata() {
      return descriptor.metadata.duplicate();
    }

    @Override
    public EntryReader newReader() {
      return createdFirstReader.compareAndSet(false, true)
          ? new ScatteringDiskEntryReader()
          : new DiskEntryReader();
    }

    @Override
    public Optional<Editor> edit() throws IOException {
      return Optional.ofNullable(entry.edit(key(), entryVersion));
    }

    @Override
    public long dataSize() {
      return descriptor.dataSize;
    }

    @Override
    public long entrySize() {
      return descriptor.metadata.remaining() + descriptor.dataSize;
    }

    @Override
    public boolean removeEntry() throws IOException {
      return DiskStore.this.removeEntry(entry, entryVersion);
    }

    @Override
    public void close() {
      closeQuietly(channel);
      if (closed.compareAndSet(false, true)) {
        entry.decrementViewerCount();
      }
    }

    private class DiskEntryReader implements EntryReader {
      final Lock lock = new ReentrantLock();
      long position;

      DiskEntryReader() {}

      @Override
      public int read(ByteBuffer dst) throws IOException {
        requireNonNull(dst);
        lock.lock();
        try {
          // Make sure we don't exceed data stream bounds.
          long available = descriptor.dataSize - position;
          if (available <= 0) {
            return -1;
          }

          int maxReadable = (int) Math.min(available, dst.remaining());
          var boundedDst = dst.duplicate().limit(dst.position() + maxReadable);
          int read = readBytes(boundedDst);
          position += read;
          dst.position(dst.position() + read);
          return read;
        } finally {
          lock.unlock();
        }
      }

      int readBytes(ByteBuffer dst) throws IOException {
        return StoreIO.readBytes(channel, dst, position);
      }
    }

    /**
     * A reader that uses scattering API for bulk reads. This reader relies on the file's native
     * position (scattering API doesn't take a position argument). As such, it must only be created
     * once.
     */
    private final class ScatteringDiskEntryReader extends DiskEntryReader {
      ScatteringDiskEntryReader() {}

      @Override
      int readBytes(ByteBuffer dst) throws IOException {
        return StoreIO.readBytes(channel, dst); // Use native file position.
      }

      @Override
      public long read(List<ByteBuffer> dsts) throws IOException {
        requireNonNull(dsts);
        lock.lock();
        try {
          // Make sure we don't exceed data stream bounds.
          long available = descriptor.dataSize - position;
          if (available <= 0) {
            return -1;
          }

          var boundedDsts = new ArrayList<ByteBuffer>(dsts.size());
          long maxReadable = 0;
          for (var dst : dsts) {
            int dstMaxReadable = (int) Math.min(dst.remaining(), available - maxReadable);
            boundedDsts.add(dst.duplicate().limit(dst.position() + dstMaxReadable));
            maxReadable = Math.addExact(maxReadable, dstMaxReadable);
            if (maxReadable >= available) {
              break;
            }
          }

          long read = StoreIO.readBytes(channel, boundedDsts.toArray(ByteBuffer[]::new));
          position += read;
          for (int i = 0; i < boundedDsts.size(); i++) {
            dsts.get(i).position(boundedDsts.get(i).position());
          }
          return read;
        } finally {
          lock.unlock();
        }
      }
    }
  }

  private static final class DiskEditor implements Editor {
    private final Entry entry;
    private final String key;
    private final FileChannel channel;
    private final DiskEntryWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean();

    DiskEditor(Entry entry, String key, FileChannel channel) {
      this.entry = entry;
      this.key = key;
      this.channel = channel;
      this.writer = new DiskEntryWriter();
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public EntryWriter writer() {
      return writer;
    }

    @Override
    public void commit(ByteBuffer metadata) throws IOException {
      requireNonNull(metadata);
      requireState(closed.compareAndSet(false, true), "closed");
      entry.commit(this, key, metadata, writer.dataSizeIfWritten(), channel);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        entry.discardIfCurrentEdit(this);
      }
    }

    public void setClosed() {
      closed.set(true);
    }

    private final class DiskEntryWriter implements EntryWriter {
      private final Lock lock = new ReentrantLock();
      private long position;
      private boolean isWritten;

      DiskEntryWriter() {}

      @Override
      public int write(ByteBuffer src) throws IOException {
        requireNonNull(src);
        requireState(!closed.get(), "closed");
        lock.lock();
        try {
          int written = StoreIO.writeBytes(channel, src);
          position += written;
          isWritten = true;
          return written;
        } finally {
          lock.unlock();
        }
      }

      @Override
      public long write(List<ByteBuffer> srcs) throws IOException {
        requireNonNull(srcs);
        requireState(!closed.get(), "closed");
        lock.lock();
        try {
          long written = StoreIO.writeBytes(channel, srcs.toArray(ByteBuffer[]::new));
          position += written;
          isWritten = true;
          return written;
        } finally {
          lock.unlock();
        }
      }

      long dataSizeIfWritten() {
        lock.lock();
        try {
          return isWritten ? position : -1;
        } finally {
          lock.unlock();
        }
      }
    }
  }

  public static final class Builder {
    private static final long DEFAULT_INDEX_UPDATE_DELAY_MILLIS = 2000;
    private static final Duration DEFAULT_INDEX_UPDATE_DELAY;

    static {
      long millis =
          Long.getLong(
              "com.github.mizosoft.methanol.internal.cache.DiskStore.indexUpdateDelayMillis",
              DEFAULT_INDEX_UPDATE_DELAY_MILLIS);
      if (millis < 0) {
        millis = DEFAULT_INDEX_UPDATE_DELAY_MILLIS;
      }
      DEFAULT_INDEX_UPDATE_DELAY = Duration.ofMillis(millis);
    }

    private static final int UNSET_NUMBER = -1;

    private long maxSize = UNSET_NUMBER;
    private @MonotonicNonNull Path directory;
    private @MonotonicNonNull Executor executor;
    private int appVersion = UNSET_NUMBER;
    private @MonotonicNonNull Hasher hasher;
    private @MonotonicNonNull Clock clock;
    private @MonotonicNonNull Delayer delayer;
    private @MonotonicNonNull Duration indexUpdateDelay;
    private boolean debugIndexOps;

    Builder() {}

    @CanIgnoreReturnValue
    public Builder directory(Path directory) {
      this.directory = requireNonNull(directory);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder maxSize(long maxSize) {
      requireArgument(maxSize > 0, "expected a positive max size");
      this.maxSize = maxSize;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder executor(Executor executor) {
      this.executor = requireNonNull(executor);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder appVersion(int appVersion) {
      this.appVersion = appVersion;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder hasher(Hasher hasher) {
      this.hasher = requireNonNull(hasher);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder clock(Clock clock) {
      this.clock = requireNonNull(clock);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder delayer(Delayer delayer) {
      this.delayer = requireNonNull(delayer);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder indexUpdateDelay(Duration duration) {
      this.indexUpdateDelay = requireNonNegativeDuration(duration);
      return this;
    }

    /**
     * If set, the store complains when the index is accessed or modified either concurrently or not
     * within the index executor.
     */
    @CanIgnoreReturnValue
    public Builder debugIndexOps(boolean on) {
      this.debugIndexOps = on;
      return this;
    }

    public DiskStore build() throws IOException {
      return new DiskStore(this, debugIndexOps || DebugUtils.isAssertionsEnabled());
    }

    long maxSize() {
      long maxSize = this.maxSize;
      requireState(maxSize != UNSET_NUMBER, "expected maxSize to bet set");
      return maxSize;
    }

    int appVersion() {
      int appVersion = this.appVersion;
      requireState(appVersion != UNSET_NUMBER, "expected appVersion to be set");
      return appVersion;
    }

    Path directory() {
      return ensureSet(directory, "directory");
    }

    Executor executor() {
      return ensureSet(executor, "executor");
    }

    Hasher hasher() {
      return requireNonNullElse(hasher, Hasher.TRUNCATED_SHA_256);
    }

    Clock clock() {
      return requireNonNullElse(clock, Utils.systemMillisUtc());
    }

    Duration indexUpdateDelay() {
      return requireNonNullElse(indexUpdateDelay, DEFAULT_INDEX_UPDATE_DELAY);
    }

    Delayer delayer() {
      return requireNonNullElse(delayer, Delayer.systemDelayer());
    }

    @CanIgnoreReturnValue
    private <T> T ensureSet(T property, String name) {
      requireState(property != null, "expected %s to bet set", name);
      return property;
    }
  }
}
