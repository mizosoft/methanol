/*
 * Copyright (c) 2022 Moataz Abdelnasser
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
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A subscriber that intercepts requests to upstream and schedules error completion if each
 * requested item isn't received within a timeout.
 */
@SuppressWarnings({"UnusedVariable", "unused"}) // VarHandle indirection
public abstract class TimeoutSubscriber<T, S extends Subscriber<? super T>>
    extends SerializedSubscriber<T> {
  /** Indicates that no further timeouts are to be scheduled as the stream has been terminated. */
  private static final Future<Void> DISABLED_TIMEOUT = CompletableFuture.completedFuture(null);

  /**
   * Indicates that further downstream signals are to be ignored as the stream has been terminated,
   * possibly due to timeout.
   */
  private static final long TOMBSTONE = -1;

  private static final VarHandle INDEX;
  private static final VarHandle DEMAND;
  private static final VarHandle TIMEOUT_FUTURE;

  static {
    var lookup = MethodHandles.lookup();
    try {
      INDEX = lookup.findVarHandle(TimeoutSubscriber.class, "index", long.class);
      DEMAND = lookup.findVarHandle(TimeoutSubscriber.class, "demand", long.class);
      TIMEOUT_FUTURE = lookup.findVarHandle(TimeoutSubscriber.class, "timeoutFuture", Future.class);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private final S downstream;
  private final Duration timeout;
  private final Delayer delayer;

  /**
   * The upstream as received from this.onSubscribe(...), without wrapping in a TimeoutSubscription.
   */
  private final Upstream unwrappedUpstream = new Upstream();

  /** Tracks downstream demand to know when to schedule timeouts. */
  private volatile long demand;

  /**
   * The index of the item to which a timeout is scheduled (or yet to be scheduled by request() if
   * demand was 0).
   */
  private volatile long index;

  /** Represents the timeout scheduled for the currently awaited item. */
  private volatile @Nullable Future<Void> timeoutFuture;

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

    var currentTimeoutFuture = timeoutFuture;
    if (currentTimeoutFuture == null
        || currentTimeoutFuture == DISABLED_TIMEOUT
        || !TIMEOUT_FUTURE.compareAndSet(this, currentTimeoutFuture, null)) {
      upstream.cancel();
      if (currentTimeoutFuture == null) {
        super.onError(
            new IllegalStateException(
                "missing backpressure support or illegal (concurrent) usage"));
      }
      return;
    }

    currentTimeoutFuture.cancel(false);

    super.onNext(item);

    var currentIndex = index;
    if (currentIndex == TOMBSTONE || !INDEX.compareAndSet(this, currentIndex, currentIndex + 1)) {
      upstream.cancel();
      super.onError(
          new IllegalStateException("missing backpressure support or illegal (concurrent) usage"));
      return;
    }

    if (subtractAndGetDemand(this, DEMAND, 1) > 0) {
      // We still have requests, so start a new timeout for the next item
      try {
        scheduleTimeout(currentIndex + 1);
      } catch (RuntimeException | Error e) {
        upstream.cancel();
        super.onError(e);
      }
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    if ((long) INDEX.getAndSet(this, TOMBSTONE) != TOMBSTONE) {
      disableTimeouts();
      super.onError(throwable);
    }
  }

  @Override
  public void onComplete() {
    if ((long) INDEX.getAndSet(this, TOMBSTONE) != TOMBSTONE) {
      disableTimeouts();
      super.onComplete();
    }
  }

  private void disableTimeouts() {
    var currentTimeoutFuture = (Future<?>) TIMEOUT_FUTURE.getAndSet(this, DISABLED_TIMEOUT);
    if (currentTimeoutFuture != null) {
      currentTimeoutFuture.cancel(false);
    }
  }

  /**
   * Called by request() & onNext() in a mutually exclusive manner. Calls come from request() only
   * when incremented demand was previously 0 (no ongoing onNext), and from onNext() only when
   * demand is still larger than 0 when decremented.
   */
  private void scheduleTimeout(long nextTimeoutIndex) {
    var currentTimeoutFuture = timeoutFuture;
    if (currentTimeoutFuture != DISABLED_TIMEOUT) {
      var nextTimeoutFuture =
          delayer.delay(() -> onTimeout(index), timeout, FlowSupport.SYNC_EXECUTOR);
      if (!TIMEOUT_FUTURE.compareAndSet(this, currentTimeoutFuture, nextTimeoutFuture)) {
        // Discard timeout on failed CAS. Expected contention is with onError(), onComplete() or
        // cancel(), all marking stream termination.
        nextTimeoutFuture.cancel(false);
      }
    }
  }

  protected abstract Throwable timeoutError(long index, Duration timeout);

  private void onTimeout(long timeoutIndex) {
    if (INDEX.compareAndSet(this, timeoutIndex, TOMBSTONE)) {
      upstream.cancel();
      super.onError(timeoutError(timeoutIndex, timeout));
    }
  }

  private final class TimeoutSubscription implements Subscription {
    TimeoutSubscription() {}

    @Override
    public void request(long n) {
      var currentIndex = index;
      if (currentIndex == TOMBSTONE) {
        return;
      }

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

    @Override
    public void cancel() {
      index = TOMBSTONE;
      disableTimeouts();
      unwrappedUpstream.cancel();
    }
  }
}
