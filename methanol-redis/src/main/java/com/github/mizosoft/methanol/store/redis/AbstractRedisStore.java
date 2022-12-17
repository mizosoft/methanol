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
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.async.RedisHashAsyncCommands;
import io.lettuce.core.api.async.RedisKeyAsyncCommands;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A {@link Store} implementation backed by redis. */
abstract class AbstractRedisStore<C extends StatefulConnection<String, ByteBuffer>>
    implements Store {
  private static final Logger logger = System.getLogger(AbstractRedisStore.class.getName());

  private static final int STORE_VERSION = 1;

  static final long ANY_ENTRY_VERSION = -1;

  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  final C connection;
  final int editorLockTtlSeconds;
  final int staleEntryTtlSeconds;
  final int appVersion;

  private volatile boolean closed;

  AbstractRedisStore(
      C connection, int editorLockTtlSeconds, int staleEntryTtlSeconds, int appVersion) {
    requireArgument(
        editorLockTtlSeconds > 0, "Expected a positive ttl, got: %s", editorLockTtlSeconds);
    requireArgument(
        staleEntryTtlSeconds > 0, "Expected a positive ttl, got: %s", staleEntryTtlSeconds);
    this.connection = requireNonNull(connection);
    this.editorLockTtlSeconds = editorLockTtlSeconds;
    this.staleEntryTtlSeconds = staleEntryTtlSeconds;
    this.appVersion = appVersion;
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
        .toCompletableFuture();
  }

  @Override
  public Iterator<Viewer> iterator() {
    requireNotClosed();
    var iter = new ScanningViewerIterator(toEntryKey("*"));
    iter.fireScan(ScanCursor.INITIAL);
    return iter;
  }

  @Override
  public boolean remove(String key) throws IOException, InterruptedException {
    return Utils.get(removeEntryAsync(toEntryKey(key), ANY_ENTRY_VERSION));
  }

  @Override
  public void clear() {
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

  CompletableFuture<Boolean> removeEntryAsync(String entryKey, long targetEntryVersion) {
    requireNotClosed();
    return evalAsync(
        Script.REMOVE,
        List.of(entryKey),
        List.of(encodeLong(targetEntryVersion), encodeLong(staleEntryTtlSeconds)),
        ScriptOutputType.BOOLEAN);
  }

  private void doClose(boolean dispose) {
    if (closed) return;

    closed = true;
    if (dispose) {
      try {
        clear();
      } catch (RedisException e) {
        logger.log(Level.WARNING, "Exception when clearing the store on closure", e);
      }
    }
    connection
        .closeAsync()
        .whenComplete(
            (__, ex) -> {
              if (ex != null) {
                logger.log(Level.WARNING, "Exception when closing connection", ex);
              }
            });
  }

  @Override
  public void flush() {}

  <T> CompletableFuture<T> evalAsync(
      Script script, List<String> keys, List<ByteBuffer> values, ScriptOutputType outputType) {
    var keysArray = keys.toArray(String[]::new);
    var valuesArray = values.toArray(ByteBuffer[]::new);
    return commands()
        .<T>evalsha(script.shaHex(), outputType, keysArray, valuesArray)
        .handle(
            (reply, ex) -> {
              if (ex instanceof RedisNoScriptException) {
                return commands().<T>eval(script.content(), outputType, keysArray, valuesArray);
              }
              return ex != null
                  ? CompletableFuture.<T>failedFuture(ex)
                  : CompletableFuture.completedFuture(reply);
            })
        .thenCompose(Function.identity())
        .toCompletableFuture();
  }

  String toEntryKey(String key) {
    requireArgument(key.indexOf('}') == -1, "Key contains a right brace");
    return String.format("methanol:%d:%d:{%s}", STORE_VERSION, appVersion, key);
  }

  void requireNotClosed() {
    requireState(!closed, "Store is closed");
  }

  private static String extractHashTag(String entryKey) {
    int leftBrace = entryKey.indexOf('{');
    int rightBrace = entryKey.indexOf('}');
    requireArgument(
        leftBrace >= 0 && rightBrace >= 0 && leftBrace + 1 < rightBrace, "Improper hash tag");
    return entryKey.substring(leftBrace + 1, rightBrace);
  }

  static ByteBuffer encodeLong(long value) {
    return UTF_8.encode(Long.toString(value));
  }

  private static long decodeLong(ByteBuffer value) {
    return Long.parseLong(UTF_8.decode(value).toString());
  }

  // TODO we can optimize out round trips with a script that returns the content of scanned keys
  //      instead of only the keys themselves.
  private final class ScanningViewerIterator implements Iterator<Viewer> {
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
        logger.log(Level.WARNING, "Entry removal failure", e);
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
              logger.log(Level.WARNING, "Exception when opening viewer", e);
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
      requireNotClosed();
      commands()
          .scan(key -> queue.add(new ScanKey(key)), cursor, args)
          .whenComplete(this::onScanCompletion);
    }

    private void onScanCompletion(ScanCursor cursor, Throwable exception) {
      if (exception != null) {
        queue.add(new ScanFailure(exception));
      } else {
        queue.add(new ScanCompletion(cursor));
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
      return AbstractRedisStore.this.editAsync(null, entryKey, entryVersion);
    }

    @Override
    public boolean removeEntry() throws IOException {
      try {
        return Utils.get(AbstractRedisStore.this.removeEntryAsync(entryKey, entryVersion));
      } catch (InterruptedException e) {
        throw (IOException) new InterruptedIOException().initCause(e);
      }
    }

    @Override
    public void close() {}

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
      return evalAsync(
          Script.COMMIT,
          List.of(entryKey, editorLockKey, wipDataKey),
          List.of(
              UTF_8.encode(editorId),
              metadata,
              encodeLong(writer.dataSizeIfWritten()), // clientDataSize
              encodeLong(staleEntryTtlSeconds),
              encodeLong(1)), // commit
          ScriptOutputType.BOOLEAN);
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
            ScriptOutputType.STATUS);
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
          throw new IllegalStateException("Editor expired");
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
