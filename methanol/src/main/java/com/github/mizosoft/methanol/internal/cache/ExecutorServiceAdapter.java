/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.internal.cache;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Adapts a plain {@code Executor} into an {@code ExecutorService}. */
final class ExecutorServiceAdapter extends AbstractExecutorService {
  private final Executor executor;

  /**
   * TrackedRunnables know they haven't been cancelled in shutdownNow() by successful removal from
   * this set.
   */
  private final Set<TrackedRunnable> queuedTasks = new HashSet<>();

  /**
   * TrackedRunnables add their executing threads here so that they can be interrupted by
   * shutdownNow().
   */
  private final Set<Thread> executingThreads = new HashSet<>();

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition termination = lock.newCondition();

  private boolean shutdown;

  ExecutorServiceAdapter(Executor executor) {
    this.executor = executor;
  }

  @Override
  public void shutdown() {
    lock.lock();
    try {
      shutdown = true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<Runnable> shutdownNow() {
    lock.lock();
    try {
      var dumped = List.<Runnable>copyOf(queuedTasks);
      queuedTasks.clear();

      // Interrupt the threads of all executing TrackedRunnables.
      executingThreads.forEach(Thread::interrupt);
      shutdown = true;
      return dumped;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isShutdown() {
    lock.lock();
    try {
      return shutdown;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isTerminated() {
    lock.lock();
    try {
      return isTerminatedUnguarded();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long remaining = unit.toNanos(timeout);
    lock.lock();
    try {
      while (!isTerminatedUnguarded()) {
        if (remaining <= 0L) {
          return false;
        }
        remaining = termination.awaitNanos(remaining);
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  private boolean isTerminatedUnguarded() {
    assert lock.isHeldByCurrentThread();
    return shutdown && queuedTasks.isEmpty() && executingThreads.isEmpty();
  }

  @Override
  public void execute(Runnable command) {
    requireNonNull(command);
    var task = new TrackedRunnable(command);
    lock.lock();
    try {
      if (shutdown) {
        throw new RejectedExecutionException("ExecutorService has been shutdown");
      }
      queuedTasks.add(task);
    } finally {
      lock.unlock();
    }

    try {
      executor.execute(task);
    } catch (RejectedExecutionException e) {
      lock.lock();
      try {
        queuedTasks.remove(task);
      } finally {
        lock.unlock();
      }
      throw e;
    }
  }

  static ExecutorService adapt(Executor executor) {
    return executor instanceof ExecutorService
        ? (ExecutorService) executor
        : new ExecutorServiceAdapter(executor);
  }

  private final class TrackedRunnable implements Runnable {
    final Runnable runnable;
    @Nullable Thread executingThread; // Guarded by parent's lock.

    TrackedRunnable(Runnable runnable) {
      this.runnable = runnable;
    }

    @Override
    public void run() {
      lock.lock();
      try {
        if (!queuedTasks.remove(this)) {
          return; // This means we're removed by shutdownNow().
        }
        executingThread = Thread.currentThread();
        executingThreads.add(executingThread);
      } finally {
        lock.unlock();
      }

      try {
        runnable.run();
      } finally {
        lock.lock();
        try {
          executingThreads.remove(executingThread);
          executingThread = null;
          // Notify anyone awaiting termination if this tasks marks it.
          if (isTerminatedUnguarded()) {
            termination.signalAll();
          }
        } finally {
          lock.unlock();
        }
      }
    }

    @Override
    public String toString() {
      return runnable.toString();
    }
  }
}
