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

import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;

/** Minimal AbstractSubscription implementation that publishes submitted items. */
public final class SubmittableSubscription<T> extends AbstractSubscription<T> {
  private final ConcurrentLinkedQueue<T> items;
  private volatile boolean complete;
  private volatile int abortCount;
  private volatile boolean flowInterrupted;

  public SubmittableSubscription(Subscriber<? super T> downstream, Executor executor) {
    super(downstream, executor);
    items = new ConcurrentLinkedQueue<>();
  }

  @Override
  protected long emit(Subscriber<? super T> downstream, long emit) {
    long submitted = 0L;
    while (true) {
      if (items.isEmpty() && complete) { // End of source.
        cancelOnComplete(downstream);
        return 0;
      } else if (items.isEmpty() || submitted >= emit) { // Exhausted source or demand.
        return submitted;
      } else if (!submitOnNext(downstream, items.poll())) {
        return 0;
      } else {
        submitted++;
      }
    }
  }

  @Override
  protected synchronized void abort(boolean flowInterrupted) {
    if (abortCount++ == 0) {
      this.flowInterrupted = flowInterrupted;
    }
    notifyAll();
  }

  public synchronized void awaitAbort() {
    while (abortCount == 0) {
      try {
        wait();
      } catch (InterruptedException e) {
        fail(e);
      }
    }
  }

  public void submit(T item) {
    items.offer(item);
    signal(false);
  }

  public void complete() {
    complete = true;
    signal(true);
  }

  public long currentDemand() {
    return super.currentDemand();
  }

  public int abortCount() {
    return abortCount;
  }

  public boolean flowInterrupted() {
    return flowInterrupted;
  }

  public Queue<T> items() {
    return items;
  }
}
