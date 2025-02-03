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

package com.github.mizosoft.methanol.internal.flow;

import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A subscription that emits items from a pollable source. This class's abstract methods are called
 * serially, and implementations may assume they're being called from a single thread.
 */
public abstract class AbstractPollableSubscription<T> extends AbstractSubscription<T> {
  protected AbstractPollableSubscription(Subscriber<? super T> downstream, Executor executor) {
    super(downstream, executor);
  }

  /** Returns the next item, or {@code null} if no items are available. */
  protected abstract @Nullable T poll();

  /**
   * Returns {@code true} if downstream is to be completed. Implementation must ensure {@code true}
   * isn't returned unless the subscription knows it won't produce any more items AND there aren't
   * any present items expected to be {@link #poll() polled}.
   */
  protected abstract boolean isComplete();

  @Override
  protected long emit(Subscriber<? super T> downstream, long emit) {
    T next;
    long submitted = 0;
    while (true) {
      if (isComplete()) {
        cancelOnComplete(downstream);
        return submitted;
      } else if (submitted >= emit || (next = poll()) == null) {
        return submitted; // Exhausted demand or items.
      } else if (submitOnNext(downstream, next)) {
        submitted++;
      } else {
        return 0;
      }
    }
  }
}
