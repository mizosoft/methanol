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

package com.github.mizosoft.methanol.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.TestSubscription;
import org.junit.jupiter.api.Test;

class ForwardingSubscriberTest {
  @Test
  void forwardsBodyToDownstream() {
    var subscriber = new TestForwardingSubscriber();
    var downstream = subscriber.downstream();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertThat(downstream.awaitSubscription()).isSameAs(FlowSupport.NOOP_SUBSCRIPTION);

    subscriber.onNext(1);
    subscriber.onNext(2);
    subscriber.onNext(3);
    subscriber.onComplete();
    assertThat(downstream.completionCount()).isOne();
    assertThat(downstream.pollNext(3)).containsExactly(1, 2, 3);
  }

  @Test
  void forwardsErrorCompletion() {
    var subscriber = new TestForwardingSubscriber();
    var downstream = subscriber.downstream();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(1);
    subscriber.onNext(2);
    subscriber.onError(new TestException());
    assertThat(downstream.awaitError()).isInstanceOf(TestException.class);
    assertThat(downstream.nextCount()).isEqualTo(2);
  }

  @Test
  void moreThanOneCallToOnSubscribe() {
    var subscriber = new TestForwardingSubscriber();
    var subscription = new TestSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onSubscribe(subscription);
    subscriber.onSubscribe(subscription);
    assertThat(subscription.cancellationCount()).isEqualTo(2);
  }

  private static final class TestForwardingSubscriber extends ForwardingSubscriber<Integer> {
    private final TestSubscriber<Integer> downstream;

    TestForwardingSubscriber() {
      this.downstream = new TestSubscriber<>();
    }

    @Override
    protected TestSubscriber<Integer> downstream() {
      return downstream;
    }
  }
}
