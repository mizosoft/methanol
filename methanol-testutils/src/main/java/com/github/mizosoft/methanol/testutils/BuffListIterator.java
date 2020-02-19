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

package com.github.mizosoft.methanol.testutils;

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.checkerframework.checker.nullness.qual.Nullable;

public class BuffListIterator implements Iterator<List<ByteBuffer>> {

  private final BuffIterator itr;
  private final int buffsPerList;
  private @Nullable List<ByteBuffer> next;

  public BuffListIterator(ByteBuffer buffer, int buffSize, int buffsPerList) {
    this.itr = new BuffIterator(buffer, buffSize);
    this.buffsPerList = buffsPerList;
  }

  @Override
  public boolean hasNext() {
    List<ByteBuffer> n = next;
    if (n == null) {
      for (int i = 0; itr.hasNext() && i < buffsPerList; i++) {
        if (n == null) {
          n = new ArrayList<>();
          next = n;
        }
        n.add(itr.next());
      }
    }
    return n != null;
  }

  @Override
  public List<ByteBuffer> next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    List<ByteBuffer> l = List.copyOf(requireNonNull(next));
    next = null;
    return l;
  }
}
