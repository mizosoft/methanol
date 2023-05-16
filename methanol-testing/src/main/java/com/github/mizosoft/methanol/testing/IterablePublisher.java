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

package com.github.mizosoft.methanol.testing;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.AbstractPollableSubscription;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Publisher;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A publisher of items obtained from an {@code Iterable<T>}. */
public final class IterablePublisher<T> implements Publisher<T> {
  private final Iterable<T> iterable;
  private final Executor executor;

  public IterablePublisher(Iterable<T> iterable, Executor executor) {
    this.iterable = requireNonNull(iterable);
    this.executor = requireNonNull(executor);
  }

  @Override
  public void subscribe(Flow.Subscriber<? super T> subscriber) {
    new IteratorSubscription<>(subscriber, executor, iterable.iterator()).fireOrKeepAlive();
  }

  private static final class IteratorSubscription<T> extends AbstractPollableSubscription<T> {
    private final Iterator<T> iterator;

    IteratorSubscription(
        Flow.Subscriber<? super T> downstream, Executor executor, Iterator<T> iterator) {
      super(downstream, executor);
      this.iterator = iterator;
    }

    @Override
    protected @Nullable T poll() {
      return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    protected boolean isComplete() {
      return !iterator.hasNext();
    }
  }
}
