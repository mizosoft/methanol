/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.TimeoutSubscriber;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.time.Duration;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimeoutSubscriberTest {
  private MockClock clock;
  private MockDelayer delayer;

  @BeforeEach
  void setUp() {
    clock = new MockClock();
    delayer = new MockDelayer(clock);
  }

  @Test
  void timeout() {
    var subscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = subscriber.downstream;
    downstream.request = 0;

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();

    // No timeouts are scheduled unless items are requested
    assertThat(delayer.taskCount()).isZero();

    downstream.subscription.request(2);
    assertThat(delayer.taskCount()).isOne();

    // Receive first item within time
    clock.advanceSeconds(1);
    subscriber.onNext(1);
    assertThat(downstream.items).last().isEqualTo(1);
    assertThat(delayer.peekEarliestTaskFuture()).isCancelled();
    assertThat(delayer.taskCount()).isEqualTo(2); // Timeout is scheduled for the second item

    // Trigger first timeout, which was cancelled & so is discarded
    clock.advanceSeconds(1);
    assertThat(delayer.taskCount()).isOne();
    assertThat(downstream.errors)
        .withFailMessage(() -> String.valueOf(downstream.lastError))
        .isZero();

    // Trigger timeout
    clock.advanceSeconds(1);
    subscriber.onNext(1);
    subscriber.onComplete();
    assertThat(downstream.nexts).isOne(); // Item after timeout isn't received
    assertThat(downstream.errors).isOne();
    assertThat(downstream.completes).isZero();
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);
    assertThat(subscriber.timeoutIndex).isEqualTo(1); // Index starts at 0
  }

  @Test
  void timeoutOnFirstItem() {
    var subscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = subscriber.downstream;
    downstream.request = 0;

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();
    downstream.subscription.request(1);

    // Trigger timeout
    clock.advanceSeconds(1);
    assertThat(downstream.nexts).isZero();
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);
    assertThat(subscriber.timeoutIndex).isEqualTo(0);

    // Further signals are ignored
    subscriber.onNext(1);
    subscriber.onComplete();
    assertThat(downstream.nexts).isZero();
    assertThat(downstream.completes).isZero();
  }

  @Test
  void timeoutBeforeOnComplete() {
    var subscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = subscriber.downstream;
    downstream.request = 0;

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();
    downstream.subscription.request(3);

    clock.advanceSeconds(1);
    subscriber.onNext(1);
    assertThat(downstream.items).last().isEqualTo(1);

    clock.advanceSeconds(1);
    subscriber.onNext(2);
    assertThat(downstream.items).last().isEqualTo(2);

    // Trigger timeout
    clock.advanceSeconds(2);
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);

    // Further signals are ignored
    subscriber.onComplete();
    assertThat(downstream.completes).isZero();
  }

  @Test
  void timeoutBeforeOnError() {
    var subscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = subscriber.downstream;
    downstream.request = 0;

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();
    downstream.subscription.request(3);

    clock.advanceSeconds(1);
    subscriber.onNext(1);
    assertThat(downstream.items).last().isEqualTo(1);

    clock.advanceSeconds(1);
    subscriber.onNext(2);
    assertThat(downstream.items).last().isEqualTo(2);

    // Trigger timeout
    clock.advanceSeconds(2);
    assertThat(downstream.errors).isOne();
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);

    // Further signals are ignored
    subscriber.onError(new TestException());
    assertThat(downstream.errors).isOne();
  }

  @Test
  void timeoutAfterOnError() {
    var subscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = subscriber.downstream;
    downstream.request = 0;

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();
    downstream.subscription.request(2);

    clock.advanceSeconds(1);
    subscriber.onNext(1);
    assertThat(downstream.items).last().isEqualTo(1);

    subscriber.onError(new TestException());
    assertThat(downstream.lastError).isInstanceOf(TestException.class);
    assertThat(delayer.peekLatestTaskFuture()).isCancelled();

    // Trigger timeout
    clock.advanceSeconds(2);
    assertThat(downstream.errors).isOne();
  }

  @Test
  void schedulingTimeouts() {
    var subscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = subscriber.downstream;
    downstream.request = 0;

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();
    assertThat(delayer.taskCount()).isZero();

    // Demand was == 0 -> schedule
    downstream.subscription.request(1);
    assertThat(delayer.taskCount()).isOne();

    // Demand was > 0 -> don't schedule
    downstream.subscription.request(1);
    assertThat(delayer.taskCount()).isOne();

    // Demand becomes > 0 -> schedule
    subscriber.onNext(1);
    assertThat(delayer.taskCount()).isEqualTo(2);

    // Demand becomes == 0 -> don't schedule
    subscriber.onNext(1);
    assertThat(delayer.taskCount()).isEqualTo(2);

    // Demand was == 0 -> schedule
    downstream.subscription.request(1);
    assertThat(delayer.taskCount()).isEqualTo(3);
  }

  @Test
  void timeoutAfterCancellation() {
    var subscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = subscriber.downstream;

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();
    assertThat(delayer.taskCount()).isOne();

    downstream.subscription.cancel();
    assertThat(delayer.peekEarliestTaskFuture()).isCancelled();

    // Trigger timeout for first item
    clock.advanceSeconds(2);
    assertThat(delayer.taskCount()).isZero();
    assertThat(downstream.errors).isZero();
    assertThat(downstream.errors).isZero();
    assertThat(downstream.errors).isZero();
  }

  private static final class TestTimeoutSubscriber extends TimeoutSubscriber<Integer> {
    final TestSubscriber<Integer> downstream = new TestSubscriber<>();

    volatile long timeoutIndex;

    TestTimeoutSubscriber(Duration timeout, Delayer delayer) {
      super(timeout, delayer);
    }

    @Override
    protected Subscriber<? super Integer> downstream() {
      return downstream;
    }

    @Override
    protected Throwable timeoutError(long index, Duration timeout) {
      timeoutIndex = index;
      return new TimeoutException();
    }
  }
}
