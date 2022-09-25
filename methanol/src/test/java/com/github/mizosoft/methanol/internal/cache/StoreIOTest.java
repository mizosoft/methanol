/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.CACHED_POOL;
import static com.github.mizosoft.methanol.testing.TestUtils.toByteArray;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.file.ForwardingAsynchronousFileChannel;
import com.github.mizosoft.methanol.testing.file.ForwardingFileChannel;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ExecutorExtension.class)
class StoreIOTest {
  private FileSystem fileSystem;
  private Path file;

  @BeforeEach
  void setUp() throws IOException {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());

    var directory = fileSystem.getPath("temp-dir");
    fileSystem.provider().createDirectory(directory);
    file = Files.createFile(directory.resolve("file.txt"));
  }

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  void fragmentedRead() throws IOException {
    Files.writeString(file, "Pikachu");

    var channel = new ForwardingFileChannel(FileChannel.open(file, READ)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public int read(ByteBuffer dst) throws IOException {
        int callCount = calls.incrementAndGet();
        if (callCount == 1) {
          // Read only half the buffer on first call
          int originalLimit = dst.limit();
          dst.limit(dst.position() + dst.remaining() / 2);
          try {
            return super.read(dst);
          } finally {
            dst.limit(originalLimit);
          }
        } else if (callCount == 2) {
          // Read nothing on second call
          return 0;
        } else {
          return super.read(dst);
        }
      }
    };

    ByteBuffer buffer;
    try (channel) {
      buffer = StoreIO.readNBytes(channel, "Pikachu".length());
    }
    assertThat(channel.calls).hasValue(3);
    assertThat(toByteArray(buffer)).asString(UTF_8).isEqualTo("Pikachu");
  }

  @Test
  void fragmentedReadWithPosition() throws IOException {
    Files.writeString(file, "1234Pikachu");

    var channel = new ForwardingFileChannel(FileChannel.open(file, READ)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public int read(ByteBuffer dst, long position) throws IOException {
        int callCount = calls.incrementAndGet();
        if (callCount == 1) {
          // Read only half the buffer on first call
          int originalLimit = dst.limit();
          dst.limit(dst.position() + dst.remaining() / 2);
          try {
            return super.read(dst, position);
          } finally {
            dst.limit(originalLimit);
          }
        } else if (callCount == 2) {
          // Read nothing on second call
          return 0;
        } else {
          return super.read(dst, position);
        }
      }
    };

    ByteBuffer buffer;
    try (channel) {
      buffer = StoreIO.readNBytes(channel, "Pikachu".length(), 4);
    }
    assertThat(channel.calls).hasValue(3);
    assertThat(toByteArray(buffer)).asString(UTF_8).isEqualTo("Pikachu");
  }

  @Test
  @ExecutorConfig(CACHED_POOL)
  void fragmentedAsyncRead(ExecutorService service) throws IOException {
    Files.writeString(file, "1234Pikachu");

    var channel = new ForwardingAsynchronousFileChannel(
        AsynchronousFileChannel.open(file, Set.of(READ), service)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public <A> void read(
          ByteBuffer dst,
          long position,
          A attachment,
          CompletionHandler<Integer, ? super A> handler) {
        int callCount = calls.incrementAndGet();
        if (callCount == 1) {
          // Read only half the buffer on first call
          int originalLimit = dst.limit();
          dst.limit(dst.position() + dst.remaining() / 2);
          super.read(dst, position, attachment, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, A attachment) {
              dst.limit(originalLimit);
              handler.completed(result, attachment);
            }

            @Override
            public void failed(Throwable exc, A attachment) {
              handler.failed(exc, attachment);
            }
          });
        } else if (callCount == 2) {
          // Read nothing on second call
          handler.completed(0, attachment);
        } else {
          super.read(dst, position, attachment, handler);
        }
      }
    };

    var buffer = ByteBuffer.allocate("Pikachu".length());
    int read;
    try (channel) {
      var future = StoreIO.readBytesAsync(channel, buffer, 4);
      assertThat(future).succeedsWithin(Duration.ofSeconds(20));
      read = future.join();
    }
    assertThat(channel.calls).hasValue(3);
    assertThat(read).isEqualTo("Pikachu".length());
    assertThat(toByteArray(buffer.flip())).asString(UTF_8).isEqualTo("Pikachu");
  }

  @Test
  void emptyRead() throws IOException {
    var channel = new ForwardingFileChannel(FileChannel.open(file, READ)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public int read(ByteBuffer dst) throws IOException {
        calls.incrementAndGet();
        return super.read(dst);
      }

      @Override
      public int read(ByteBuffer dst, long position) throws IOException {
        calls.incrementAndGet();
        return super.read(dst, position);
      }
    };

    ByteBuffer buffer;
    ByteBuffer buffer2;
    try (channel) {
      buffer = StoreIO.readNBytes(channel, 0);
      buffer2 = StoreIO.readNBytes(channel, 0, 10);
    }
    assertThat(channel.calls).hasValue(0);
    assertThat(buffer.remaining()).isEqualTo(0);
    assertThat(buffer2.remaining()).isEqualTo(0);
  }

  @Test
  @ExecutorConfig(CACHED_POOL)
  void emptyAsyncRead(ExecutorService service) throws IOException {
    Files.writeString(file, "1234");

    var channel = new ForwardingAsynchronousFileChannel(
        AsynchronousFileChannel.open(file, Set.of(READ), service)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public <A> void read(
          ByteBuffer dst,
          long position,
          A attachment,
          CompletionHandler<Integer, ? super A> handler) {
        calls.incrementAndGet();
        super.read(dst, position, attachment, handler);
      }
    };

    var buffer = ByteBuffer.allocate(10);
    int read;
    try (channel) {
      var future = StoreIO.readBytesAsync(channel, buffer, 4);
      assertThat(future).succeedsWithin(Duration.ofSeconds(20));
      read = future.join();
    }
    assertThat(channel.calls).hasValue(1);
    assertThat(read).isEqualTo(-1);
    assertThat(buffer.position()).isEqualTo(0);
    assertThat(buffer.limit()).isEqualTo(buffer.capacity());
    assertThat(toByteArray(buffer.flip())).asString(UTF_8).isEqualTo("");
  }

  @Test
  void endOfFileRead() throws IOException {
    Files.writeString(file, "1234");
    try (var channel = FileChannel.open(file, READ)) {
      assertThatExceptionOfType(EOFException.class)
          .isThrownBy(() -> StoreIO.readNBytes(channel, 5));
      assertThatExceptionOfType(EOFException.class)
          .isThrownBy(() -> StoreIO.readNBytes(channel, 4, 1));
    }
  }

  @Test
  void fragmentedWrite() throws IOException {
    var channel = new ForwardingFileChannel(FileChannel.open(file, WRITE)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public int write(ByteBuffer src) throws IOException {
        int callCount = calls.incrementAndGet();
        if (callCount == 1) {
          // Write only half the buffer on first call
          int originalLimit = src.limit();
          src.limit(src.position() + src.remaining() / 2);
          try {
            return super.write(src);
          } finally {
            src.limit(originalLimit);
          }
        } else if (callCount == 2) {
          // Write nothing on second call
          return 0;
        } else {
          return super.write(src);
        }
      }
    };

    try (channel) {
      StoreIO.writeBytes(channel, UTF_8.encode("Pikachu"));
    }
    assertThat(channel.calls).hasValue(3);
    assertThat(file).usingCharset(UTF_8).hasContent("Pikachu");
  }

  @Test
  void fragmentedWriteWithPosition() throws IOException {
    Files.writeString(file, "1234");

    var channel = new ForwardingFileChannel(FileChannel.open(file, WRITE)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public int write(ByteBuffer src, long position) throws IOException {
        int callCount = calls.incrementAndGet();
        if (callCount == 1) {
          // Write only half the buffer on first call
          int originalLimit = src.limit();
          src.limit(src.position() + src.remaining() / 2);
          try {
            return super.write(src, position);
          } finally {
            src.limit(originalLimit);
          }
        } else if (callCount == 2) {
          // Write nothing on second call
          return 0;
        } else {
          return super.write(src, position);
        }
      }
    };

    try (channel) {
      StoreIO.writeBytes(channel, UTF_8.encode("Pikachu"), 4);
    }
    assertThat(channel.calls).hasValue(3);
    assertThat(file).usingCharset(UTF_8).hasContent("1234Pikachu");
  }

  @Test
  @ExecutorConfig(CACHED_POOL)
  void fragmentedAsyncWrite(ExecutorService service) throws IOException {
    Files.writeString(file, "1234");

    var channel = new ForwardingAsynchronousFileChannel(
        AsynchronousFileChannel.open(file, Set.of(WRITE), service)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public <A> void write(
          ByteBuffer dst,
          long position,
          A attachment,
          CompletionHandler<Integer, ? super A> handler) {
        int callCount = calls.incrementAndGet();
        if (callCount == 1) {
          // Write only half the buffer on first call
          int originalLimit = dst.limit();
          dst.limit(dst.position() + dst.remaining() / 2);
          super.write(dst, position, attachment, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, A attachment) {
              dst.limit(originalLimit);
              handler.completed(result, attachment);
            }

            @Override
            public void failed(Throwable exc, A attachment) {
              handler.failed(exc, attachment);
            }
          });
        } else if (callCount == 2) {
          // Write nothing on second call
          handler.completed(0, attachment);
        } else {
          super.write(dst, position, attachment, handler);
        }
      }
    };

    int written;
    try (channel) {
      var future = StoreIO.writeBytesAsync(channel, UTF_8.encode("Pikachu"), 4);
      assertThat(future).succeedsWithin(Duration.ofSeconds(20));
      written = future.join();
    }
    assertThat(channel.calls).hasValue(3);
    assertThat(written).isEqualTo("Pikachu".length());
    assertThat(file).usingCharset(UTF_8).hasContent("1234Pikachu");
  }

  @Test
  void emptyWrite() throws IOException {
    Files.writeString(file, "1234");

    var channel = new ForwardingFileChannel(FileChannel.open(file, WRITE)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public int read(ByteBuffer dst) throws IOException {
        calls.incrementAndGet();
        return super.read(dst);
      }

      @Override
      public int read(ByteBuffer dst, long position) throws IOException {
        calls.incrementAndGet();
        return super.read(dst, position);
      }
    };

    try (channel) {
      StoreIO.writeBytes(channel, ByteBuffer.allocate(0));
      StoreIO.writeBytes(channel, ByteBuffer.allocate(0), 4);
    }
    assertThat(file).usingCharset(UTF_8).hasContent("1234");
  }

  @Test
  @ExecutorConfig(CACHED_POOL)
  void emptyAsyncWrite(ExecutorService service) throws IOException {
    Files.writeString(file, "1234");

    var channel = new ForwardingAsynchronousFileChannel(
        AsynchronousFileChannel.open(file, Set.of(WRITE), service)) {
      final AtomicInteger calls = new AtomicInteger();

      @Override
      public <A> void write(
          ByteBuffer dst,
          long position,
          A attachment,
          CompletionHandler<Integer, ? super A> handler) {
        calls.incrementAndGet();
        super.write(dst, position, attachment, handler);
      }
    };

    int written;
    try (channel) {
      var future = StoreIO.writeBytesAsync(channel, ByteBuffer.allocate(0), 4);
      assertThat(future).succeedsWithin(Duration.ofSeconds(20));
      written = future.join();
    }
    assertThat(channel.calls).hasValue(1);
    assertThat(written).isEqualTo(0);
    assertThat(file).usingCharset(UTF_8).hasContent("1234");
  }
}
