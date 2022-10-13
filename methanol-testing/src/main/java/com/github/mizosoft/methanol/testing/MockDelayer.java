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

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** A Delayer that delays tasks based on a MockClock's time. */
public final class MockDelayer implements Delayer {
  private final MockClock clock;
  private final Queue<TimestampedTask> taskQueue =
      new PriorityQueue<>(
          Comparator.comparing((TimestampedTask task) -> task.stamp)
              .thenComparingLong(task -> task.sequenceNumber));

  public MockDelayer(MockClock clock) {
    this.clock = clock;
    clock.onTick((instant, ticks) -> dispatchReadyTasks(instant.plus(ticks), false));
  }

  @Override
  public Future<Void> delay(Runnable task, Duration delay, Executor executor) {
    var now = clock.peekInstant(); // Do not advance clock if auto-advancing
    var timestampedTask = new TimestampedTask(executor, task, now.plus(delay));
    synchronized (taskQueue) {
      taskQueue.add(timestampedTask);
    }

    dispatchReadyTasks(now, false);
    return timestampedTask.future;
  }

  public int taskCount() {
    synchronized (taskQueue) {
      return taskQueue.size();
    }
  }

  public CompletableFuture<Void> peekEarliestTaskFuture() {
    synchronized (taskQueue) {
      assertThat(taskQueue).isNotEmpty();
      return taskQueue.element().future;
    }
  }

  public CompletableFuture<Void> peekLatestTaskFuture() {
    synchronized (taskQueue) {
      assertThat(taskQueue).isNotEmpty();

      var iter = taskQueue.iterator();
      TimestampedTask last;
      do {
        last = iter.next();
      } while (iter.hasNext());
      return last.future;
    }
  }

  void dispatchReadyTasks(Instant now, boolean ignoreRejected) {
    synchronized (taskQueue) {
      TimestampedTask task;
      while ((task = taskQueue.peek()) != null && now.compareTo(task.stamp) >= 0) {
        taskQueue.poll();
        try {
          task.dispatch();
        } catch (RejectedExecutionException e) {
          if (!ignoreRejected) {
            throw e;
          }
        }
      }
    }
  }

  public void drainQueuedTasks(boolean ignoreRejected) {
    dispatchReadyTasks(Instant.MAX, ignoreRejected);
  }

  private static final class TimestampedTask {
    /** Sequence number generator to break ties on comparison. */
    private static final AtomicLong sequencer = new AtomicLong();

    final Executor executor;
    final Runnable task;
    final Instant stamp;
    final CompletableFuture<Void> future = new CompletableFuture<>();
    final long sequenceNumber = sequencer.getAndIncrement();

    TimestampedTask(Executor executor, Runnable task, Instant stamp) {
      this.task = task;
      this.stamp = stamp;
      this.executor = executor;
    }

    void dispatch() {
      future.completeAsync(
          () -> {
            task.run();
            return null;
          },
          executor);
    }
  }
}
