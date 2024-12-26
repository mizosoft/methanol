/*
 * Copyright (c) 2024 Moataz Hussein
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
import static java.util.Objects.requireNonNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An abstract {@link Subscription} that implements most of the machinery for execution and
 * backpressure control.
 */
@SuppressWarnings("unused") // VarHandle indirection.
public abstract class AbstractSubscription<T> implements Subscription {
  private static final Logger logger = System.getLogger(AbstractSubscription.class.getName());

  /** Consumer is running or about to run. */
  private static final int RUNNING = 0x1;

  /** Consumer must continue to process potentially missed signals. */
  private static final int KEEP_ALIVE = 0x2;

  /** Subscriber::onSubscribe has been invoked. */
  private static final int SUBSCRIBED = 0x4;

  /** There's a pending error that must be passed downstream if not cancelled. */
  private static final int ERROR = 0x8;

  /** The subscription is cancelled. */
  private static final int CANCELLED = 0x10;

  private static final VarHandle SYNC;
  private static final VarHandle PENDING_EXCEPTION;
  private static final VarHandle DEMAND;

  static {
    var lookup = MethodHandles.lookup();
    try {
      SYNC = lookup.findVarHandle(AbstractSubscription.class, "sync", int.class);
      DEMAND = lookup.findVarHandle(AbstractSubscription.class, "demand", long.class);
      PENDING_EXCEPTION =
          lookup.findVarHandle(AbstractSubscription.class, "pendingException", Throwable.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Subscriber<? super T> downstream;
  private final Executor executor;
  private volatile int sync;
  private volatile long demand;
  private volatile @MonotonicNonNull Throwable pendingException;

  protected AbstractSubscription(Subscriber<? super T> downstream, Executor executor) {
    this.downstream = requireNonNull(downstream);
    this.executor = requireNonNull(executor);
  }

  @Override
  public final void request(long n) {
    if (n > 0) {
      getAndAddDemand(this, DEMAND, n);
      fireOrKeepAlive();
    } else {
      fireOrKeepAliveOnError(FlowSupport.illegalRequest());
    }
  }

  @Override
  public final void cancel() {
    if ((getAndBitwiseOrSync(CANCELLED) & CANCELLED) == 0) {
      guardedAbort(true);
      consumePendingException();
    }
  }

  public void fireOrKeepAlive() {
    if ((sync & KEEP_ALIVE) == 0
        && (getAndBitwiseOrSync(RUNNING | KEEP_ALIVE) & (RUNNING | CANCELLED)) == 0) {
      fire();
    }
  }

  public void fireOrKeepAliveOnNext() {
    if (demand > 0) {
      fireOrKeepAlive();
    }
  }

  public void fireOrKeepAliveOnError(Throwable exception) {
    requireNonNull(exception);

    // Make sure exceptions are reported even if they can't be passed on downstream. This is
    // maintained by two sides: producer (this method) and consumer (terminal methods like cancel(),
    // cancelOnError(...) and cancelOnComplete(...)).
    boolean produced;
    if ((produced = PENDING_EXCEPTION.compareAndSet(this, null, exception))
        && (getAndBitwiseOrSync(RUNNING | KEEP_ALIVE | ERROR) & (RUNNING | CANCELLED)) == 0) {
      fire();
    } else if (!produced) {
      FlowSupport.onDroppedException(exception);
    }
  }

  private void fire() {
    try {
      executor.execute(this::drain);
    } catch (RuntimeException | Error e) {
      // This is a problem because we cannot call downstream here as that would ruin the execution
      // context guarantee. SubmissionPublisher's behaviour here is followed (cancel & rethrow).
      logger.log(Level.ERROR, "Couldn't execute subscription's signaller task", e);
      cancel();
      throw e;
    }
  }

  /**
   * Emits at most {@code emit} items to downstream using {@link #submitOnNext( Subscriber, Object)}
   * as long as it returns {@code true}. The actual number of emitted items is returned, may be 0 in
   * case of cancellation or if no items are emitted, perhaps due to lack thereof, or if {@code
   * emit} itself is zero. If the underlying source is finished, the subscriber is completed with
   * {@link #cancelOnComplete(Subscriber)}.
   */
  protected abstract long emit(Subscriber<? super T> downstream, long emit);

  private long guardedEmit(Subscriber<? super T> downstream, long emit) {
    try {
      return emit(downstream, emit);
    } catch (Throwable t) {
      cancelOnError(downstream, t, true);
      return -1;
    }
  }

  /**
   * Releases resources held by this subscription. {@code flowInterrupted} tells whether
   * cancellation was due to flow interruption by downstream (e.g. calling {@code cancel()} or
   * throwing from {@code onNext} or {@code onSubscribe}), or due to ending the normal flow of
   * signals (onSubscribe -> onNext* -> (onError | onComplete)).
   */
  protected void abort(boolean flowInterrupted) {}

  private void guardedAbort(boolean flowInterrupted) {
    try {
      abort(flowInterrupted);
    } catch (Throwable t) {
      logger.log(Level.WARNING, "Exception thrown during subscription cancellation", t);
    }
  }

  private void consumePendingException() {
    var exception =
        (Throwable) PENDING_EXCEPTION.getAndSet(this, ConsumedPendingException.INSTANCE);
    if (exception != null && exception != ConsumedPendingException.INSTANCE) {
      FlowSupport.onDroppedException(exception);
    }
  }

  /**
   * Returns {@code true} if this subscription is cancelled. {@code false} result is immediately
   * outdated. Can be used by implementation to halt producing items in case the subscription was
   * asynchronously cancelled.
   */
  protected final boolean isCancelled() {
    return (sync & CANCELLED) != 0;
  }

  /**
   * Returns {@code true} if the subscriber is to be completed exceptionally. {@code false} result
   * is immediately outdated. Can be used by implementation to halt producing items in case the
   * subscription was asynchronously signalled with an error.
   */
  protected final boolean hasPendingErrors() {
    return (sync & ERROR) != 0;
  }

  /**
   * Calls downstream's {@code onError} with the given exception after cancelling this subscription.
   * {@code flowInterrupted} tells whether the error interrupted the normal flow of signals.
   */
  protected final void cancelOnError(
      Subscriber<? super T> downstream, Throwable exception, boolean flowInterrupted) {
    if ((getAndBitwiseOrSync(CANCELLED) & CANCELLED) == 0) {
      guardedAbort(flowInterrupted);
      if (flowInterrupted) { // Otherwise drain() has already consumed the pending exception.
        consumePendingException();
      }

      try {
        downstream.onError(exception);
      } catch (Throwable t) {
        t.addSuppressed(exception);
        logger.log(Level.WARNING, "Exception thrown by subscriber's onError", t);
      }
    } else {
      FlowSupport.onDroppedException(exception);
    }
  }

  /** Calls downstream's {@code onComplete} after cancelling this subscription. */
  protected final void cancelOnComplete(Subscriber<? super T> downstream) {
    if ((getAndBitwiseOrSync(CANCELLED) & CANCELLED) == 0) {
      guardedAbort(false);
      consumePendingException();
      try {
        downstream.onComplete();
      } catch (Throwable t) {
        logger.log(
            Level.WARNING, () -> "Exception thrown by subscriber's onComplete: " + downstream, t);
      }
    }
  }

  /**
   * Submits given item to the downstream, returning {@code false} and cancelling on failure. {@code
   * false} is also returned if the subscription is already cancelled or has pending errors. On such
   * cases, caller should stop emitting items.
   */
  protected final boolean submitOnNext(Subscriber<? super T> downstream, T item) {
    if ((sync & (ERROR | CANCELLED)) == 0) {
      try {
        downstream.onNext(item);
        return true;
      } catch (Throwable t) {
        cancelOnError(downstream, t, true);
      }
    }
    return false;
  }

  private void drain() {
    int s;
    var d = downstream;
    subscribeOnDrain(d);
    for (long x = 0L, r = demand; ((s = sync) & CANCELLED) == 0; ) {
      long emitted;
      int unsetBit;
      if ((s & ERROR) != 0) {
        var exception =
            (Throwable) PENDING_EXCEPTION.getAndSet(this, ConsumedPendingException.INSTANCE);
        cancelOnError(d, castNonNull(exception), false);
      } else if ((emitted = guardedEmit(d, r - x)) > 0L) {
        x += emitted;
      } else if (emitted < 0) {
        return; // Emit failed and the subscriber is completed exceptionally.
      } else if (x > 0) {
        // Flush satisfied demand.
        r = subtractAndGetDemand(this, DEMAND, x);
        x = 0L;
      } else if (r == (r = demand) // Check no new demand has arrived.
          && SYNC.compareAndSet(
              this, s, s & ~(unsetBit = (s & KEEP_ALIVE) == 0 ? RUNNING : KEEP_ALIVE))
          && unsetBit == RUNNING) { // Exit or consume KEEP_ALIVE.
        return;
      }
    }
  }

  private void subscribeOnDrain(Subscriber<? super T> downstream) {
    if ((sync & SUBSCRIBED) == 0
        && (getAndBitwiseOrSync(SUBSCRIBED) & (SUBSCRIBED | CANCELLED)) == 0) {
      try {
        downstream.onSubscribe(this);
      } catch (Throwable t) {
        cancelOnError(downstream, t, true);
      }
    }
  }

  private int getAndBitwiseOrSync(int bits) {
    return (int) SYNC.getAndBitwiseOr(this, bits);
  }

  protected long currentDemand() {
    return demand;
  }

  private static final class ConsumedPendingException extends Exception {
    @SuppressWarnings("StaticAssignmentOfThrowable") // Not thrown, just used for CAS control.
    static final ConsumedPendingException INSTANCE = new ConsumedPendingException();

    private ConsumedPendingException() {
      super("", null, false, false);
    }
  }
}
