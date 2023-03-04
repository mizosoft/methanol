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

package com.github.mizosoft.methanol.internal.flow;

import static com.github.mizosoft.methanol.internal.Utils.requirePositiveDuration;
import static com.github.mizosoft.methanol.internal.flow.FlowSupport.getAndAddDemand;
import static com.github.mizosoft.methanol.internal.flow.FlowSupport.subtractAndGetDemand;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.Future;

/**
 * A subscriber that intercepts requests to upstream and schedules error completion if each
 * requested item isn't received within a timeout.
 */
public abstract class TimeoutSubscriber<T, S extends Subscriber<? super T>>
    extends SerializedSubscriber<T> {
  private static final Future<Void> COMPLETED_FUTURE = CompletableFuture.completedFuture(null);

  private static final VarHandle DEMAND;
  private static final VarHandle TIMEOUT_TASK;

  static {
    var lookup = MethodHandles.lookup();
    try {
      DEMAND = lookup.findVarHandle(TimeoutSubscriber.class, "demand", long.class);
      TIMEOUT_TASK =
          lookup.findVarHandle(TimeoutSubscriber.class, "timeoutTask", TimeoutTask.class);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private final S downstream;
  private final Duration timeout;
  private final Delayer delayer;

  /**
   * The upstream subscription as received from this.onSubscribe(...), without wrapping in a
   * TimeoutSubscription.
   */
  private final Upstream unwrappedUpstream = new Upstream();

  /** Tracks downstream demand to know when to schedule timeouts. */
  @SuppressWarnings("unused") // VarHandle indirection.
  private volatile long demand;

  @SuppressWarnings("FieldMayBeFinal") // VarHandle indirection.
  private volatile TimeoutTask timeoutTask = new TimeoutTask(0, COMPLETED_FUTURE);

  public TimeoutSubscriber(S downstream, Duration timeout, Delayer delayer) {
    this.downstream = requireNonNull(downstream);
    this.timeout = requirePositiveDuration(timeout);
    this.delayer = requireNonNull(delayer);
  }

  @Override
  protected S downstream() {
    return downstream;
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

    var currentTimeoutTask = timeoutTask;
    if (currentTimeoutTask == TimeoutTask.TOMBSTONE) {
      upstream.cancel();
      return;
    }

    currentTimeoutTask.cancel();

    super.onNext(item);

    long currentDemand = subtractAndGetDemand(this, DEMAND, 1);
    if (currentDemand > 0) {
      // We still have requests, so start a new timeout for the next item.
      try {
        scheduleTimeout(currentTimeoutTask.index + 1);
      } catch (RuntimeException | Error e) {
        cancelOnError(this, e);
      }
    } else if (currentDemand < 0) {
      // We're getting overflowed.
      cancelOnError(this, new IllegalStateException("getting more items than requested"));
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    cancelTimeout();
    super.onError(throwable);
  }

  @Override
  public void onComplete() {
    cancelTimeout();
    super.onComplete();
  }

  private void cancelTimeout() {
    var currentTimeoutTask = (TimeoutTask) TIMEOUT_TASK.getAndSet(this, TimeoutTask.TOMBSTONE);
    currentTimeoutTask.cancel();
  }

  private void scheduleTimeout(long newTimeoutIndex) {
    TimeoutTask newTimeoutTask = null;
    while (true) {
      var currentTimeoutTask = timeoutTask;
      if (currentTimeoutTask == TimeoutTask.TOMBSTONE
          || newTimeoutIndex <= currentTimeoutTask.index) {
        if (newTimeoutTask != null) {
          newTimeoutTask.future.cancel(false);
        }
        return;
      }

      if (newTimeoutTask == null) {
        newTimeoutTask =
            new TimeoutTask(
                newTimeoutIndex,
                delayer.delay(
                    () -> onTimeout(newTimeoutIndex), timeout, FlowSupport.SYNC_EXECUTOR));
      }
      if (TIMEOUT_TASK.compareAndSet(this, currentTimeoutTask, newTimeoutTask)) {
        currentTimeoutTask.future.cancel(false);
        return;
      }
    }
  }

  private void cancelOnError(Subscriber<?> subscriber, Throwable exception) {
    // Capture the subscription before it's cleared by onError. The subscription is cancelled after
    // onError as the HTTP client seems to sometimes complete downstream with an IOException if
    // the subscription is cancelled, which causes the wrong exception to be passed on.
    var subscription = upstream.get();
    try {
      subscriber.onError(exception);
    } finally {
      subscription.cancel();
    }
  }

  protected abstract Throwable timeoutError(long index, Duration timeout);

  private void onTimeout(long timeoutIndex) {
    var currentTimeoutTask = timeoutTask;
    if (currentTimeoutTask.index == timeoutIndex
        && TIMEOUT_TASK.compareAndSet(this, currentTimeoutTask, TimeoutTask.TOMBSTONE)) {
      cancelOnError(downstream(), timeoutError(timeoutIndex, timeout));
    }
  }

  private final class TimeoutSubscription implements Subscription {
    TimeoutSubscription() {}

    @Override
    public void request(long n) {
      var currentIndex = timeoutTask.index;
      if (currentIndex == TimeoutTask.TOMBSTONE_INDEX) {
        return;
      }

      if (n > 0 && getAndAddDemand(TimeoutSubscriber.this, DEMAND, n) == 0) {
        try {
          scheduleTimeout(currentIndex + 1);
        } catch (RuntimeException | Error e) {
          cancel();
          throw e;
        }
      }
      unwrappedUpstream.request(n);
    }

    @Override
    public void cancel() {
      cancelTimeout();
      unwrappedUpstream.cancel();
    }
  }

  private static final class TimeoutTask {
    static final int TOMBSTONE_INDEX = -1;

    /**
     * A sentinel value indicating that no further timeouts are to be scheduled as the stream has
     * been terminated.
     */
    static final TimeoutTask TOMBSTONE = new TimeoutTask(TOMBSTONE_INDEX, COMPLETED_FUTURE);

    final long index;
    final Future<?> future;

    TimeoutTask(long index, Future<?> future) {
      this.index = index;
      this.future = future;
    }

    void cancel() {
      future.cancel(false);
    }
  }
}
