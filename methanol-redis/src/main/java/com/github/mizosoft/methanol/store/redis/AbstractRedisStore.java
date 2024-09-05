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

package com.github.mizosoft.methanol.store.redis;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.async.RedisHashAsyncCommands;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
import io.lettuce.core.api.sync.RedisHashCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
abstract class AbstractRedisStore<
        C extends StatefulConnection<String, ByteBuffer>,
        CMD extends
            RedisHashCommands<String, ByteBuffer> & RedisScriptingCommands<String, ByteBuffer>
                & RedisKeyCommands<String, ByteBuffer> & RedisStringCommands<String, ByteBuffer>,
        ASYNC_CMD extends
            RedisHashAsyncCommands<String, ByteBuffer>
                & RedisScriptingAsyncCommands<String, ByteBuffer>
                & RedisStringAsyncCommands<String, ByteBuffer>>
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

  final AtomicBoolean closed = new AtomicBoolean();

  AbstractRedisStore(
      C connection,
      RedisConnectionProvider<C> connectionProvider,
      int editorLockTtlSeconds,
      int staleEntryTtlSeconds,
      int appVersion,
      String clockKey) {
    requireArgument(
        editorLockTtlSeconds > 0, "Expected a positive ttl, got: %s", editorLockTtlSeconds);
    requireArgument(
        staleEntryTtlSeconds > 0, "Expected a positive ttl, got: %s", staleEntryTtlSeconds);
    this.connection = requireNonNull(connection);
    this.connectionProvider = requireNonNull(connectionProvider);
    this.editorLockTtlSeconds = editorLockTtlSeconds;
    this.staleEntryTtlSeconds = staleEntryTtlSeconds;
    this.appVersion = appVersion;
    this.clockKey = requireNonNull(clockKey);
  }

  abstract CMD commands();

  abstract ASYNC_CMD asyncCommands();

  @Override
  public long maxSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public CompletableFuture<Optional<Viewer>> view(String key, Executor executor) {
    return view(key, toEntryKey(key));
  }

  private CompletableFuture<Optional<Viewer>> view(@Nullable String key, String entryKey) {
    requireNotClosed();
    return asyncCommands()
        .hmget(entryKey, "metadata", "dataSize", "entryVersion", "dataVersion")
        .thenApply(
            fields ->
                fields.stream().allMatch(Predicate.not(KeyValue::isEmpty))
                    ? Optional.of(
                        createViewer(
                            key,
                            entryKey,
                            () -> fields.stream().map(KeyValue::getValue).iterator()))
                    : Optional.<Viewer>empty())
        .toCompletableFuture();
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
  public CompletableFuture<Optional<Editor>> edit(String key, Executor executor) {
    return edit(key, toEntryKey(key), ANY_ENTRY_VERSION);
  }

  private CompletableFuture<Optional<Editor>> edit(
      @Nullable String key, String entryKey, long targetEntryVersion) {
    requireNotClosed();
    var editorId = UUID.randomUUID().toString();
    var editorLockKey = entryKey + ":editor";
    var wipDataKey = entryKey + ":data:wip:" + editorId;
    return Script.EDIT
        .evalOn(asyncCommands())
        .getAsBoolean(
            List.of(entryKey, editorLockKey, wipDataKey),
            List.of(encode(editorId), encode(targetEntryVersion), encode(editorLockTtlSeconds)))
        .thenApply(
            isLockAcquired ->
                isLockAcquired
                    ? Optional.of(
                        new RedisEditor(key, entryKey, editorLockKey, wipDataKey, editorId))
                    : Optional.empty());
  }

  @Override
  public Iterator<Viewer> iterator() {
    requireNotClosed();
    return new ScanningViewerIterator(toEntryKey("*"));
  }

  @Override
  public boolean remove(String key) {
    requireNotClosed();
    return removeEntry(toEntryKey(key), ANY_ENTRY_VERSION);
  }

  @Override
  public boolean removeAll(List<String> keys) {
    requireNotClosed();
    return removeAllEntries(
        keys.stream().map(this::toEntryKey).collect(Collectors.toUnmodifiableList()));
  }

  abstract boolean removeAllEntries(List<String> entryKeys);

  boolean removeEntry(String entryKey, long targetEntryVersion) {
    return Script.REMOVE
        .evalOn(commands())
        .getAsBoolean(
            List.of(entryKey), List.of(encode(targetEntryVersion), encode(staleEntryTtlSeconds)));
  }

  @Override
  public void clear() {
    requireNotClosed();
    removeAll();
  }

  @Override
  public long size() {
    long size = 0;
    for (var iterator = iterator(); iterator.hasNext(); ) {
      try (var viewer = iterator.next()) {
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
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    if (dispose) {
      try {
        removeAll();
      } catch (RedisException e) {
        logger.log(Level.WARNING, "Exception thrown when clearing the store on closure", e);
      }
    }

    try (connectionProvider) {
      connectionProvider.release(connection);
    }
  }

  private void removeAll() {
    var cursor = ScanCursor.INITIAL;
    var scanArgs = ScanArgs.Builder.matches(toEntryKey("*")).limit(REMOVE_ALL_SCAN_LIMIT);
    do {
      var keyScanCursor = commands().scan(cursor, scanArgs);
      removeAllEntries(keyScanCursor.getKeys());
      cursor = keyScanCursor;
    } while (!cursor.isFinished());
  }

  @Override
  public void flush() {}

  String toEntryKey(String key) {
    requireArgument(key.indexOf('}') == -1, "Malformed key");
    return String.format("methanol:%d:%d:{%s}", STORE_VERSION, appVersion, key);
  }

  String wipDataKey(Editor editor) {
    requireArgument(
        editor instanceof AbstractRedisStore<?, ?, ?>.RedisEditor,
        "Unrecognized editor: %s",
        editor);
    return ((AbstractRedisStore<?, ?, ?>.RedisEditor) editor).wipDataKey();
  }

  void requireNotClosed() {
    requireState(!closed.get(), "Store is closed");
  }

  static ByteBuffer encode(int value) {
    return UTF_8.encode(Integer.toString(value));
  }

  static ByteBuffer encode(long value) {
    return UTF_8.encode(Long.toString(value));
  }

  static ByteBuffer encode(String value) {
    return UTF_8.encode(value);
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
    private final RedisScriptingCommands<String, ByteBuffer> commands;

    /**
     * The set of keys seen so far, tracked to avoid returning duplicate entries. This may happen
     * when the SCAN command iterates over the same key multiple times, which is possible if the
     * keyspace changes due to how SCAN is implemented.
     */
    private final Set<String> seenKeys;

    private String cursor = INITIAL_CURSOR_VALUE;
    private boolean isInitialScan = true;
    private @Nullable Iterator<ScanEntry> entryIterator;
    private @Nullable Viewer nextViewer;
    private @Nullable Viewer currentViewer;
    private boolean finished;

    ScanningViewerIterator(String pattern) {
      this(pattern, new HashSet<>(), commands());
    }

    ScanningViewerIterator(
        String pattern, Set<String> seenKeys, RedisScriptingCommands<String, ByteBuffer> commands) {
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
        castNonNull(viewer).removeEntry();
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
          var iterator = entryIterator;
          if (iterator != null && iterator.hasNext()) {
            var entry = iterator.next();
            if (seenKeys.add(entry.key)) {
              nextViewer = createViewer(null, entry.key, entry.fields);
              return true;
            }
          } else if ((!cursor.equals(INITIAL_CURSOR_VALUE) || isInitialScan) && !closed.get()) {
            var scanResult = scan();
            cursor = scanResult.cursor;
            entryIterator = scanResult.entries.iterator();
            isInitialScan = false;
          } else {
            finished = true;
            return false;
          }
        } catch (RedisException e) {
          finished = true;
          logger.log(Level.WARNING, "Exception thrown during iteration", e);
          return false;
        }
      }
    }

    ScanResult scan() {
      return ScanResult.from(
          Script.SCAN_ENTRIES
              .evalOn(commands)
              .getAsMulti(
                  List.of(),
                  List.of(encode(cursor), encode(pattern), encode(ITERATOR_SCAN_LIMIT))));
    }
  }

  private static final class ScanResult {
    final String cursor;
    final List<ScanEntry> entries;

    ScanResult(String cursor, List<ScanEntry> entries) {
      this.cursor = cursor;
      this.entries = entries;
    }

    static ScanResult from(List<?> cursorAndEntries) {
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
    public CompletableFuture<Optional<Editor>> edit(Executor executor) {
      return AbstractRedisStore.this.edit(null, entryKey, entryVersion);
    }

    @Override
    public boolean removeEntry() {
      requireNotClosed();
      return AbstractRedisStore.this.removeEntry(entryKey, entryVersion);
    }

    @Override
    public void close() {}

    private final class RedisEntryReader implements EntryReader {
      /**
       * A conservative value for the maximum length to create an array with that won't result in an
       * OutOfMemoryException. Taken from JDK's ArraysSupport.SOFT_MAX_ARRAY_LENGTH.
       */
      private static final int SOFT_MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

      private final AtomicBoolean readLatch = new AtomicBoolean();

      /**
       * Position of the next byte to read. This field can be accessed from multiple threads, but
       * never simultaneously. Accesses are synchronized by {@link #readLatch}.
       */
      private long position;

      /**
       * Whether the data we're currently reading is expected to be fresh. Data becomes stale when
       * it is edited or removed. In such case, the data key is renamed and set to expire. If we
       * detect that while reading, this field's value is set to {@code false} and never changes.
       * After which reading is always directed to the stale data entry.
       *
       * <p>This field can be accessed from multiple threads, but never simultaneously. Accesses are
       * synchronized by {@link #readLatch}.
       */
      private boolean readingFreshEntry = true;

      RedisEntryReader() {}

      @Override
      public CompletableFuture<Long> read(List<ByteBuffer> dsts, Executor ignored) {
        requireNonNull(dsts);
        requireState(!closed.get(), "Closed");
        requireState(readLatch.compareAndSet(false, true), "Ongoing read");
        try {
          long position = this.position;
          if (position >= dataSize) {
            return CompletableFuture.completedFuture(-1L);
          }

          // We must be careful not to call GETRANGE with `long` arguments as the returned value is
          // a ByteBuffer, which has an `int` capacity.
          long readableByteCount = Math.min(Utils.remaining(dsts), dataSize - position);
          requireArgument(
              readableByteCount <= SOFT_MAX_ARRAY_LENGTH,
              "Too many bytes requested: %d",
              readableByteCount);
          long inclusiveLimit = position + readableByteCount - 1;
          var rangeFuture =
              readingFreshEntry
                  ? asyncCommands().getrange(dataKey, position, inclusiveLimit)
                  : getRangeOfStaleEntry(position, inclusiveLimit);
          return rangeFuture
              .thenCompose(range -> recoverIfStale(range, position, inclusiveLimit))
              .thenApply(
                  range -> {
                    long totalRead = 0;
                    for (var dst : dsts) {
                      totalRead += Utils.copyRemaining(range, dst);
                    }
                    this.position += totalRead;
                    return totalRead;
                  })
              .whenComplete((__, ___) -> readLatch.compareAndSet(true, false))
              .toCompletableFuture()
              .copy(); // Defensively copy to make sure readLatch is always reset.
        } catch (Throwable t) {
          readLatch.compareAndSet(true, false);
          throw t;
        }
      }

      private CompletableFuture<ByteBuffer> getRangeOfStaleEntry(
          long position, long inclusiveLimit) {
        return Script.GET_STALE_RANGE
            .evalOn(asyncCommands())
            .getAsValue(
                List.of(dataKey),
                List.of(encode(position), encode(inclusiveLimit), encode(staleEntryTtlSeconds)));
      }

      private CompletableFuture<ByteBuffer> recoverIfStale(
          ByteBuffer range, long position, long inclusiveLimit) {
        if (range.hasRemaining()) {
          return CompletableFuture.completedFuture(range);
        }

        requireState(readingFreshEntry, "Stale entry is absent, normally due to expiry");
        return getRangeOfStaleEntry(position, inclusiveLimit)
            .thenApply(
                staleEntryRange -> {
                  requireState(
                      staleEntryRange.hasRemaining(),
                      "Stale entry is absent, normally due to expiry");
                  readingFreshEntry = false; // Write is protected by readLatch.
                  return staleEntryRange;
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
    private final AtomicBoolean writeLatch = new AtomicBoolean();

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

    String wipDataKey() {
      return wipDataKey;
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
    public CompletableFuture<Void> commit(ByteBuffer metadata, Executor ignored) {
      requireNonNull(metadata);
      requireState(closed.compareAndSet(false, true), "Closed");
      return Script.COMMIT
          .evalOn(asyncCommands())
          .getAsList(
              List.of(entryKey, editorLockKey, wipDataKey),
              List.of(
                  encode(editorId),
                  metadata,
                  encode(writer.dataSizeIfWritten()), // clientDataSize
                  encode(staleEntryTtlSeconds),
                  encode(1), // commit
                  encode(clockKey)))
          .thenAccept(
              response -> {
                if (response.get(0).toString().equals("0")) {
                  throw new IllegalStateException(response.get(1).toString());
                }
              });
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        // We must run the command asynchronously as blocking indefinitely risks deadlocks if
        // close() is called from netty's NioEventLoop, which is single-threaded. Thus, blocking
        // will make us wait for a command which cannot be processed. We have no scruples not making
        // sure the command has executed as even if it hasn't, redis will expire this editor's state
        // anyhow.
        Script.COMMIT
            .evalOn(asyncCommands())
            .getAsBoolean(
                List.of(entryKey, editorLockKey, wipDataKey),
                List.of(
                    encode(editorId),
                    EMPTY_BUFFER, // metadata
                    encode(-1), // clientDataSize
                    encode(staleEntryTtlSeconds),
                    encode(0), // commit
                    encode(clockKey)))
            .whenComplete(
                (__, ex) -> {
                  if (ex != null) {
                    logger.log(Level.WARNING, "Exception thrown by closing the editor", ex);
                  }
                });
      }
    }

    private class RedisEntryWriter implements EntryWriter {
      private final Lock lock = new ReentrantLock();

      @GuardedBy("lock")
      private long totalBytesWritten;

      @GuardedBy("lock")
      private boolean isWritten;

      RedisEntryWriter() {}

      public CompletableFuture<Long> write(List<ByteBuffer> srcs, Executor ignored) {
        requireNonNull(srcs);
        requireState(!closed.get(), "Closed");
        requireState(writeLatch.compareAndSet(false, true), "Ongoing write");
        try {
          return append(srcs)
              .thenApply(serverBytesWritten -> onAppend(srcs, serverBytesWritten))
              .whenComplete((__, ___) -> writeLatch.compareAndSet(true, false))
              .copy(); // Defensively copy to make sure writeLatch is always reset.
        } catch (Throwable t) {
          writeLatch.compareAndSet(false, true);
          throw t;
        }
      }

      private CompletableFuture<Long> append(List<ByteBuffer> srcs) {
        var args =
            new ArrayList<>(
                List.of(encode(editorId), encode(editorLockTtlSeconds), encode(srcs.size())));
        args.addAll(srcs);
        return Script.APPEND
            .evalOn(asyncCommands())
            .getAsLong(List.of(editorLockKey, wipDataKey), Collections.unmodifiableList(args));
      }

      private long onAppend(List<ByteBuffer> srcs, long serverBytesWritten) {
        if (serverBytesWritten < 0) {
          RedisEditor.this.close();
          throw new IllegalStateException("Editor lock expired");
        }

        long written = 0;
        for (var src : srcs) {
          int position = src.position();
          int limit = src.limit();
          src.position(limit);
          written += position <= limit ? limit - position : 0;
        }

        lock.lock();
        try {
          totalBytesWritten += written;
          if (totalBytesWritten != serverBytesWritten) {
            RedisEditor.this.close();
            throw new IllegalStateException(
                "Server/client data inconsistency; expected "
                    + totalBytesWritten
                    + " bytes to be written so far, but server says "
                    + serverBytesWritten
                    + " bytes were written");
          }
          isWritten = true;
          return written;
        } finally {
          lock.unlock();
        }
      }

      long dataSizeIfWritten() {
        lock.lock();
        try {
          return isWritten ? totalBytesWritten : -1;
        } finally {
          lock.unlock();
        }
      }
    }
  }
}
