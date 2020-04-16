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

package com.github.mizosoft.methanol.testutils.dec;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import java.nio.ByteBuffer;

/** A ByteSource that reads from a byte array up to a limit that can be incremented each round. */
final class IncrementalByteArraySource implements AsyncDecoder.ByteSource {

  private final byte[] source;
  private final ByteBuffer buffer;
  private final int increment;
  private int position;
  private int limit;

  IncrementalByteArraySource(byte[] source, int bufferSize, int increment) {
    this.source = source;
    buffer = ByteBuffer.allocate(bufferSize);
    this.increment = increment;
    buffer.flip(); // Mark as empty initially
  }

  @Override
  public ByteBuffer currentSource() {
    if (!buffer.hasRemaining()) {
      buffer.clear();
    } else {
      buffer.compact();
    }
    int copy = Math.min(buffer.remaining(), limit - position);
    buffer.put(source, position, copy);
    position += copy;
    return buffer.flip();
  }

  @Override
  public long remaining() {
    return (long) buffer.remaining() + (limit - position);
  }

  @Override
  public boolean finalSource() {
    return limit >= source.length;
  }

  void increment() {
    limit = Math.min(source.length, Math.addExact(limit, increment));
  }
}