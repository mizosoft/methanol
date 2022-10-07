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

import static com.github.mizosoft.methanol.internal.Validate.TODO;
import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

final class RedisStore implements Store {
  private static final int STORE_VERSION = 1;
  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final RedisClient client;
  private final int appVersion;

  private final StatefulRedisConnection<String, ByteBuffer> connection;
  private final ScriptRunner<String, ByteBuffer> scriptRunner;

  RedisStore(RedisClient client, int appVersion) {
    this.client = requireNonNull(client);
    this.appVersion = appVersion;
    connection =
        client.connect(RedisCodec.of(new StringCodec(UTF_8), ByteBufferCopyCodec.INSTANCE));
    scriptRunner = new ScriptRunner<>(connection);
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
  public void initialize() throws IOException {
    TODO();
  }

  @Override
  public CompletableFuture<Void> initializeAsync() {
    return TODO();
  }

  @Override
  public @Nullable Viewer view(String key) throws IOException {
    return openViewer(toRedisKey(key));
  }

  private @Nullable Viewer openViewer(String redisKey) throws IOException {
    var reply = scriptRunner.<List<ByteBuffer>>run(Script.VIEW, ScriptOutputType.MULTI, redisKey);

    if (reply.isEmpty()) {
      return null;
    }

    var metadata = reply.get(0);
    long dataSize = decodeLong(reply.get(1));
    long version = decodeLong(reply.get(2));
    var dataKey = UTF_8.decode(reply.get(3)).toString();
    return new RedisViewer(
        "", redisKey, metadata, dataSize, version, dataKey, connection, scriptRunner);
  }

  @Override
  public @Nullable Editor edit(String key) throws IOException {
    var redisKey = toRedisKey(key);
    var wipDataKey = redisKey + ":data:wip";
    return connection.sync().setnx(wipDataKey, EMPTY_BUFFER)
        ? new RedisEditor(key, redisKey, wipDataKey, connection, scriptRunner)
        : null;
  }

  @Override
  public Iterator<Viewer> iterator() throws IOException {
    var iterator = new ScanningViewerIterator(connection, toRedisKey("*"));
    iterator.fireScan(ScanCursor.INITIAL);
    return iterator;
  }

  @Override
  public boolean remove(String key) throws IOException {
    return scriptRunner.run(
        Script.REMOVE, ScriptOutputType.BOOLEAN, new String[] {toRedisKey(key)}, encodeLong(-1));
  }

  @Override
  public void clear() throws IOException {
    TODO();
  }

  @Override
  public long size() throws IOException {
    return TODO();
  }

  @Override
  public void dispose() throws IOException {
    TODO();
  }

  @Override
  public void close() throws IOException {
    TODO();
  }

  @Override
  public void flush() throws IOException {
    TODO();
  }

  private String toRedisKey(String key) {
    return String.format("methanol:%d:%d:{%s}", STORE_VERSION, appVersion, requireNonNull(key));
  }

  private final class ScanningViewerIterator implements Iterator<Viewer> {
    private final StatefulRedisConnection<String, ByteBuffer> connection;
    private final ScanArgs args;
    private final BlockingQueue<ScanSignal> queue = new LinkedBlockingQueue<>();

    private @Nullable Viewer nextViewer;
    private @Nullable Viewer currentViewer;

    private boolean finished;
    private @MonotonicNonNull Throwable error;

    ScanningViewerIterator(StatefulRedisConnection<String, ByteBuffer> connection, String pattern) {
      this.connection = connection;
      this.args = ScanArgs.Builder.limit(512).match(pattern, UTF_8);
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
        // logger.log(Level.WARNING, "entry removal failure", e);
      } catch (IllegalStateException ignored) {
        // Fail silently if the store is closed.
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
            throw new CompletionException(error);
          }
        }

        try {
          var signal = queue.take();
          if (signal instanceof ScanKey) {
            var viewer = openViewer(((ScanKey) signal).key);
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
          finished = true;
        } catch (IOException ignored) {
          // TODO log.
        }
      }
    }

    void fireScan(ScanCursor cursor) {
      connection
          .async()
          .scan(key -> queue.add(new ScanKey(key)), cursor, args)
          .whenComplete(
              (nextCursor, error) -> {
                if (nextCursor != null) {
                  queue.add(new ScanCompletion(nextCursor));
                } else {
                  queue.add(new ScanError(error));
                }
              });
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

  private static final class RedisViewer implements Viewer {
    private final String key;
    private final String redisKey;
    private final ByteBuffer metadata;
    private final long dataSize;
    private final long version;
    private final String dataKey;
    private final StatefulRedisConnection<String, ByteBuffer> connection;
    private final ScriptRunner<String, ByteBuffer> scriptRunner;

    RedisViewer(
        String key,
        String redisKey,
        ByteBuffer metadata,
        long dataSize,
        long version,
        String dataKey,
        StatefulRedisConnection<String, ByteBuffer> connection,
        ScriptRunner<String, ByteBuffer> scriptRunner) {
      this.key = key;
      this.redisKey = redisKey;
      this.metadata = metadata.asReadOnlyBuffer();
      this.dataSize = dataSize;
      this.version = version;
      this.dataKey = dataKey;
      this.connection = connection;
      this.scriptRunner = scriptRunner;
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
    public @Nullable Editor edit() throws IOException {
      return scriptRunner.run(
          Script.EDIT_VERSION,
          ScriptOutputType.BOOLEAN,
          new String[] {redisKey},
          encodeLong(version));
    }

    @Override
    public boolean removeEntry() throws IOException {
      return scriptRunner.run(
          Script.REMOVE, ScriptOutputType.BOOLEAN, new String[] {redisKey}, encodeLong(version));
    }

    @Override
    public void close() {
      // TODO delete data if openCount is zero and the entry is stale.
    }
  }

  private static final class RedisEditor implements Editor {
    private final String key;
    private final String redisKeyPrefix;
    private final String dataKey;
    private final StatefulRedisConnection<String, ByteBuffer> connection;

    private final ScriptRunner<String, ByteBuffer> scriptRunner;
    private final Lock lock = new ReentrantLock();
    private @MonotonicNonNull ByteBuffer metadata;
    private boolean commitOnClose;
    private boolean closed;
    private boolean commitData;

    RedisEditor(
        String key,
        String redisKeyPrefix,
        String dataKey,
        StatefulRedisConnection<String, ByteBuffer> connection,
        ScriptRunner<String, ByteBuffer> scriptRunner) {
      this.key = key;
      this.redisKeyPrefix = redisKeyPrefix;
      this.dataKey = dataKey;
      this.connection = connection;
      this.scriptRunner = scriptRunner;
    }

    @Override
    public String key() {
      return key;
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
        if (closed) {
          throw new IllegalStateException("closed");
        }
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
          .append(dataKey, src)
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
    public void close() throws IOException {
      ByteBuffer metadata;
      boolean commitMetadata;
      boolean commitData;
      lock.lock();
      try {
        if (closed) {
          return;
        }
        closed = true;

        if (!commitOnClose) {
          return;
        }

        metadata = requireNonNullElse(this.metadata, EMPTY_BUFFER);
        commitMetadata = this.metadata != null;
        commitData = this.commitData;

        if (!commitMetadata && !commitData) {
          throw new IllegalStateException("nothing to commit");
        }
      } finally {
        lock.unlock();
      }

      var reply =
          scriptRunner.run(
              Script.COMMIT,
              ScriptOutputType.STATUS,
              new String[] {redisKeyPrefix},
              metadata,
              encodeInt(commitMetadata ? 1 : 0),
              encodeInt(commitData ? 1 : 0));
      System.out.println("Commit reply: " + reply);
    }
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

  enum ByteBufferCopyCodec implements RedisCodec<ByteBuffer, ByteBuffer> {
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

  public static void main(String[] args) throws Exception {
    try (var client = RedisClient.create(RedisURI.create("redis://localhost"))) {

      var store = new RedisStore(client, 1);
      try (var editor = requireNonNull(store.edit("e1"))) {
        requireState(store.edit("e1") == null, "multiple editors");
        editor.metadata(UTF_8.encode("Steve Rogers"));
        editor.writeAsync(0, UTF_8.encode("Avengers, Assemble!"));
        editor.commitOnClose();
      }

      System.out.println("Committed entry");

      try (var viewer = requireNonNull(store.view("e1"))) {
        var metadata = UTF_8.decode(viewer.metadata()).toString();
        if (!metadata.equals("Steve Rogers")) {
          throw new RuntimeException("what? " + metadata);
        }

        var buffers = new ArrayList<ByteBuffer>();

        var buffer = ByteBuffer.allocate(1);
        long position = 0;
        int read;
        while ((read = viewer.readAsync(position, buffer).join()) != -1) {
          position += read;
          if (!buffer.hasRemaining()) {
            buffers.add(buffer.flip());
            buffer = ByteBuffer.allocate(1);
          }
        }
        if (buffer.position() > 0) {
          buffers.add(buffer.flip());
        }

        var all =
            buffers.stream()
                .reduce(
                    (b1, b2) ->
                        ByteBuffer.allocate(b1.remaining() + b2.remaining()).put(b1).put(b2).flip())
                .orElseThrow();

        var data = UTF_8.decode(all).toString();
        if (!data.equals("Avengers, Assemble!")) {
          throw new RuntimeException("what? " + data);
        }
      }

      var iter = store.iterator();
      System.out.println("Has next? " + iter.hasNext());
      System.out.println("Next metadata: " + UTF_8.decode(iter.next().metadata()));

      store.remove("e1");
      System.out.println("After removal: " + store.view("e1"));
    }

    System.out.println("Great success!");
  }
}
