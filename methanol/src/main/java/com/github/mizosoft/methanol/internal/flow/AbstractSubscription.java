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

package com.github.mizosoft.methanol.internal.flow;

import static com.github.mizosoft.methanol.internal.flow.FlowSupport.getAndAddDemand;
import static com.github.mizosoft.methanol.internal.flow.FlowSupport.subtractAndGetDemand;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An abstract {@code Subscription} that implements most of the machinery for execution and
 * backpressure control.
 *
 * @param <T> item's type
 */
public abstract class AbstractSubscription<T> implements Subscription {

  /*
   * Implementation is loosely modeled after SubmissionPublisher$BufferedSubscription, mainly
   * regarding how execution is controlled by CASes on a state field that manipulate bits
   * representing execution states.
   */

  private static final int RUN = 0x1; // run signaller task
  private static final int KEEP_ALIVE = 0x2; // keep running signaller task
  private static final int CANCELLED = 0x4; // subscription is cancelled
  private static final int SUBSCRIBED = 0x8; // onSubscribe called

  private static final VarHandle STATE;
  private static final VarHandle PENDING_ERROR;
  private static final VarHandle DEMAND;

  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    try {
      STATE = lookup.findVarHandle(AbstractSubscription.class, "state", int.class);
      DEMAND = lookup.findVarHandle(AbstractSubscription.class, "demand", long.class);
      PENDING_ERROR =
          lookup.findVarHandle(AbstractSubscription.class, "pendingError", Throwable.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Subscriber<? super T> downstream;
  private final Executor executor;
  private volatile int state;
  private volatile long demand;
  private volatile @Nullable Throwable pendingError;

  protected AbstractSubscription(Subscriber<? super T> downstream, Executor executor) {
    this.downstream = downstream;
    this.executor = executor;
  }

  @Override
  public final void request(long n) {
    if (n > 0 && getAndAddDemand(this, DEMAND, n) == 0) {
      signal();
    } else if (n <= 0) {
      signalError(FlowSupport.illegalRequest());
    }
  }

  @Override
  public final void cancel() {
    if ((getAndBitwiseOrState(CANCELLED) & CANCELLED) == 0) {
      abort(true);
    }
  }

  /** Schedules a signaller task. {@code force} tells whether to schedule in case of no demand */
  public final void signal(boolean force) {
    if (force || demand > 0) {
      signal();
    }
  }

  public final void signalError(Throwable error) {
    propagateError(error);
    signal();
  }

  /**
   * Main method for item emission. At most {@code e} items are emitted to the downstream using
   * {@link #submitOnNext(Subscriber, Object)} as long as it returns {@code true}. The actual number
   * of emitted items is returned, may be {@code 0} in case of cancellation. If the underlying
   * source is finished, the subscriber is completed with {@link #cancelOnComplete(Subscriber)}.
   */
  protected abstract long emit(Subscriber<? super T> downstream, long emit);

  /**
   * Called when the subscription is cancelled. {@code flowInterrupted} specifies whether
   * cancellation was due to ending the normal flow of signals (signal|signalError) or due to flow
   * interruption by downstream (e.g. calling {@code cancel()} or throwing from {@code onNext}).
   */
  protected void abort(boolean flowInterrupted) {}

  /** Returns {@code true} if cancelled. {@code false} result is immediately outdated. */
  protected final boolean isCancelled() {
    return (state & CANCELLED) != 0;
  }

  /**
   * Returns {@code true} if the subscriber is to be completed exceptionally. {@code false} result
   * is immediately outdated. Can be used by implementation to halt producing items in case the
   * subscription was asynchronously signalled with an error.
   */
  protected final boolean hasPendingErrors() {
    return pendingError != null;
  }

  // TODO: catch and log throwable from onError|onComplete instead of rethrowing?

  /**
   * Calls downstream's {@code onError} after cancelling this subscription. {@code flowInterrupted}
   * tells whether the error interrupted the normal flow of signals.
   */
  protected final void cancelOnError(
      Subscriber<? super T> downstream, Throwable error, boolean flowInterrupted) {
    if ((getAndBitwiseOrState(CANCELLED) & CANCELLED) == 0) {
      try {
        downstream.onError(error);
      } finally {
        abort(flowInterrupted);
      }
    }
  }

  /** Calls downstream's {@code onComplete} after cancelling this subscription. */
  protected final void cancelOnComplete(Subscriber<? super T> downstream) {
    if ((getAndBitwiseOrState(CANCELLED) & CANCELLED) == 0) {
      try {
        downstream.onComplete();
      } finally {
        abort(false);
      }
    }
  }

  /** Submits given item to the downstream, returning {@code false} and cancelling on failure. */
  protected final boolean submitOnNext(Subscriber<? super T> downstream, T item) {
    if (!(isCancelled() || hasPendingErrors())) {
      try {
        downstream.onNext(item);
        return true;
      } catch (Throwable t) {
        Throwable error = propagateError(t);
        pendingError = null;
        cancelOnError(downstream, error, true);
      }
    }
    return false;
  }

  private void signal() {
    boolean casSucceeded = false;
    for (int s; !casSucceeded && ((s = state) & CANCELLED) == 0; ) {
      int setBit = (s & RUN) != 0 ? KEEP_ALIVE : RUN; // try to keep alive or run & execute
      casSucceeded = STATE.compareAndSet(this, s, s | setBit);
      if (casSucceeded && setBit == RUN) {
        try {
          executor.execute(this::run);
        } catch (RuntimeException | Error e) {
          // this is a problem because we cannot call any of onXXXX methods here
          // as that would ruin the execution context guarantee. SubmissionPublisher's
          // behaviour here is followed (cancel & rethrow).
          cancel();
          throw e;
        }
      }
    }
  }

  private void run() {
    int s;
    Subscriber<? super T> d = downstream;
    subscribeOnDrain(d);
    for (long x = 0L, r = demand; ((s = state) & CANCELLED) == 0; ) {
      long emitted;
      Throwable error = pendingError;
      if (error != null) {
        pendingError = null;
        cancelOnError(d, error, false);
      } else if ((emitted = emit(d, r - x)) > 0L) {
        x += emitted;
        r = demand; // get fresh demand
        if (x == r) { // 'x' needs to be flushed
          r = subtractAndGetDemand(this, DEMAND, x);
          x = 0L;
        }
      } else if (r == (r = demand)) { // check that emit() actually failed on a fresh demand
        // un keep-alive or kill task if a dead-end is reached, which is possible if:
        // - there is no active emission (x <= 0)
        // - there is no active demand (r <= 0 after flushing x)
        // - cancelled (checked by the loop condition)
        boolean exhausted = x <= 0L;
        if (!exhausted) {
          r = subtractAndGetDemand(this, DEMAND, x);
          x = 0L;
          exhausted = r <= 0L;
        }
        int unsetBit = (s & KEEP_ALIVE) != 0 ? KEEP_ALIVE : RUN;
        if (exhausted && STATE.compareAndSet(this, s, s & ~unsetBit) && unsetBit == RUN) {
          break;
        }
      }
    }
  }

  private void subscribeOnDrain(Subscriber<? super T> downstream) {
    if ((state & (SUBSCRIBED | CANCELLED)) == 0
        && (getAndBitwiseOrState(SUBSCRIBED) & (SUBSCRIBED | CANCELLED)) == 0) {
      try {
        downstream.onSubscribe(this);
      } catch (Throwable t) {
        Throwable e = propagateError(t);
        pendingError = null;
        cancelOnError(downstream, e, true);
      }
    }
  }

  /** Sets pending error or adds new one as suppressed in case of multiple error sources. */
  private Throwable propagateError(Throwable error) {
    while(true) {
      Throwable currentError = pendingError;
      if (currentError != null) {
        currentError.addSuppressed(error); // addSuppressed is thread-safe
        return currentError;
      }
      if (PENDING_ERROR.compareAndSet(this, null, error)) {
        return error;
      }
    }
  }

  private int getAndBitwiseOrState(int bits) {
    return (int) STATE.getAndBitwiseOr(this, bits);
  }
}
