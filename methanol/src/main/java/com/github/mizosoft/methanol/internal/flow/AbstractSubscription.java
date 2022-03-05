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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.flow.FlowSupport.getAndAddDemand;
import static com.github.mizosoft.methanol.internal.flow.FlowSupport.subtractAndGetDemand;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
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
@SuppressWarnings("unused") // VarHandle indirection
public abstract class AbstractSubscription<T> implements Subscription {
  private static final Logger logger = System.getLogger(AbstractSubscription.class.getName());

  /*
   * Implementation is loosely modeled after SubmissionPublisher$BufferedSubscription, mainly
   * regarding how execution is controlled by CASes on a state field that manipulate bits
   * representing execution states.
   */

  /** Signaller task is running or about to run. */
  private static final int RUNNING = 0x1;

  /** Signaller task should keep running to process recently arrived signals. */
  private static final int KEEP_ALIVE = 0x2;

  /** Downstream's onSubscribe has been invoked. */
  private static final int SUBSCRIBED = 0x4;

  /** The subscription has a pending error that should be passed downstream if not done yet. */
  private static final int ERROR = 0x8;

  /** The subscription is cancelled. */
  private static final int CANCELLED = 0x10;

  private static final VarHandle STATE;
  private static final VarHandle PENDING_ERROR;
  private static final VarHandle DEMAND;

  static {
    var lookup = MethodHandles.lookup();
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
    // Only try to signal on previously exhausted (zero) demand. This is to not fire the signaller
    // needlessly and call emit(...) knowing there's demand residue that it couldn't previously
    // satisfy (i.e. it ran out of items). Note that this assumes emit(...) exhausts its source and
    // doesn't, for instance, selectively choose to reject demand despite having 'items'. Otherwise,
    // it would never be called unless signal() here is called regardless of demand (or some other
    // part calls signal(boolean)), but this is not how current use cases go. In addition to the
    // first assumption, it's also assumed that signal(boolean) is called when items later become
    // available or arrive from another producer, so missed demand will be satisfied.
    if (n > 0 && getAndAddDemand(this, DEMAND, n) == 0) {
      signal();
    } else if (n <= 0) {
      signalError(FlowSupport.illegalRequest());
    }
  }

  @Override
  public final void cancel() {
    if ((getAndBitwiseOrState(CANCELLED) & CANCELLED) == 0) {
      guardedAbort(true);
    }
  }

  /** Schedules a signaller task. {@code force} tells whether to schedule in case of no demand. */
  public final void signal(boolean force) {
    if (force || demand > 0) {
      signal();
    }
  }

  public final void signalError(Throwable error) {
    recordError(error);
    signal();
  }

  /**
   * Main method for item emission. At most {@code e} items are emitted to the downstream using
   * {@link #submitOnNext(Subscriber, Object)} as long as it returns {@code true}. The actual number
   * of emitted items is returned, may be 0 in case of cancellation or if no items are emitted,
   * perhaps due to lack thereof, or if {@code emit} itself is zero. If the underlying source is
   * finished, the subscriber is completed with {@link #cancelOnComplete(Subscriber)}.
   */
  protected abstract long emit(Subscriber<? super T> downstream, long emit);

  /**
   * Called when the subscription is cancelled. {@code flowInterrupted} tells whether cancellation
   * was due to ending the normal flow of signals (signal|signalError) or due to flow interruption
   * by downstream (e.g. calling {@code cancel()} or throwing from {@code onNext}).
   */
  protected void abort(boolean flowInterrupted) {}

  private void guardedAbort(boolean flowInterrupted) {
    try {
      abort(flowInterrupted);
    } catch (Throwable t) {
      logger.log(Level.WARNING, "exception thrown during subscription cancellation", t);
    }
  }

  /**
   * Returns {@code true} if this subscription is cancelled. {@code false} result is immediately
   * outdated.
   */
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

  /**
   * Calls downstream's {@code onError} after cancelling this subscription. {@code flowInterrupted}
   * tells whether the error interrupted the normal flow of signals.
   */
  protected final void cancelOnError(
      Subscriber<? super T> downstream, Throwable error, boolean flowInterrupted) {
    if ((getAndBitwiseOrState(CANCELLED) & CANCELLED) == 0) {
      guardedAbort(flowInterrupted);
      try {
        downstream.onError(error);
      } catch (Throwable t) {
        logger.log(
            Level.WARNING, () -> "exception thrown by subscriber's onError: " + downstream, t);
      }
    }
  }

  /** Calls downstream's {@code onComplete} after cancelling this subscription. */
  protected final void cancelOnComplete(Subscriber<? super T> downstream) {
    if ((getAndBitwiseOrState(CANCELLED) & CANCELLED) == 0) {
      guardedAbort(false);
      try {
        downstream.onComplete();
      } catch (Throwable t) {
        logger.log(
            Level.WARNING, () -> "exception thrown by subscriber's onComplete: " + downstream, t);
      }
    }
  }

  /**
   * Submits given item to the downstream, returning {@code false} and cancelling on failure. {@code
   * false} is also returned if the subscription is cancelled or has pending errors. On such cases,
   * caller should stop emitting items.
   */
  protected final boolean submitOnNext(Subscriber<? super T> downstream, T item) {
    if (!(isCancelled() || hasPendingErrors())) {
      try {
        downstream.onNext(item);
        return true;
      } catch (Throwable t) {
        cancelOnError(downstream, recordError(t), true);
      }
    }
    return false;
  }

  private void signal() {
    for (int s; ((s = state) & CANCELLED) == 0; ) {
      int setBit = (s & RUNNING) != 0 ? KEEP_ALIVE : RUNNING; // Try to keep alive or run & execute
      if (STATE.compareAndSet(this, s, s | setBit)) {
        if (setBit == RUNNING) {
          try {
            executor.execute(this::run);
          } catch (RuntimeException | Error e) {
            // This is a problem because we cannot call any of onXXXX methods here
            // as that would ruin the execution context guarantee. SubmissionPublisher's
            // behaviour here is followed (cancel & rethrow).
            logger.log(Level.ERROR, "subscription couldn't execute its signaller task", e);
            cancel();
            throw e;
          }
        }
        break;
      }
    }
  }

  private void run() {
    int s;
    Subscriber<? super T> d = downstream;
    subscribeOnDrain(d);
    for (long x = 0L, r = demand; ((s = state) & CANCELLED) == 0; ) {
      long emitted;
      if ((s & ERROR) != 0) {
        cancelOnError(d, castNonNull(pendingError), false);
      } else if ((emitted = emit(d, r - x)) > 0L) {
        x += emitted;
        r = demand; // Get fresh demand
        if (x == r) { // 'x' needs to be flushed
          r = subtractAndGetDemand(this, DEMAND, x);
          x = 0L;
        }
      } else if (r == (r = demand)) { // Check that emit() actually failed on a fresh demand
        // Un keep-alive or kill task if a dead-end is reached, which is possible if:
        // - There is no active emission (x <= 0).
        // - There is no active demand (r <= 0 after flushing x).
        // - Cancelled (checked by the loop condition).
        boolean exhausted = x <= 0L;
        if (!exhausted) {
          r = subtractAndGetDemand(this, DEMAND, x);
          x = 0L;
          exhausted = r <= 0L;
        }

        if (exhausted) {
          int unsetBit = (s & KEEP_ALIVE) != 0 ? KEEP_ALIVE : RUNNING;
          if (STATE.compareAndSet(this, s, s & ~unsetBit) && unsetBit == RUNNING) {
            break;
          }
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
        cancelOnError(downstream, recordError(t), true);
      }
    }
  }

  /** Sets pending error or adds a new one as suppressed in case of multiple error sources. */
  @CanIgnoreReturnValue
  private Throwable recordError(Throwable error) {
    while (true) {
      var currentPendingError = pendingError;
      if (currentPendingError != null) {
        currentPendingError.addSuppressed(error); // addSuppressed is thread-safe
        return currentPendingError;
      } else if (PENDING_ERROR.compareAndSet(this, null, error)) {
        getAndBitwiseOrState(ERROR);
        return error;
      }
    }
  }

  private int getAndBitwiseOrState(int bits) {
    return (int) STATE.getAndBitwiseOr(this, bits);
  }

  protected long currentDemand() {
    return demand;
  }
}
