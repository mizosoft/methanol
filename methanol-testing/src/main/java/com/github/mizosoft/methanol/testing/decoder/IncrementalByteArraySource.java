/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing.decoder;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.nio.ByteBuffer;

/**
 * A {@code ByteSource} that reads from a byte array up to a limit that can be incremented each
 * round.
 */
final class IncrementalByteArraySource implements AsyncDecoder.ByteSource {
  private final ByteBuffer source;
  private final ByteBuffer buffer;
  private final int increment;

  IncrementalByteArraySource(byte[] source, int bufferSize, int increment) {
    this.source = ByteBuffer.wrap(source).asReadOnlyBuffer().limit(0);
    this.buffer = ByteBuffer.allocate(bufferSize).limit(0);
    this.increment = increment;
  }

  @Override
  public ByteBuffer currentSource() {
    if (!buffer.hasRemaining()) {
      buffer.clear();
    } else {
      buffer.compact();
    }
    TestUtils.copyRemaining(source, buffer);
    return buffer.flip();
  }

  @Override
  public long remaining() {
    return (long) source.remaining() + buffer.remaining();
  }

  @Override
  public boolean finalSource() {
    return source.limit() == source.capacity();
  }

  void increment() {
    source.limit(Math.min(source.capacity(), Math.addExact(source.limit(), increment)));
  }
}
