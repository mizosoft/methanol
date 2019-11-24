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

import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscription;

/**
 * A subscription that has a {@link Schedulable} task for fulfilling subscriber demand.
 */
public abstract class SchedulableSubscription extends Schedulable implements Subscription {

  // A maximum of 1 ongoing schedules will do as the need is to either execute the task or schedule
  // a "follow up" if one is already running. In "fulfuiller" subscription tasks, a "follow up"
  // is needed on demand/cancellation or arrival of upstream signals, as a running (but maybe
  // about to exit) task might not have not observed such signals, so another task must follow it.
  private static final int MAX_SCHEDULES = 1;

  /**
   * Creates a new {@code SchedulableSubscription} with the given executor.
   *
   * @param executor the executor on which the task runs
   */
  protected SchedulableSubscription(Executor executor) {
    super(executor, MAX_SCHEDULES);
  }

  /**
   * Signals this subscription to either run or be scheduled to run later if already running.
   */
  public void signal() {
    super.runOrSchedule();
  }

  @Override
  protected final void run() {
    drain();
  }

  /**
   * Method to be overridden for implementing "drain" logic.
   */
  protected abstract void drain();
}
