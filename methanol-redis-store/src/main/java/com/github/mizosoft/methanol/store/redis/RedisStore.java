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
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
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
  private final int blockSize;
  private final RedisClient client;

  private final StatefulRedisConnection<String, ByteBuffer> connection;

  RedisStore(int blockSize, RedisClient client) {
    this.blockSize = blockSize;
    this.client = requireNonNull(client);
    this.connection =
        client.connect(RedisCodec.of(new StringCodec(UTF_8), ByteBufferCopyCodec.INSTANCE));
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

  private String toRedisKey(String key) {
    return key;
  }

  @Override
  public @Nullable Viewer view(String key) throws IOException {
    var commands = connection.sync();
    var redisKey = toRedisKey(key);
    List<ByteBuffer> reply =
        commands.eval(
            "local version = redis.call('get', KEYS[1] .. ':version')\n"
                + "if not version then return false end\n"
                + "local key = KEYS[1] .. ':' .. version\n"
                + "redis.call('hincrby', key, 'openCount', 1)\n"
                + "local reply = redis.call('hmget', key, 'metadata', 'blockCount', 'blockSize')\n"
                + "table.insert(reply, key)\n"
                + "return reply\n",
            ScriptOutputType.MULTI,
            redisKey);

    if (reply.size() == 1) {
      return null;
    }

    var metadata = reply.get(0);
    int blockCount = Integer.parseInt(UTF_8.decode(reply.get(1)).toString());
    int blockSize = Integer.parseInt(UTF_8.decode(reply.get(2)).toString());
    var entryKey = UTF_8.decode(reply.get(3)).toString();

    return new RedisViewer(key, entryKey, metadata, blockSize, blockCount, connection);
  }

  @Override
  public @Nullable Editor edit(String key) throws IOException {
    requireNonNull(key);

    var redisKey = toRedisKey(key);
    var wipRedisKey = redisKey + ":wip";
    var commands = connection.sync();
    if (!commands.hsetnx(wipRedisKey, "lock", encodeInt(1))) {
      return null;
    }
    return new RedisEditor(key, redisKey, wipRedisKey, blockSize, connection);
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
        String entryKey,
        ByteBuffer metadata,
        int blockSize,
        int blockCount,
        StatefulRedisConnection<String, ByteBuffer> connection) {
      this.key = key;
      this.entryKey = entryKey;
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
                  .mapToObj(i -> "b" + i)
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

  private static final class RedisEditor implements Editor {
    private final String key;
    private final String redisKey;
    private final String wipRedisKey;
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
        String redisKey,
        String wipRedisKey,
        int blockSize,
        StatefulRedisConnection<String, ByteBuffer> connection) {
      this.key = key;
      this.redisKey = redisKey;
      this.wipRedisKey = wipRedisKey;
      this.blockSize = blockSize;
      this.connection = connection;
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
          blocks.put("b" + localBlockIndex, buffer.flip());
          localBlockIndex++;
        }

        pendingWrite = true;
        return connection
            .async()
            .hset(wipRedisKey, blocks)
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
          connection.sync().del(wipRedisKey);
          return;
        }

        var slice = slice(true);
        fields =
            Stream.concat(
                    Stream.of(
                        metadata,
                        encodeInt(blockIndex),
                        encodeInt(blockSize),
                        encodeInt(slice.size())),
                    slice.stream().map(ByteBuffer::flip))
                .toArray(ByteBuffer[]::new);
      } finally {
        lock.unlock();
      }

      System.out.println("here: " + Thread.currentThread().getName());
      long reply =
          connection
              .sync()
              .eval(
                  "local metadata = ARGV[1]\n"
                      + "redis.log(redis.LOG_WARNING, 'here!')\n"
                      + "local blockCount = ARGV[2]\n"
                      + "local blockSize = ARGV[3]\n"
                      + "local remainingBlockCount = ARGV[4]\n"
                      + "\n"
                      + "local fields = { 'metadata', metadata, 'blockCount', blockCount + remainingBlockCount, 'blockSize', blockSize }\n"
                      + "\n"
                      + "for index = 1, remainingBlockCount do\n"
                      + "    table.insert(fields, 'b' .. (blockCount + index - 1))\n"
                      + "    table.insert(fields, ARGV[index + 4])\n"
                      + "end\n"
                      + "\n"
                      + "redis.log(redis.LOG_WARNING, KEYS[2], unpack(fields))\n"
                      + "redis.call('hset', KEYS[2], unpack(fields))\n"
                      + "\n"
                      + "local version = 1 + (redis.call('get', KEYS[1] .. ':version') or -1)\n"
                      + "redis.log(redis.LOG_WARNING, 'here!')\n"
                      + "redis.call('rename', KEYS[2], KEYS[1] .. ':' .. version)\n"
                      + "redis.call('set', KEYS[1] .. ':version', version)\n"
                      + "redis.call('hdel', KEYS[1] .. ':' .. version, 'lock')\n"
                      + "return version\n",
                  ScriptOutputType.INTEGER,
                  new String[] {redisKey, wipRedisKey},
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
      ByteBuffer copy = ByteBuffer.allocate(source.remaining());
      copy.put(source);
      copy.flip();
      return copy;
    }
  }

  public static void main(String[] args) throws Exception {
    try (var client = RedisClient.create(RedisURI.create("redis://localhost"))) {
      client.setOptions(
          ClientOptions.builder()
              .timeoutOptions(TimeoutOptions.builder().fixedTimeout(Duration.ofDays(1)).build())
              .build());

      var store = new RedisStore(1, client);
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
        while (viewer.readAsync(0, buffer).join() != -1) {
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
