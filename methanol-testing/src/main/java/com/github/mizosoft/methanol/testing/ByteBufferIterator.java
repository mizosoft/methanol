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

package com.github.mizosoft.methanol.testing;

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ByteBufferIterator implements Iterator<ByteBuffer> {
  private final ByteBuffer source;
  private final int bufferSize;
  private @Nullable ByteBuffer next;

  public ByteBufferIterator(ByteBuffer source, int bufferSize) {
    this.source = source;
    this.bufferSize = bufferSize;
  }

  @Override
  @EnsuresNonNullIf(expression = "this.next", result = true)
  public boolean hasNext() {
    if (next == null && source.hasRemaining()) {
      next = ByteBuffer.allocate(Math.min(bufferSize, source.remaining()));
      TestUtils.copyRemaining(source, next);
      next.flip();
    }
    return next != null;
  }

  @Override
  public ByteBuffer next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    var localNext = requireNonNull(next);
    next = null;
    return localNext;
  }
}
