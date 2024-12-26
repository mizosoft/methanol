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

package com.github.mizosoft.methanol.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FlowSupportTest {
  @Test
  void SYNC_EXECUTOR_executesInCurrentThread() {
    var threadRef = new AtomicReference<Thread>();
    FlowSupport.SYNC_EXECUTOR.execute(() -> threadRef.set(Thread.currentThread()));
    assertThat(threadRef).hasValue(Thread.currentThread());
  }

  @Test
  void loadPrefetch_canSetPrefetch() {
    System.setProperty(FlowSupport.PREFETCH_PROP, "123");
    assertThat(FlowSupport.loadPrefetch()).isEqualTo(123);
  }

  @Test
  void loadPrefetchFactor_canSetPrefetchFactor() {
    for (int i : List.of(0, 75, 100)) {
      System.setProperty(FlowSupport.PREFETCH_FACTOR_PROP, Integer.toString(i));
      assertThat(FlowSupport.loadPrefetchFactor()).isEqualTo(i);
    }
  }

  @Test
  void loadPrefetch_usesDefaultOnNonPositiveValue() {
    System.setProperty(FlowSupport.PREFETCH_PROP, "0");
    assertThat(FlowSupport.loadPrefetch()).isEqualTo(FlowSupport.DEFAULT_PREFETCH);
  }

  @Test
  void loadPrefetch_usesDefaultOnNonIntegerValue() {
    System.setProperty(FlowSupport.PREFETCH_PROP, "123.33f");
    assertThat(FlowSupport.loadPrefetch()).isEqualTo(FlowSupport.DEFAULT_PREFETCH);
  }

  @Test
  void loadPrefetchFactor_usesDefaultOnNegativeValue() {
    System.setProperty(FlowSupport.PREFETCH_FACTOR_PROP, "-1");
    assertThat(FlowSupport.loadPrefetchFactor()).isEqualTo(FlowSupport.DEFAULT_PREFETCH_FACTOR);
  }

  @Test
  void loadPrefetchFactor_usesDefaultOnNonPercentageValue() {
    System.setProperty(FlowSupport.PREFETCH_FACTOR_PROP, "101");
    assertThat(FlowSupport.loadPrefetchFactor()).isEqualTo(FlowSupport.DEFAULT_PREFETCH_FACTOR);
  }

  @Test
  void loadPrefetchFactor_usesDefaultOnNonIntegerValue() {
    System.setProperty(FlowSupport.PREFETCH_FACTOR_PROP, "12.123f");
    assertThat(FlowSupport.loadPrefetchFactor()).isEqualTo(FlowSupport.DEFAULT_PREFETCH_FACTOR);
  }
}
