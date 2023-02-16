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

/*
 * This code is adapted from https://githubcom/openjdk/jdk/blob/36e5ad61e63e2f1da9cf565c607db28f23622ea9/test/jdk/java/util/concurrent/tck/SubmissionPublisherTest.java#L67.
 * The source file contained the following licenses.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea and Martin Buchholz with assistance from
 * members of JCP JSR-166 Expert Group and released to the public
 * domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package com.github.mizosoft.methanol.testing;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@code Subscriber} implementation that facilitates testing {@code Publisher} implementations
 * and the like.
 */
public class TestSubscriber<T> implements Subscriber<T> {
  public final Deque<T> items = new ArrayDeque<>();

  public volatile int nextCount;
  public volatile int errorCount;
  public volatile int completionCount;
  public volatile long request = 1L;
  public volatile @MonotonicNonNull Subscription subscription;
  public volatile @MonotonicNonNull Throwable lastError;
  public volatile boolean throwOnCall;
  private volatile int pendingNextCount;

  public TestSubscriber() {}

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
    items.addLast(item);
    nextCount++;
    pendingNextCount++;
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
    errorCount++;
    lastError = throwable;
    notifyAll();
  }

  @Override
  public synchronized void onComplete() {
    completionCount++;
    notifyAll();
  }

  public synchronized Subscription awaitOnSubscribe() {
    while (subscription == null) {
      try {
        wait();
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }
    return subscription;
  }

  public synchronized void awaitOnNext(int n) {
    while (nextCount < n) {
      try {
        wait();
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }
  }

  public synchronized T awaitNextItem() {
    while (pendingNextCount <= 0) {
      try {
        wait();
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
    pendingNextCount--;
    return items.peekLast();
  }

  public synchronized void awaitComplete() {
    while (completionCount == 0 && errorCount == 0) {
      try {
        wait();
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }
  }

  public synchronized void awaitError() {
    while (errorCount == 0) {
      try {
        wait();
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }
  }
}
