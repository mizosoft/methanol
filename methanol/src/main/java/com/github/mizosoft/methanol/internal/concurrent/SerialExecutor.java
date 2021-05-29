/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

/**
 * An {@code Executor} that ensures submitted tasks are executed serially. This is similar to
 * Guava's {@code SequentialExecutor} but completely relies on atomics for synchronization.
 */
public final class SerialExecutor implements Executor {
  private static final int DRAIN_COUNT_BITS = Long.SIZE - 4; // There're 4 state bits

  /** Mask for the drain count maintained in the lower 60 bits of {@link #sync} field. */
  private static final long DRAIN_COUNT_MASK = (1L << DRAIN_COUNT_BITS) - 1;

  /**
   * Drain task is submitted to delegate executor. This is used to prevent resubmission of drain
   * task multiple times if it commences execution late. If set, the bit is retained till drain
   * exits.
   */
  private static final long SUBMITTED = 1L << DRAIN_COUNT_BITS;
  /** Drain task commenced execution. Retained till drain exits. */
  private static final long RUNNING = 2L << DRAIN_COUNT_BITS;
  /** Drain loop should keep running to recheck for incoming tasks. */
  private static final long KEEP_ALIVE = 4L << DRAIN_COUNT_BITS;
  /** Don't accept more tasks. */
  private static final long SHUTDOWN = 8L << DRAIN_COUNT_BITS;

  private static final VarHandle SYNC;

  static {
    try {
      var lookup = MethodHandles.lookup();
      SYNC = lookup.findVarHandle(SerialExecutor.class, "sync", long.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Executor delegate;
  private final ConcurrentLinkedQueue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();

  /**
   * Field that maintains execution state at its first 4 MSBs along with the number of times the
   * drain task has executed to completion at the lower bits. The drain execution count is only
   * maintained to avoid an ABA problem that would otherwise occur under the following scenario: A
   * thread reads the sync field, sees RUNNING is not set, then fires a drain task. Before the
   * thread has the chance to set SUBMITTED, the drain task begins (sets RUNNING) then completes
   * execution (unsets RUNNING) (e.g. same thread executor). The thread then sees the sync field
   * hasn't changed, then successfully sets SUBMITTED via a CAS. Other threads will later fail to
   * submit the drain as they'll think it's already been submitted, but that's not true. Attaching a
   * 'stamp' to the field fixes this issue. Kudos to Guava's SequentialExecutor for bringing this
   * issue to mind ;).
   */
  @SuppressWarnings("unused") // VarHandle indirection
  private volatile long sync;

  public SerialExecutor(Executor delegate) {
    this.delegate = delegate;
  }

  @Override
  public void execute(Runnable command) {
    requireNonNull(command);
    if ((sync & SHUTDOWN) != 0) {
      throw new RejectedExecutionException(command.toString());
    }

    var task = new RunnableDecorator(command);
    taskQueue.offer(task);
    while (true) {
      // Try to execute drain task or keep it alive if it's already running or about
      // to run (submitted to delegate executor). In case of contention, multiple
      // threads might succeed to submit the drain task after observing the absence
      // of RUNNING and SUBMITTED bits, but that's OK since the drain task itself ensures
      // it's only run once.
      long s = sync;
      boolean drainIsIdle = (s & (SUBMITTED | RUNNING)) == 0;
      if (drainIsIdle || SYNC.compareAndSet(this, s, (s | KEEP_ALIVE))) {
        if (drainIsIdle) {
          try {
            delegate.execute(this::drainTaskQueue);
            // Set the SUBMITTED bit ONLY if it hasn't changed
            SYNC.compareAndSet(this, s, s | SUBMITTED);
          } catch (RejectedExecutionException e) {
            // Relay REE to caller after removing the rejected task so it doesn't run again later
            taskQueue.remove(task);
            throw e;
          }
        }
        break;
      }
    }
  }

  public void shutdown() {
    SYNC.getAndBitwiseOr(this, SHUTDOWN);
  }

  private void drainTaskQueue() {
    if (!acquireRun()) {
      // Another drain won the race
      return;
    }

    while (true) {
      var task = taskQueue.poll();
      if (task != null) {
        try {
          task.run();
        } catch (Throwable t) {
          // Before propagating that to delegate's thread, try to schedule an
          // empty task so we can be executed again as there might sill be tasks
          // in the queue. This is done asynchronously in common FJ pool to rethrow
          // immediately (delegate is not guaranteed to execute tasks asynchronously).
          SYNC.getAndBitwiseAnd(this, ~(RUNNING | KEEP_ALIVE | SUBMITTED));
          ForkJoinPool.commonPool().execute(() -> execute(() -> {}));
          throw t;
        }
      } else {
        // Exit or consume keep-alive bit. Don't forget to also unset SUBMITTED if exiting.
        long s = sync;
        long unsetBits = (s & KEEP_ALIVE) != 0 ? KEEP_ALIVE : (RUNNING | SUBMITTED);
        if (SYNC.compareAndSet(this, s, incrementDrainCount(s) & ~unsetBits)
            && (unsetBits & RUNNING) != 0) {
          break;
        }
      }
    }
  }

  /** Atomically sets the {@link #RUNNING} bit, returning true if successful. */
  private boolean acquireRun() {
    long s = (long) SYNC.getAndBitwiseOr(this, RUNNING);
    return (s & RUNNING) == 0;
  }

  /** Returns {@code sync} with an incremented drain count and existing state bits. */
  private static long incrementDrainCount(long sync) {
    long count = sync & DRAIN_COUNT_MASK;
    // Make sure drain count wraps around if it ever overflows,
    // which would take about 37 years assuming each drain task takes 1 ns.
    long incrementedCount = (count + 1) & DRAIN_COUNT_MASK;
    long stateBits = sync & ~DRAIN_COUNT_MASK;
    return incrementedCount | stateBits;
  }

  // For testing

  boolean isRunningBitSet() {
    return (sync & RUNNING) != 0;
  }

  long drainCount() {
    return (sync & DRAIN_COUNT_MASK);
  }

  boolean isSubmittedBitSet() {
    return (sync & SUBMITTED) != 0;
  }

  boolean isShutdownBitSet() {
    return (sync & SHUTDOWN) != 0;
  }

  /**
   * Associates an identity with each task passed to {@link #execute(Runnable)} so it is
   * deterministically removed from the task queue when the delegate executor rejects the drain
   * task.
   */
  private static final class RunnableDecorator implements Runnable {
    private final Runnable delegate;

    RunnableDecorator(Runnable delegate) {
      this.delegate = delegate;
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
