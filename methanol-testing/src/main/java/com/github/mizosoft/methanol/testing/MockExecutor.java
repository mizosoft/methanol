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

import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** An {@code Executor} that records submitted tasks and allows running them later. */
public class MockExecutor implements Executor {
  private final Queue<Runnable> tasks = new ArrayDeque<>();
  private final Lock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();

  @GuardedBy("lock")
  private boolean reject;

  @GuardedBy("lock")
  private boolean executeDirectly;

  public MockExecutor() {}

  @Override
  public void execute(Runnable command) {
    lock.lock();
    try {
      if (reject) {
        throw new RejectedExecutionException();
      }
      if (!executeDirectly) {
        tasks.add(command);
        notEmpty.signalAll();
        return;
      }
    } finally {
      lock.unlock();
    }

    command.run();
  }

  public boolean hasNext() {
    lock.lock();
    try {
      return !tasks.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  public void runNext() {
    lock.lock();
    try {
      if (!hasNext()) {
        throw new AssertionError("no tasks to run");
      }
      tasks.remove().run();
    } finally {
      lock.unlock();
    }
  }

  public void runAll() {
    lock.lock();
    try {
      while (hasNext()) {
        tasks.remove().run();
      }
    } finally {
      lock.unlock();
    }
  }

  public int taskCount() {
    lock.lock();
    try {
      return tasks.size();
    } finally {
      lock.unlock();
    }
  }

  public void reject(boolean on) {
    lock.lock();
    try {
      this.reject = on;
    } finally {
      lock.unlock();
    }
  }

  public void executeDirectly(boolean on) {
    lock.lock();
    try {
      this.executeDirectly = on;
    } finally {
      lock.unlock();
    }
  }

  public boolean awaitNext(long timeout, TimeUnit unit) {
    lock.lock();
    try {
      long remaining = unit.toNanos(timeout);
      while (tasks.isEmpty()) {
        if (remaining <= 0L) {
          return false;
        }
        try {
          remaining = notEmpty.awaitNanos(remaining);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String toString() {
    lock.lock();
    try {
      return super.toString()
          + "{tasks="
          + tasks
          + ", reject="
          + reject
          + ", executeDirectly="
          + executeDirectly
          + "}";
    } finally {
      lock.unlock();
    }
  }
}
