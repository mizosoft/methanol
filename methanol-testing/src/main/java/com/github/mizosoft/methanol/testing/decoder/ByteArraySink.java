/*
 * Copyright (c) 2024 Moataz Hussein
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
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

final class ByteArraySink implements AsyncDecoder.ByteSink {
  private final ByteBuffer buffer;
  private final ByteArrayOutputStream dump;

  ByteArraySink(int bufferSize) {
    buffer = ByteBuffer.allocate(bufferSize);
    dump = new ByteArrayOutputStream();
  }

  @Override
  public ByteBuffer currentSink() {
    flush();
    return buffer.clear();
  }

  byte[] toByteArray() {
    flush();
    return dump.toByteArray();
  }

  void flush() {
    buffer.flip();
    if (buffer.hasRemaining()) {
      dump.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.limit());
    }
  }
}
