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

import static java.util.Objects.requireNonNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.stream.Collectors;

/** Helpers for implementing reactive streams subscriptions and the like. */
public class FlowSupport {
  private static final Logger logger = System.getLogger(FlowSupport.class.getName());

  static final String PREFETCH_PROP = "com.github.mizosoft.methanol.flow.prefetch";
  static final String PREFETCH_FACTOR_PROP = "com.github.mizosoft.methanol.flow.prefetchFactor";

  /** Have a humble number as we're dealing with 16Kb buffers. */
  static final int DEFAULT_PREFETCH = 8;

  static final int DEFAULT_PREFETCH_FACTOR = 50; // Request more when half are consumed.

  private static final int PREFETCH = loadPrefetch();
  private static final int PREFETCH_THRESHOLD = (int) (PREFETCH * (loadPrefetchFactor() / 100f));

  private static final int DROPPED_EXCEPTION_STACK_TRACE_LIMIT = 10;

  /** A subscription that does nothing. */
  public static final Flow.Subscription NOOP_SUBSCRIPTION =
      new Flow.Subscription() {
        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
      };

  private static final Publisher<?> EMPTY_PUBLISHER =
      (Publisher<Object>)
          subscriber -> {
            requireNonNull(subscriber);
            try {
              subscriber.onSubscribe(NOOP_SUBSCRIPTION);
            } catch (Throwable t) {
              subscriber.onError(t);
              return;
            }
            subscriber.onComplete();
          };

  /** An executor that executes the runnable in the calling thread. */
  public static final Executor SYNC_EXECUTOR = SyncExecutor.INSTANCE;

  private enum SyncExecutor implements Executor {
    INSTANCE;

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public String toString() {
      return SyncExecutor.class.getSimpleName();
    }
  }

  private FlowSupport() {} // non-instantiable

  static int loadPrefetch() {
    int prefetch = Integer.getInteger(PREFETCH_PROP, DEFAULT_PREFETCH);
    return prefetch > 0 ? prefetch : DEFAULT_PREFETCH;
  }

  static int loadPrefetchFactor() {
    int prefetchFactor = Integer.getInteger(PREFETCH_FACTOR_PROP, DEFAULT_PREFETCH_FACTOR);
    return (prefetchFactor >= 0 && prefetchFactor <= 100)
        ? prefetchFactor
        : DEFAULT_PREFETCH_FACTOR;
  }

  /**
   * Returns an {@code IllegalArgumentException} to signal if the subscriber requests a non-positive
   * number of items.
   */
  public static IllegalArgumentException illegalRequest() {
    return new IllegalArgumentException("non-positive subscription request");
  }

  /** Returns the prefetch property or a default of {@value DEFAULT_PREFETCH}. */
  public static int prefetch() {
    return PREFETCH;
  }

  /**
   * Returns the prefetch threshold according to the prefetch factor property or a default of
   * {@value DEFAULT_PREFETCH} {@code / 2}.
   */
  public static int prefetchThreshold() {
    return PREFETCH_THRESHOLD;
  }

  /** Adds the given count to demand, making sure it doesn't exceed {@code Long.MAX_VALUE}. */
  public static long getAndAddDemand(Object owner, VarHandle demand, long n) {
    while (true) {
      long currentDemand = (long) demand.getVolatile(owner);
      long updatedDemand = currentDemand + n;
      if (updatedDemand < 0) { // overflow
        updatedDemand = Long.MAX_VALUE;
      }
      if (demand.compareAndSet(owner, currentDemand, updatedDemand)) {
        return currentDemand;
      }
    }
  }

  /** Subtracts given count from demand. Caller must ensure the result won't be negative. */
  public static long subtractAndGetDemand(Object owner, VarHandle demand, long n) {
    return (long) demand.getAndAdd(owner, -n) - n;
  }

  @SuppressWarnings("unchecked")
  public static <T> Publisher<T> emptyPublisher() {
    return (Publisher<T>) EMPTY_PUBLISHER;
  }

  public static void rejectMulticast(Subscriber<?> subscriber) {
    reject(subscriber, new IllegalStateException("Multiple subscribers not supported"));
  }

  public static void reject(Subscriber<?> subscriber, Throwable cause) {
    try {
      subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    } catch (Throwable t) {
      cause.addSuppressed(t);
    } finally {
      subscriber.onError(cause);
    }
  }

  // TODO allow the user to install a hook instead of always logging

  public static void onDroppedException(Throwable exception) {
    if (logger.isLoggable(Level.WARNING)) {
      logger.log(
          Level.WARNING,
          () ->
              "Dropped exception: "
                  + System.lineSeparator()
                  + "\tat "
                  + StackWalker.getInstance()
                      .walk(
                          frames ->
                              frames
                                  .limit(DROPPED_EXCEPTION_STACK_TRACE_LIMIT)
                                  .map(StackWalker.StackFrame::toString)
                                  .collect(Collectors.joining(System.lineSeparator() + "\tat "))),
          exception);
    }
  }
}
