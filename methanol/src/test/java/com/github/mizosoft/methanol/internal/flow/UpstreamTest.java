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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Flow.Subscription;
import org.junit.jupiter.api.Test;

class UpstreamTest {
  @Test
  void setOrCancel() {
    var upstream = new Upstream();
    assertThat(upstream.setOrCancel(new TestSubscription())).isTrue();

    var secondSubscription = new TestSubscription();
    assertThat(upstream.setOrCancel(secondSubscription)).isFalse();
    assertThat(secondSubscription.cancelCount).isOne();
  }

  @Test
  void setOrCancelCleared() {
    var subscription = new TestSubscription();
    var upstream = new Upstream();
    upstream.clear();
    assertThat(upstream.setOrCancel(subscription)).isFalse();
    assertThat(subscription.cancelCount).isOne();
  }

  @Test
  void setOrCancelCancelled() {
    var subscription = new TestSubscription();
    var upstream = new Upstream();
    upstream.cancel();
    assertThat(upstream.setOrCancel(subscription)).isFalse();
    assertThat(subscription.cancelCount).isOne();
  }

  @Test
  void clear() {
    var subscription = new TestSubscription();
    var upstream = new Upstream();
    assertTrue(upstream.setOrCancel(subscription));
    upstream.clear();
    assertThat(subscription.cancelCount).isZero();
  }

  @Test
  void cancel() {
    var subscription = new TestSubscription();
    var upstream = new Upstream();
    assertTrue(upstream.setOrCancel(subscription));
    upstream.cancel();
    assertThat(subscription.cancelCount).isOne();
  }

  @Test
  void request() {
    var subscription = new TestSubscription();
    var upstream = new Upstream();
    upstream.request(1);
    upstream.setOrCancel(subscription);
    upstream.request(5);
    upstream.cancel();
    upstream.request(1);
    assertThat(subscription.cancelCount).isOne();
    assertThat(subscription.demands).hasSize(1).first().isEqualTo(5L);
  }

  private static final class TestSubscription implements Subscription {
    private int cancelCount;
    private final Queue<Long> demands = new LinkedList<>();

    TestSubscription() {}

    @Override
    public void request(long n) {
      demands.offer(n);
    }

    @Override
    public void cancel() {
      cancelCount++;
    }
  }
}
