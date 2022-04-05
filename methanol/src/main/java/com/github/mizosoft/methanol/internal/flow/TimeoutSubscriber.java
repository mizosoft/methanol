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

package com.github.mizosoft.methanol.internal.flow;

import static com.github.mizosoft.methanol.internal.flow.FlowSupport.getAndAddDemand;
import static com.github.mizosoft.methanol.internal.flow.FlowSupport.subtractAndGetDemand;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.Future;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A subscriber that intercepts requests to upstream and schedules error completion if each
 * requested item isn't received within a timeout.
 */
@SuppressWarnings({"UnusedVariable", "unused"}) // VarHandle indirection
public abstract class TimeoutSubscriber<T> extends SerializedSubscriber<T> {
  /** Indicates that no further timeouts are to be scheduled as the stream has been terminated. */
  private static final Future<Void> DISABLED_TIMEOUT = CompletableFuture.completedFuture(null);

  /**
   * Indicates that further downstream signals are to be ignored as the stream has been terminated,
   * possibly due to timeout.
   */
  private static final long TOMBSTONE = -1;

  private static final VarHandle INDEX;
  private static final VarHandle DEMAND;
  private static final VarHandle TIMEOUT_TASK;

  static {
    var lookup = MethodHandles.lookup();
    try {
      INDEX = lookup.findVarHandle(TimeoutSubscriber.class, "index", long.class);
      DEMAND = lookup.findVarHandle(TimeoutSubscriber.class, "demand", long.class);
      TIMEOUT_TASK = lookup.findVarHandle(TimeoutSubscriber.class, "timeoutTask", Future.class);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private final Duration timeout;
  private final Delayer delayer;
  private final Upstream unwrappedUpstream = new Upstream();

  /** Tracks downstream demand to know when to schedule timeouts. */
  private volatile long demand;

  /**
   * The index of the item to which a timeout is scheduled (or yet to be scheduled by request() if
   * demand was 0).
   */
  private volatile long index;

  /** Timeout scheduled for the currently awaited item. */
  private volatile @Nullable Future<Void> timeoutTask;

  @SuppressWarnings("ClassEscapesDefinedScope") // TimeoutSubscriber & Delayer are both encapsulated
  public TimeoutSubscriber(Duration timeout, Delayer delayer) {
    this.timeout = timeout;
    this.delayer = delayer;
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (unwrappedUpstream.setOrCancel(subscription)) {
      super.onSubscribe(new TimeoutSubscription());
    }
  }

  @Override
  public void onNext(T item) {
    requireNonNull(item);

    // Could we receive this item before timeout?
    long currentIndex = index;
    if (currentIndex != TOMBSTONE && INDEX.compareAndSet(this, currentIndex, ++currentIndex)) {
      var currentTimeoutTask = timeoutTask;
      if (currentTimeoutTask == DISABLED_TIMEOUT
          || !TIMEOUT_TASK.compareAndSet(this, currentTimeoutTask, null)) {
        // If the CAS fails, the only possible contention would be with cancel() (or a concurrent
        // onNext(), onError() or onComplete() if upstream is misbehaving), so return.
        return;
      }
      if (currentTimeoutTask != null) {
        currentTimeoutTask.cancel(true);
      }

      long currentDemand = subtractAndGetDemand(this, DEMAND, 1);
      if (currentDemand > 0) {
        // We still have requests, so start a new timeout for the next item
        try {
          scheduleTimeout(currentIndex);
        } catch (RuntimeException | Error e) { // delay() can throw
          upstream.cancel();
          super.onError(e);
          return;
        }
      } else if (currentDemand < 0) { // This means upstream is trying to overflow us
        upstream.cancel();
        super.onError(
            new IllegalStateException("missing backpressure: receiving more items than requested"));
        return;
      }

      super.onNext(item);
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    if ((long) INDEX.getAndSet(this, TOMBSTONE) != TOMBSTONE) { // Could reach before timeout?
      disableTimeouts();
      super.onError(throwable);
    }
  }

  @Override
  public void onComplete() {
    if ((long) INDEX.getAndSet(this, TOMBSTONE) != TOMBSTONE) { // Could reach before timeout?
      disableTimeouts();
      super.onComplete();
    }
  }

  /**
   * Called by request() & onNext() in a mutually exclusive manner. Calls come from request() only
   * when incremented demand was previously 0 (no ongoing onNext), and from onNext() only when
   * demand is still larger than 0 when decremented.
   */
  private void scheduleTimeout(long index) {
    var currentTimeoutTask = timeoutTask;
    if (currentTimeoutTask != DISABLED_TIMEOUT) {
      var newTimeoutTask =
          delayer.delay(() -> onTimeout(index), timeout, FlowSupport.SYNC_EXECUTOR);
      if (!TIMEOUT_TASK.compareAndSet(this, currentTimeoutTask, newTimeoutTask)) {
        // Discard timeout on failed CAS. Possible contention is with onError(), onComplete() or
        // cancel(), all marking stream termination.
        newTimeoutTask.cancel(true);
      }
    }
  }

  protected abstract Throwable timeoutError(long index, Duration timeout);

  private void onTimeout(long index) {
    if (INDEX.compareAndSet(this, index, TOMBSTONE)) {
      upstream.cancel();
      super.onError(timeoutError(index, timeout));
    }
  }

  @SuppressWarnings("unchecked")
  private void disableTimeouts() {
    var future = (Future<Void>) TIMEOUT_TASK.getAndSet(this, DISABLED_TIMEOUT);
    if (future != null) {
      future.cancel(true);
    }
  }

  private final class TimeoutSubscription implements Subscription {
    TimeoutSubscription() {}

    @Override
    public void request(long n) {
      long currentIndex = index;
      if (currentIndex != TOMBSTONE) {
        if (n > 0 && getAndAddDemand(TimeoutSubscriber.this, DEMAND, n) == 0) {
          try {
            scheduleTimeout(currentIndex);
          } catch (RuntimeException | Error e) { // delay() can throw
            cancel();
            throw e;
          }
        }
        unwrappedUpstream.request(n);
      }
    }

    @Override
    public void cancel() {
      index = TOMBSTONE;
      disableTimeouts();
      unwrappedUpstream.cancel();
    }
  }
}
