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
import static org.assertj.core.api.Assertions.from;
import static org.assertj.core.api.InstanceOfAssertFactories.LONG;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow.Subscription;
import org.junit.jupiter.api.Test;

class PrefetcherTest {
  @Test
  void defaultConstructor() {
    assertThat(new Prefetcher())
        .returns(FlowSupport.prefetch(), from(Prefetcher::prefetch))
        .returns(FlowSupport.prefetchThreshold(), from(Prefetcher::prefetchThreshold));
  }

  @Test
  void initializeRequestsPrefetch() {
    var subscription = new RecordingSubscription();
    var upstream = new Upstream();
    assertThat(upstream.setOrCancel(subscription)).isTrue();

    var prefetcher = new Prefetcher(10, 5);
    prefetcher.initialize(upstream);
    assertThat(subscription.demands).hasSize(1).first(LONG).isEqualTo(10);
  }

  @Test
  void updateAfterThreshold() {
    var subscription = new RecordingSubscription();
    var upstream = new Upstream();
    assertThat(upstream.setOrCancel(subscription)).isTrue();

    var prefetcher = new Prefetcher(10, 7);
    prefetcher.initialize(upstream);
    assertThat(subscription.demands).first(LONG).isEqualTo(10);

    int limit = 3;
    for (int i = 0; i < limit; i++) {
      assertThat(subscription.demands.size()).isEqualTo(1); // No new requests are made.
      prefetcher.update(upstream);
    }

    assertThat(subscription.demands).hasSize(2).element(1, LONG).isEqualTo(limit);

    for (int i = 0; i < limit; i++) {
      assertThat(subscription.demands.size()).isEqualTo(2); // No new requests are made.
      prefetcher.update(upstream);
    }

    assertThat(subscription.demands).hasSize(3).element(1, LONG).isEqualTo(limit);
  }

  @Test
  void maxPrefetchThreshold() {
    var subscription = new RecordingSubscription();
    var upstream = new Upstream();
    assertThat(upstream.setOrCancel(subscription)).isTrue();

    var prefetcher = new Prefetcher(10, 10);
    prefetcher.initialize(upstream);
    assertThat(subscription.demands).first(LONG).isEqualTo(10);

    prefetcher.update(upstream);
    assertThat(subscription.demands).hasSize(2).element(1, LONG).isEqualTo(1);

    prefetcher.update(upstream);
    assertThat(subscription.demands).hasSize(3).element(1, LONG).isEqualTo(1);
  }

  @Test
  void minPrefetchThreshold() {
    var subscription = new RecordingSubscription();
    var upstream = new Upstream();
    assertThat(upstream.setOrCancel(subscription)).isTrue();

    var prefetcher = new Prefetcher(10, 0);
    prefetcher.initialize(upstream);
    assertThat(subscription.demands).first(LONG).isEqualTo(10);

    for (int i = 0; i < 10; i++) {
      assertThat(subscription.demands).hasSize(1);
      prefetcher.update(upstream);
    }

    assertThat(subscription.demands).hasSize(2).element(1, LONG).isEqualTo(10);
  }

  @Test
  void activeUpdates() {
    var subscription = new RecordingSubscription();
    var upstream = new Upstream();
    upstream.setOrCancel(subscription);

    var prefetcher = new Prefetcher(10, 7);
    prefetcher.initialize(upstream);
    for (int i = 0; i < 100 * 10; i++) {
      prefetcher.update(upstream);
      int w = prefetcher.currentWindow();
      assertThat(w).isBetween(7, 11); // [7, 10]
    }
  }

  private static class RecordingSubscription implements Subscription {
    private final List<Long> demands = new ArrayList<>();

    RecordingSubscription() {}

    @Override
    public void request(long n) {
      demands.add(n);
    }

    @Override
    public void cancel() {
      throw new AssertionError();
    }
  }
}
