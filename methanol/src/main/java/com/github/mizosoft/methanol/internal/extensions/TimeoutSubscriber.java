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

package com.github.mizosoft.methanol.internal.extensions;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.HttpReadTimeoutException;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Intercepts requests to upstream and schedules error completion with
 * {@code HttpReadTimeoutException} if each not fulfilled within a timeout.
 */
public final class TimeoutSubscriber<T>
    extends DelegatingSubscriber<List<ByteBuffer>, BodySubscriber<T>>
    implements BodySubscriber<T> {

  private static final VarHandle ITEM_INDEX;
  private static final VarHandle DEMAND;
  private static final VarHandle TIMEOUT_TASK;
  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    try {
      ITEM_INDEX = lookup.findVarHandle(TimeoutSubscriber.class, "itemIndex", long.class);
      DEMAND = lookup.findVarHandle(TimeoutSubscriber.class, "demand", long.class);
      TIMEOUT_TASK = lookup.findVarHandle(
          TimeoutSubscriber.class, "timeoutTask", Cancellable.class);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new IllegalArgumentException(e);
    }
  }

  // Terminal state to ignore passing any further signals to downstream
  // after reaching either timeout or completion from upstream or cancellation
  private static final long TOMBSTONE = -1;

  private final long timeoutMillis;
  private final Timer timer;
  // This counter represents the index of the item to which a timeout is
  // scheduled, incremented by onNext and set to TOMBSTONE by terminal signals.
  // This ensures that timeout error completion is not signalled for the wrong
  // item or after another terminal signal.
  private volatile long itemIndex;
  // demand must be tracked to know when to schedule timeouts
  private volatile long demand;
  private volatile @Nullable Cancellable timeoutTask;

  public TimeoutSubscriber(BodySubscriber<T> downstream, Duration timeout,
      @Nullable ScheduledExecutorService schedulerService) {
    super(downstream);
    this.timeoutMillis = TimeUnit.MILLISECONDS.convert(timeout);
    if (schedulerService != null) {
      this.timer = idx -> new ScheduledTimeoutTask(idx, schedulerService, timeoutMillis);
    } else {
      // use the system-wide scheduler from CompletableFuture
      Executor delayedExecutor = CompletableFuture.delayedExecutor(
          timeoutMillis, TimeUnit.MILLISECONDS, FlowSupport.SYNC_EXECUTOR);
      this.timer = idx -> new DelayedTimeoutTask(idx, delayedExecutor);
    }
  }

  @Override
  public CompletionStage<T> getBody() {
    return downstream.getBody();
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    // Intercept with our TimeoutSubscription
    super.onSubscribe(new TimeoutSubscription(subscription));
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    long idx = itemIndex;
    if (idx != TOMBSTONE && ITEM_INDEX.compareAndSet(this, idx, ++idx)) { // could reach before timeout?
      // remove timeout scheduled for this signal
      Cancellable currentTask = (Cancellable) TIMEOUT_TASK.getAndSet(this, null);
      if (currentTask != null) {
        if (currentTask == Cancellable.CANCELLED) {
          return; // detected cancellation promptly, downstream is lucky!
        }
        currentTask.cancel();
      }
      long d = decrementAndGetDemand();
      if (d > 0) { // still have requests, start a new timeout for the next
        try {
          setAndScheduleTimeout(idx);
        } catch (RuntimeException e) { // execute() | schedule() can throw
          upstream.cancel();
          super.onError(e);
          return;
        }
      } else if (d < 0) { // this means that upstream is trying to overflow us
        upstream.cancel();
        super.onError(
            new IllegalStateException(
                "missing backpressure: receiving more items than requested"));
        return;
      }
      super.onNext(item);
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    if ((long) ITEM_INDEX.getAndSet(this, TOMBSTONE) != TOMBSTONE) { // could reach before timeout?
      Cancellable currentTask = cancelTimeout();
      if (currentTask != null) {
        currentTask.cancel();
      }
      super.onError(throwable); // we're done!
    }
  }

  @Override
  public void onComplete() {
    if ((long) ITEM_INDEX.getAndSet(this, TOMBSTONE) != TOMBSTONE) { // could reach before timeout?
      Cancellable currentTask = cancelTimeout();
      if (currentTask != null) {
        currentTask.cancel();
      }
      super.onComplete(); // we're done!
    }
  }

  // Called by request and onNext in a mutual exclusive manner; Calls come from
  // request only when incremented demand was previously 0 (no ongoing onNext),
  // and from onNext only when demand is still larger than 0 when decremented
  private void setAndScheduleTimeout(long idx) {
    Cancellable currentTask = timeoutTask;
    if (currentTask != Cancellable.CANCELLED) {
      Cancellable newTask = timer.scheduleTimeout(idx);
      if (!TIMEOUT_TASK.compareAndSet(this, currentTask, newTask)) {
        newTask.cancel(); // CAS failed so discard
      }
    }
  }

  private @Nullable Cancellable cancelTimeout() {
    return (Cancellable) TIMEOUT_TASK.getAndSet(this, Cancellable.CANCELLED);
  }

  private long getAndAddDemand(long n) {
    do {
      long d = demand;
      long r = Long.MAX_VALUE - d >= n ? d + n : Long.MAX_VALUE;
      if (DEMAND.compareAndSet(this, d, r)) {
        return d;
      }
    } while (true);
  }

  private long decrementAndGetDemand() {
    return (long) DEMAND.getAndAdd(this, -1L) - 1L;
  }

  private final class TimeoutSubscription implements Subscription {

    private final Subscription actualUpstream;

    TimeoutSubscription(Subscription actualUpstream) {
      this.actualUpstream = actualUpstream;
    }

    @Override
    public void request(long n) {
      long idx = itemIndex;
      if (idx != TOMBSTONE) {
        if (n > 0 && getAndAddDemand(n) == 0) {
          // start timeout for first item in demand, further timeouts are scheduled by onNext()
          try {
            setAndScheduleTimeout(idx);
          } catch (RuntimeException e) { // schedule() can throw
            cancel();
            throw e;
          }
        }
        actualUpstream.request(n);
      }
    }

    @Override
    public void cancel() {
      itemIndex = TOMBSTONE; // ignore further signals
      Cancellable task = cancelTimeout();
      if (task != null) {
        task.cancel();
      }
      actualUpstream.cancel();
    }
  }

  private interface Cancellable {

    // Tombstone to prevent any further timeout schedules after cancellation
    Cancellable CANCELLED = () -> {};

    void cancel();
  }

  private interface Timer {

    Cancellable scheduleTimeout(long idx);
  }

  private abstract class TimeoutTask implements Cancellable, Runnable {

    private final long timeoutIndex;

    TimeoutTask(long timeoutIndex) {
      this.timeoutIndex = timeoutIndex;
    }

    @Override
    public void run() {
      if (ITEM_INDEX.compareAndSet(TimeoutSubscriber.this, timeoutIndex, TOMBSTONE)) { // could reach before other signals?
        upstream.cancel(); // cancels TimeoutSubscription & actualUpstream
        TimeoutSubscriber.super.onError(new HttpReadTimeoutException(
            String.format("read [%d] timed out after %d ms", timeoutIndex, timeoutMillis)));
      }
    }
  }

  private final class DelayedTimeoutTask extends TimeoutTask {

    private final AtomicBoolean virgin;

    DelayedTimeoutTask(long timeoutIndex, Executor delayedExecutor) {
      super(timeoutIndex);
      virgin = new AtomicBoolean(true);
      delayedExecutor.execute(this);
    }

    @Override
    public void run() {
      if (virgin.compareAndSet(true, false)) {
        super.run();
      }
    }

    @Override
    public void cancel() {
      virgin.set(false);
    }
  }

  private final class ScheduledTimeoutTask extends TimeoutTask {

    private final ScheduledFuture<?> future;

    ScheduledTimeoutTask(
        long timeoutIndex, ScheduledExecutorService scheduler, long delayMillis) {
      super(timeoutIndex);
      this.future = scheduler.schedule(this, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel() {
      future.cancel(false);
    }
  }
}
