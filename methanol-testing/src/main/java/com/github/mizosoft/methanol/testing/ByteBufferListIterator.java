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

package com.github.mizosoft.methanol.testing;

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ByteBufferListIterator implements Iterator<List<ByteBuffer>> {
  private final ByteBufferIterator sourceIterator;
  private final int buffersPerList;
  private @Nullable List<ByteBuffer> next;

  public ByteBufferListIterator(ByteBuffer source, int bufferSize, int buffersPerList) {
    this.sourceIterator = new ByteBufferIterator(source, bufferSize);
    this.buffersPerList = buffersPerList;
  }

  @Override
  public boolean hasNext() {
    var localNext = next;
    if (localNext == null) {
      for (int i = 0; sourceIterator.hasNext() && i < buffersPerList; i++) {
        if (localNext == null) {
          localNext = new ArrayList<>();
        }
        localNext.add(sourceIterator.next());
      }
    }
    next = localNext;
    return localNext != null;
  }

  @Override
  public List<ByteBuffer> next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    var localNext = requireNonNull(next);
    next = null;
    return Collections.unmodifiableList(localNext);
  }
}
