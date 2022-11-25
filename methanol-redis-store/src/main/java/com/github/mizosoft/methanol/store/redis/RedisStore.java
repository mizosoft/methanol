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
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A {@link Store} implementation that saves content on a standalone redis instance. */
public final class RedisStore implements Store {
  private static final Logger logger = System.getLogger(RedisStore.class.getName());

  private static final int STORE_VERSION = 1;
  private static final long ANY_ENTRY_VERSION = -1;
  private static final int SIGN_BIT = 1 << 31;

  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final StatefulRedisConnection<String, ByteBuffer> connection;
  private final long editorLockTtlSeconds;
  private final long staleEntryTtlSeconds;
  private final int appVersion;

  /**
   * The number of operations using the connection. The connection is closed iff the current ref
   * count, discarding the sign bit, becomes 0 after decrementing (note that the ref count starts
   * with 1 and is decremented when the store is closed). Closing the store sets the count's sign
   * bit, indicating further increments are prohibited.
   */
  private final AtomicInteger connectionRefCount = new AtomicInteger(1); // Register self.

  public RedisStore(
      StatefulRedisConnection<String, ByteBuffer> connection,
      long editorLockTtlSeconds,
      long staleEntryTtlSeconds,
      int appVersion) {
    this.connection = connection;
    this.editorLockTtlSeconds = editorLockTtlSeconds;
    this.staleEntryTtlSeconds = staleEntryTtlSeconds;
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
  public Optional<Viewer> view(String key) throws IOException, InterruptedException {
    return Utils.get(viewAsync(key));
  }

  @Override
  public CompletableFuture<Optional<Viewer>> viewAsync(String key) {
    return viewAsync(key, toEntryKey(key));
  }

  private CompletableFuture<Optional<Viewer>> viewAsync(@Nullable String key, String entryKey) {
    referenceConnectionIfOpen();
    return connection
        .async()
        .hmget(entryKey, "metadata", "dataSize", "entryVersion", "dataVersion")
        .thenApply(fields -> createViewer(key, entryKey, fields))
        .whenComplete(
            (viewer, ex) -> {
              if (ex != null || viewer.isEmpty()) {
                dereferenceConnection(false);
              }
            })
        .toCompletableFuture();
  }

  private Optional<Viewer> createViewer(
      String key, String entryKey, List<KeyValue<String, ByteBuffer>> entryFields) {
    if (entryFields.stream().anyMatch(KeyValue::isEmpty)) {
      return Optional.empty();
    }

    var iter = entryFields.iterator();
    var metadata = iter.next().getValue();
    long dataSize = decodeLong(iter.next().getValue());
    long entryVersion = decodeLong(iter.next().getValue());
    long dataVersion = decodeLong(iter.next().getValue());
    var viewer =
        new RedisViewer(
            key, entryKey, entryKey + ":data:" + dataVersion, entryVersion, metadata, dataSize);
    return Optional.of(viewer);
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
    // TODO prepend client ID
    var editorId = UUID.randomUUID().toString();
    var editorLockKey = entryKey + ":editor";
    var wipDataKey = entryKey + ":wip_data:" + editorId;
    referenceConnectionIfOpen();
    return this.<Boolean>evalAsync(
            Script.EDIT,
            List.of(entryKey, editorLockKey, wipDataKey),
            List.of(
                encodeLong(targetEntryVersion),
                UTF_8.encode(editorId),
                encodeLong(editorLockTtlSeconds)),
            ScriptOutputType.BOOLEAN)
        .thenApply(
            isLockAcquired ->
                isLockAcquired
                    ? Optional.<Editor>of(
                        new RedisEditor(key, entryKey, editorLockKey, wipDataKey, editorId))
                    : Optional.<Editor>empty())
        .whenComplete(
            (editor, ex) -> {
              if (ex != null || editor.isEmpty()) {
                dereferenceConnection(false);
              }
            })
        .toCompletableFuture();
  }

  @Override
  public Iterator<Viewer> iterator() {
    var iterator = new ScanningViewerIterator(toEntryKey("*")); // Match all keys.
    iterator.fireScan(ScanCursor.INITIAL);
    return iterator;
  }

  @Override
  public boolean remove(String key) throws IOException, InterruptedException {
    return Utils.get(removeEntryAsync(toEntryKey(key), ANY_ENTRY_VERSION));
  }

  @Override
  public CompletableFuture<Void> removeAllAsync(List<String> keys) {
    return CompletableFuture.allOf(
        keys.stream()
            .map(key -> removeEntryAsync(toEntryKey(key), ANY_ENTRY_VERSION))
            .toArray(CompletableFuture<?>[]::new));
  }

  @Override
  public void clear() {
    referenceConnectionIfOpen();
    try {
      unguardedClear();
    } finally {
      dereferenceConnection(false);
    }
  }

  @Override
  public long size() {
    referenceConnectionIfOpen();
    try {
      long size = 0;
      for (var iter = iterator(); iter.hasNext(); ) {
        try (var viewer = iter.next()) {
          size += viewer.entrySize();
        }
      }
      return size;
    } finally {
      dereferenceConnection(false);
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

  private CompletableFuture<Boolean> removeEntryAsync(String entryKey, long targetEntryVersion) {
    referenceConnectionIfOpen();
    return this.<Boolean>evalAsync(
            Script.REMOVE,
            List.of(entryKey),
            List.of(encodeLong(targetEntryVersion), encodeLong(staleEntryTtlSeconds)),
            ScriptOutputType.BOOLEAN)
        .whenComplete((__, ___) -> dereferenceConnection(false));
  }

  private void unguardedClear() {
    var iter = iterator();
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
    try {
      if (dispose) {
        unguardedClear();
      }
    } finally {
      dereferenceConnection(true);
    }
  }

  @Override
  public void flush() {}

  private <T> CompletableFuture<T> evalAsync(
      Script script, List<String> keys, List<ByteBuffer> values, ScriptOutputType outputType) {
    var keysArray = keys.toArray(String[]::new);
    var valuesArray = values.toArray(ByteBuffer[]::new);
    return connection
        .async()
        .<T>evalsha(script.shaHex(), outputType, keysArray, valuesArray)
        .handle(
            (reply, ex) -> {
              if (ex instanceof RedisNoScriptException) {
                return connection
                    .async()
                    .<T>eval(script.content(), outputType, keysArray, valuesArray);
              }
              return ex != null
                  ? CompletableFuture.<T>failedFuture(ex)
                  : CompletableFuture.completedFuture(reply);
            })
        .thenCompose(Function.identity())
        .toCompletableFuture();
  }

  private String toEntryKey(String key) {
    requireArgument(key.indexOf('}') == -1, "key contains a right brace");
    return String.format("methanol:%d:%d:{%s}", STORE_VERSION, appVersion, key);
  }

  private void referenceConnectionIfOpen() {
    requireState(tryReferenceConnectionIfOpen(), "closed");
  }

  private boolean tryReferenceConnectionIfOpen() {
    while (true) {
      int refCount = connectionRefCount.get();
      if (refCount <= 0) {
        return false;
      }
      if (connectionRefCount.compareAndSet(refCount, refCount + 1)) {
        return true;
      }
    }
  }

  private void dereferenceConnection(boolean close) {
    int closedBit = close ? SIGN_BIT : 0;
    while (true) {
      int refCount = connectionRefCount.get();
      int absRefCount = Math.abs(refCount);
      if (absRefCount == 0) {
        return;
      }

      int newRefCount = (absRefCount - 1) | closedBit | (refCount & SIGN_BIT);
      if (connectionRefCount.compareAndSet(refCount, newRefCount)) {
        if (absRefCount - 1 == 0) {
          closeConnection();
        }
        return;
      }
    }
  }

  private void closeConnection() {
    connection
        .closeAsync()
        .whenComplete(
            (__, ex) -> {
              if (ex != null) {
                logger.log(Level.WARNING, "exception when closing connection", ex);
              }
            });
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

  private static long decodeLong(ByteBuffer value) {
    return Long.parseLong(UTF_8.decode(value).toString());
  }

  // TODO we can optimize out round trips with a script that returns the content of scanned keys
  //      instead of only the keys themselves.
  private class ScanningViewerIterator implements Iterator<Viewer> {
    private static final int SCAN_LIMIT = 256;

    private final ScanArgs args;
    private final BlockingQueue<ScanSignal> queue = new LinkedBlockingQueue<>();

    private @Nullable Viewer nextViewer;
    private @Nullable Viewer currentViewer;

    private boolean finished;
    private @MonotonicNonNull Throwable exception;

    ScanningViewerIterator(String pattern) {
      this.args = ScanArgs.Builder.limit(SCAN_LIMIT).match(pattern, UTF_8);
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
        // Fail silently if closed or interrupted.
      }
    }

    private boolean findNext() {
      while (true) {
        if (finished) {
          return false;
        }

        if (exception != null) {
          if (exception instanceof RuntimeException) {
            throw (RuntimeException) exception;
          } else if (exception instanceof Error) {
            throw (Error) exception;
          } else {
            throw new UncheckedIOException(new IOException(exception));
          }
        }

        try {
          var signal = queue.take();
          if (signal instanceof ScanKey) {
            try {
              var viewer = Utils.get(viewAsync(null, ((ScanKey) signal).key)).orElse(null);
              if (viewer != null) {
                nextViewer = viewer;
                return true;
              }
            } catch (RedisException | IOException e) {
              // Log & try with next key.
              logger.log(Level.WARNING, "exception when opening viewer", e);
            }
          } else if (signal instanceof ScanCompletion) {
            var cursor = ((ScanCompletion) signal).cursor;
            if (cursor.isFinished()) {
              finished = true;
            } else {
              fireScan(cursor);
            }
          } else {
            exception = ((ScanFailure) signal).exception;
          }
        } catch (InterruptedException e) {
          // Handle interruption by gracefully ending iteration.
          finished = true;
        }
      }
    }

    void fireScan(ScanCursor cursor) {
      if (!tryReferenceConnectionIfOpen()) {
        queue.add(new ScanCompletion(ScanCursor.FINISHED));
        return;
      }
      connection
          .async()
          .scan(key -> queue.add(new ScanKey(key)), cursor, args)
          .whenComplete(this::onScanCompletion);
    }

    private void onScanCompletion(ScanCursor cursor, Throwable exception) {
      try {
        if (exception != null) {
          queue.add(new ScanFailure(exception));
        } else {
          queue.add(new ScanCompletion(cursor));
        }
      } finally {
        dereferenceConnection(false);
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

  private static final class ScanFailure implements ScanSignal {
    final Throwable exception;

    ScanFailure(Throwable exception) {
      this.exception = exception;
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
    public CompletableFuture<Optional<Editor>> editAsync() {
      return RedisStore.this.editAsync(null, entryKey, entryVersion);
    }

    @Override
    public boolean removeEntry() throws IOException {
      try {
        return Utils.get(RedisStore.this.removeEntryAsync(entryKey, entryVersion));
      } catch (InterruptedException e) {
        throw (IOException) new InterruptedIOException().initCause(e);
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        dereferenceConnection(false);
      }
    }

    private final class RedisEntryReader implements EntryReader {
      private final AtomicBoolean pendingRead = new AtomicBoolean();
      private final AtomicLong streamPosition = new AtomicLong();

      /**
       * Whether it's expected that the data we're currently reading is fresh (i.e. has not been
       * overwritten or removed).
       */
      private volatile boolean readingFreshEntry = true;

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
                ? connection.async().getrange(dataKey, position, inclusiveLimit)
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
        return evalAsync(
            Script.GET_STALE_RANGE,
            List.of(dataKey),
            List.of(
                encodeLong(position), encodeLong(inclusiveLimit), encodeLong(staleEntryTtlSeconds)),
            ScriptOutputType.VALUE);
      }

      private CompletionStage<ByteBuffer> fallbackToStaleEntryIfEmptyRange(
          ByteBuffer range, long position, long inclusiveLimit) {
        if (range.hasRemaining()) {
          return CompletableFuture.completedFuture(range);
        }
        if (!readingFreshEntry) {
          return CompletableFuture.failedFuture(new EntryEvictedException("stale entry removed"));
        }
        return getStaleRange(position, inclusiveLimit)
            .thenCompose(
                staleRange -> {
                  if (!staleRange.hasRemaining()) {
                    return CompletableFuture.failedFuture(
                        new EntryEvictedException("stale entry removed"));
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
      return RedisStore.this
          .<Boolean>evalAsync(
              Script.COMMIT,
              List.of(entryKey, editorLockKey, wipDataKey),
              List.of(
                  UTF_8.encode(editorId),
                  metadata,
                  encodeLong(writer.dataSizeIfWritten()), // clientDataSize
                  encodeLong(staleEntryTtlSeconds),
                  encodeLong(1)), // commit
              ScriptOutputType.BOOLEAN)
          .whenComplete((__, ___) -> dereferenceConnection(false));
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        // Discard the edit in background.
        evalAsync(
                Script.COMMIT,
                List.of(entryKey, editorLockKey, wipDataKey),
                List.of(
                    UTF_8.encode(editorId),
                    EMPTY_BUFFER, // metadata
                    encodeLong(-1), // clientDataSize
                    encodeLong(staleEntryTtlSeconds),
                    encodeLong(0)), // commit
                ScriptOutputType.STATUS)
            .whenComplete((__, ___) -> dereferenceConnection(false));
      }
    }

    private class RedisEntryWriter implements EntryWriter {
      /* Whether a write is currently in progress. */
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

        int toWriteCount = src.remaining();
        return append(src)
            .thenApply(size -> onWriteCompletion(size, toWriteCount))
            .whenComplete((__, ___) -> pendingWrite.set(false))
            .toCompletableFuture();
      }

      private CompletionStage<Long> append(ByteBuffer src) {
        return evalAsync(
            Script.APPEND,
            List.of(editorLockKey, wipDataKey),
            List.of(src, UTF_8.encode(editorId), encodeLong(editorLockTtlSeconds)),
            ScriptOutputType.INTEGER);
      }

      private int onWriteCompletion(long wipDataSize, int written) {
        if (written < 0 || totalBytesWritten.addAndGet(written) < wipDataSize) {
          RedisEditor.this.close();
          throw new IllegalStateException("wip entry evicted");
        }
        isWritten = true;
        return written;
      }

      long dataSizeIfWritten() {
        return isWritten ? totalBytesWritten.get() : -1;
      }
    }
  }
}
