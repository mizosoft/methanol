/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;

/** An executor that ensures submitted tasks are executed serially. */
public final class SerialExecutor implements Executor {
  /** Drain task started or about to start execution. Retained till drain exits. */
  private static final int RUNNING = 1;

  /** Drain task should keep running to recheck for incoming tasks it may have missed. */
  private static final int KEEP_ALIVE = 2;

  /** Don't accept more tasks. */
  private static final int SHUTDOWN = 4;

  private static final VarHandle SYNC;

  static {
    try {
      SYNC = MethodHandles.lookup().findVarHandle(SerialExecutor.class, "sync", int.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final ConcurrentLinkedQueue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();

  private final Executor delegate;

  @SuppressWarnings("unused") // VarHandle indirection.
  private volatile int sync;

  public SerialExecutor(Executor delegate) {
    this.delegate = requireNonNull(delegate);
  }

  @Override
  public void execute(Runnable task) {
    if (isShutdownBitSet()) {
      throw new RejectedExecutionException("shutdown");
    }

    var decoratedTask = new RunnableDecorator(task);
    taskQueue.add(decoratedTask);

    while (true) {
      int s = sync;

      // If drain has been asked to recheck for tasks, but hasn't yet rechecked after adding the
      // new task, then it will surely see the added task.
      if (isKeepAliveBitSet()) {
        return;
      }

      if (!isRunningBitSet()) {
        if (SYNC.compareAndSet(this, s, s | RUNNING)) {
          tryStart(decoratedTask);
          return;
        }
      } else if (SYNC.compareAndSet(this, s, (s | KEEP_ALIVE))) {
        return;
      }
    }
  }

  private void tryStart(RunnableDecorator task) {
    // TODO consider retrying/offloading to an async executor (e.g. common pool).
    try {
      delegate.execute(this::drain);
    } catch (RuntimeException | Error e) {
      SYNC.getAndBitwiseAnd(this, ~(RUNNING | KEEP_ALIVE));
      if (!(e instanceof RejectedExecutionException) || taskQueue.remove(task)) {
        throw e;
      }
    }
  }

  public void shutdown() {
    SYNC.getAndBitwiseOr(this, SHUTDOWN);
  }

  private void drain() {
    boolean interrupted = false;
    while (true) {
      Runnable task;
      while ((task = taskQueue.poll()) != null) {
        try {
          interrupted |= Thread.interrupted();
          task.run();
        } catch (Throwable t) {
          // Before propagating, try to reschedule ourselves if we still have work. This is done
          // asynchronously in common FJ pool to rethrow immediately (delegate is not guaranteed to
          // execute tasks asynchronously).
          SYNC.getAndBitwiseAnd(this, ~(RUNNING | KEEP_ALIVE));
          if (!taskQueue.isEmpty()) {
            try {
              ForkJoinPool.commonPool().execute(() -> execute(() -> {}));
            } catch (RuntimeException | Error e) {
              // Not much we can do here.
              t.addSuppressed(e);
            }
          }
          throw t;
        }
      }

      // Exit or consume KEEP_ALIVE bit.
      int s = sync;
      int unsetBit = (s & KEEP_ALIVE) != 0 ? KEEP_ALIVE : RUNNING;
      if (SYNC.weakCompareAndSet(this, s, s & ~unsetBit) && unsetBit == RUNNING) {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        return;
      }
    }
  }

  boolean isRunningBitSet() {
    return (sync & RUNNING) != 0;
  }

  boolean isShutdownBitSet() {
    return (sync & SHUTDOWN) != 0;
  }

  boolean isKeepAliveBitSet() {
    return (sync & KEEP_ALIVE) != 0;
  }

  @Override
  public String toString() {
    return "SerialExecutor@"
        + Integer.toHexString(hashCode())
        + "{delegate="
        + delegate
        + ", running="
        + isRunningBitSet()
        + ", keepAlive="
        + isKeepAliveBitSet()
        + ", shutdown="
        + isShutdownBitSet()
        + "}";
  }

  /**
   * Associates an identity with each task passed to {@link #execute(Runnable)} so it is
   * deterministically removed from the task queue when the delegate executor rejects the drain
   * task.
   */
  private static final class RunnableDecorator implements Runnable {
    private final Runnable delegate;

    RunnableDecorator(Runnable delegate) {
      this.delegate = requireNonNull(delegate);
    }

    @Override
    public void run() {
      delegate.run();
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
