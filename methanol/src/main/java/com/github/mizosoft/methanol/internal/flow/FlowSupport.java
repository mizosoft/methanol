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

import static java.util.Objects.requireNonNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;

/** Helpers for implementing reactive streams subscriptions and the like. */
public class FlowSupport {
  private static final Logger logger = System.getLogger(FlowSupport.class.getName());

  private static final String PREFETCH_PROP = "com.github.mizosoft.methanol.flow.prefetch";
  private static final String PREFETCH_FACTOR_PROP =
      "com.github.mizosoft.methanol.flow.prefetchFactor";

  // The value is small because usage is normally with ByteBuffer items, which already
  // take non-trivial space (the HTTP-client allocates 16Kb sizes). So using
  // Flow.defaultBufferSize() (256) with such sizes would require 4Mb of space!
  private static final int DEFAULT_PREFETCH = 16;
  // Request more when half consumed
  private static final int DEFAULT_PREFETCH_FACTOR = 50;

  private static final int PREFETCH = loadPrefetch();
  private static final int PREFETCH_THRESHOLD = (int) (PREFETCH * (loadPrefetchFactor() / 100f));

  /** A subscription that does nothing */
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
  public static final Executor SYNC_EXECUTOR = Runnable::run;

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

  /** Adds the given count to demand making sure it doesn't exceed {@code Long.MAX_VALUE}. */
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

  /** Subtracts given count from demand. Caller ensures result won't be negative. */
  public static long subtractAndGetDemand(Object owner, VarHandle demand, long n) {
    return (long) demand.getAndAdd(owner, -n) - n;
  }

  @SuppressWarnings("unchecked")
  public static <T> Publisher<T> emptyPublisher() {
    return (Publisher<T>) EMPTY_PUBLISHER;
  }

  public static IllegalStateException multipleSubscribersToUnicast() {
    return new IllegalStateException("multiple subscribers to a unicast publisher");
  }

  public static void refuse(Subscriber<?> subscriber, Throwable error) {
    try {
      subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    } catch (Throwable t) {
      error.addSuppressed(t);
    } finally {
      subscriber.onError(error);
    }
  }

  public static void onDroppedError(Throwable error) {
    // TODO allow the user to install a hook instead of always logging
    logger.log(Level.WARNING, "the following error was dropped", error);
  }
}
