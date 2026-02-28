/*
 * Copyright (c) 2026 Konrad Windszus
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

package com.github.mizosoft.methanol.zstd.internal;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.Validate;
import com.github.luben.zstd.Zstd;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

final class ZstdDecoder implements AsyncDecoder {
  private static final Cleaner CLEANER = Cleaner.create();
  private static final int BUFFER_SIZE = Utils.BUFFER_SIZE;

  private final WrapperHandle handle = new WrapperHandle();
  private final Cleanable cleanable = CLEANER.register(this, new Destroyer(handle));

  ZstdDecoder() {}

  @Override
  public String encoding() {
    return ZstdBodyDecoderFactory.ZSTD_ENCODING;
  }

  @Override
  public void close() {
    cleanable.clean();
  }

  @Override
  public void decode(ByteSource source, ByteSink sink) throws IOException {
    handle.lock.lock();
    try {
      Validate.requireState(!handle.destroyed, "Decoder instance already destroyed");

      if (handle.inputBuffer == null) {
        handle.inputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        handle.outputBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * 2);
      }

      var inputBuffer = handle.inputBuffer;
      var outputBuffer = handle.outputBuffer;

      // Accumulate input data
      if (source.hasRemaining()) {
        source.pullBytes(inputBuffer);
      }

      // Only decompress when we have the final source (complete frame)
      if (source.finalSource() && inputBuffer.position() > 0 && !handle.decompressed) {
        inputBuffer.flip();
        outputBuffer.clear();

        try {
          // Decompress the complete frame from input buffer to output buffer
          long decompressed = Zstd.decompressDirectByteBuffer(
              outputBuffer, 0, outputBuffer.capacity(),
              inputBuffer, 0, inputBuffer.remaining()
          );

          if (decompressed < 0) {
            throw new IOException("Zstd decompression failed");
          }

          if (decompressed > 0) {
            outputBuffer.position(0);
            outputBuffer.limit((int) decompressed);
            sink.pushBytes(outputBuffer);
          }

          handle.decompressed = true;
        } catch (RuntimeException e) {
          throw new IOException("Zstd decompression error", e);
        }

        inputBuffer.clear();
      }
    } finally {
      handle.lock.unlock();
    }
  }

  private static final class WrapperHandle {
    final Lock lock = new ReentrantLock();
    @GuardedBy("lock")
    @MonotonicNonNull
    ByteBuffer inputBuffer;
    @GuardedBy("lock")
    @MonotonicNonNull
    ByteBuffer outputBuffer;
    @GuardedBy("lock")
    boolean decompressed;
    @GuardedBy("lock")
    boolean destroyed;
  }

  private static final class Destroyer implements Runnable {
    private final WrapperHandle handle;

    Destroyer(WrapperHandle handle) {
      this.handle = handle;
    }

    @Override
    public void run() {
      handle.lock.lock();
      try {
        if (!handle.destroyed) {
          handle.destroyed = true;
        }
      } finally {
        handle.lock.unlock();
      }
    }
  }
}

