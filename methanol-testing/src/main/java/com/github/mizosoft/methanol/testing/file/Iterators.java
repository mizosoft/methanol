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

package com.github.mizosoft.methanol.testing.file;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.StreamSupport;

public class Iterators {
  private Iterators() {}

  static <T, U> Iterator<U> map(Iterator<T> iterator, Function<? super T, ? extends U> mapper) {
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public U next() {
        return mapper.apply(iterator.next());
      }

      @Override
      public void remove() {
        iterator.remove();
      }

      @Override
      public void forEachRemaining(Consumer<? super U> action) {
        iterator.forEachRemaining(t -> action.accept(mapper.apply(t)));
      }
    };
  }

  static <T, U> Iterable<U> map(Iterable<T> iterable, Function<? super T, ? extends U> mapper) {
    return new Iterable<>() {
      @Override
      public Iterator<U> iterator() {
        return map(iterable.iterator(), mapper);
      }

      @Override
      public void forEach(Consumer<? super U> action) {
        iterable.forEach(t -> action.accept(mapper.apply(t)));
      }

      @SuppressWarnings("unchecked")
      @Override
      public Spliterator<U> spliterator() {
        return (Spliterator<U>)
            StreamSupport.stream(iterable.spliterator(), false).map(mapper).spliterator();
      }
    };
  }
}
