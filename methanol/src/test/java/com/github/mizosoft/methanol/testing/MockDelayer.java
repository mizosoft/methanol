/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.internal.cache.DiskStore.Delayer;
import com.github.mizosoft.methanol.testutils.MockClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** A Delayer that delays tasks based on a MockClock's time. */
public final class MockDelayer implements Delayer {
  private final MockClock clock;
  private final Queue<TimestampedTask> taskQueue =
      new PriorityQueue<>(Comparator.comparing(task -> task.stamp));

  MockDelayer(MockClock clock) {
    this.clock = clock;
    clock.onTick((instant, ticks) -> dispatchExpiredTasks(instant.plus(ticks), false));
  }

  @Override
  public CompletableFuture<Void> delay(Executor executor, Runnable task, Duration delay) {
    var now = clock.peekInstant(); // Do not advance clock
    var timestampedTask = new TimestampedTask(executor, task, now.plus(delay));
    synchronized (taskQueue) {
      taskQueue.add(timestampedTask);
    }

    dispatchExpiredTasks(now, false);
    return timestampedTask.future;
  }

  public int taskCount() {
    synchronized (taskQueue) {
      return taskQueue.size();
    }
  }

  void dispatchExpiredTasks(Instant now, boolean ignoreRejected) {
    TimestampedTask task;
    synchronized (taskQueue) {
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

  private static final class TimestampedTask {
    final Executor executor;
    final Runnable task;
    final Instant stamp;
    final CompletableFuture<Void> future = new CompletableFuture<>();

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
