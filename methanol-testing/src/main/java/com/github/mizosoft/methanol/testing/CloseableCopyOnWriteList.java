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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A minimal thread-safe list that create a new copy of the array whenever a new item is added. The
 * list can be closed and a snapshot of added items will be returned, after which no further items
 * can be added.
 */
public final class CloseableCopyOnWriteList<T> implements Iterable<T> {
  private static final Object[] CLOSED_SENTINEL = new Object[0];

  private static final VarHandle ITEMS;

  static {
    try {
      ITEMS =
          MethodHandles.lookup()
              .findVarHandle(CloseableCopyOnWriteList.class, "items", Object[].class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @SuppressWarnings("FieldMayBeFinal") // VarHandle indirection.
  private Object[] items = new Object[0];

  CloseableCopyOnWriteList() {}

  public boolean add(T item) {
    while (true) {
      var currentItems = items;
      if (currentItems == CLOSED_SENTINEL) {
        return false;
      }

      var updatedItems = Arrays.copyOf(currentItems, currentItems.length + 1);
      updatedItems[updatedItems.length - 1] = item;
      if (ITEMS.compareAndSet(this, currentItems, updatedItems)) {
        return true;
      }
    }
  }

  @SuppressWarnings("unchecked")
  public T get(int index) {
    var currentItems = items;
    Objects.checkIndex(index, currentItems.length);
    return (T) currentItems[index];
  }

  @Override
  public Iterator<T> iterator() {
    return new Iterator<>() {
      private final Object[] items = CloseableCopyOnWriteList.this.items;

      private int index = 0;

      @Override
      public boolean hasNext() {
        return index < items.length;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return (T) items[index++];
      }
    };
  }

  @SuppressWarnings("unchecked")
  @Override
  public void forEach(Consumer<? super T> action) {
    for (var item : items) {
      action.accept((T) item);
    }
  }

  @SuppressWarnings("unchecked")
  public List<T> close() {
    return List.of((T[]) ITEMS.getAndSet(this, CLOSED_SENTINEL));
  }
}
