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

package com.github.mizosoft.methanol.internal.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import java.util.List;
import java.util.concurrent.Flow.Subscription;
import org.junit.jupiter.api.Test;

class ForwardingSubscriberTest {

  @Test
  void forwardsBodyToDownstream() {
    var subscriber = new TestForwardingSubscriber();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertSame(FlowSupport.NOOP_SUBSCRIPTION, subscriber.downstream.subscription);
    var seq = List.of(1, 2, 3);
    seq.forEach(subscriber::onNext);
    subscriber.onComplete();
    assertEquals(1, subscriber.downstream.completionCount);
    assertEquals(seq, List.copyOf(subscriber.downstream.items));
  }

  @Test
  void forwardsErrorCompletion() {
    var subscriber = new TestForwardingSubscriber();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(1);
    subscriber.onNext(2);
    subscriber.onError(new TestException());
    assertTrue(
        subscriber.downstream.lastError instanceof TestException,
        String.valueOf(subscriber.downstream.lastError));
    assertEquals(2, subscriber.downstream.nextCount);
  }

  @Test
  void callOnSubscribeTwice() {
    var subscriber = new TestForwardingSubscriber();
    var subscription = new Subscription() {
      int cancels;

      @Override public void request(long n) {}

      @Override
      public void cancel() {
        cancels++;
      }
    };
    subscriber.onSubscribe(subscription);
    subscriber.onSubscribe(subscription);
    subscriber.onSubscribe(subscription);
    assertEquals(2, subscription.cancels);
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
