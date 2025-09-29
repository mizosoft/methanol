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

package com.github.mizosoft.methanol.internal.concurrent;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.github.mizosoft.methanol.internal.Utils;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

class ScheduledExecutorServiceDelayer implements Delayer {
  private final ScheduledExecutorService scheduler;

  ScheduledExecutorServiceDelayer(ScheduledExecutorService scheduler) {
    this.scheduler = requireNonNull(scheduler);
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  public CompletableFuture<Void> delay(Runnable task, Duration delay, Executor executor) {
    if (delay.isZero()) {
      return CompletableFuture.runAsync(task, executor);
    }

    var taskCompletionFuture = new CompletableFuture<Void>();
    var taskSubmissionFuture =
        scheduler.schedule(
            () -> {
              taskCompletionFuture.completeAsync(
                  () -> {
                    task.run();
                    return null;
                  },
                  executor);
            },
            NANOSECONDS.convert(delay),
            NANOSECONDS);
    taskCompletionFuture.whenComplete(
        (__, e) -> {
          if (e instanceof CancellationException) {
            taskSubmissionFuture.cancel(false);
          }
        });
    return taskCompletionFuture;
  }

  @Override
  public String toString() {
    return Utils.toStringIdentityPrefix(this) + "[scheduler=" + scheduler + "]";
  }
}
