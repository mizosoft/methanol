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

package com.github.mizosoft.methanol.testing;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestSubscription implements Subscription {
  private static final int TIMEOUT_SECONDS = 2;

  private final BlockingQueue<Long> requests = new LinkedBlockingQueue<>();
  private final CountDownLatch cancelled = new CountDownLatch(1);
  private final AtomicInteger requestCount = new AtomicInteger();
  private final AtomicInteger cancellationCount = new AtomicInteger();

  @Override
  public void request(long n) {
    requestCount.incrementAndGet();
    requests.add(n);
  }

  @Override
  public void cancel() {
    cancellationCount.incrementAndGet();
    cancelled.countDown();
  }

  public long awaitRequest() {
    try {
      var request = requests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(request)
          .withFailMessage(() -> "expected a request within " + TIMEOUT_SECONDS + " seconds")
          .isNotNull();
      return castNonNull(request);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void awaitCancellation() {
    try {
      assertThat(cancelled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .withFailMessage(() -> "expected cancellation within " + TIMEOUT_SECONDS + " seconds")
          .isTrue();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public boolean isCancelled() {
    return cancellationCount() > 0;
  }

  public int cancellationCount() {
    return cancellationCount.get();
  }

  public int requestCount() {
    return requestCount.get();
  }
}
