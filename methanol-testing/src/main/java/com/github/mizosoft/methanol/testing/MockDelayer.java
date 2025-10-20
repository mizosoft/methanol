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

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A Delayer that delays tasks based on a {@link MockClock}'s time. */
public final class MockDelayer implements Delayer {
  private final MockClock clock;

  /**
   * Whether {@link #dispatchReadyTasks(Instant, boolean)} should be eagerly invoked each time a
   * task is submitted to this delayer. This gives immediately executable tasks no chance to get
   * observably queued.
   */
  private final boolean dispatchEagerly;

  @GuardedBy("lock")
  private final Queue<DelayedFuture> taskQueue = new PriorityQueue<>(DelayedFuture.DELAY_ORDER);

  private final Lock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();

  public MockDelayer(MockClock clock) {
    this(clock, true);
  }

  public MockDelayer(MockClock clock, boolean dispatchEagerly) {
    this.clock = clock;
    this.dispatchEagerly = dispatchEagerly;
    clock.onTick((instant, ticks) -> dispatchReadyTasks(instant.plus(ticks), false));
  }

  @Override
  public CompletableFuture<Void> delay(Runnable task, Duration delay, Executor executor) {
    var now = clock.peekInstant(); // Do not advance clock if auto-advancing.
    var future = new DelayedFuture(task, now.plus(delay), executor, clock);
    lock.lock();
    try {
      taskQueue.add(future);
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }

    if (dispatchEagerly) {
      dispatchReadyTasks(now, false);
    }
    return future;
  }

  public int taskCount() {
    lock.lock();
    try {
      return taskQueue.size();
    } finally {
      lock.unlock();
    }
  }

  public DelayedFuture peekEarliestFuture() {
    lock.lock();
    try {
      assertThat(taskQueue).isNotEmpty();
      return taskQueue.element();
    } finally {
      lock.unlock();
    }
  }

  public DelayedFuture peekLatestFuture() {
    lock.lock();
    try {
      assertThat(taskQueue).isNotEmpty();

      var iter = taskQueue.iterator();
      DelayedFuture future;
      do {
        future = iter.next();
      } while (iter.hasNext());
      return future;
    } finally {
      lock.unlock();
    }
  }

  public DelayedFuture awaitingPeekLatestFuture() throws InterruptedException {
    lock.lock();
    try {
      while (taskQueue.isEmpty()) {
        notEmpty.await();
      }

      var iter = taskQueue.iterator();
      DelayedFuture future;
      do {
        future = iter.next();
      } while (iter.hasNext());
      return future;
    } finally {
      lock.unlock();
    }
  }

  private void dispatchReadyTasks(Instant now, boolean ignoreRejected) {
    DelayedFuture ready;
    while ((ready = pollReady(now)) != null) {
      try {
        ready.dispatch();
      } catch (RejectedExecutionException e) {
        if (!ignoreRejected) {
          throw e;
        }
      }
    }
  }

  private @Nullable DelayedFuture pollReady(Instant now) {
    lock.lock();
    try {
      DelayedFuture future;
      if ((future = taskQueue.peek()) != null && future.isReady(now)) {
        taskQueue.poll();
        return future;
      }
    } finally {
      lock.unlock();
    }
    return null;
  }

  public void drainQueuedTasks(boolean ignoreRejected) {
    dispatchReadyTasks(Instant.MAX, ignoreRejected);
  }

  public static final class DelayedFuture extends CompletableFuture<Void> {
    static final Comparator<DelayedFuture> DELAY_ORDER =
        Comparator.<DelayedFuture, Instant>comparing(future -> future.when)
            .thenComparingLong(task -> task.sequenceNumber);

    /** Sequence number generator to break ties on delay comparisons. */
    private static final AtomicLong sequencer = new AtomicLong();

    final long sequenceNumber = sequencer.getAndIncrement();

    final Runnable task;
    final Instant when;
    final Executor executor;
    final MockClock clock;

    private DelayedFuture(Runnable task, Instant when, Executor executor, MockClock clock) {
      this.task = task;
      this.when = when;
      this.executor = executor;
      this.clock = clock;
    }

    public Duration delay() {
      return Duration.between(clock.peekInstant(), when);
    }

    void dispatch() {
      completeAsync(
          () -> {
            task.run();
            return null;
          },
          executor);
    }

    boolean isReady(Instant now) {
      return now.compareTo(when) >= 0;
    }
  }
}
