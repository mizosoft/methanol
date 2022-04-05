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

import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.CACHED_POOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.TimeoutSubscriber;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
  void timeoutOnSecondItem() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    var upstreamSubscription = new RecordingSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);
    assertThat(downstream.subscription).isNotNull();

    // No timeouts are scheduled unless items are requested
    assertThat(delayer.taskCount()).isZero();

    downstream.subscription.request(2);
    assertThat(upstreamSubscription.request).isEqualTo(2);
    assertThat(delayer.taskCount()).isOne();

    // Receive first item within time
    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
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
    timeoutSubscriber.onNext(1);
    timeoutSubscriber.onComplete();
    assertThat(downstream.nexts).isOne(); // Item after timeout isn't received
    assertThat(downstream.errors).isOne();
    assertThat(downstream.completes).isZero();
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);
    assertThat(upstreamSubscription.cancelled).isTrue();

    // Timeout occurs at second item (index starts at 0)
    assertThat(timeoutSubscriber.timeoutIndex).isEqualTo(1);
  }

  @Test
  void timeoutOnFirstItem() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    var upstreamSubscription = new RecordingSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);
    assertThat(downstream.subscription).isNotNull();

    downstream.subscription.request(1);
    assertThat(upstreamSubscription.request).isOne();
    assertThat(delayer.taskCount()).isOne();

    // Trigger timeout
    clock.advanceSeconds(1);
    assertThat(downstream.nexts).isZero();
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);
    assertThat(upstreamSubscription.cancelled).isTrue();
    assertThat(timeoutSubscriber.timeoutIndex).isEqualTo(0);

    // Further signals are ignored
    timeoutSubscriber.onNext(1);
    timeoutSubscriber.onComplete();
    assertThat(downstream.nexts).isZero();
    assertThat(downstream.completes).isZero();
  }

  @Test
  void timeoutBeforeOnComplete() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();

    // A timeout is scheduled for this request
    downstream.subscription.request(3);
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
    assertThat(downstream.items).last().isEqualTo(1);

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(2);
    assertThat(downstream.items).last().isEqualTo(2);

    // Trigger timeout
    clock.advanceSeconds(2);
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);
    assertThat(timeoutSubscriber.timeoutIndex).isEqualTo(2);

    // Further signals are ignored
    timeoutSubscriber.onComplete();
    assertThat(downstream.completes).isZero();
  }

  @Test
  void timeoutBeforeOnError() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();

    downstream.subscription.request(3);
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
    assertThat(downstream.items).last().isEqualTo(1);

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(2);
    assertThat(downstream.items).last().isEqualTo(2);

    // Trigger timeout
    clock.advanceSeconds(2);
    assertThat(downstream.errors).isOne();
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);
    assertThat(timeoutSubscriber.timeoutIndex).isEqualTo(2);

    // Further signals are ignored
    timeoutSubscriber.onError(new TestException());
    assertThat(downstream.errors).isOne();
  }

  @Test
  void timeoutAfterOnError() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(2), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();

    downstream.subscription.request(2);
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(1);
    timeoutSubscriber.onNext(1);
    assertThat(downstream.items).last().isEqualTo(1);

    timeoutSubscriber.onError(new TestException());
    assertThat(downstream.lastError).isInstanceOf(TestException.class);
    assertThat(delayer.peekLatestTaskFuture()).isCancelled();

    // Trigger timeout, which is ignored
    clock.advanceSeconds(2);
    assertThat(delayer.taskCount()).isZero();
    assertThat(downstream.errors).isOne();
  }

  @Test
  void schedulingTimeouts() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();
    assertThat(delayer.taskCount()).isZero();

    // Demand was == 0 -> schedule
    downstream.subscription.request(1);
    assertThat(delayer.taskCount()).isOne();

    // Demand was > 0 -> don't schedule
    downstream.subscription.request(1);
    assertThat(delayer.taskCount()).isOne();

    // Demand becomes > 0 -> schedule
    timeoutSubscriber.onNext(1);
    assertThat(delayer.taskCount()).isEqualTo(2);

    // Demand becomes == 0 -> don't schedule
    timeoutSubscriber.onNext(1);
    assertThat(delayer.taskCount()).isEqualTo(2);

    // Demand was == 0 -> schedule
    downstream.subscription.request(1);
    assertThat(delayer.taskCount()).isEqualTo(3);
  }

  @Test
  void timeoutAfterCancellation() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.subscription).isNotNull();

    downstream.subscription.request(1);
    assertThat(delayer.taskCount()).isOne();

    downstream.subscription.cancel();
    assertThat(delayer.peekEarliestTaskFuture()).isCancelled();

    // Trigger timeout for first item
    clock.advanceSeconds(2);
    assertThat(delayer.taskCount()).isZero();
    assertThat(downstream.nexts).isZero();
    assertThat(downstream.completes).isZero();
    assertThat(downstream.errors).isZero();
  }

  @Test
  void timeoutRejectionByRequest() {
    Delayer rejectingDelayer =
        (task, timeout, executor) -> {
          throw new RejectedExecutionException();
        };
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), rejectingDelayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    var upstreamSubscription = new RecordingSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);
    assertThat(downstream.subscription).isNotNull();

    // Schedule a new timeout for the 1st item, which is rejected
    assertThatThrownBy(() -> downstream.subscription.request(2))
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(upstreamSubscription.cancelled).isTrue();
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
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    var upstreamSubscription = new RecordingSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);
    assertThat(downstream.subscription).isNotNull();

    downstream.subscription.request(2);
    assertThat(upstreamSubscription.request).isEqualTo(2);
    assertThat(delayCount).hasValue(1);

    // Schedule a new timeout for the 2nd item, which is rejected
    timeoutSubscriber.onNext(1);
    assertThat(delayCount).hasValue(2);
    assertThat(downstream.lastError).isInstanceOf(RejectedExecutionException.class);
    assertThat(upstreamSubscription.cancelled).isTrue();
  }

  @Test
  void overflowRequestByUpstream() {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    var upstreamSubscription = new RecordingSubscription();
    timeoutSubscriber.onSubscribe(upstreamSubscription);
    downstream.subscription.request(2);
    timeoutSubscriber.onNext(1);
    timeoutSubscriber.onNext(2);
    assertThat(downstream.items).containsExactly(1, 2);

    // Unrequested item is rejected & causes the subscriber to complete exceptionally
    timeoutSubscriber.onNext(3);
    assertThat(downstream.items).containsExactly(1, 2);
    assertThat(downstream.lastError).isInstanceOf(IllegalStateException.class);
    assertThat(upstreamSubscription.cancelled).isTrue();
  }

  @ExecutorParameterizedTest
  @ExecutorConfig(CACHED_POOL)
  void concurrentOnNext(Executor executor) {
    var timeoutSubscriber = new TestTimeoutSubscriber(Duration.ofSeconds(1), delayer);
    var downstream = timeoutSubscriber.downstream;
    downstream.request = 0;

    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);

    int itemCount = 100;
    downstream.subscription.request(itemCount + 1);
    CompletableFuture.allOf(
            IntStream.rangeClosed(1, itemCount)
                .mapToObj(
                    i -> CompletableFuture.runAsync(() -> timeoutSubscriber.onNext(i), executor))
                .toArray(CompletableFuture<?>[]::new))
        .join();

    assertThat(downstream.items)
        .containsExactlyInAnyOrderElementsOf(
            IntStream.rangeClosed(1, itemCount).boxed().collect(Collectors.toList()));

    // Trigger timeout on last requested item
    clock.advanceSeconds(1);
    assertThat(downstream.lastError).isInstanceOf(TimeoutException.class);
  }

  private static final class RecordingSubscription implements Subscription {
    volatile long request;
    volatile boolean cancelled;

    @Override
    public void request(long n) {
      request = n;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
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
