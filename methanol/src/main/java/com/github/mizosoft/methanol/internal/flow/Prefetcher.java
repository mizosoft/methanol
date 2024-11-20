/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;

/**
 * An object that manages requests to an upstream subscription according to a prefetching policy.
 * Callers call {@link #initialize(Upstream)} when they first receive the subscription and {@link
 * #update(Upstream)} when a received item is consumed. The prefetching policy ensures that the
 * number of requested but not fulfilled items remains between two values, namely {@code
 * prefetchThreshold} and {@code prefetch}.
 *
 * <p>This class is not thread-safe. Caller must ensure that {@link #initialize(Upstream)} & {@link
 * #update(Upstream)} are not interleaved and are properly synchronized.
 */
public final class Prefetcher {
  private final int prefetch;
  private final int consumedLimit;
  private int consumed;

  public Prefetcher() {
    this(FlowSupport.prefetch(), FlowSupport.prefetchThreshold());
  }

  public Prefetcher(int prefetch, int prefetchThreshold) {
    requireArgument(
        prefetch > 0 && prefetchThreshold >= 0 && prefetchThreshold <= prefetch,
        "Illegal prefetch and/or prefetchThreshold");
    this.prefetch = prefetch;
    this.consumedLimit = prefetch - prefetchThreshold;
  }

  public void initialize(Upstream upstream) {
    upstream.request(prefetch);
  }

  public void update(Upstream upstream) {
    int c = ++consumed;
    if (c >= consumedLimit) {
      consumed = 0;
      upstream.request(c);
    }
  }

  // For testing.

  int prefetch() {
    return prefetch;
  }

  int prefetchThreshold() {
    return prefetch - consumedLimit;
  }

  int currentWindow() {
    return prefetch - consumed;
  }
}
