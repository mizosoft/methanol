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
import static java.util.Objects.requireNonNullElse;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.time.Clock;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class RedisStore implements Store {
  private static final Logger logger = System.getLogger(RedisStore.class.getName());

  private static final int STORE_VERSION = 1;
  private static final long ANY_ENTRY_VERSION = -1;
  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final int appVersion;
  private final StatefulRedisConnection<String, ByteBuffer> connection;
  private final long editorLockTimeToLiveMillis;
  private final long staleEntryTimeToLiveMillis;
  private final Clock clock;
  private final AtomicInteger connectionRefCount = new AtomicInteger(1);
  private final Set<RedisEditor> openEditors = ConcurrentHashMap.newKeySet();
  private final ReadWriteLock closeLock = new ReentrantReadWriteLock();
  private boolean closed;

  public RedisStore(
      StatefulRedisConnection<String, ByteBuffer> connection,
      long editorLockTimeToLiveMillis,
      long staleEntryTimeToLiveMillis,
      Clock clock,
      int appVersion) {
    this.connection = connection;
    this.editorLockTimeToLiveMillis = editorLockTimeToLiveMillis;
    this.staleEntryTimeToLiveMillis = staleEntryTimeToLiveMillis;
    this.clock = clock;
    this.appVersion = appVersion;
  }

  @Override
  public long maxSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  @Override
  public void initialize() {}

  @Override
  public CompletableFuture<Void> initializeAsync() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public @Nullable Viewer view(String key) {
    return openViewer(key, toEntryKey(key));
  }

  @Override
  public @Nullable Editor edit(String key) {
    return openEditor(key, toEntryKey(key), ANY_ENTRY_VERSION);
  }

  @Override
  public Iterator<Viewer> iterator() {
    return iterator(true);
  }

  @Override
  public boolean remove(String key) {
    return removeEntry(toEntryKey(key), ANY_ENTRY_VERSION);
  }

  @Override
  public void clear() {
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      unguardedClear();
    } finally {
      closeLock.readLock().unlock();
    }
  }

  @Override
  public long size() {
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      long size = 0;
      var iter = iterator(false);
      while (iter.hasNext()) {
        try (var viewer = iter.next()) {
          size += viewer.entrySize();
        }
      }
      return size;
    } finally {
      closeLock.readLock().unlock();
    }
  }

  @Override
  public void dispose() {
    doClose(true);
  }

  @Override
  public void close() {
    doClose(false);
  }

  private @Nullable Viewer openViewer(@Nullable String key, String entryKey) {
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      var fields =
          connection.sync().hmget(entryKey, "metadata", "dataSize", "entryVersion", "dataVersion");
      if (fields.stream().anyMatch(KeyValue::isEmpty)) {
        return null;
      }
      var metadata = fields.get(0).getValue();
      long dataSize = decodeLong(fields.get(1).getValue());
      long entryVersion = decodeLong(fields.get(2).getValue());
      long dataVersion = decodeLong(fields.get(3).getValue());
      var viewer =
          new RedisViewer(
              key, entryKey, metadata, dataSize, entryVersion, entryKey + ":data:" + dataVersion);
      referenceConnection();
      return viewer;
    } finally {
      closeLock.readLock().unlock();
    }
  }

  private @Nullable Editor openEditor(
      @Nullable String key, String entryKey, long targetEntryVersion) {
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      var editorId = UUID.randomUUID().toString();
      var editorLockKey = entryKey + ":editor";
      var wipDataKey = entryKey + ":wip_data:" + editorId;
      boolean acquiredLock =
          eval(
              Script.EDIT,
              List.of(entryKey, editorLockKey, wipDataKey),
              List.of(
                  encodeLong(targetEntryVersion),
                  UTF_8.encode(editorId),
                  encodeLong(editorLockTimeToLiveMillis)),
              ScriptOutputType.BOOLEAN);
      var editor =
          acquiredLock ? new RedisEditor(key, entryKey, editorLockKey, wipDataKey, editorId) : null;
      if (editor != null) {
        openEditors.add(editor);
        referenceConnection();
      }
      return editor;
    } finally {
      closeLock.readLock().unlock();
    }
  }

  private boolean removeEntry(String entryKey, long targetEntryVersion) {
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      return eval(
          Script.REMOVE,
          List.of(entryKey),
          List.of(encodeLong(targetEntryVersion), encodeLong(staleEntryTimeToLiveMillis)),
          ScriptOutputType.BOOLEAN);
    } finally {
      closeLock.readLock().unlock();
    }
  }

  private Iterator<Viewer> iterator(boolean concurrentlyCloseable) {
    var pattern = toEntryKey("*"); // Match all keys.
    var iterator =
        concurrentlyCloseable
            ? new ClosureAwareScanningViewerIterator(pattern)
            : new ScanningViewerIterator(pattern);
    iterator.fireScan(ScanCursor.INITIAL);
    return iterator;
  }

  private void unguardedClear() {
    var iter = iterator(false);
    while (iter.hasNext()) {
      try (var viewer = iter.next()) {
        viewer.removeEntry();
      } catch (IOException e) {
        // RedisViewer doesn't throw IOExceptions.
        throw new AssertionError(e);
      }
    }
  }

  private void doClose(boolean dispose) {
    closeLock.writeLock().lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
    } finally {
      closeLock.writeLock().unlock();
    }

    try {
      // Prevent active editors from committing successfully. We know no editors can be openned
      // concurrently since we've set closed to `true`, and editors are added within
      // closeLock.readLock() scope only if not closed.
      openEditors.forEach(RedisEditor::discard);
      openEditors.clear();
      if (dispose) {
        unguardedClear();
      }
    } finally {
      dereferenceConnection();
    }
  }

  @Override
  public void flush() {}

  private <T> T eval(
      Script script, List<String> keys, List<ByteBuffer> values, ScriptOutputType outputType) {
    var keysArray = keys.toArray(String[]::new);
    var valuesArray = values.toArray(ByteBuffer[]::new);
    try {
      return connection.sync().evalsha(script.shaHex(), outputType, keysArray, valuesArray);
    } catch (RedisNoScriptException e) {
      return connection.sync().eval(script.content(), outputType, keysArray, valuesArray);
    }
  }

  @SuppressWarnings("SameParameterValue")
  private <T> CompletableFuture<T> evalAsync(
      Script script, List<String> keys, List<ByteBuffer> values, ScriptOutputType outputType) {
    var keysArray = keys.toArray(String[]::new);
    var valuesArray = values.toArray(ByteBuffer[]::new);
    return connection
        .async()
        .<T>evalsha(script.shaHex(), outputType, keysArray, valuesArray)
        .handle(
            (reply, error) -> {
              if (error instanceof RedisNoScriptException) {
                return connection
                    .async()
                    .<T>eval(script.content(), outputType, keysArray, valuesArray);
              }
              return error != null
                  ? CompletableFuture.<T>failedFuture(error)
                  : CompletableFuture.completedFuture(reply);
            })
        .thenCompose(Function.identity())
        .toCompletableFuture();
  }

  private String checkKey(String key) {
    requireArgument(key.indexOf('}') == -1, "key contains a right brace");
    return key;
  }

  private String toEntryKey(String key) {
    return String.format("methanol:%d:%d:{%s}", STORE_VERSION, appVersion, checkKey(key));
  }

  private void requireNotClosed() {
    requireState(!closed, "closed");
  }

  private void referenceConnection() {
    connectionRefCount.incrementAndGet();
  }

  private void dereferenceConnection() {
    if (connectionRefCount.decrementAndGet() == 0) {
      connection.close();
    }
  }

  private static String extractHashTag(String entryKey) {
    int leftBrace = entryKey.indexOf('{');
    int rightBrace = entryKey.indexOf('}');
    requireArgument(
        leftBrace >= 0 && rightBrace >= 0 && leftBrace + 1 < rightBrace, "improper hash tag");
    return entryKey.substring(leftBrace + 1, rightBrace);
  }

  private static ByteBuffer encodeLong(long value) {
    return UTF_8.encode(Long.toString(value));
  }

  private static ByteBuffer encodeInt(int value) {
    return UTF_8.encode(Integer.toString(value));
  }

  private static long decodeLong(ByteBuffer value) {
    return Long.parseLong(UTF_8.decode(value).toString());
  }

  private class ScanningViewerIterator implements Iterator<Viewer> {
    private final ScanArgs args;
    final BlockingQueue<ScanSignal> queue = new LinkedBlockingQueue<>();

    private @Nullable Viewer nextViewer;
    private @Nullable Viewer currentViewer;
    private boolean finished;
    private @MonotonicNonNull Throwable error;

    ScanningViewerIterator(String pattern) {
      this.args = ScanArgs.Builder.limit(256).match(pattern, UTF_8);
    }

    @Override
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
        logger.log(Level.WARNING, "entry removal failure", e);
      } catch (IllegalStateException ignored) {
        // Fail silently if closed.
      }
    }

    private boolean findNext() {
      while (true) {
        if (finished) {
          return false;
        }

        if (error != null) {
          if (error instanceof RuntimeException) {
            throw (RuntimeException) error;
          } else if (error instanceof Error) {
            throw (Error) error;
          } else {
            throw new UncheckedIOException(new IOException(error));
          }
        }

        try {
          var signal = queue.take();
          if (signal instanceof ScanKey) {
            try {
              var viewer = openViewer(null, ((ScanKey) signal).key);
              if (viewer != null) {
                nextViewer = viewer;
                return true;
              }
            } catch (RedisException e) {
              logger.log(Level.WARNING, "failed to open viewer", e);
            }
          } else if (signal instanceof ScanCompletion) {
            var cursor = ((ScanCompletion) signal).cursor;
            if (cursor.isFinished()) {
              finished = true;
            } else {
              fireScan(cursor);
            }
          } else {
            error = ((ScanError) signal).error;
          }
        } catch (InterruptedException e) {
          // To handle interruption gracefully, we just end iteration.
          finished = true;
        }
      }
    }

    void fireScan(ScanCursor cursor) {
      referenceConnection();
      connection
          .async()
          .scan(key -> queue.add(new ScanKey(key)), cursor, args)
          .whenComplete(this::onScanCompletion);
    }

    private void onScanCompletion(ScanCursor cursor, Throwable error) {
      try {
        if (error != null) {
          queue.add(new ScanError(error));
        } else {
          queue.add(new ScanCompletion(cursor));
        }
      } finally {
        dereferenceConnection();
      }
    }
  }

  /**
   * A {@code ScanningViewerIterator} that finishes iteration if the store is closed while
   * iterating.
   */
  private final class ClosureAwareScanningViewerIterator extends ScanningViewerIterator {
    ClosureAwareScanningViewerIterator(String pattern) {
      super(pattern);
    }

    @Override
    void fireScan(ScanCursor cursor) {
      closeLock.readLock().lock();
      try {
        if (closed) {
          queue.add(new ScanCompletion(ScanCursor.FINISHED));
          return;
        }
        super.fireScan(cursor);
      } finally {
        closeLock.readLock().unlock();
      }
    }
  }

  private interface ScanSignal {}

  private static final class ScanKey implements ScanSignal {
    final String key;

    ScanKey(String key) {
      this.key = key;
    }
  }

  private static final class ScanCompletion implements ScanSignal {
    final ScanCursor cursor;

    ScanCompletion(ScanCursor cursor) {
      this.cursor = cursor;
    }
  }

  private static final class ScanError implements ScanSignal {
    final Throwable error;

    ScanError(Throwable error) {
      this.error = error;
    }
  }

  private final class RedisViewer implements Viewer {
    private final @Nullable String key;
    private final String entryKey;
    private final ByteBuffer metadata;
    private final long dataSize;
    private final long entryVersion;
    private final String dataKey;
    private final AtomicBoolean hasPendingRead = new AtomicBoolean();
    private volatile boolean readingFreshEntry = true;
    private volatile long lastExpiryUpdateMillis;

    RedisViewer(
        @Nullable String key,
        String entryKey,
        ByteBuffer metadata,
        long dataSize,
        long entryVersion,
        String dataKey) {
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
    public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
      requireArgument(position >= 0, "negative position");
      requireNonNull(dst);
      if (!hasPendingRead.compareAndSet(false, true)) {
        throw new ReadPendingException();
      }
      if (position >= dataSize) {
        hasPendingRead.set(false);
        return CompletableFuture.completedFuture(-1);
      }
      if (!dst.hasRemaining()) {
        hasPendingRead.set(false);
        return CompletableFuture.completedFuture(0);
      }

      long limit = position + dst.remaining() - 1; // End position in GETRANGE is inclusive.
      var future =
          readingFreshEntry
              ? connection.async().getrange(dataKey, position, limit)
              : getRangeForStaleEntry(position, limit);
      return future
          .thenCompose(range -> handleEmptyGetRange(range, position, limit))
          .thenApply(range -> Utils.copyRemaining(range, dst))
          .whenComplete((__, ___) -> hasPendingRead.set(false))
          .toCompletableFuture();
    }

    private CompletionStage<ByteBuffer> getRangeForStaleEntry(long position, long limit) {
      long startMillis = clock.millis();

      // Fast path: if there's enough time till expiry we don't need to update it.
      if (startMillis - lastExpiryUpdateMillis < staleEntryTimeToLiveMillis * 0.7) {
        return connection.async().getrange(dataKey + ":stale", position, limit);
      }

      // Perform a GETRANGE with expiry update so the key doesn't expire while we still need it.
      return RedisStore.this
          .<ByteBuffer>evalAsync(
              Script.GETRANGE_EXPIRY_UPDATE,
              List.of(dataKey + ":stale"),
              List.of(
                  encodeLong(position), encodeLong(limit), encodeLong(staleEntryTimeToLiveMillis)),
              ScriptOutputType.VALUE)
          .thenApply(
              result -> {
                long rtt = clock.millis() - startMillis;
                lastExpiryUpdateMillis = startMillis + rtt / 2;
                return result;
              });
    }

    private CompletionStage<ByteBuffer> handleEmptyGetRange(
        ByteBuffer result, long position, long limit) {
      if (result.hasRemaining()) {
        return CompletableFuture.completedFuture(result);
      }
      if (!readingFreshEntry) {
        return CompletableFuture.failedFuture(new EntryEvictedException("stale entry removed"));
      }
      return getRangeForStaleEntry(position, limit)
          .thenCompose(
              lambdaResult -> {
                if (!lambdaResult.hasRemaining()) {
                  return CompletableFuture.failedFuture(new EntryEvictedException("entry removed"));
                }
                readingFreshEntry = false;
                return CompletableFuture.completedFuture(lambdaResult);
              });
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
    public @Nullable Editor edit() {
      return openEditor(null, entryKey, entryVersion);
    }

    @Override
    public boolean removeEntry() {
      return RedisStore.this.removeEntry(entryKey, entryVersion);
    }

    @Override
    public void close() {
      dereferenceConnection();
    }
  }

  private final class RedisEditor implements Editor {
    private final @Nullable String key;
    private final String entryKey;
    private final String editorLockKey;
    private final String wipDataKey;
    private final String editorId;
    private final Lock lock = new ReentrantLock();
    private @MonotonicNonNull ByteBuffer metadata;
    private boolean hasPendingWrite;
    private long totalBytesWritten;
    private boolean commitOnClose;
    private boolean commitData;
    private boolean closed;
    private volatile long lastExpiryUpdateMillis;

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
    public void metadata(ByteBuffer metadata) {
      requireNonNull(metadata);
      lock.lock();
      try {
        requireState(!closed, "closed");
        this.metadata = Utils.copy(metadata).asReadOnlyBuffer();
      } finally {
        lock.unlock();
      }
    }

    @Override
    public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
      // TODO ignore position for now (assume append-only).
      requireNonNull(src);

      lock.lock();
      try {
        requireState(!closed, "closed");
        if (hasPendingWrite) {
          throw new WritePendingException();
        }
        commitData = true;

        int remaining = src.remaining();
        var future =
            append(src)
                .thenApply(__ -> remaining)
                .whenComplete(
                    (written, error) -> finishPendingWrite(written != null ? written : -1))
                .toCompletableFuture();
        hasPendingWrite = true;
        return future;
      } finally {
        lock.unlock();
      }
    }

    private CompletionStage<Long> append(ByteBuffer src) {
      long startMillis = clock.millis();

      // Fast path: if there's enough time till expiry we don't need to update it.
      if (startMillis - lastExpiryUpdateMillis < editorLockTimeToLiveMillis * 0.7) {
        return connection.async().append(wipDataKey, src);
      }

      // Perform a APPEND with expiry update so the key doesn't expire while we still need it.
      return RedisStore.this
          .<Long>evalAsync(
              Script.APPEND_EXPIRY_UPDATE,
              List.of(wipDataKey),
              List.of(src, encodeLong(staleEntryTimeToLiveMillis)),
              ScriptOutputType.INTEGER)
          .thenApply(
              result -> {
                long rtt = clock.millis() - startMillis;
                lastExpiryUpdateMillis = startMillis + rtt / 2;
                return result;
              });
    }

    private void finishPendingWrite(long written) {
      lock.lock();
      try {
        hasPendingWrite = false;
        totalBytesWritten += Math.max(0, written);
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void commitOnClose() {
      lock.lock();
      try {
        requireState(!closed, "closed");
        commitOnClose = true;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void close() {
      ByteBuffer metadata;
      boolean commitMetadata;
      boolean commitData;
      long dataSize;
      lock.lock();
      try {
        if (closed) {
          return;
        }
        closed = true;
        if (commitOnClose) {
          metadata = requireNonNullElse(this.metadata, EMPTY_BUFFER);
          commitMetadata = this.metadata != null;
          commitData = this.commitData;
          dataSize = totalBytesWritten;
        } else {
          metadata = EMPTY_BUFFER;
          commitMetadata = false;
          commitData = false;
          dataSize = 0;
        }
      } finally {
        lock.unlock();
      }

      try {
        eval(
            Script.COMMIT,
            List.of(entryKey, editorLockKey, wipDataKey),
            List.of(
                UTF_8.encode(editorId),
                encodeLong(dataSize),
                metadata,
                encodeInt(commitMetadata ? 1 : 0),
                encodeInt(commitData ? 1 : 0),
                encodeLong(staleEntryTimeToLiveMillis)),
            ScriptOutputType.STATUS);
      } finally {
        dereferenceConnection();
      }
    }

    void discard() {
      lock.lock();
      try {
        if (closed) {
          return;
        }
        closed = true;
      } finally {
        lock.unlock();
      }

      evalAsync(
              Script.COMMIT,
              List.of(entryKey, editorLockKey, wipDataKey),
              List.of(
                  UTF_8.encode(editorId),
                  encodeLong(0),
                  EMPTY_BUFFER,
                  encodeInt(0),
                  encodeInt(0),
                  encodeLong(staleEntryTimeToLiveMillis)),
              ScriptOutputType.STATUS)
          .whenComplete((__, ___) -> dereferenceConnection());
    }
  }
}
