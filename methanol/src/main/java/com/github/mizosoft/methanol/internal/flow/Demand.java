/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

import java.util.concurrent.atomic.AtomicLong;

/** Represents a subscriber's demand for items. */
public class Demand {

  private final AtomicLong demand;

  /** Creates zero demand. */
  public Demand() {
    demand = new AtomicLong();
  }

  private long getAndDemand(long n) {
    requireArgument(n > 0, "non-positive demand: %d", n);
    return demand.getAndAccumulate(
        n,
        (x, y) -> {
          long r = x + y;
          if (r < 0) { // Overflow
            r = Long.MAX_VALUE;
          }
          return r;
        });
  }

  /**
   * Decreases demand with the given number then returns it's updated value.
   *
   * @param n the number to subtract
   * @throws IllegalArgumentException if {@code n} is negative
   */
  public long decreaseAndGet(long n) {
    requireArgument(n >= 0, "non-positive subtraction: %d", n);
    return demand.accumulateAndGet(n, (x, y) -> x > y ? x - y : 0);
  }

  /**
   * Increases demand with the specified amount then returns if all demand was previously fulfilled.
   *
   * @param n the number of requested items
   * @throws IllegalArgumentException if {@code n} is not positive
   */
  public boolean increase(long n) {
    return getAndDemand(n) == 0;
  }

  /** Returns the current demand. */
  public long current() {
    return demand.get();
  }
}
