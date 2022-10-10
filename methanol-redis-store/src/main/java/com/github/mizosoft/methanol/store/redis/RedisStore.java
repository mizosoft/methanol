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
import static com.github.mizosoft.methanol.store.redis.Script.COMMIT;
import static com.github.mizosoft.methanol.store.redis.Script.EDIT;
import static com.github.mizosoft.methanol.store.redis.Script.REMOVE;
import static com.github.mizosoft.methanol.store.redis.Script.VIEW;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

final class RedisStore implements Store {
  private static final int STORE_VERSION = 1;
  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final int appVersion;
  private final StatefulRedisConnection<String, ByteBuffer> connection;
  private final AtomicInteger connectionRefCount = new AtomicInteger(1);
  private final Set<RedisEditor> openEditors = ConcurrentHashMap.newKeySet();
  private final ReadWriteLock closeLock = new ReentrantReadWriteLock();
  private boolean closed;

  RedisStore(RedisClient client, RedisURI redisUri, int appVersion) {
    connection =
        client.connect(RedisCodec.of(new StringCodec(UTF_8), ByteBufferCodec.INSTANCE), redisUri);
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
    return openEditor(key, toEntryKey(key), -1);
  }

  @Override
  public Iterator<Viewer> iterator() {
    return iterator(true);
  }

  @Override
  public boolean remove(String key) {
    return removeEntry(toEntryKey(key), -1);
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
      List<ByteBuffer> viewResult = runScript(VIEW, ScriptOutputType.MULTI, List.of(entryKey));
      var viewer =
          viewResult.isEmpty()
              ? null
              : new RedisViewer(
                  key,
                  entryKey,
                  viewResult.get(0), // metadata
                  decodeLong(viewResult.get(1)), // dataSize
                  decodeLong(viewResult.get(2)), // version
                  UTF_8.decode(viewResult.get(3)).toString()); // dataKey
      if (viewer != null) {
        referenceConnection();
      }
      return viewer;
    } finally {
      closeLock.readLock().unlock();
    }
  }

  private @Nullable Editor openEditor(@Nullable String key, String entryKey, long version) {
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      var wipDataKey = entryKey + ":data:wip";
      var editor =
          runScript(EDIT, ScriptOutputType.BOOLEAN, List.of(entryKey), encodeLong(version))
              ? new RedisEditor(key, entryKey, wipDataKey)
              : null;
      if (editor != null) {
        openEditors.add(editor);
        referenceConnection();
      }
      return editor;
    } finally {
      closeLock.readLock().unlock();
    }
  }

  private boolean removeEntry(String entryKey, long version) {
    closeLock.readLock().lock();
    try {
      requireNotClosed();
      return runScript(REMOVE, ScriptOutputType.BOOLEAN, List.of(entryKey), encodeLong(version));
    } finally {
      closeLock.readLock().unlock();
    }
  }

  private Iterator<Viewer> iterator(boolean concurrentlyCloseable) {
    var pattern = toEntryKey("*"); // Match all keys.
    var iterator =
        concurrentlyCloseable
            ? new ClosureAwareScanningViewerIterator(pattern)
            : new ScanningViewerIterator(toEntryKey("*"));
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
      // Prevent active editors from committing successfully. We know no editors can be added
      // concurrently since we've set closed to `true`, and editors are added within
      // closeLock.readLock() scope only if yet closed.
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

  private <T> T runScript(
      Script script, ScriptOutputType outputType, List<String> keys, ByteBuffer... values) {
    var commands = connection.sync();
    try {
      return commands.evalsha(script.sha1(), outputType, keys.toArray(String[]::new), values);
    } catch (RedisNoScriptException e) {
      return commands.eval(script.encodedBytes(), outputType, keys.toArray(String[]::new), values);
    }
  }

  private String toEntryKey(String key) {
    return String.format("methanol:%d:%d:{%s}", STORE_VERSION, appVersion, requireNonNull(key));
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
    int firstBrace = entryKey.indexOf('{');
    int lastBrace = entryKey.indexOf('}');
    requireArgument(
        firstBrace >= 0 && lastBrace >= 0 && firstBrace + 1 < lastBrace, "improper hash tag");
    return entryKey.substring(firstBrace + 1, lastBrace);
  }

  private static ByteBuffer encodeLong(long value) {
    return UTF_8.encode(Long.toString(value));
  }

  private static ByteBuffer encodeInt(int value) {
    return UTF_8.encode(Integer.toString(value));
  }

  private static int decodeInt(ByteBuffer value) {
    return Integer.parseInt(UTF_8.decode(value).toString());
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
        // TODO log.
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
            var viewer = openViewer(null, ((ScanKey) signal).key);
            if (viewer != null) {
              nextViewer = viewer;
              return true;
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
          // To handle interruption gracefully, we just end the iteration process.
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
        // If the store is closed concurrently, finish iteration gracefully.
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
    private final long version;
    private final String dataKey;

    RedisViewer(
        @Nullable String key,
        String entryKey,
        ByteBuffer metadata,
        long dataSize,
        long version,
        String dataKey) {
      this.key = key;
      this.entryKey = entryKey;
      this.metadata = metadata.asReadOnlyBuffer();
      this.dataSize = dataSize;
      this.version = version;
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
      if (position >= dataSize) {
        return CompletableFuture.completedFuture(-1);
      }
      if (!dst.hasRemaining()) {
        return CompletableFuture.completedFuture(0);
      }

      // TODO handle failed getrange for stale entries.
      return connection
          .async()
          .getrange(dataKey, position, Math.min(position + dst.remaining(), dataSize) - 1)
          .thenApply(src -> Utils.copyRemaining(src, dst))
          .toCompletableFuture();
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
      return openEditor(null, entryKey, version);
    }

    @Override
    public boolean removeEntry() {
      return RedisStore.this.removeEntry(entryKey, version);
    }

    @Override
    public void close() {
      try {
        // TODO delete data if openCount is zero and the entry is stale.
      } finally {
        dereferenceConnection();
      }
    }
  }

  private final class RedisEditor implements Editor {
    private final String key;
    private final String entryKey;
    private final String wipDataKey;
    private final Lock lock = new ReentrantLock();
    private @MonotonicNonNull ByteBuffer metadata;
    private boolean commitOnClose;
    private boolean commitData;
    private boolean closed;

    RedisEditor(String key, String entryKey, String wipDataKey) {
      this.key = key;
      this.entryKey = entryKey;
      this.wipDataKey = wipDataKey;
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
        commitData = true;
      } finally {
        lock.unlock();
      }

      if (!src.hasRemaining()) {
        return CompletableFuture.completedFuture(0);
      }

      int remaining = src.remaining();
      return connection
          .async()
          .append(wipDataKey, src)
          .thenApply(__ -> remaining)
          .toCompletableFuture();
    }

    @Override
    public void commitOnClose() {
      lock.lock();
      try {
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
        } else {
          metadata = EMPTY_BUFFER;
          commitMetadata = false;
          commitData = false;
        }
      } finally {
        lock.unlock();
      }

      try {
        runScript(
            COMMIT,
            ScriptOutputType.STATUS,
            List.of(entryKey),
            metadata,
            encodeInt(commitMetadata ? 1 : 0),
            encodeInt(commitData ? 1 : 0));
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

        try {
          connection.sync().unlink(wipDataKey);
        } finally {
          dereferenceConnection();
        }
      } finally {
        lock.unlock();
      }
    }
  }

  private enum ByteBufferCodec implements RedisCodec<ByteBuffer, ByteBuffer> {
    INSTANCE;

    @Override
    public ByteBuffer decodeKey(ByteBuffer bytes) {
      return copy(bytes);
    }

    @Override
    public ByteBuffer decodeValue(ByteBuffer bytes) {
      return copy(bytes);
    }

    @Override
    public ByteBuffer encodeKey(ByteBuffer key) {
      return copy(key);
    }

    @Override
    public ByteBuffer encodeValue(ByteBuffer value) {
      return copy(value);
    }

    private static ByteBuffer copy(ByteBuffer source) {
      return Utils.copy(source.duplicate());
    }
  }
}
