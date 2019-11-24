/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.internal.flow;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A task that can be either executed or scheduled for execution if already running.
 */
public abstract class Schedulable {

  private static final int DIRTY = -1;
  private static final int STOPPED = -2;

  private final Executor executor;
  private final int maxSchedules;
  // Control state that can be either DIRTY, STOPPED or the number of schedules + 1
  // (where 1 represents the currently running task). The happens-before guarantee
  // relies upon CASes on this field either before the first run or between further
  // scheduled runs
  private final AtomicInteger state;

  /**
   * Creates a new {@code Schedulable} with the given executor and maximum schedules.
   *
   * @param executor     the executor on which the task runs
   * @param maxSchedules the maximum number of times the task is allowed to be scheduled
   * @throws IllegalArgumentException if {@code maxSchedules} is negative
   */
  protected Schedulable(Executor executor, int maxSchedules) {
    this.executor = requireNonNull(executor);
    if (maxSchedules < 0) {
      throw new IllegalArgumentException("maxSchedules must be non-negative: " + maxSchedules);
    }
    this.maxSchedules = maxSchedules;
    state = new AtomicInteger();
  }

  /**
   * Either runs or schedules the task.
   *
   * @throws RejectedExecutionException if the executor rejects the task
   */
  protected void runOrSchedule() {
    int s = getAndIncrementState();
    if (s == 0 || s == DIRTY) {
      try {
        executor.execute(this::runSchedulable);
      } catch (RejectedExecutionException e) {
        decrementAndGetState(true); // False alarm
        throw e;
      }
    }
  }

  /**
   * Permanently disables further executions or schedules for the task.
   */
  protected void stop() {
    state.set(STOPPED);
  }

  /**
   * Method to be overridden to run the task.
   */
  protected abstract void run();

  private void runSchedulable() {
    for (int p = state.get(); p > 0; ) {
      boolean dirty = false;
      try {
        run();
      } catch (Throwable t) {
        dirty = true;
        throw t; // Rethrow, further runOrSchedule() calls will restart the task
      } finally {
        p = decrementAndGetState(dirty);
      }
    }
  }

  private int decrementAndGetState(boolean dirty) {
    for (int s, r; ; ) {
      // Only decrement if "active".
      // However the CAS must be made to hold the happens-before guarantee.
      s = state.get();
      if (s > 0) {
        r = dirty ? DIRTY : s - 1;
      } else {
        r = s; // Not active -> DIRTY or STOPPED (can't be 0)
      }
      if (state.compareAndSet(s, r)) {
        return r; // post CAS
      }
    }
  }

  private int getAndIncrementState() {
    // Only increment if not stopped or already reached maxSchedules + 1.
    // However the CAS must be made to hold the happens-before guarantee.
    for (int s, r; ; ) {
      s = state.get();
      if (s <= maxSchedules && s != STOPPED) {
        r = s != DIRTY ? s + 1 : 1;
      } else {
        r = s;
      }
      if (state.compareAndSet(s, r)) {
        return s; // pre CAS
      }
    }
  }
}
