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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Flow.Subscription;
import org.junit.jupiter.api.Test;

class UpstreamTest {

  @Test
  void setOrCancel() {
    var s1 = new TestSubscription();
    var ups = new Upstream();
    assertTrue(ups.setOrCancel(s1));
    var s2 = new TestSubscription();
    assertFalse(ups.setOrCancel(s2));
    assertEquals(1, s2.cancels);
  }

  @Test
  void setOrCancelCleared() {
    var s = new TestSubscription();
    var ups = new Upstream();
    ups.clear();
    assertFalse(ups.setOrCancel(s));
    assertEquals(1, s.cancels);
  }

  @Test
  void setOrCancelCancelled() {
    var s = new TestSubscription();
    var ups = new Upstream();
    ups.cancel();
    assertFalse(ups.setOrCancel(s));
    assertEquals(1, s.cancels);
  }

  @Test
  void clear() {
    var s = new TestSubscription();
    var ups = new Upstream();
    assertTrue(ups.setOrCancel(s));
    ups.clear();
    assertEquals(0, s.cancels);
  }

  @Test
  void cancel() {
    var s = new TestSubscription();
    var ups = new Upstream();
    assertTrue(ups.setOrCancel(s));
    ups.cancel();
    assertEquals(1, s.cancels);
  }

  @Test
  void request() {
    var s = new TestSubscription();
    var ups = new Upstream();
    ups.request(1);
    ups.setOrCancel(s);
    ups.request(5);
    ups.cancel();
    ups.request(1);
    assertEquals(1, s.cancels);
    assertEquals(1, s.demands.size());
    assertEquals(5, s.demands.poll());
  }

  private static final class TestSubscription implements Subscription {

    private int cancels;
    private final Queue<Long> demands = new LinkedList<>();

    TestSubscription() {}

    @Override
    public void request(long n) {
      demands.offer(n);
    }

    @Override
    public void cancel() {
      cancels++;
    }
  }
}