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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Flow.Subscription;
import org.junit.jupiter.api.Test;

class PrefetcherTest {

  @Test
  void initializeRequestsPrefetch() {
    var s = new RecordingSubscription();
    var ups = new Upstream();
    ups.setOrCancel(s);
    var pref = new Prefetcher();
    pref.initialize(ups);
    assertEquals(1, s.demands.size());
    assertEquals(FlowSupport.prefetch(), s.demands.poll());
  }

  @Test
  void updateAfterThreshold() {
    var s = new RecordingSubscription();
    var ups = new Upstream();
    ups.setOrCancel(s);
    var pref = new Prefetcher();
    pref.initialize(ups);
    int limit = FlowSupport.prefetch() - FlowSupport.prefetchThreshold();
    for (int i = 0; i < Math.max(1, limit); i++) { // should have at least 1 update
      assertEquals(1, s.demands.size());
      assertEquals(FlowSupport.prefetch(), s.demands.peek());
      pref.update(ups);
    }
    assertEquals(2, s.demands.size());
    s.demands.poll();
    int expectedRequest = Math.max(1, limit);
    assertEquals(expectedRequest, s.demands.poll());
  }

  @Test
  void activeUpdates() {
    var s = new RecordingSubscription();
    var ups = new Upstream();
    ups.setOrCancel(s);
    var pref = new Prefetcher();
    pref.initialize(ups);
    for (int i = 0; i < 1 + 100 * FlowSupport.prefetch(); i++) {
      pref.update(ups);
      int w = pref.currentWindow();
      assertTrue(w <= FlowSupport.prefetch() && w >= FlowSupport.prefetchThreshold(),
          "unexpected window: " + w);
    }
  }

  private static class RecordingSubscription implements Subscription {

    private final Queue<Long> demands = new LinkedList<>();

    RecordingSubscription() {}

    @Override public synchronized void request(long n) {
      demands.offer(n);
    }

    @Override public void cancel() {
      throw new AssertionError();
    }
  }
}