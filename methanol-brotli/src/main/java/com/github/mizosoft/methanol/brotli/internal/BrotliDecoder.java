/*
 * Copyright (c) 2025 Moataz Hussein
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

package com.github.mizosoft.methanol.brotli.internal;

import com.github.mizosoft.methanol.brotli.internal.vendor.DecoderJNI;
import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import java.io.EOFException;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.github.mizosoft.methanol.internal.Utils;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import static com.github.mizosoft.methanol.internal.Validate.requireState;

final class BrotliDecoder implements AsyncDecoder {
  private static final Cleaner CLEANER = Cleaner.create();

  private final WrapperHandle handle = new WrapperHandle();
  private final Cleanable cleanable = CLEANER.register(this, new Destroyer(handle));

  BrotliDecoder() {}

  @Override
  public String encoding() {
    return BrotliBodyDecoderFactory.BROTLI_ENCODING;
  }

  @Override
  public void decode(ByteSource source, ByteSink sink) throws IOException {
    handle.lock.lock();
    try {
      requireState(!handle.destroyed, "Native instance already destroyed");

      var brotliNative = handle.brotliNative;
      if (brotliNative == null) {
        brotliNative = new DecoderJNI.Wrapper(Utils.BUFFER_SIZE);
        handle.brotliNative = brotliNative;
      }

      outerLoop:
      while (true) {
        switch (brotliNative.getStatus()) {
          case OK:
            brotliNative.push(0);
            break;

          case NEEDS_MORE_INPUT:
            if (!source.hasRemaining()) {
              if (source.finalSource()) {
                throw new EOFException("Unexpected end of brotli stream");
              }
              break outerLoop; // More decode rounds to come...
            }

            var brotliIn = brotliNative.getInputBuffer();
            source.pullBytes(brotliIn.clear());
            brotliNative.push(brotliIn.position());
            break;

          case NEEDS_MORE_OUTPUT:
            do {
              sink.pushBytes(brotliNative.pull());
            } while (brotliNative.hasOutput());
            break;

          case ERROR:
            throw new IOException("Corrupt brotli stream");

          case DONE:
            if (source.hasRemaining()) {
              throw new IOException("Brotli stream finished prematurely");
            }
            // Flush any remaining output
            while (brotliNative.hasOutput()) {
              sink.pushBytes(brotliNative.pull());
            }
            break outerLoop; // Brotli stream finished!
        }
      }
    } finally {
      handle.lock.unlock();
    }
  }

  @Override
  public void close() {
    cleanable.clean();
  }

  /**
   * Shared handle between {@link Destroyer} and {@link BrotliDecoder} over the lazily initialized
   * native instance.
   */
  private static final class WrapperHandle {
    // Initialization is deferred to first decode() to rethrow any IOException directly.
    @GuardedBy("lock")
    private DecoderJNI.@MonotonicNonNull Wrapper brotliNative;

    @GuardedBy("lock")
    private boolean destroyed;

    /**
     * A lock for guarding {@link #brotliNative} against possible concurrent decodes & (closes |
     * cleanup).
     */
    private final Lock lock = new ReentrantLock();

    WrapperHandle() {}
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
          var brotliNative = handle.brotliNative;
          if (brotliNative != null) {
            brotliNative.destroy();
          }
        }
      } finally {
        handle.lock.unlock();
      }
    }
  }
}
