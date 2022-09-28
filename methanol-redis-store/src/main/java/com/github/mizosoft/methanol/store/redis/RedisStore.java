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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class RedisStore implements Store {

  @Override
  public long maxSize() {
    return TODO();
  }

  @Override
  public Optional<Executor> executor() {
    return TODO();
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
    return TODO();
  }

  @Override
  public @Nullable Editor edit(String key) throws IOException {
    return TODO();
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

  private static final class DiskViewer implements Viewer {
    @Override
    public String key() {
      return TODO();
    }

    @Override
    public ByteBuffer metadata() {
      return TODO();
    }

    @Override
    public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
      return TODO();
    }

    @Override
    public long dataSize() {
      return TODO();
    }

    @Override
    public long entrySize() {
      return TODO();
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
      TODO();
    }
  }

  private static final class RedisEditor implements Editor {
    private final String key;
    private final String redisKey;
    private final String wipRedisKey;
    private final RedisAsyncCommands<String, ByteBuffer> commands;

    private @MonotonicNonNull ByteBuffer metadata;

    private final List<ByteBuffer> buffers = new ArrayList<>();

    private final Lock lock = new ReentrantLock();

    private int blockCount;

    private boolean commitOnClose;
    private boolean closed;

    private final int blockSize;

    private final int blockCountForFlush = 1;

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
        RedisAsyncCommands<String, ByteBuffer> commands) {
      this.key = key;
      this.redisKey = redisKey;
      this.wipRedisKey = wipRedisKey;
      this.blockSize = blockSize;
      this.commands = commands;
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
      // Ignore position for now.

      int toWrite = src.remaining();

      write(src);

      var slice = slice(false);
      if (slice.size() >= blockCountForFlush) {
        var snapshot = List.copyOf(slice);
        slice.clear(); // Remove from the original list.

        int blockIndex = blockCount;
        blockCount += snapshot.size();

        var blocks = new HashMap<String, ByteBuffer>();
        for (var buffer : snapshot) {
          blocks.put("b" + blockIndex, buffer.flip());
          blockIndex++;
        }

        return commands.hset(wipRedisKey, blocks).thenApply(__ -> toWrite).toCompletableFuture();
      }

      return CompletableFuture.completedFuture(toWrite);
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
      if (closed) {
        return;
      }
      closed = true;

      if (!commitOnClose) {
        commands.del(wipRedisKey);
        return;
      }

      var fields = new HashMap<String, ByteBuffer>();
      if (metadata != null) {
        fields.put("metadata", metadata);
      }

      int blockIndex = blockCount;
      for (var buffer : slice(true)) {
        fields.put("b" + blockIndex, buffer.flip());
        blockIndex++;
      }

      fields.put("blockCount", UTF_8.encode(Long.toString(blockIndex)));
      fields.put("blockSize", UTF_8.encode(Long.toString(blockSize)));

      commands.hset(wipRedisKey, fields);
      Utils.blockOnIO(commands.rename(wipRedisKey, redisKey));
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
    try (var client = RedisClient.create(RedisURI.create("redis://localhost"));
        var connection =
            client.connect(RedisCodec.of(new StringCodec(UTF_8), ByteBufferCopyCodec.INSTANCE))) {
      var editor = new RedisEditor("e1", "e1", "e1:wip", 1, connection.async());
      editor.metadata(UTF_8.encode("123"));
      editor.writeAsync(0, UTF_8.encode("Optimus Prime"));
      editor.commitOnClose();

      System.in.read();

      editor.close();
      System.out.println("Closed");
    }
  }
}
