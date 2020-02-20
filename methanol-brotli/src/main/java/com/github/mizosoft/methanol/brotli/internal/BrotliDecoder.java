/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.dec.AsyncDecoder;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

class BrotliDecoder implements AsyncDecoder {

  // TODO: maybe make it configurable with a system property ?
  private static final int INPUT_BUFFER_SIZE = 4096;

  // Initialization is deferred to first decode() to rethrow any IOException directly
  private DecoderJNI.@MonotonicNonNull Wrapper brotliNative;
  private boolean destroyed;

  BrotliDecoder() { // package-private
  }

  @Override
  public String encoding() {
    return BrotliBodyDecoderFactory.BROTLI_ENCODING;
  }

  @Override
  public synchronized void decode(ByteSource source, ByteSink sink) throws IOException {
    if (destroyed) {
      return;
    }

    if (brotliNative == null) {
      brotliNative = new DecoderJNI.Wrapper(INPUT_BUFFER_SIZE);
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
              throw new EOFException("unexpected end of brotli stream");
            }
            break outerLoop; // More decode rounds to come...
          }
          ByteBuffer brotliIn = brotliNative.getInputBuffer();
          source.pullBytes(brotliIn.clear());
          brotliNative.push(brotliIn.position());
          break;

        case NEEDS_MORE_OUTPUT:
          do {
            sink.pushBytes(brotliNative.pull());
          } while (brotliNative.hasOutput());
          break;

        case ERROR:
          throw new IOException("corrupt brotli stream");

        case DONE:
          if (source.hasRemaining()) {
            throw new IOException("brotli stream finished prematurely");
          }
          // Flush any remaining output
          while (brotliNative.hasOutput()) {
            sink.pushBytes(brotliNative.pull());
          }
          break outerLoop; // Brotli stream finished!
      }
    }
  }

  @Override
  public synchronized void close() {
    if (!destroyed) {
      destroyed = true;
      if (brotliNative != null) {
        brotliNative.destroy();
      }
    }
  }
}
