/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.internal.flow.AbstractQueueSubscription;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** A subscription that publishes submitted items. */
public final class SubmittableSubscription<T> extends AbstractQueueSubscription<T> {
  private static final int AWAIT_ABORT_TIMEOUT_SECONDS = 2;

  private final Lock abortLock = new ReentrantLock();
  private final Condition abortedCondition = abortLock.newCondition();

  @GuardedBy("abortLock")
  private int abortCount;

  @GuardedBy("abortLock")
  private boolean flowInterrupted;

  public SubmittableSubscription(Subscriber<? super T> downstream, Executor executor) {
    super(downstream, executor);
  }

  @Override
  protected void abort(boolean flowInterrupted) {
    super.abort(flowInterrupted);
    abortLock.lock();
    try {
      if (abortCount++ == 0) {
        this.flowInterrupted = flowInterrupted;
      }
      abortedCondition.signalAll();
    } finally {
      abortLock.unlock();
    }
  }

  public void awaitAbort() {
    abortLock.lock();
    try {
      long remainingNanos = TimeUnit.SECONDS.toNanos(AWAIT_ABORT_TIMEOUT_SECONDS);
      while (abortCount == 0) {
        if (remainingNanos <= 0) {
          fail("timed out while waiting for abort()");
        }
        try {
          remainingNanos = abortedCondition.awaitNanos(remainingNanos);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    } finally {
      abortLock.unlock();
    }
  }

  @Override
  public void submit(T item) {
    super.submit(item);
  }

  @Override
  public void submitSilently(T item) {
    super.submitSilently(item);
  }

  @Override
  public void complete() {
    super.complete();
  }

  @Override
  public long currentDemand() {
    return super.currentDemand();
  }

  public int abortCount() {
    return abortCount;
  }

  public boolean flowInterrupted() {
    return flowInterrupted;
  }
}
