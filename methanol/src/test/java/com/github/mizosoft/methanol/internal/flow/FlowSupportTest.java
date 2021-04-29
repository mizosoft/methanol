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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FlowSupportTest {

  @Test
  void SYNC_EXECUTOR_executesInCurrentThread() {
    var threadRef = new AtomicReference<Thread>();
    FlowSupport.SYNC_EXECUTOR.execute(() -> threadRef.set(Thread.currentThread()));
    assertEquals(Thread.currentThread(), threadRef.get());
  }

  @Test
  void loadPrefetch_canSetPrefetch() {
    System.setProperty("com.github.mizosoft.methanol.flow.prefetch", "123");
    assertEquals(123, FlowSupport.loadPrefetch());
  }

  @Test
  void loadPrefetchFactor_canSetPrefetchFactor() {
    for (int i : List.of(0, 75, 100)) {
      System.setProperty("com.github.mizosoft.methanol.flow.prefetchFactor", Integer.toString(i));
      assertEquals(i, FlowSupport.loadPrefetchFactor());
    }
  }

  @Test
  void loadPrefetch_usesDefaultOnNonPositiveValue() {
    System.setProperty("com.github.mizosoft.methanol.flow.prefetch", "0");
    assertEquals(16, FlowSupport.loadPrefetch());
  }

  @Test
  void loadPrefetch_usesDefaultOnNonIntegerValue() {
    System.setProperty("com.github.mizosoft.methanol.flow.prefetch", "123.33f");
    assertEquals(16, FlowSupport.loadPrefetch());
  }

  @Test
  void loadPrefetchFactor_usesDefaultOnNegativeValue() {
    System.setProperty("com.github.mizosoft.methanol.flow.prefetchFactor", "-1");
    assertEquals(50, FlowSupport.loadPrefetchFactor());
  }

  @Test
  void loadPrefetchFactor_usesDefaultOnNonPercentageValue() {
    System.setProperty("com.github.mizosoft.methanol.flow.prefetchFactor", "101");
    assertEquals(50, FlowSupport.loadPrefetchFactor());
  }

  @Test
  void loadPrefetchFactor_usesDefaultOnNonIntegerValue() {
    System.setProperty("com.github.mizosoft.methanol.flow.prefetchFactor", "12.123f");
    assertEquals(50, FlowSupport.loadPrefetchFactor());
  }
}
