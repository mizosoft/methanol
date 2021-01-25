/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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
import static com.github.mizosoft.methanol.internal.cache.DateUtils.max;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.function.ThrowingRunnable;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

// TODO Add more logging
/**
 * A persistent {@link Store} implementation that saves entries into a specified directory. Each
 * {@code DiskStore} instance assumes exclusive ownership of its directory; only a single {@code
 * DiskStore} from a single JVM process can safely operate on a given directory. This assumption is
 * cooperatively enforced among different {@code DisStores} such that attempting to initialize a
 * store with a directory that is in use by another store in the same or a different JVM process
 * will cause an {@code IOException} to be thrown.
 *
 * <p>The store keeps track of entries known to it across sessions by maintaining an on-disk
 * hashtable called the index. As changes are made to the store, the index is atomically replaced
 * with an updated index in a time-limited manner; every 4 seconds there's at most one index update.
 * This rate can be changed by setting the system property: {@code
 * com.github.mizosoft.methanol.internal.cache.DiskStore.indexUpdateDelayMillis}.
 *
 * <p>To ensure entries are not lost across sessions, a store must be {@link #close() closed} after
 * it has been with. The {@link #dispose()} method can be called to atomically close the store and
 * clear its directory if persistence isn't needed (e.g. caching in a temp directory). A closed
 * store will throw an {@code IllegalStateException} when either of {@code initialize} (if not yet
 * initialized), {@code view}, {@code edit}, {@code remove} or {@code clear} is invoked.
 */
public final class DiskStore implements Store {
  /*
   * The store's layout on disk is as follows:
   *
   *   - An 'index' file.
   *   - A corresponding file for each entry with its name being the hex string of the first 80
   *     bits of the key's SHA-245, concatenated to the suffix '.ch3oh'.
   *   - A '.lock' indicating that the directory is in use if a store operating on that directory is
   *     initialized.
   *
   * The index and entry files are formatted as follows (in slang BNF):
   *
   *   <index> = <index-header> <entry-descriptor>*
   *   <index-header> = 8-bytes-index-magic
   *                    4-bytes-store-version
   *                    4-bytes-app-version
   *                    8-bytes-entry-count
   *   <entry-descriptor> = 10-bytes-entry-hash
   *                        8-bytes-last-used-millis (maintained for LRU eviction)
   *                        8-bytes-entry-size
   *
   *   <entry> = <data> <entry-footer>
   *   <data> = byte*
   *   <entry-footer> = <key> <metadata> <entry-trailer>
   *   <key> = utf8-byte*
   *   <metadata> = byte*
   *   <entry-trailer> = 8-bytes-entry-magic
   *                     4-bytes-store-version
   *                     4-bytes-app-version
   *                     4-bytes-key-size
   *                     4-bytes-metadata-size
   *                     8-bytes-data-size
   *
   * Having the key, metadata & their sizes at the end of the file makes it easy and quick to update
   * an entry when only its metadata block changes (and possibly its key in case there's a hash
   * collision). In such case, an entry update only overwrites <entry-footer> next to an existing
   * <data>, truncating the file if necessary. Having an <entry-trailer> instead of an
   * <entry-header> allows validating the entry file and knowing its key & metadata sizes in a
   * single read.
   *
   * An effort is made to ensure store operations on disk are atomic. Index and entry writers first
   * do their work on a temp file. After they're done, a channel::force is issued then the previous
   * version of the file is atomically replaced. Viewers opened for an entry see a constant snapshot
   * of that entry's data even if the entry is removed or edited one or more times.
   */

  private static final Logger LOGGER = Logger.getLogger(DiskStore.class.getName());

  // Visible for testing
  static final long INDEX_MAGIC = 0x6d657468616e6f6cL;
  static final long ENTRY_MAGIC = 0x7b6368332d6f687dL;
  static final int STORE_VERSION = 1;
  static final int INDEX_HEADER_SIZE = 2 * Long.BYTES + 2 * Integer.BYTES;
  static final int ENTRY_DESCRIPTOR_SIZE = Hash.BYTES + 2 * Long.BYTES;
  static final int ENTRY_TRAILER_SIZE = 2 * Long.BYTES + 4 * Integer.BYTES;

  static final String LOCK_FILENAME = ".lock";
  static final String INDEX_FILENAME = "index";
  static final String TEMP_INDEX_FILENAME = "index.tmp";
  static final String ENTRY_FILE_SUFFIX = ".ch3oh";
  static final String TEMP_ENTRY_FILE_SUFFIX = ".ch3oh.tmp";

  /**
   * This caps on what to be read from the index so that an {@code OutOfMemoryError} is not thrown
   * when reading some corrupt index file.
   */
  private static final int MAX_ENTRY_COUNT = 1_000_000;

  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private static final long DEFAULT_INDEX_UPDATE_DELAY = 4000;
  private static final Duration INDEX_UPDATE_DELAY;

  static {
    long millis =
        Long.getLong(
            "com.github.mizosoft.methanol.internal.cache.DiskStore.indexUpdateDelayMillis",
            DEFAULT_INDEX_UPDATE_DELAY);
    if (millis < 0) {
      millis = DEFAULT_INDEX_UPDATE_DELAY;
    }
    INDEX_UPDATE_DELAY = Duration.ofMillis(millis);
  }

  private final Path directory;
  private final long maxSize;
  private final Executor executor;
  private final int appVersion;
  private final Hasher hasher;
  private final Clock clock;
  private final SerialExecutor indexExecutor; // Operate on the index sequentially
  private final IndexOperator indexOperator;
  private final IndexWriteScheduler indexWriteScheduler;
  private final EvictionScheduler evictionScheduler;
  private final ConcurrentHashMap<Hash, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicLong size = new AtomicLong();
  private final StampedLock closeLock = new StampedLock();

  private @MonotonicNonNull DirectoryLock directoryLock; // Acquired when initializing

  private volatile boolean initialized;
  private boolean closed;

  private DiskStore(Builder builder) {
    this.directory = requireNonNull(builder.directory);
    this.maxSize = builder.maxSize;
    this.executor = requireNonNull(builder.executor);
    this.appVersion = builder.appVersion;
    this.hasher = requireNonNullElse(builder.hasher, Hasher.TRUNCATED_SHA_256);
    this.clock = requireNonNullElseGet(builder.clock, Utils::systemMillisUtc);
    indexExecutor = new SerialExecutor(executor);
    indexOperator = new IndexOperator(directory, appVersion);
    indexWriteScheduler =
        new IndexWriteScheduler(
            indexOperator,
            requireNonNullElse(builder.indexFlushDelay, INDEX_UPDATE_DELAY),
            indexExecutor,
            this::entrySetSnapshot,
            clock);
    evictionScheduler = new EvictionScheduler(this, executor);
  }

  @Override
  public void initialize() throws IOException {
    Utils.blockOnIO(initializeAsync());
  }

  @Override
  public CompletableFuture<Void> initializeAsync() {
    return initialized
        ? CompletableFuture.completedFuture(null)
        : Unchecked.runAsync(this::doInitialize, indexExecutor);
  }

  /**
   * Synchronously initializes the store. Must be run by the index executor. Operations that are
   * guarded by closeLock's read lock must first ensure the store is initialized by calling {@link
   * #initialize()} (except {@code flush}, which is a NO-OP for an uninitialized store).
   */
  private void doInitialize() throws IOException {
    if (initialized) { // Recheck as another initialize() might have taken over indexExecutor
      return;
    }

    long stamp = closeLock.readLock();
    try {
      requireNotClosed();
      Files.createDirectories(directory); // Make sure the directory exists
      directoryLock = DirectoryLock.acquire(directory);

      long totalSize = 0L;
      for (var descriptor : indexOperator.recoverEntrySet()) {
        entries.put(descriptor.hash, new Entry(descriptor));
        totalSize += descriptor.size;
      }
      size.set(totalSize);
      initialized = true;

      // Make sure we start within bounds
      if (totalSize > maxSize) {
        evictionScheduler.schedule();
      }
    } finally {
      closeLock.unlockRead(stamp);
    }
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
  public @Nullable Viewer view(String key) throws IOException {
    requireNonNull(key);
    initialize();
    long stamp = closeLock.readLock();
    try {
      requireNotClosed();
      var entry = entries.get(hasher.hash(key));
      if (entry == null) {
        return null;
      }
      try {
        var viewer = entry.openViewer(key);
        if (viewer != null) {
          indexWriteScheduler.trySchedule(); // Update LRU info
        }
        return viewer;
      } catch (NoSuchFileException e) {
        // The entry file disappeared! This means something is messing with our directory.
        // We'll handle this gracefully by just losing track of the entry.
        try {
          removeEntry(entry, true);
        } catch (IOException ignored) {
        }
        return null;
      }
    } finally {
      closeLock.unlockRead(stamp);
    }
  }

  @Override
  public CompletableFuture<@Nullable Viewer> viewAsync(String key) {
    requireNonNull(key);
    return Unchecked.supplyAsync(() -> view(key), executor);
  }

  @Override
  public @Nullable Editor edit(String key) throws IOException {
    requireNonNull(key);
    initialize();
    long stamp = closeLock.readLock();
    try {
      requireNotClosed();
      var entry = entries.computeIfAbsent(hasher.hash(key), Entry::new);
      var editor = entry.newEditor(key, Entry.ANY_ENTRY_VERSION);
      if (editor != null && entry.isReadable()) {
        indexWriteScheduler.trySchedule(); // Update LRU info
      }
      return editor;
    } finally {
      closeLock.unlockRead(stamp);
    }
  }

  @Override
  public CompletableFuture<@Nullable Editor> editAsync(String key) {
    requireNonNull(key);
    return Unchecked.supplyAsync(() -> edit(key), executor);
  }

  @Override
  public Iterator<Viewer> viewAll() throws IOException {
    initialize();
    return new ConcurrentViewerIterator();
  }

  @Override
  public boolean remove(String key) throws IOException {
    requireNonNull(key);
    initialize();
    long stamp = closeLock.readLock();
    try {
      requireNotClosed();
      var entry = entries.get(hasher.hash(key));
      if (entry != null) {
        var cachedKey = entry.cachedKey;
        if (cachedKey == null || key.equals(cachedKey)) {
          return removeEntry(entry, true);
        }
      }
      return false;
    } finally {
      closeLock.unlockRead(stamp);
    }
  }

  @Override
  public void clear() throws IOException {
    initialize();
    long stamp = closeLock.readLock();
    try {
      requireNotClosed();
      for (var entry : entries.values()) {
        removeEntry(entry, false);
      }
      indexWriteScheduler.trySchedule(); // Update entry set
    } finally {
      closeLock.unlockRead(stamp);
    }
  }

  @Override
  public long size() {
    return size.get();
  }

  @Override
  public void dispose() throws IOException {
    long stamp = closeLock.writeLock();
    try {
      doClose(true);
      size.set(0); // There's no contention on size at this point so there's no need to CAS
    } finally {
      closeLock.unlockWrite(stamp);
    }
  }

  @Override
  public void close() throws IOException {
    long stamp = closeLock.writeLock();
    try {
      doClose(false);
    } finally {
      closeLock.unlockWrite(stamp);
    }
  }

  private void doClose(boolean disposing) throws IOException {
    assert closeLock.isWriteLocked();
    if (closed) {
      return;
    }
    closed = true;

    // Make entries unmodifiable from now on
    for (var entry : entries.values()) {
      entry.freeze();
    }
    if (disposing) {
      // Avoid overlapping an index update with store directory deletion
      indexWriteScheduler.abortOrAwaitCurrentlyScheduled();
      deleteStoreContent(directory);
    } else {
      indexWriteScheduler.abortCurrentlyScheduled();
      // Make sure we close within our size bound
      evictExcessiveEntries();
      Utils.blockOnIO(indexWriteScheduler.scheduleNow());
    }
    indexExecutor.shutdown();
    evictionScheduler.stop();
    entries.clear();

    // Finally release the directory
    var lock = directoryLock;
    if (lock != null) {
      lock.release();
    }
  }

  @Override
  public void flush() throws IOException {
    long stamp = closeLock.readLock();
    try {
      if (!initialized || closed) {
        return; // There's nothing to flush
      }
      Utils.blockOnIO(indexWriteScheduler.scheduleNow());
    } finally {
      closeLock.unlockRead(stamp);
    }
  }

  private Set<EntryDescriptor> entrySetSnapshot() {
    var snapshot = new HashSet<EntryDescriptor>();
    for (var entry : entries.values()) {
      var descriptor = entry.descriptor();
      if (descriptor != null) {
        snapshot.add(descriptor);
      }
    }
    return Collections.unmodifiableSet(snapshot);
  }

  /**
   * Atomically evicts the given entry, returning its last committed size if evicted or -1
   * otherwise.
   */
  private long evict(Entry entry) throws IOException {
    assert holdsCloseLock();

    long evictedSize = entry.evict();
    return entries.remove(entry.hash, entry) ? evictedSize : -1L;
  }

  /**
   * Atomically evicts the given entry and decrements its size, returning {@code true} if the entry
   * was actually removed.
   */
  private boolean removeEntry(Entry entry, boolean scheduleIndexWrite) throws IOException {
    assert holdsCloseLock();

    long evictedSize = evict(entry);
    if (evictedSize >= 0) {
      size.addAndGet(-evictedSize);
      if (scheduleIndexWrite) {
        indexWriteScheduler.trySchedule(); // Update entry set
      }
      return true;
    }
    return false;
  }

  /**
   * Runs an eviction that is scheduled by {@link EvictionScheduler} and returns {@code true} only
   * if the store is not closed, returns {@code false} otherwise.
   */
  private boolean runScheduledEviction() throws IOException {
    assert initialized;
    long stamp = closeLock.readLock();
    try {
      if (closed) {
        // Ignore eviction, the store ensures it's closed within bounds
        return false;
      }
      if (evictExcessiveEntries()) {
        indexWriteScheduler.trySchedule(); // Update entry set
      }
      return true;
    } finally {
      closeLock.unlockRead(stamp);
    }
  }

  /**
   * Keeps evicting entries in LRU order as long as the size bound is exceeded, returning {@code
   * true} if at least one entry was evicted.
   */
  private boolean evictExcessiveEntries() throws IOException {
    assert holdsCloseLock();

    boolean evictedAtLeastOneEntry = false;
    Iterator<Entry> lruIterator = null;
    for (long currentSize = size.get(); currentSize > maxSize; ) {
      if (lruIterator == null) {
        lruIterator = entriesSnapshotInLruOrder().iterator();
      }
      if (!lruIterator.hasNext()) {
        break;
      }

      long evictedSize = evict(lruIterator.next());
      if (evictedSize >= 0) {
        currentSize = size.addAndGet(-evictedSize);
        evictedAtLeastOneEntry = true;
      } else {
        // Get fresh size in case of eviction races
        currentSize = size.get();
      }
    }
    return evictedAtLeastOneEntry;
  }

  private Collection<Entry> entriesSnapshotInLruOrder() {
    var lruEntries = new TreeMap<EntryDescriptor, Entry>(EntryDescriptor.LRU_ORDER);
    for (var entry : entries.values()) {
      var descriptor = entry.descriptor();
      if (descriptor != null) {
        lruEntries.put(descriptor, entry);
      } // Otherwise, the entry isn't readable so it doesn't have an applied size
    }
    return Collections.unmodifiableCollection(lruEntries.values());
  }

  private void requireNotClosed() {
    assert holdsCloseLock();
    requireState(!closed, "closed");
  }

  private boolean holdsCloseLock() {
    return closeLock.isReadLocked() || closeLock.isWriteLocked();
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
    try {
      Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING);
    } catch (AccessDeniedException accessDenied) {
      try {
        // Even tough NIO uses FILE_SHARE_DELETE on windows, Files::move + ATOMIC_MOVE
        // (on trails of MoveFileEx) behaves weirdly by throwing an AccessDeniedException
        // when the target has open handles. Explicitly deleting the target file beforehand
        // seems to fix this issue.
        Files.deleteIfExists(target);
        Files.move(source, target, ATOMIC_MOVE);
      } catch (IOException ioe) {
        ioe.addSuppressed(accessDenied);
        throw ioe;
      }
    }
  }

  private static void deleteStoreContent(Path directory) throws IOException {
    // Don't delete the lock file as the directory is still being used
    var lockFile = directory.resolve(LOCK_FILENAME);
    var filter = Predicate.not(lockFile::equals);
    try (var stream = Files.newDirectoryStream(directory, filter::test)) {
      for (var file : stream) {
        Files.delete(file);
      }
    } catch (DirectoryIteratorException e) {
      throw e.getCause();
    }
  }

  private static void closeQuietly(@Nullable Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "exception while closing: " + closeable, e);
      }
    }
  }

  private class ConcurrentViewerIterator implements Iterator<Viewer> {
    private final Iterator<Entry> entryIterator = entries.values().iterator();

    private @Nullable Viewer nextViewer;
    private @Nullable Entry nextEntry;

    /** The entry remove() would evict. */
    private @Nullable Entry currentEntry;

    ConcurrentViewerIterator() {}

    @Override
    @EnsuresNonNullIf(expression = "nextViewer", result = true)
    public boolean hasNext() {
      return nextViewer != null || findNextEntry();
    }

    @Override
    public Viewer next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      var viewer = castNonNull(nextViewer);
      nextViewer = null;
      currentEntry = nextEntry;
      nextEntry = null;
      return viewer;
    }

    @Override
    public void remove() {
      var entry = currentEntry;
      requireState(entry != null, "next() must be called before remove()");
      currentEntry = null;
      long stamp = closeLock.readLock();
      try {
        if (closed) {
          return; // Silently fail
        }

        try {
          removeEntry(entry, true);
        } catch (IOException ioe) {
          LOGGER.log(Level.WARNING, "exception while removing an entry via Iterator::remove", ioe);
        }
      } finally {
        closeLock.unlockRead(stamp);
      }
    }

    @EnsuresNonNullIf(expression = "nextViewer", result = true)
    private boolean findNextEntry() {
      long stamp = closeLock.readLock();
      try {
        if (closed) {
          return false; // End iteration
        }

        while (nextViewer == null && entryIterator.hasNext()) {
          var entry = entryIterator.next();
          try {
            var viewer = entry.openViewer(null); // Not expecting a specific key
            if (viewer != null) {
              nextEntry = entry;
              nextViewer = viewer;
              indexWriteScheduler.trySchedule(); // Update LRU info
              return true;
            }
          } catch (NoSuchFileException e) {
            // See view(String)
            try {
              removeEntry(entry, true);
            } catch (IOException ignored) {
            }
          } catch (IOException ignored) {
            // Try next entry...
          }
        }
        return false;
      } finally {
        closeLock.unlockRead(stamp);
      }
    }
  }

  private static final class IndexOperator {
    private final Path directory;
    private final Path indexFile;
    private final Path tempIndexFile;
    private final int appVersion;

    IndexOperator(Path directory, int appVersion) {
      this.directory = directory;
      this.appVersion = appVersion;
      indexFile = directory.resolve(INDEX_FILENAME);
      tempIndexFile = directory.resolve(TEMP_INDEX_FILENAME);
    }

    Set<EntryDescriptor> recoverEntrySet() throws IOException {
      var indexEntrySet = readOrCreateIndexIfAbsent();
      var entriesFoundOnDisk = scanDirectoryForEntries();
      var processedEntrySet = new HashSet<EntryDescriptor>(indexEntrySet.size());
      var toDelete = new HashSet<Path>(); // Stale entry files to delete
      for (var descriptor : indexEntrySet) {
        var entryFiles = entriesFoundOnDisk.get(descriptor.hash);
        if (entryFiles != null) {
          if (entryFiles.dirtyFile != null) {
            // An edit didn't complete properly, delete the dirty file
            toDelete.add(entryFiles.dirtyFile);
          }
          if (entryFiles.cleanFile != null) {
            processedEntrySet.add(descriptor);
          }
        }
      }
      // Delete untracked entries found on disk. This can happen if the index
      // couldn't be successfully updated after these entries were created,
      // probably due to a crash or missing a close().
      // TODO consider trying to recover these entries
      if (processedEntrySet.size() != entriesFoundOnDisk.size()) {
        var untrackedEntries = new HashMap<>(entriesFoundOnDisk);
        processedEntrySet.forEach(descriptor -> untrackedEntries.remove(descriptor.hash));
        for (var entryFiles : untrackedEntries.values()) {
          if (entryFiles.cleanFile != null) {
            toDelete.add(entryFiles.cleanFile);
          }
          if (entryFiles.dirtyFile != null) {
            toDelete.add(entryFiles.dirtyFile);
          }
        }
      }

      for (var file : toDelete) {
        Files.deleteIfExists(file);
      }
      return Collections.unmodifiableSet(processedEntrySet);
    }

    void writeIndex(Set<EntryDescriptor> entrySet) throws IOException {
      try (var channel = FileChannel.open(tempIndexFile, CREATE, WRITE)) {
        var header =
            ByteBuffer.allocate(INDEX_HEADER_SIZE)
                .putLong(INDEX_MAGIC)
                .putInt(STORE_VERSION)
                .putInt(appVersion)
                .putLong(entrySet.size());
        StoreIO.writeBytes(channel, header.flip());
        if (entrySet.size() > 0) {
          var entryTable = ByteBuffer.allocate(entrySet.size() * ENTRY_DESCRIPTOR_SIZE);
          entrySet.forEach(descriptor -> descriptor.writeTo(entryTable));
          StoreIO.writeBytes(channel, entryTable.flip());
        }
        channel.force(false);
      }
      replace(tempIndexFile, indexFile);
    }

    private Set<EntryDescriptor> readOrCreateIndexIfAbsent() throws IOException {
      // Delete the temp index file if it exists as a result of a possible crash
      Files.deleteIfExists(tempIndexFile);
      try {
        return readIndex();
      } catch (NoSuchFileException e) {
        // The index wasn't found, create a new one with 0 entries
        writeIndex(Set.of());
        return Set.of();
      } catch (StoreCorruptionException | EOFException e) {
        // The index is not readable, drop store contents and start afresh
        // TODO consider trying to rebuild the index from a directory scan instead
        LOGGER.log(Level.WARNING, "dropping store contents due to an unreadable index", e);

        deleteStoreContent(directory);
        writeIndex(Set.of());
        return Set.of();
      }
    }

    // TODO add an upgrade routine if version changes
    private Set<EntryDescriptor> readIndex() throws IOException {
      try (var channel = FileChannel.open(indexFile, READ)) {
        var header = StoreIO.readNBytes(channel, INDEX_HEADER_SIZE);
        checkValue(INDEX_MAGIC, header.getLong(), "not in index format");
        checkValue(STORE_VERSION, header.getInt(), "unknown store version");
        checkValue(appVersion, header.getInt(), "unknown app version");

        long entryCount = header.getLong();
        checkValue(
            entryCount >= 0 && entryCount <= MAX_ENTRY_COUNT, "invalid entry count", entryCount);
        if (entryCount == 0) {
          return Set.of();
        }
        int intEntryCount = (int) entryCount;
        int entryTableSize = intEntryCount * ENTRY_DESCRIPTOR_SIZE;
        var entryTable = StoreIO.readNBytes(channel, entryTableSize);
        var result = new HashSet<EntryDescriptor>(intEntryCount);
        for (int i = 0; i < intEntryCount; i++) {
          result.add(new EntryDescriptor(entryTable));
        }
        return Collections.unmodifiableSet(result);
      }
    }

    private Map<Hash, EntryFiles> scanDirectoryForEntries() throws IOException {
      var scanResult = new HashMap<Hash, EntryFiles>();
      try (var stream = Files.newDirectoryStream(directory)) {
        for (var path : stream) {
          var filename = path.getFileName().toString();
          if (filename.equals(INDEX_FILENAME)
              || filename.equals(TEMP_INDEX_FILENAME)
              || filename.equals(LOCK_FILENAME)) {
            continue; // Skip non-entry files
          }

          Hash entryHash;
          if (Files.isRegularFile(path)
              && (filename.endsWith(ENTRY_FILE_SUFFIX) || filename.endsWith(TEMP_ENTRY_FILE_SUFFIX))
              && (entryHash = entryFileToHash(filename)) != null) {
            var files = scanResult.computeIfAbsent(entryHash, __ -> new EntryFiles());
            if (filename.endsWith(ENTRY_FILE_SUFFIX)) {
              files.cleanFile = path;
            } else {
              files.dirtyFile = path;
            }
          } else {
            LOGGER.warning(
                "unrecognized file or directory found during initialization: "
                    + path
                    + System.lineSeparator()
                    + "it is generally not a good idea to let the cache directory be used by other entities");
          }
        }
      }
      return scanResult;
    }

    /** Entry related files found by a directory scan. */
    private static final class EntryFiles {
      @MonotonicNonNull Path cleanFile;
      @MonotonicNonNull Path dirtyFile;

      EntryFiles() {}
    }
  }

  /**
   * A scheduler for index updates that time-limits writes such that no more than one write is
   * performed each INDEX_UPDATE_DELAY.toMillis().
   */
  private static final class IndexWriteScheduler {
    private final IndexOperator indexOperator;
    private final Duration flushDelay;
    private final Executor indexExecutor;
    private final Supplier<Set<EntryDescriptor>> entrySetSnapshotSupplier;
    private final Clock clock;
    private final AtomicReference<IndexWriteTask> scheduledWriteTask = new AtomicReference<>();

    IndexWriteScheduler(
        IndexOperator indexOperator,
        Duration flushDelay,
        Executor indexExecutor,
        Supplier<Set<EntryDescriptor>> entrySetSnapshotSupplier,
        Clock clock) {
      this.indexOperator = indexOperator;
      this.flushDelay = flushDelay;
      this.indexExecutor = indexExecutor;
      this.entrySetSnapshotSupplier = entrySetSnapshotSupplier;
      this.clock = clock;
    }

    void trySchedule() {
      // Decide whether to schedule and when as follows:
      // - If scheduledWriteTask is null, then this is the first call,
      //   so schedule immediately.
      // - If scheduledWriteTask is set to run in the future, then it'll
      //   see the changes made so far and there's no need to schedule.
      // - If less than INDEX_UPDATE_DELAY time has passed since the last
      //   write, then schedule a write to when INDEX_UPDATE_DELAY will be
      //   evaluated from the last write.
      // - Otherwise, a timeslot is available, so schedule immediately.
      //
      // This is retried in case of contention.

      var now = clock.instant();
      while (true) {
        var task = scheduledWriteTask.get();
        var nextFireTime = task != null ? task.fireTime : null;
        Duration delay;
        if (nextFireTime == null) {
          delay = Duration.ZERO;
        } else if (nextFireTime.isAfter(now)) {
          delay = null;
        } else {
          var idleness = Duration.between(nextFireTime, now);
          var remainingUpdateDelay = flushDelay.minus(idleness);
          delay = max(remainingUpdateDelay, Duration.ZERO);
        }

        IndexWriteTask nextTask = null;
        if (delay == null
            || scheduledWriteTask.compareAndSet(
                task, nextTask = new IndexWriteTask(now.plus(delay)))) {
          if (nextTask != null) {
            getDelayedExecutor(delay).execute(nextTask.logOnFailure());
          }
          break;
        }
      }
    }

    /** Forcibly submit an index write to the index executor, ignoring the time rate. */
    CompletableFuture<Void> scheduleNow() {
      var newTask = new IndexWriteTask(clock.instant()); // Firing now...
      var task = scheduledWriteTask.getAndSet(newTask);
      if (task != null) {
        task.abort(); // OK if already ran or running
      }
      return Unchecked.runAsync(newTask, indexExecutor);
    }

    private Executor getDelayedExecutor(Duration delay) {
      return delay.equals(Duration.ZERO)
          ? indexExecutor // Execute immediately
          : CompletableFuture.delayedExecutor(
              delay.toMillis(), TimeUnit.MILLISECONDS, indexExecutor);
    }

    void abortCurrentlyScheduled() {
      var task = scheduledWriteTask.getAndSet(null);
      if (task != null) {
        task.abort();
      }
    }

    void abortOrAwaitCurrentlyScheduled() throws InterruptedIOException {
      var task = scheduledWriteTask.getAndSet(null);
      if (task != null && !task.abort()) {
        task.awaitDone();
      }
    }

    private final class IndexWriteTask implements ThrowingRunnable {
      final Instant fireTime;
      private final AtomicBoolean fence = new AtomicBoolean();
      private final CountDownLatch done = new CountDownLatch(1);

      IndexWriteTask(Instant fireTime) {
        this.fireTime = fireTime;
      }

      @Override
      public void run() throws IOException {
        if (fence.compareAndSet(false, true)) {
          try {
            indexOperator.writeIndex(entrySetSnapshotSupplier.get());
          } finally {
            done.countDown();
          }
        }
      }

      boolean abort() {
        return fence.compareAndSet(false, true);
      }

      void awaitDone() throws InterruptedIOException {
        try {
          done.await();
        } catch (InterruptedException e) {
          throw new InterruptedIOException();
        }
      }

      Runnable logOnFailure() {
        // TODO try instead to disable the store if this happens too often
        return () -> {
          try {
            run();
          } catch (IOException e) {
            LOGGER.log(Level.WARNING, "index write failed", e);
          }
        };
      }
    }
  }

  /** Schedules eviction tasks on demand while ensuring they're run sequentially. */
  private static final class EvictionScheduler {
    private static final int RUN = 0x1;
    private static final int KEEP_ALIVE = 0x2;
    private static final int STOP = 0x4;

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

    @SuppressWarnings("unused") // VarHandle indirection
    private volatile int sync;

    EvictionScheduler(DiskStore store, Executor executor) {
      this.store = store;
      this.executor = executor;
    }

    void schedule() {
      for (int s; ((s = sync) & STOP) == 0; ) {
        int bit = (s & RUN) == 0 ? RUN : KEEP_ALIVE; // Run or keep-alive
        if (SYNC.compareAndSet(this, s, (s | bit))) {
          if (bit == RUN) {
            executor.execute(this::runEviction);
          }
          break;
        }
      }
    }

    private void runEviction() {
      for (int s; ((s = sync) & STOP) == 0; ) {
        try {
          if (!store.runScheduledEviction()) {
            // The store is closed
            stop();
            break;
          }
        } catch (IOException ioe) {
          LOGGER.log(Level.SEVERE, "exception while running background eviction", ioe);
        }

        // Exit or consume keep-alive bit
        int bit = (s & KEEP_ALIVE) != 0 ? KEEP_ALIVE : RUN;
        if (SYNC.compareAndSet(this, s, s & ~bit) && bit == RUN) {
          break;
        }
      }
    }

    void stop() {
      SYNC.getAndBitwiseOr(this, STOP);
    }
  }

  /**
   * A lock on the store directory that ensures it's operated upon by a single DiskStore instance in
   * a single JVM process. This only works in a cooperative manner; it doesn't prevent other
   * entities from using the directory.
   */
  private static final class DirectoryLock {
    private static final ConcurrentHashMap<Path, DirectoryLock> acquiredLocks =
        new ConcurrentHashMap<>();

    private final Path directory;
    private final Path lockFile;

    // Initialized lazily when DirectoryLock is successfully put in acquiredLocks
    private volatile @MonotonicNonNull FileChannel channel;
    private volatile @MonotonicNonNull FileLock fileLock;

    DirectoryLock(Path directory) {
      this.directory = directory;
      this.lockFile = directory.resolve(LOCK_FILENAME);
    }

    void release() throws IOException {
      closeQuietly(channel); // Closing the channel also releases fileLock
      // Delete lock file if the lock could be acquired
      if (fileLock != null) {
        Files.deleteIfExists(lockFile);
      }
      acquiredLocks.remove(directory, this);
    }

    static DirectoryLock acquire(Path directory) throws IOException {
      var lock = new DirectoryLock(directory);
      var inserted = acquiredLocks.putIfAbsent(directory, lock) == null;
      if (!inserted || !tryLock(lock)) {
        lock.release();
        throw new IOException(
            format(
                "cache directory <%s> is being used by another %s",
                directory, (inserted ? "process" : "instance")));
      }
      return lock;
    }

    private static boolean tryLock(DirectoryLock lock) throws IOException {
      var channel =
          FileChannel.open(
              lock.lockFile, CREATE, WRITE); // WRITE is needed for an exclusive FileLock
      lock.channel = channel;
      try {
        var fileLock = channel.tryLock();
        lock.fileLock = fileLock;
        return fileLock != null;
      } catch (OverlappingFileLockException e) {
        return false;
      } catch (IOException ioe) {
        // Make sure channel is closed if tryLock() throws
        closeQuietly(channel);
        throw ioe;
      }
    }
  }

  /** Function that computes the {@code Hash} of an entry key. */
  @FunctionalInterface
  public interface Hasher {
    /** A Hasher returning the first 80 bits of the SHA-256 of the key's UTF-8 encoded bytes. */
    Hasher TRUNCATED_SHA_256 = Hasher::truncatedSha256Hash;

    Hash hash(String key);

    private static Hash truncatedSha256Hash(String key) {
      var digest = sha256Digest();
      digest.update(UTF_8.encode(key));
      var buffer = ByteBuffer.wrap(digest.digest());
      return new Hash(buffer);
    }

    private static MessageDigest sha256Digest() {
      try {
        return MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new UnsupportedOperationException("SHA-256 not available!", e);
      }
    }
  }

  /** An immutable 80-bit hash code. */
  public static final class Hash {
    private static final int BYTES = 10; // 80 bits
    private static final int HEX_STRING_LENGTH = 2 * BYTES;

    // Upper 64 bits + lower 16 bits in big-endian order
    private final long upper64Bits;
    private final short lower16Bits;
    private @MonotonicNonNull String lazyHex;

    public Hash(ByteBuffer buffer) {
      this.upper64Bits = buffer.getLong();
      this.lower16Bits = buffer.getShort();
    }

    void writeTo(ByteBuffer buffer) {
      assert buffer.remaining() >= BYTES;
      buffer.putLong(upper64Bits);
      buffer.putShort(lower16Bits);
    }

    String toHexString() {
      var hex = lazyHex;
      if (hex == null) {
        var sb = new StringBuilder(HEX_STRING_LENGTH);
        var buffer = ByteBuffer.allocate(BYTES);
        writeTo(buffer);
        buffer.flip();
        while (buffer.hasRemaining()) {
          byte b = buffer.get();
          char upperHex = Character.forDigit((b >> 4) & 0xf, 16);
          char lowerHex = Character.forDigit(b & 0xf, 16);
          sb.append(upperHex).append(lowerHex);
        }
        hex = sb.toString();
        lazyHex = hex;
      }
      return hex;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(upper64Bits) ^ Short.hashCode(lower16Bits);
    }

    @Override
    public boolean equals(Object obj) {
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
      var buffer = ByteBuffer.allocate(BYTES);
      for (int i = 0; i < BYTES; i++) {
        int upperNibble = Character.digit(hex.charAt(2 * i), 16);
        int lowerNibble = Character.digit(hex.charAt(2 * i + 1), 16);
        if (upperNibble == -1 || lowerNibble == -1) {
          return null; // Not a hex character
        }
        buffer.put((byte) ((upperNibble << 4) | lowerNibble));
      }
      return new Hash(buffer.flip());
    }
  }

  private static final class EntryDescriptor {
    /**
     * A comparator with LRU eviction order, preferring to evict smaller entries over larger ones
     * when used within the same timestamp.
     */
    static final Comparator<EntryDescriptor> LRU_ORDER =
        Comparator.<EntryDescriptor, Instant>comparing(descriptor -> descriptor.lastUsed)
            .thenComparingLong(descriptor -> descriptor.size);

    final Hash hash;
    final Instant lastUsed;
    final long size;

    EntryDescriptor(Hash hash, Instant lastUsed, long size) {
      this.hash = hash;
      this.lastUsed = lastUsed;
      this.size = size;
    }

    EntryDescriptor(ByteBuffer buffer) throws StoreCorruptionException {
      hash = new Hash(buffer);
      lastUsed = Instant.ofEpochMilli(buffer.getLong());
      size = getPositiveLong(buffer);
    }

    void writeTo(ByteBuffer buffer) {
      assert buffer.remaining() >= ENTRY_DESCRIPTOR_SIZE;
      hash.writeTo(buffer);
      buffer.putLong(lastUsed.toEpochMilli());
      buffer.putLong(size);
    }

    @Override
    public int hashCode() {
      return hash.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || (obj instanceof EntryDescriptor && hash.equals(((EntryDescriptor) obj).hash));
    }
  }

  private final class Entry {
    static final int ANY_ENTRY_VERSION = -1;

    private final ReentrantLock lock = new ReentrantLock();

    final Hash hash;

    /**
     * This entry's key as known from the last open/edit. Used to minimize removals of the wrong
     * entry in case of hash collisions, only if the entry knows its key. If it doesn't however,
     * the entry is evicted either ways, which is considered better than having to read the entry
     * file in each remove(String), bearing in mind that collisions under the used hasher are
     * extremely rare in practice.
     */
    volatile @MonotonicNonNull String cachedKey;

    private Instant lastUsed;
    private long entrySize;

    // Lazily initialized in a racy manner
    @MonotonicNonNull Path entryFile;
    @MonotonicNonNull Path tempEntryFile;

    private @Nullable DiskEditor currentEditor;

    /**
     * The number of times this entry has changed. Starts with 1 if the entry is recovered during
     * initialization, or 0 if newly created for opening an editor. On the latter case, the entry
     * won't be viewable until the edit is committed.
     */
    private int entryVersion;

    /**
     * True if this entry has been evicted. This is necessary since the entry temporarily lives as a
     * 'ghost' after it's been evicted but before it's removed from the map, so it mustn't open any
     * viewers or editors during this period.
     */
    private boolean evicted;

    /**
     * True to refuse opening any editors. This is set when the store is closing before the last
     * index write so that it captures the entry's final state.
     */
    private boolean frozen;

    Entry(Hash hash) {
      this.hash = hash;
      lastUsed = Instant.MAX;
    }

    Entry(EntryDescriptor descriptor) {
      this.hash = descriptor.hash;
      lastUsed = descriptor.lastUsed;
      entrySize = descriptor.size;
      entryVersion = 1;
    }

    boolean isReadable() {
      lock.lock();
      try {
        return entryVersion > 0 && !evicted;
      } finally {
        lock.unlock();
      }
    }

    @Nullable
    EntryDescriptor descriptor() {
      lock.lock();
      try {
        return isReadable() ? new EntryDescriptor(hash, lastUsed, entrySize) : null;
      } finally {
        lock.unlock();
      }
    }

    /** @param key expected entry key or {@code null} to open for any key. */
    @Nullable
    Viewer openViewer(@Nullable String key) throws IOException {
      lock.lock();
      try {
        var result = tryReadEntry(key);
        if (result == null) {
          return null;
        }
        var channel =
            AsynchronousFileChannel.open(
                entryFile(), Set.of(READ), ExecutorServiceAdapter.adapt(executor));
        var viewer =
            new DiskViewer(
                this, entryVersion, result.key, result.metadata, channel, result.dataSize);
        lastUsed = clock.instant();
        return viewer;
      } finally {
        lock.unlock();
      }
    }

    @Nullable
    Editor newEditor(String key, int targetEntryVersion) {
      lock.lock();
      try {
        if (currentEditor != null // An edit is already in progress
            || (targetEntryVersion != entryVersion
                && targetEntryVersion != ANY_ENTRY_VERSION) // Viewer is stale
            || evicted
            || frozen) {
          return null;
        }

        var editor = new DiskEditor(this, key);
        currentEditor = editor;
        lastUsed = clock.instant();
        return editor;
      } finally {
        lock.unlock();
      }
    }

    void freeze() throws IOException {
      lock.lock();
      try {
        frozen = true;
        discardCurrentEdit();
      } finally {
        lock.unlock();
      }
    }

    private void discardCurrentEdit() throws IOException {
      assert lock.isHeldByCurrentThread();

      var editor = currentEditor;
      currentEditor = null;
      if (editor != null) {
        editor.discard();
      }
    }

    void commitEdit(
        DiskEditor editor,
        String key,
        @Nullable ByteBuffer newMetadata,
        @Nullable AsynchronousFileChannel dataChannel, // null if no data was written
        long dataSize)
        throws IOException {
      long oldEntrySize;
      long newEntrySize;
      boolean firstTimeReadable;
      lock.lock();
      try {
        boolean ownedEditor = currentEditor == editor;
        currentEditor = null;
        if (!ownedEditor
            || (newMetadata == null && dataChannel == null) // Edit isn't committed
            || evicted) {
          closeQuietly(dataChannel);
          Files.deleteIfExists(tempEntryFile());
          if (entryVersion == 0 && !evicted) {
            // The entry's first ever edit wasn't committed, so remove it. It's safe
            // to directly remove it from the map since it's not visible to the user
            // at this point (no views/edits) and doesn't contribute to store size.
            evicted = true;
            entries.remove(hash, this);
          }
          return;
        }

        // Write a new entry file (replacing the previous one if existed) if data
        // is written, or if this is the entry's first edit. Otherwise, just update
        // the end of the old entry file with the new metadata (and possibly new key).
        var readResult = tryReadEntry(null); // Read old entry for whatever key
        var oldMetadata = readResult != null ? readResult.metadata : EMPTY_BUFFER;
        var metadataToWrite = requireNonNullElse(newMetadata, oldMetadata);
        long newMetadataSize = metadataToWrite.remaining();
        if (dataChannel != null || readResult == null) {
          writeEntry(key, metadataToWrite, dataChannel, dataSize);
        } else {
          updateEntry(key, metadataToWrite, readResult.dataSize); // No changes to data stream
        }

        long updatedDataSize;
        if (dataChannel != null) {
          updatedDataSize = dataSize;
        } else if (readResult != null) {
          // Data is untouched
          updatedDataSize = readResult.dataSize;
        } else {
          // The entry wasn't readable
          updatedDataSize = 0L;
        }
        newEntrySize = newMetadataSize + updatedDataSize;
        oldEntrySize = entrySize;
        entrySize = newEntrySize;
        cachedKey = key; // Now we know what's our key
        firstTimeReadable = entryVersion == 0;
        entryVersion++;
      } finally {
        lock.unlock();
      }

      // TODO if the entry is itself larger than maxSize, just discard it
      long netEntrySize = newEntrySize - oldEntrySize; // Might be negative
      if (size.addAndGet(netEntrySize) > maxSize) {
        evictionScheduler.schedule();
      }

      if (firstTimeReadable) {
        indexWriteScheduler.trySchedule(); // Update LRU info if we've just become readable
      }
    }

    private void writeEntry(
        String key,
        ByteBuffer metadata,
        @Nullable AsynchronousFileChannel dataChannel, // null if no data was written
        long dataSize)
        throws IOException {
      var footer = buildEntryFooter(UTF_8.encode(key), metadata, dataSize);
      if (dataChannel != null) {
        try (dataChannel) {
          Utils.blockOnIO(StoreIO.writeBytesAsync(dataChannel, footer, dataSize));
          dataChannel.force(false);
        }
      } else {
        try (var channel = FileChannel.open(tempEntryFile(), CREATE, WRITE)) {
          StoreIO.writeBytes(channel, footer, dataSize);
          channel.force(false);
        }
      }
      replace(tempEntryFile(), entryFile());
    }

    private void updateEntry(String key, ByteBuffer metadata, long dataSize) throws IOException {
      // Have the entry's temp file as our work file. This ensures a clean file
      // doesn't end up in a corrupt state in case of crashes.
      replace(entryFile(), tempEntryFile());
      var footer = buildEntryFooter(UTF_8.encode(key), metadata, dataSize);
      try (var channel = FileChannel.open(tempEntryFile(), WRITE)) {
        // Truncate in case the previous entry had a larger size
        channel.truncate(dataSize + footer.remaining());
        StoreIO.writeBytes(channel, footer, dataSize);
        channel.force(false);
      }
      replace(tempEntryFile(), entryFile());
    }

    private ByteBuffer buildEntryFooter(ByteBuffer key, ByteBuffer metadata, long dataSize) {
      int keySize = key.remaining();
      int metadataSize = metadata.remaining();
      return ByteBuffer.allocate(keySize + metadataSize + ENTRY_TRAILER_SIZE)
          .put(key)
          .put(metadata)
          .putLong(ENTRY_MAGIC)
          .putInt(STORE_VERSION)
          .putInt(appVersion)
          .putInt(keySize)
          .putInt(metadataSize)
          .putLong(dataSize)
          .flip();
    }

    /** Reads this entry only if it's readable provided its key matches expectedKey if not null. */
    private @Nullable EntryReadResult tryReadEntry(@Nullable String expectedKey)
        throws IOException {
      var key = cachedKey;
      if (isReadable() && (key == null || expectedKey == null || key.equals(expectedKey))) {
        var readResult = readEntry();
        if (expectedKey == null || readResult.key.equals(expectedKey)) {
          cachedKey = readResult.key;
          return readResult;
        }
      }
      return null;
    }

    private EntryReadResult readEntry() throws IOException {
      try (var channel = FileChannel.open(entryFile(), READ)) {
        var trailer =
            StoreIO.readNBytes(
                channel, ENTRY_TRAILER_SIZE, /* position */ channel.size() - ENTRY_TRAILER_SIZE);
        checkValue(ENTRY_MAGIC, trailer.getLong(), "not in entry file format");
        checkValue(STORE_VERSION, trailer.getInt(), "unexpected store version");
        checkValue(appVersion, trailer.getInt(), "unexpected app version");

        int keySize = getNonNegativeInt(trailer);
        int metadataSize = getNonNegativeInt(trailer);
        long dataSize = getNonNegativeLong(trailer);
        var keyAndMetadata =
            StoreIO.readNBytes(channel, keySize + metadataSize, /* position */ dataSize);
        var key = UTF_8.decode(keyAndMetadata.limit(keySize)).toString();
        var metadata =
            keyAndMetadata
                .limit(keySize + metadataSize)
                .slice() // Slice to have 0 position metadataSize capacity
                .asReadOnlyBuffer();
        return new EntryReadResult(key, metadata, dataSize);
      }
    }

    /**
     * Evicts this entry and returns either -1 if this entry is already evicted, it's last committed
     * size if the entry was readable or 0 otherwise.
     */
    long evict() throws IOException {
      lock.lock();
      try {
        if (evicted) {
          return -1L; // Already evicted
        }

        evicted = true;
        discardCurrentEdit();
        Files.deleteIfExists(entryFile());
        return entrySize; // 0 if the entry wasn't readable
      } finally {
        lock.unlock();
      }
    }

    Path entryFile() {
      var file = entryFile;
      if (file == null) {
        file = directory.resolve(hash.toHexString() + ENTRY_FILE_SUFFIX);
        entryFile = file;
      }
      return file;
    }

    Path tempEntryFile() {
      var file = tempEntryFile;
      if (file == null) {
        file = directory.resolve(hash.toHexString() + TEMP_ENTRY_FILE_SUFFIX);
        tempEntryFile = file;
      }
      return file;
    }

    ExecutorService newChannelExecutor() {
      return ExecutorServiceAdapter.adapt(executor);
    }
  }

  private static final class EntryReadResult {
    final String key;
    final ByteBuffer metadata;
    final long dataSize;

    EntryReadResult(String key, ByteBuffer metadata, long dataSize) {
      this.key = key;
      this.metadata = metadata;
      this.dataSize = dataSize;
    }
  }

  private static final class DiskViewer implements Viewer {
    private final Entry entry;

    /** Entry's version at the time of opening the viewer. */
    private final int entryVersion;

    private final String key;
    private final ByteBuffer metadata;
    private final AsynchronousFileChannel channel;
    private final long dataSize;

    DiskViewer(
        Entry entry,
        int entryVersion,
        String key,
        ByteBuffer metadata,
        AsynchronousFileChannel channel,
        long dataSize) {
      this.entry = entry;
      this.entryVersion = entryVersion;
      this.key = key;
      this.metadata = metadata;
      this.channel = channel;
      this.dataSize = dataSize;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public ByteBuffer metadata() {
      return metadata.duplicate();
    }

    @Override
    public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
      requireArgument(position >= 0, "negative position: %d", position);
      requireNonNull(dst);
      // Make sure the read doesn't exceed data stream bounds
      long availableBytes = dataSize - position;
      if (availableBytes <= 0) {
        return CompletableFuture.completedFuture(-1); // End of data stream
      }
      int toRead = (int) Math.min(availableBytes, dst.remaining());
      int originalLimit = dst.limit();
      dst.limit(dst.position() + toRead);
      return StoreIO.readBytesAsync(channel, dst, position)
          .thenRun(() -> dst.limit(originalLimit))
          .thenApply(__ -> toRead);
    }

    @Override
    public long dataSize() {
      return dataSize;
    }

    @Override
    public long entrySize() {
      return metadata.remaining() + dataSize;
    }

    @Override
    public @Nullable Editor edit() throws IOException {
      return entry.newEditor(key(), entryVersion);
    }

    @Override
    public void close() {
      closeQuietly(channel);
    }
  }

  private static final class DiskEditor implements Editor {
    private final Entry entry;
    private final String key;
    private final Lock lock = new ReentrantLock();

    private ByteBuffer metadata = EMPTY_BUFFER;
    private boolean editedMetadata;
    private @MonotonicNonNull AsynchronousFileChannel lazyChannel;
    private long writtenCount;
    private boolean committed;
    private boolean closed;

    DiskEditor(Entry entry, String key) {
      this.entry = entry;
      this.key = key;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public void metadata(ByteBuffer metadata) {
      lock.lock();
      try {
        requireNotCommitted();
        this.metadata = Utils.copy(metadata, this.metadata);
        editedMetadata = true;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
      AsynchronousFileChannel channel;
      lock.lock();
      try {
        requireNotCommitted();
        requireArgument(
            position >= 0 && position <= writtenCount, "position out of range: %d", position);
        if (closed) {
          // Instead of throwing, simulate a fake write. This is an expected scenario when
          // the editor is used concurrently. For example, if a thread discards the edit by
          // closing the editor (due to a failed write or closing the store), another thread
          // might not immediately know and try to schedule more writes when it gets more data.
          int fakeWritten = src.remaining();
          src.position(src.position() + fakeWritten);
          return CompletableFuture.completedFuture(fakeWritten);
        }

        channel = lazyChannel;
        if (channel == null) {
          channel =
              AsynchronousFileChannel.open(
                  entry.tempEntryFile(), Set.of(WRITE, CREATE), entry.newChannelExecutor());
          lazyChannel = channel;
        }
      } catch (IOException ioe) {
        return CompletableFuture.failedFuture(ioe);
      } finally {
        lock.unlock();
      }
      return StoreIO.writeBytesAsync(channel, src, position)
          .thenApply(written -> updateWrittenCount(position, written));
    }

    private int updateWrittenCount(long position, int written) {
      lock.lock();
      try {
        writtenCount = Math.max(writtenCount, position + written);
        return written;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void commitOnClose() {
      lock.lock();
      try {
        committed = true;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void close() throws IOException {
      ByteBuffer newMetadata = null;
      AsynchronousFileChannel channel = null;
      long dataSize = -1;
      lock.lock();
      try {
        if (closed) {
          return;
        }
        closed = true;

        if (committed) {
          newMetadata = editedMetadata ? Utils.copy(metadata) : null;
          channel = lazyChannel;
          dataSize = writtenCount;
        }
      } finally {
        lock.unlock();
      }

      entry.commitEdit(this, key, newMetadata, channel, dataSize);
    }

    /** Discards anything that's written or about to be written (only if not closed). */
    void discard() throws IOException {
      lock.lock();
      try {
        if (closed) {
          return;
        }
        closed = true;
        closeQuietly(lazyChannel);
        lazyChannel = null;
        Files.deleteIfExists(entry.tempEntryFile());
      } finally {
        lock.unlock();
      }
    }

    private void requireNotCommitted() {
      requireState(!committed, "committed");
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private static final int UNSET = -1;

    private @MonotonicNonNull Path directory;
    private long maxSize = UNSET;
    private @MonotonicNonNull Executor executor;
    private int appVersion = UNSET;
    public @MonotonicNonNull Hasher hasher;
    private @MonotonicNonNull Clock clock;
    private @MonotonicNonNull Duration indexFlushDelay;

    Builder() {}

    public Builder directory(Path directory) {
      this.directory = requireNonNull(directory);
      return this;
    }

    public Builder maxSize(long maxSize) {
      requireArgument(maxSize > 0, "non-positive max size");
      this.maxSize = maxSize;
      return this;
    }

    public Builder executor(Executor executor) {
      this.executor = requireNonNull(executor);
      return this;
    }

    public Builder appVersion(int appVersion) {
      this.appVersion = appVersion;
      return this;
    }

    public Builder hasher(Hasher hasher) {
      this.hasher = requireNonNull(hasher);
      return this;
    }

    public Builder clock(@Nullable Clock clock) {
      this.clock = clock;
      return this;
    }

    public Builder indexFlushDelay(@Nullable Duration duration) {
      if (duration != null) {
        requireArgument(!duration.isNegative(), "negative duration");
        this.indexFlushDelay = duration;
      }
      return this;
    }

    public DiskStore build() {
      requireState(
          directory != null && maxSize != UNSET && executor != null && appVersion != UNSET,
          "missing required fields");
      return new DiskStore(this);
    }
  }
}
