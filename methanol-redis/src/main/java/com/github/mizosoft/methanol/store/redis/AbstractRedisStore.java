/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.store.redis;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.async.RedisHashAsyncCommands;
import io.lettuce.core.api.async.RedisKeyAsyncCommands;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@link Store} implementation backed by Redis. Specialized implementations are {@link
 * RedisStandaloneStore} and {@link RedisClusterStore} for Redis Standalone and Redis Cluster setups
 * respectively.
 */
abstract class AbstractRedisStore<C extends StatefulConnection<String, ByteBuffer>>
    implements Store {
  static final Logger logger = System.getLogger(AbstractRedisStore.class.getName());

  static final long ANY_ENTRY_VERSION = -1;
  static final int STORE_VERSION = 1;
  static final String INITIAL_CURSOR_VALUE = "0";
  static final int REMOVE_ALL_SCAN_LIMIT = 128;
  static final int ITERATOR_SCAN_LIMIT = 64;

  static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  final C connection;
  final RedisConnectionProvider<C> connectionProvider;
  final int editorLockTtlSeconds;
  final int staleEntryTtlSeconds;
  final int appVersion;

  /**
   * The key to a shared monotonic clock (counter) used for versioning entries (See {@link
   * Script#COMMIT}). This avoids ABA problems caused by restarting versions with 0. Consider the
   * following scenario:
   *
   * <ul>
   *   <li>A entry is newly created with dataVersion = 0, and dataKey = '...data:0'.
   *   <li>A viewer is opened for that entry, referencing data with dataKey = '...data:0'.
   *   <li>The entry is deleted, then created again with dataVersion = 0 (dataKey = '...data:0'),
   *       all while the viewer is still active (with activity suspended between deletion and
   *       creation).
   *   <li>The viewer continues to read with dataKey = '...data:0', thinking it's still reading the
   *       data of the entry it was opened for. But the data it reads is partly old & partly new!
   * </ul>
   *
   * <p>A shared counter is only used in Redis Standalone. Scripts executed in clusters base
   * versioning on time instead, as they can't reliably share one key between nodes.
   */
  final String clockKey;

  volatile boolean closed;

  /** A lock to ensure only one call to {@code close()} proceeds to closing underlying resources. */
  final Lock closeLock = new ReentrantLock();

  AbstractRedisStore(
      C connection,
      RedisConnectionProvider<C> connectionProvider,
      int editorLockTtlSeconds,
      int staleEntryTtlSeconds,
      int appVersion,
      String clockKey) {
    requireArgument(
        editorLockTtlSeconds > 0, "expected a positive ttl, got: %s", editorLockTtlSeconds);
    requireArgument(
        staleEntryTtlSeconds > 0, "expected a positive ttl, got: %s", staleEntryTtlSeconds);
    this.connection = requireNonNull(connection);
    this.connectionProvider = requireNonNull(connectionProvider);
    this.editorLockTtlSeconds = editorLockTtlSeconds;
    this.staleEntryTtlSeconds = staleEntryTtlSeconds;
    this.appVersion = appVersion;
    this.clockKey = requireNonNull(clockKey);
  }

  abstract <
          CMD extends
              RedisHashAsyncCommands<String, ByteBuffer>
                  & RedisScriptingAsyncCommands<String, ByteBuffer>
                  & RedisKeyAsyncCommands<String, ByteBuffer>
                  & RedisStringAsyncCommands<String, ByteBuffer>>
      CMD commands();

  @Override
  public long maxSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  @Override
  public Optional<Viewer> view(String key) throws IOException, InterruptedException {
    return Utils.get(viewAsync(key));
  }

  @Override
  public CompletableFuture<Optional<Viewer>> viewAsync(String key) {
    return viewAsync(key, toEntryKey(key));
  }

  private CompletableFuture<Optional<Viewer>> viewAsync(@Nullable String key, String entryKey) {
    requireNotClosed();
    return commands()
        .hmget(entryKey, "metadata", "dataSize", "entryVersion", "dataVersion")
        .thenApply(fields -> createViewerIfPresent(key, entryKey, fields))
        .toCompletableFuture();
  }

  private Optional<Viewer> createViewerIfPresent(
      @Nullable String key, String entryKey, List<KeyValue<String, ByteBuffer>> fields) {
    return fields.stream().allMatch(Predicate.not(KeyValue::isEmpty))
        ? Optional.of(
            createViewer(key, entryKey, fields.stream().map(KeyValue::getValue)::iterator))
        : Optional.empty();
  }

  private Viewer createViewer(@Nullable String key, String entryKey, Iterable<ByteBuffer> fields) {
    var fieldsIter = fields.iterator();
    var metadata = fieldsIter.next();
    long dataSize = decodeLong(fieldsIter.next());
    long entryVersion = decodeLong(fieldsIter.next());
    long dataVersion = decodeLong(fieldsIter.next());
    return new RedisViewer(
        key, entryKey, entryKey + ":data:" + dataVersion, entryVersion, metadata, dataSize);
  }

  @Override
  public Optional<Editor> edit(String key) throws IOException, InterruptedException {
    return Utils.get(editAsync(key));
  }

  @Override
  public CompletableFuture<Optional<Editor>> editAsync(String key) {
    return editAsync(key, toEntryKey(key), ANY_ENTRY_VERSION);
  }

  private CompletableFuture<Optional<Editor>> editAsync(
      @Nullable String key, String entryKey, long targetEntryVersion) {
    requireNotClosed();
    // TODO prepend client ID
    var editorId = UUID.randomUUID().toString();
    var editorLockKey = entryKey + ":editor";
    var wipDataKey = entryKey + ":wip_data:" + editorId;
    return Script.EDIT
        .evalOn(commands())
        .asBoolean(
            List.of(entryKey, editorLockKey, wipDataKey),
            List.of(
                encodeLong(targetEntryVersion),
                UTF_8.encode(editorId),
                encodeLong(editorLockTtlSeconds)))
        .thenApply(
            isLockAcquired ->
                isLockAcquired
                    ? Optional.<Editor>of(
                        new RedisEditor(key, entryKey, editorLockKey, wipDataKey, editorId))
                    : Optional.<Editor>empty())
        .toCompletableFuture();
  }

  @Override
  public Iterator<Viewer> iterator() {
    requireNotClosed();
    return new ScanningViewerIterator(toEntryKey("*"));
  }

  @Override
  public boolean remove(String key) throws IOException, InterruptedException {
    requireNotClosed();
    return Utils.get(removeEntryAsync(toEntryKey(key), ANY_ENTRY_VERSION));
  }

  @Override
  public CompletableFuture<Void> removeAllAsync(List<String> keys) {
    requireNotClosed();
    return removeAllEntriesAsync(
        keys.stream().map(this::toEntryKey).collect(Collectors.toUnmodifiableList()));
  }

  abstract CompletableFuture<Void> removeAllEntriesAsync(List<String> entryKeys);

  CompletableFuture<Boolean> removeEntryAsync(String entryKey, long targetEntryVersion) {
    return Script.REMOVE
        .evalOn(commands())
        .asBoolean(
            List.of(entryKey),
            List.of(encodeLong(targetEntryVersion), encodeLong(staleEntryTtlSeconds)));
  }

  @Override
  public void clear() throws IOException {
    requireNotClosed();
    removeAll();
  }

  @Override
  public long size() {
    long size = 0;
    for (var iter = iterator(); iter.hasNext(); ) {
      try (var viewer = iter.next()) {
        size += viewer.entrySize();
      }
    }
    return size;
  }

  @Override
  public void dispose() {
    doClose(true);
  }

  @Override
  public void close() {
    doClose(false);
  }

  private void doClose(boolean dispose) {
    closeLock.lock();
    try {
      if (closed) return;
      closed = true;
    } finally {
      closeLock.unlock();
    }

    if (dispose) {
      try {
        removeAll();
      } catch (IOException e) {
        logger.log(Level.WARNING, "Exception thrown when clearing the store on closure", e);
      }
    }
    connectionProvider.release(connection);
    connectionProvider.close();
  }

  private void removeAll() throws IOException {
    var cursor = ScanCursor.INITIAL;
    var scanArgs = ScanArgs.Builder.matches(toEntryKey("*")).limit(REMOVE_ALL_SCAN_LIMIT);
    try {
      do {
        cursor =
            Utils.get(
                commands()
                    .scan(cursor, scanArgs)
                    .thenCompose(
                        keyScanCursor ->
                            removeAllEntriesAsync(keyScanCursor.getKeys())
                                .thenApply(__ -> keyScanCursor))
                    .toCompletableFuture());
      } while (!cursor.isFinished());
    } catch (InterruptedException e) {
      throw Utils.toInterruptedIOException(e);
    }
  }

  @Override
  public void flush() {}

  String toEntryKey(String key) {
    requireArgument(key.indexOf('}') == -1, "key contains a right brace");
    return String.format("methanol:%d:%d:{%s}", STORE_VERSION, appVersion, key);
  }

  void requireNotClosed() {
    requireState(!closed, "Store is closed");
  }

  static ByteBuffer encodeLong(long value) {
    return UTF_8.encode(Long.toString(value));
  }

  static long decodeLong(ByteBuffer value) {
    return Long.parseLong(UTF_8.decode(value).toString());
  }

  private static String extractHashTag(String entryKey) {
    int leftBrace = entryKey.indexOf('{');
    int rightBrace = entryKey.indexOf('}');
    requireArgument(
        leftBrace >= 0 && rightBrace >= 0 && leftBrace + 1 < rightBrace, "Improper hash tag");
    return entryKey.substring(leftBrace + 1, rightBrace);
  }

  final class ScanningViewerIterator implements Iterator<Viewer> {
    private final String pattern;
    private final RedisScriptingAsyncCommands<String, ByteBuffer> commands;

    private String cursor = INITIAL_CURSOR_VALUE;
    private boolean isInitialScan = true;

    private @Nullable Iterator<ScanEntry> entryIterator;

    private @Nullable Viewer nextViewer;
    private @Nullable Viewer currentViewer;

    /**
     * The set of keys seen so far, tracked to avoid returning duplicate entries. This may happen
     * when the SCAN command returns iterates over the same key multiple times, which is possible if
     * the keyspace changes due to how SCAN is implemented.
     */
    private final Set<String> seenKeys;

    private boolean finished;

    ScanningViewerIterator(String pattern) {
      this(pattern, new HashSet<>(), commands());
    }

    ScanningViewerIterator(
        String pattern,
        Set<String> seenKeys,
        RedisScriptingAsyncCommands<String, ByteBuffer> commands) {
      this.pattern = pattern;
      this.seenKeys = seenKeys;
      this.commands = commands;
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
      try {
        viewer.removeEntry();
      } catch (IOException e) {
        // RedisViewer doesn't throw IOExceptions.
        throw new AssertionError(e);
      } catch (RedisException e) {
        logger.log(Level.WARNING, "Exception thrown when removing entry", e);
      } catch (IllegalStateException ignored) {
        // Fail silently if closed or interrupted.
      }
    }

    @EnsuresNonNullIf(expression = "nextViewer", result = true)
    private boolean findNext() {
      while (true) {
        if (finished) {
          return false;
        }

        try {
          var iter = entryIterator;
          if (iter != null && iter.hasNext()) {
            var entry = iter.next();
            if (seenKeys.add(entry.key)) {
              nextViewer = createViewer(null, entry.key, entry.fields);
              return true;
            }
          } else if ((!cursor.equals(INITIAL_CURSOR_VALUE) || isInitialScan) && !closed) {
            var scanResult = scan().get();
            cursor = scanResult.cursor;
            entryIterator = scanResult.entries.iterator();
            isInitialScan = false;
          } else {
            finished = true;
            return false;
          }
        } catch (InterruptedException e) {
          // Handle interruption gracefully by ending iteration and let caller handle interruption.
          finished = true;
          Thread.currentThread().interrupt();
          return false;
        } catch (ExecutionException e) {
          finished = true;
          logger.log(Level.WARNING, "Exception thrown during iteration", e.getCause());
          return false;
        }
      }
    }

    CompletableFuture<ScanResult> scan() {
      return Script.SCAN_ENTRIES
          .evalOn(commands)
          .asMulti(
              List.of(),
              List.of(UTF_8.encode(cursor), UTF_8.encode(pattern), encodeLong(ITERATOR_SCAN_LIMIT)))
          .thenApply(ScanResult::from);
    }
  }

  private static final class ScanResult {
    final String cursor;
    final List<ScanEntry> entries;

    ScanResult(String cursor, List<ScanEntry> entries) {
      this.cursor = cursor;
      this.entries = entries;
    }

    static ScanResult from(List<Object> cursorAndEntries) {
      var cursor = UTF_8.decode((ByteBuffer) cursorAndEntries.get(0)).toString();
      @SuppressWarnings("unchecked")
      var entries = (List<List<ByteBuffer>>) cursorAndEntries.get(1);
      return new ScanResult(
          cursor, entries.stream().map(ScanEntry::from).collect(Collectors.toUnmodifiableList()));
    }
  }

  private static final class ScanEntry {
    final String key;
    final List<ByteBuffer> fields;

    ScanEntry(String key, List<ByteBuffer> fields) {
      this.key = key;
      this.fields = fields;
    }

    static ScanEntry from(List<ByteBuffer> keyAndFields) {
      var key = UTF_8.decode(keyAndFields.get(0)).toString();
      var fields = keyAndFields.subList(1, keyAndFields.size());
      return new ScanEntry(key, fields);
    }
  }

  private final class RedisViewer implements Viewer {
    private final @Nullable String key;
    private final String entryKey;
    private final String dataKey;
    private final long entryVersion;
    private final ByteBuffer metadata;
    private final long dataSize;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Whether the data we're currently reading is expected to be fresh. Data becomes stale when it
     * is edited or removed. On such case, the data key is renamed and set to expire. If we detect
     * that while reading, this field's value is set to {@code false} and never changes. After which
     * reading is always directed to the stale data entry.
     */
    private volatile boolean readingFreshEntry = true;

    RedisViewer(
        @Nullable String key,
        String entryKey,
        String dataKey,
        long entryVersion,
        ByteBuffer metadata,
        long dataSize) {
      this.key = key;
      this.entryKey = entryKey;
      this.metadata = metadata.asReadOnlyBuffer();
      this.dataSize = dataSize;
      this.entryVersion = entryVersion;
      this.dataKey = dataKey;
    }

    @Override
    public String key() {
      return requireNonNullElseGet(key, () -> extractHashTag(entryKey));
    }

    @Override
    public ByteBuffer metadata() {
      return metadata.duplicate();
    }

    @Override
    public EntryReader newReader() {
      return new RedisEntryReader();
    }

    @Override
    public long dataSize() {
      return dataSize;
    }

    @Override
    public long entrySize() {
      return dataSize + metadata.remaining();
    }

    @Override
    public CompletableFuture<Optional<Editor>> editAsync() {
      return AbstractRedisStore.this.editAsync(null, entryKey, entryVersion);
    }

    @Override
    public boolean removeEntry() throws IOException {
      requireNotClosed();
      try {
        return Utils.get(AbstractRedisStore.this.removeEntryAsync(entryKey, entryVersion));
      } catch (InterruptedException e) {
        throw Utils.toInterruptedIOException(e);
      }
    }

    @Override
    public void close() {}

    private final class RedisEntryReader implements EntryReader {
      private final AtomicBoolean pendingRead = new AtomicBoolean();
      private final AtomicLong streamPosition = new AtomicLong();

      RedisEntryReader() {}

      @Override
      public CompletableFuture<Integer> read(ByteBuffer dst) {
        requireNonNull(dst);
        requireState(!closed.get(), "closed");
        if (!pendingRead.compareAndSet(false, true)) {
          throw new ReadPendingException();
        }

        long position = streamPosition.get();
        if (position >= dataSize) {
          pendingRead.set(false);
          return CompletableFuture.completedFuture(-1);
        }

        long inclusiveLimit = position + dst.remaining() - 1;
        var future =
            readingFreshEntry
                ? commands().getrange(dataKey, position, inclusiveLimit)
                : getStaleRange(position, inclusiveLimit);
        return future
            .thenCompose(range -> fallbackToStaleEntryIfEmptyRange(range, position, inclusiveLimit))
            .thenApply(range -> Utils.copyRemaining(range, dst))
            .thenApply(
                read -> {
                  streamPosition.getAndAdd(read);
                  return read;
                })
            .whenComplete((__, ___) -> pendingRead.set(false))
            .toCompletableFuture();
      }

      private CompletionStage<ByteBuffer> getStaleRange(long position, long inclusiveLimit) {
        return Script.GET_STALE_RANGE
            .evalOn(commands())
            .asValue(
                List.of(dataKey),
                List.of(
                    encodeLong(position),
                    encodeLong(inclusiveLimit),
                    encodeLong(staleEntryTtlSeconds)));
      }

      private CompletionStage<ByteBuffer> fallbackToStaleEntryIfEmptyRange(
          ByteBuffer range, long position, long inclusiveLimit) {
        if (range.hasRemaining()) {
          return CompletableFuture.completedFuture(range);
        }
        if (!readingFreshEntry) {
          return CompletableFuture.failedFuture(new IllegalStateException("Stale entry expired"));
        }
        return getStaleRange(position, inclusiveLimit)
            .thenCompose(
                staleRange -> {
                  if (!staleRange.hasRemaining()) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException("Stale entry expired"));
                  }
                  readingFreshEntry = false;
                  return CompletableFuture.completedFuture(staleRange);
                });
      }
    }
  }

  private final class RedisEditor implements Editor {
    private final @Nullable String key;
    private final String entryKey;
    private final String editorLockKey;
    private final String wipDataKey;
    private final String editorId;
    private final RedisEntryWriter writer = new RedisEntryWriter();
    private final AtomicBoolean closed = new AtomicBoolean();

    RedisEditor(
        @Nullable String key,
        String entryKey,
        String editorLockKey,
        String wipDataKey,
        String editorId) {
      this.key = key;
      this.entryKey = entryKey;
      this.editorLockKey = editorLockKey;
      this.wipDataKey = wipDataKey;
      this.editorId = editorId;
    }

    @Override
    public String key() {
      return requireNonNullElseGet(key, () -> extractHashTag(entryKey));
    }

    @Override
    public EntryWriter writer() {
      return writer;
    }

    @Override
    public CompletableFuture<Boolean> commitAsync(ByteBuffer metadata) {
      requireNonNull(metadata);
      requireState(closed.compareAndSet(false, true), "closed");
      return Script.COMMIT
          .evalOn(commands())
          .asBoolean(
              List.of(entryKey, editorLockKey, wipDataKey),
              List.of(
                  UTF_8.encode(editorId),
                  metadata,
                  encodeLong(writer.dataSizeIfWritten()), // clientDataSize
                  encodeLong(staleEntryTtlSeconds),
                  encodeLong(1), // commit
                  UTF_8.encode(clockKey)));
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        // Discard the edit in background.
        Script.COMMIT
            .evalOn(commands())
            .asBoolean(
                List.of(entryKey, editorLockKey, wipDataKey),
                List.of(
                    UTF_8.encode(editorId),
                    EMPTY_BUFFER, // metadata
                    encodeLong(-1), // clientDataSize
                    encodeLong(staleEntryTtlSeconds),
                    encodeLong(0), // commit
                    UTF_8.encode(clockKey)));
      }
    }

    private class RedisEntryWriter implements EntryWriter {
      private final AtomicBoolean pendingWrite = new AtomicBoolean();
      private final AtomicLong totalBytesWritten = new AtomicLong();

      private volatile boolean isWritten;

      RedisEntryWriter() {}

      @Override
      public CompletableFuture<Integer> write(ByteBuffer src) {
        requireNonNull(src);
        requireState(!closed.get(), "closed");
        if (!pendingWrite.compareAndSet(false, true)) {
          throw new WritePendingException();
        }

        int bytesToWrite = src.remaining();
        return append(src)
            .thenApply(
                serverTotalBytesWritten -> onWriteCompletion(bytesToWrite, serverTotalBytesWritten))
            .whenComplete((__, ___) -> pendingWrite.set(false))
            .toCompletableFuture();
      }

      private CompletionStage<Long> append(ByteBuffer src) {
        return Script.APPEND
            .evalOn(commands())
            .asLong(
                List.of(editorLockKey, wipDataKey),
                List.of(src, UTF_8.encode(editorId), encodeLong(editorLockTtlSeconds)));
      }

      private int onWriteCompletion(int bytesWritten, long serverTotalBytesWritten) {
        if (bytesWritten < 0) {
          RedisEditor.this.close();
          throw new IllegalStateException("Editor lock expired");
        }
        if (totalBytesWritten.addAndGet(bytesWritten) != serverTotalBytesWritten) {
          RedisEditor.this.close();
          throw new IllegalStateException("Editor data inconsistency");
        }
        isWritten = true;
        return bytesWritten;
      }

      long dataSizeIfWritten() {
        return isWritten ? totalBytesWritten.get() : -1;
      }
    }
  }
}
