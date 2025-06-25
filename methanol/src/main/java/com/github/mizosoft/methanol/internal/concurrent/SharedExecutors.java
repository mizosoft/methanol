/*
 * Copyright (c) 2025 Moataz Hussein
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides default executors that are used across the library when no executor is supplied by the
 * user.
 */
public class SharedExecutors {
  private static final AtomicInteger nextThreadId = new AtomicInteger();
  private static final ThreadFactory threadFactory =
      r -> {
        var thread = new Thread(r);
        thread.setName("methanol-thread-" + nextThreadId.getAndIncrement());
        return thread;
      };

  private static final Lazy<ExecutorService> lazyExecutor =
      Lazy.of(() -> Executors.newCachedThreadPool(threadFactory));
  private static final Lazy<ScheduledExecutorService> lazyScheduler =
      Lazy.of(() -> Executors.newScheduledThreadPool(1, threadFactory));

  private SharedExecutors() {}

  public static ExecutorService executor() {
    return lazyExecutor.get();
  }

  public static ScheduledExecutorService scheduler() {
    return lazyScheduler.get();
  }
}
