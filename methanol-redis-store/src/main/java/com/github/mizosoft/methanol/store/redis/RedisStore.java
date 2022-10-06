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
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

final class RedisStore implements Store {
  private static final int STORE_VERSION = 1;
  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final RedisClient client;
  private final int blockSize;
  private final int appVersion;

  private final StatefulRedisConnection<String, ByteBuffer> connection;
  private final ScriptRunner<String, ByteBuffer> scriptRunner;

  RedisStore(RedisClient client, int blockSize, int appVersion) {
    this.blockSize = blockSize;
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
    var redisKeyPrefix = toEntryKeyPrefix(key);
    var reply =
        scriptRunner.<List<ByteBuffer>>run(
            Script.VIEW2, ScriptOutputType.MULTI, new String[] {redisKeyPrefix});

    if (reply.isEmpty()) {
      return null;
    }

    var metadata = reply.get(0);
    long dataSize = decodeLong(reply.get(1));
    int version = decodeInt(reply.get(2));
    var dataKey = UTF_8.decode(reply.get(3)).toString();
    return new RedisViewer2(key, metadata, dataSize, dataKey, version, connection);
  }

  @Override
  public @Nullable Editor edit(String key) throws IOException {
    var redisKeyPrefix = toEntryKeyPrefix(key);
    var wipDataKey = redisKeyPrefix + ":data:wip";
    return connection.sync().setnx(wipDataKey, EMPTY_BUFFER)
        ? new RedisEditor2(key, redisKeyPrefix, wipDataKey, connection, scriptRunner)
        : null;
  }

  @Override
  public Iterator<Viewer> iterator() throws IOException {
    return TODO();
  }

  @Override
  public boolean remove(String key) throws IOException {
    return TODO();
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

  private String toEntryKeyPrefix(String key) {
    return String.format("methanol:%d:%d:{%s}", STORE_VERSION, appVersion, requireNonNull(key));
  }

  private static final class RedisViewer2 implements Viewer {
    private final String key;
    private final ByteBuffer metadata;
    private final long dataSize;
    private final String dataKey;
    private final int version;
    private final StatefulRedisConnection<String, ByteBuffer> connection;

    private RedisViewer2(
        String key,
        ByteBuffer metadata,
        long dataSize,
        String dataKey,
        int version,
        StatefulRedisConnection<String, ByteBuffer> connection) {
      this.key = key;
      this.metadata = metadata.asReadOnlyBuffer();
      this.dataSize = dataSize;
      this.dataKey = dataKey;
      this.version = version;
      this.connection = connection;
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
      return TODO();
    }

    @Override
    public boolean removeEntry() throws IOException {
      return TODO();
    }

    @Override
    public void close() {
      // TODO delete data if openCount is zero and the entry is stale.
    }
  }

  private static final class RedisEditor2 implements Editor {
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

    private RedisEditor2(
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
              Script.COMMIT2,
              ScriptOutputType.STATUS,
              new String[] {redisKeyPrefix},
              metadata,
              encodeInt(commitMetadata ? 1 : 0),
              encodeInt(commitData ? 1 : 0));
      System.out.println("Commit reply: " + reply);
    }
  }

  private static final class RedisViewer implements Viewer {
    private final String key;
    private final String entryKey;
    private final ByteBuffer metadata;
    private final int blockSize;
    private final int blockCount;
    private final StatefulRedisConnection<String, ByteBuffer> connection;

    private final int prefetch = 4;
    private final int prefetchThreshold = 0;
    private final Queue<ByteBuffer> window = new ArrayDeque<>();
    private final Lock lock = new ReentrantLock();

    private int blockIndex;
    private boolean pendingRead;
    private CompletableFuture<?> pendingWindowUpdate;
    private boolean hasPendingWindowUpdate;

    private RedisViewer(
        String key,
        String entryKeyPrefix,
        ByteBuffer metadata,
        int blockSize,
        int blockCount,
        int entryVersion,
        StatefulRedisConnection<String, ByteBuffer> connection) {
      this.key = key;
      this.entryKey = entryKeyPrefix + ":" + entryVersion;
      this.metadata = metadata.asReadOnlyBuffer();
      this.blockSize = blockSize;
      this.blockCount = blockCount;
      this.connection = connection;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public ByteBuffer metadata() {
      return metadata.duplicate();
    }

    private int read(ByteBuffer dst) {
      int read = 0;
      while (dst.hasRemaining() && !window.isEmpty()) {
        var head = window.peek();
        read += Utils.copyRemaining(head, dst);
        if (!head.hasRemaining()) {
          window.poll();
        }
      }
      return read;
    }

    private void finishWindowUpdate(@Nullable List<ByteBuffer> blocks) {
      lock.lock();
      try {
        pendingWindowUpdate = null;
        hasPendingWindowUpdate = false;
        if (blocks != null && blocks.size() > 0) {
          window.addAll(blocks);
          blockIndex += blocks.size();
        }
      } finally {
        lock.unlock();
      }
    }

    private CompletableFuture<?> tryScheduleWindowUpdate() {
      var ongoing = pendingWindowUpdate;
      if (ongoing != null) {
        return ongoing;
      }

      int current = window.size();
      if (current <= prefetchThreshold && blockIndex < blockCount) {
        int remaining = prefetch - current;
        hasPendingWindowUpdate = true;
        var future =
            readBlocks(remaining)
                .handle(
                    (blocks, error) -> {
                      finishWindowUpdate(blocks);
                      return error != null
                          ? CompletableFuture.failedFuture(error)
                          : CompletableFuture.completedFuture(null);
                    })
                .thenCompose(Function.identity());
        if (hasPendingWindowUpdate) {
          pendingWindowUpdate = future;
        }
        return future;
      }

      return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<List<ByteBuffer>> readBlocks(int count) {
      var commands = connection.async();
      return commands
          .hmget(
              entryKey,
              IntStream.range(blockIndex, Math.min(blockIndex + count, blockCount))
                  .mapToObj(Integer::toString)
                  .toArray(String[]::new))
          .thenApply(
              result ->
                  result.stream().map(KeyValue::getValue).collect(Collectors.toUnmodifiableList()))
          .toCompletableFuture();
    }

    @Override
    public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
      // TODO ignore position for now.

      requireNonNull(dst);
      if (!dst.hasRemaining()) {
        return CompletableFuture.completedFuture(0);
      }

      lock.lock();
      try {
        requireState(!pendingRead, "pending read");

        int read = read(dst);
        if (read > 0) {
          tryScheduleWindowUpdate();
          return CompletableFuture.completedFuture(read);
        }

        if (blockIndex >= blockCount) {
          return CompletableFuture.completedFuture(-1);
        }

        pendingRead = true;
        return tryScheduleWindowUpdate()
            .handle(
                (__, error) -> {
                  int localRead = finishPendingRead(error != null ? null : dst);
                  return error != null
                      ? CompletableFuture.<Integer>failedFuture(error)
                      : CompletableFuture.completedFuture(localRead);
                })
            .thenCompose(Function.identity());
      } finally {
        lock.unlock();
      }
    }

    private int finishPendingRead(@Nullable ByteBuffer dst) {
      lock.lock();
      try {
        pendingRead = false;
        if (dst == null) {
          return 0;
        }

        int read = read(dst);
        tryScheduleWindowUpdate();
        return read;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public long dataSize() {
      return (long) blockSize * blockCount;
    }

    @Override
    public long entrySize() {
      return dataSize() + metadata.remaining();
    }

    @Override
    public @Nullable Editor edit() throws IOException {
      return TODO();
    }

    @Override
    public boolean removeEntry() throws IOException {
      return false;
    }

    @Override
    public void close() {}
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

  private static final class RedisEditor implements Editor {
    private final String key;
    private final String entryKeyPrefix;
    private final String wipEntryKey;
    private final StatefulRedisConnection<String, ByteBuffer> connection;

    private @MonotonicNonNull ByteBuffer metadata;

    private final List<ByteBuffer> buffers = new ArrayList<>();

    private final Lock lock = new ReentrantLock();

    private int blockIndex;

    private boolean commitOnClose;
    private boolean closed;

    private final int blockSize;

    private final int blockCountForFlush = 8;

    private boolean pendingWrite;
    private final ScriptRunner<String, ByteBuffer> scriptRunner;

    private void write(ByteBuffer src) {
      var tail = buffers.isEmpty() ? null : buffers.get(buffers.size() - 1);
      while (src.hasRemaining()) {
        if (tail == null || !tail.hasRemaining()) {
          tail = ByteBuffer.allocate(blockSize);
          buffers.add(tail);
        }
        Utils.copyRemaining(src, tail);
      }
    }

    private RedisEditor(
        String key,
        String entryKeyPrefix,
        String wipEntryKey,
        int blockSize,
        StatefulRedisConnection<String, ByteBuffer> connection,
        ScriptRunner<String, ByteBuffer> scriptRunner) {
      this.key = key;
      this.entryKeyPrefix = entryKeyPrefix;
      this.wipEntryKey = wipEntryKey;
      this.blockSize = blockSize;
      this.connection = connection;
      this.scriptRunner = scriptRunner;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public void metadata(java.nio.ByteBuffer metadata) {
      lock.lock();
      try {
        this.metadata = Utils.copy(metadata).asReadOnlyBuffer();
      } finally {
        lock.unlock();
      }
    }

    private List<ByteBuffer> slice(boolean finished) {
      if (buffers.isEmpty()) {
        return List.of();
      }

      // If last buffer is incomplete, it is only submitted if finished is true provided it has some
      // bytes.
      int size = buffers.size();
      int snapshotSize = size;
      var last = buffers.get(size - 1);
      if (last.hasRemaining() && (!finished || last.position() == 0)) {
        snapshotSize--; // Don't include in the slice.
      }
      return buffers.subList(0, snapshotSize);
    }

    @Override
    public CompletableFuture<Integer> writeAsync(long position, java.nio.ByteBuffer src) {
      // TODO ignore position for now.
      requireNonNull(src);
      if (!src.hasRemaining()) {
        return CompletableFuture.completedFuture(0);
      }

      lock.lock();
      try {
        requireState(!pendingWrite, "pending write");

        int toWrite = src.remaining();

        write(src);

        var slice = slice(false);
        if (slice.size() < blockCountForFlush) {
          return CompletableFuture.completedFuture(toWrite);
        }

        int localBlockIndex = this.blockIndex;

        var blocks = new HashMap<String, ByteBuffer>();
        for (var buffer : slice) {
          blocks.put(Integer.toString(localBlockIndex), buffer.flip());
          localBlockIndex++;
        }

        pendingWrite = true;
        return connection
            .async()
            .hset(wipEntryKey, blocks)
            .handle(
                (__, error) -> {
                  finishPendingWrite(error != null ? null : slice);
                  return error != null
                      ? CompletableFuture.<Integer>failedFuture(error)
                      : CompletableFuture.completedFuture(toWrite);
                })
            .thenCompose(Function.identity())
            .toCompletableFuture();
      } finally {
        lock.unlock();
      }
    }

    private void finishPendingWrite(@Nullable List<ByteBuffer> slice) {
      lock.lock();
      try {
        pendingWrite = false;
        if (slice != null) {
          blockIndex += slice.size();
          slice.clear();
        }
      } finally {
        lock.unlock();
      }
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
      ByteBuffer[] fields;

      lock.lock();
      try {
        if (closed) {
          return;
        }
        closed = true;

        if (!commitOnClose) {
          connection.sync().del(wipEntryKey);
          return;
        }

        var slice = slice(true);
        int blockCountSoFar = blockIndex;
        int remainingBlockCount = slice.size();
        fields =
            Stream.concat(
                    Stream.of(
                        metadata,
                        encodeInt(blockCountSoFar),
                        encodeInt(blockSize),
                        encodeInt(remainingBlockCount)),
                    slice.stream().map(ByteBuffer::flip))
                .toArray(ByteBuffer[]::new);
      } finally {
        lock.unlock();
      }

      long reply =
          scriptRunner.run(
              Script.COMMIT,
              ScriptOutputType.INTEGER,
              new String[] {entryKeyPrefix, wipEntryKey},
              fields);

      System.out.println("Version after committing: " + reply);
    }
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
      source.mark();
      var copy = Utils.copy(source);
      source.reset();
      return copy;
    }
  }

  public static void main(String[] args) throws Exception {
    try (var client = RedisClient.create(RedisURI.create("redis://localhost"))) {
      var store = new RedisStore(client, 1, 1);
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
    }

    System.out.println("Great success!");
  }
}
