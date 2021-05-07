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

package com.github.mizosoft.methanol.testutils;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Facilitates testing Publisher implementations and the like. Adapted from
 * https://github.com/openjdk/jdk/blob/36e5ad61e63e2f1da9cf565c607db28f23622ea9/test/jdk/java/util/concurrent/tck/SubmissionPublisherTest.java#L67
 */
public class TestSubscriber<T> implements Subscriber<T> {
  public volatile int nexts;
  public volatile int errors;
  public volatile int completes;
  public volatile long request = 1L;
  public volatile @MonotonicNonNull Subscription subscription;
  public volatile @MonotonicNonNull Throwable lastError;
  public volatile boolean throwOnCall;
  public final Deque<T> items = new ArrayDeque<>();

  @Override
  public synchronized void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (this.subscription != null) {
      throw new AssertionError("my subscription is not null");
    }
    this.subscription = subscription;
    notifyAll();
    if (throwOnCall) {
      throw new TestException();
    }
    if (request != 0L) {
      subscription.request(request);
    }
  }

  @Override
  public synchronized void onNext(T item) {
    requireNonNull(item);
    nexts++;
    items.addLast(item);
    notifyAll();
    if (throwOnCall) {
      throw new TestException();
    }
    if (request != 0L) {
      subscription.request(request);
    }
  }

  @Override
  public synchronized void onError(Throwable throwable) {
    requireNonNull(throwable);
    errors++;
    lastError = throwable;
    notifyAll();
  }

  @Override
  public synchronized void onComplete() {
    completes++;
    notifyAll();
  }

  public synchronized void awaitSubscribe() {
    while (subscription == null) {
      try {
        wait();
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }
  }

  public synchronized void awaitNext(int n) {
    while (nexts < n) {
      try {
        wait();
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }
  }

  public synchronized void awaitComplete() {
    while (completes == 0 && errors == 0) {
      try {
        wait();
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }
  }

  public synchronized void awaitError() {
    while (errors == 0) {
      try {
        wait();
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }
  }
}
