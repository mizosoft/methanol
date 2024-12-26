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

package com.github.mizosoft.methanol.internal.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.TimeoutSubscriber;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.TestSubscriberContext;
import com.github.mizosoft.methanol.testing.TestSubscriberExtension;
import com.github.mizosoft.methanol.testing.TestSubscription;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestSubscriberExtension.class)
class TimeoutSubscriberTest {
  private MockClock clock;
  private MockDelayer delayer;
  private TestSubscriberContext subscriberContext;

  @BeforeEach
  void setUp(TestSubscriberContext subscriberContext) {
    this.subscriberContext = subscriberContext;
    this.clock = new MockClock();
    this.delayer = new MockDelayer(clock);
  }

  @Test
  void timeoutOnSecondItem() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = timeoutSubscriber.delegate();
    var upstreamSubscription = new TestSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);

    // No timeouts are scheduled unless items are requested
    assertThat(delayer.taskCount()).isZero();

    downstream.requestItems(2);
    assertThat(upstreamSubscription.awaitRequest()).isEqualTo(2);
    assertThat(delayer.taskCount()).isOne();

    // Receive first item within time
    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
    assertThat(downstream.pollNext()).isEqualTo(1);
    assertThat(delayer.peekEarliestFuture()).isCancelled();
    assertThat(delayer.taskCount()).isEqualTo(2); // Timeout is scheduled for the second item

    // Trigger first timeout, which was cancelled & so is discarded
    clock.advanceSeconds(1);
    assertThat(delayer.taskCount()).isOne();
    assertThat(downstream.errorCount())
        .withFailMessage(() -> String.valueOf(downstream.awaitError()))
        .isZero();

    // Trigger timeout
    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
    timeoutSubscriber.onComplete();
    assertThat(downstream.nextCount()).isOne(); // Item after timeout isn't received
    assertThat(downstream.errorCount()).isOne();
    assertThat(downstream.completionCount()).isZero();
    assertThat(downstream.awaitError()).isInstanceOf(ItemTimeoutException.class);
    assertThat(upstreamSubscription.isCancelled()).isTrue();

    // Timeout occurs at second item (index starts at 1)
    assertThat(timeoutSubscriber.timeoutIndex).isEqualTo(2);
  }

  @Test
  void timeoutOnFirstItem() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.delegate();
    var upstreamSubscription = new TestSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);

    downstream.requestItems(1);
    assertThat(upstreamSubscription.awaitRequest()).isOne();
    assertThat(delayer.taskCount()).isOne();

    // Trigger timeout
    clock.advanceSeconds(1);
    assertThat(downstream.nextCount()).isZero();
    assertThat(downstream.awaitError()).isInstanceOf(ItemTimeoutException.class);
    assertThat(upstreamSubscription.isCancelled()).isTrue();
    assertThat(timeoutSubscriber.timeoutIndex).isEqualTo(1);

    // Further signals are ignored
    timeoutSubscriber.onNext(1);
    timeoutSubscriber.onComplete();
    assertThat(downstream.nextCount()).isZero();
    assertThat(downstream.completionCount()).isZero();
  }

  @Test
  void timeoutBeforeOnComplete() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = timeoutSubscriber.delegate();
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);

    // A timeout is scheduled for this request
    downstream.requestItems(3);
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
    assertThat(downstream.pollNext()).isEqualTo(1);

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(2);
    assertThat(downstream.pollNext()).isEqualTo(2);

    // Trigger timeout
    clock.advanceSeconds(2);
    assertThat(downstream.awaitError()).isInstanceOf(ItemTimeoutException.class);
    assertThat(timeoutSubscriber.timeoutIndex).isEqualTo(3);

    // Further signals are ignored
    timeoutSubscriber.onComplete();
    assertThat(downstream.completionCount()).isZero();
  }

  @Test
  void timeoutBeforeOnError() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = timeoutSubscriber.delegate();
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);

    downstream.requestItems(3);
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
    assertThat(downstream.pollNext()).isEqualTo(1);

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(2);
    assertThat(downstream.pollNext()).isEqualTo(2);

    // Trigger timeout
    clock.advanceSeconds(2);
    assertThat(downstream.errorCount()).isOne();
    assertThat(downstream.awaitError()).isInstanceOf(ItemTimeoutException.class);
    assertThat(timeoutSubscriber.timeoutIndex).isEqualTo(3);

    // Further signals are ignored
    timeoutSubscriber.onError(new TestException());
    assertThat(downstream.errorCount()).isOne();
  }

  @Test
  void timeoutAfterOnError() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = timeoutSubscriber.delegate();
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);

    downstream.requestItems(2);
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
    assertThat(downstream.pollNext()).isEqualTo(1);

    timeoutSubscriber.onError(new TestException());
    assertThat(downstream.awaitError()).isInstanceOf(TestException.class);
    assertThat(delayer.peekLatestFuture()).isCancelled();

    // Trigger timeout, which is ignored
    clock.advanceSeconds(2);
    assertThat(delayer.taskCount()).isZero();
    assertThat(downstream.errorCount()).isOne();
  }

  @Test
  void schedulingTimeouts() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.delegate();
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(delayer.taskCount()).isZero();

    // Demand was == 0 -> schedule
    downstream.requestItems(1);
    assertThat(delayer.taskCount()).isOne();

    // Demand was > 0 -> don't schedule
    downstream.requestItems(1);
    assertThat(delayer.taskCount()).isOne();

    // Demand becomes > 0 -> schedule
    timeoutSubscriber.onNext(1);
    assertThat(delayer.taskCount()).isEqualTo(2);

    // Demand becomes == 0 -> don't schedule
    timeoutSubscriber.onNext(1);
    assertThat(delayer.taskCount()).isEqualTo(2);

    // Demand was == 0 -> schedule
    downstream.requestItems(1);
    assertThat(delayer.taskCount()).isEqualTo(3);
  }

  @Test
  void timeoutAfterCancellation() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.delegate();
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);

    downstream.requestItems(1);
    assertThat(delayer.taskCount()).isOne();

    downstream.awaitSubscription().cancel();
    assertThat(delayer.peekEarliestFuture()).isCancelled();

    // Trigger timeout for first item
    clock.advanceSeconds(2);
    assertThat(delayer.taskCount()).isZero();
    assertThat(downstream.nextCount()).isZero();
    assertThat(downstream.completionCount()).isZero();
    assertThat(downstream.errorCount()).isZero();
  }

  @Test
  void timeoutRejectionByRequest() {
    Delayer rejectingDelayer =
        (task, timeout, executor) -> {
          throw new RejectedExecutionException();
        };
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), rejectingDelayer);
    var downstream = timeoutSubscriber.delegate();
    var upstreamSubscription = new TestSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);

    // Schedule a new timeout for the 1st item, which is rejected
    assertThatThrownBy(() -> downstream.requestItems(2))
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(upstreamSubscription.isCancelled()).isTrue();
  }

  @Test
  void timeoutRejectionByOnNext() {
    int allowedDelays = 1;
    var delayCount = new AtomicInteger();
    Delayer rejectingDelayer =
        (task, timeout, executor) -> {
          if (delayCount.incrementAndGet() > allowedDelays) {
            throw new RejectedExecutionException();
          }
          return this.delayer.delay(task, timeout, executor);
        };
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), rejectingDelayer);
    var downstream = timeoutSubscriber.delegate();
    var upstreamSubscription = new TestSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);

    downstream.requestItems(2);
    assertThat(upstreamSubscription.awaitRequest()).isEqualTo(2);
    assertThat(delayCount).hasValue(1);

    // Schedule a new timeout for the 2nd item, which is rejected
    timeoutSubscriber.onNext(1);
    assertThat(delayCount).hasValue(2);
    assertThat(downstream.awaitError()).isInstanceOf(RejectedExecutionException.class);
    assertThat(upstreamSubscription.isCancelled()).isTrue();
  }

  @Test
  void moreOnNextThanRequested() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.delegate();
    var upstreamSubscription = new TestSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);

    downstream.requestItems(2);

    timeoutSubscriber.onNext(1);
    timeoutSubscriber.onNext(2);
    assertThat(downstream.pollNext(2)).containsExactly(1, 2);

    timeoutSubscriber.onNext(3);
    assertThat(downstream.awaitError()).isInstanceOf(IllegalStateException.class);
    assertThat(upstreamSubscription.isCancelled()).isTrue();
    assertThat(delayer.peekLatestFuture()).isCancelled();
  }

  private static class ItemTimeoutException extends Exception {}

  private final class TestTimeoutSubscriber
      extends TimeoutSubscriber<Integer, TestSubscriber<Integer>> {
    volatile long timeoutIndex;

    TestTimeoutSubscriber(Duration timeout, Delayer delayer) {
      super(
          subscriberContext.<Integer>createSubscriber().autoRequest(0), // Request manually.
          timeout,
          delayer);
    }

    @Override
    protected TestSubscriber<Integer> delegate() {
      return super.delegate();
    }

    @Override
    protected Exception timeoutError(long index, Duration timeout) {
      timeoutIndex = index;
      return new ItemTimeoutException();
    }
  }
}
